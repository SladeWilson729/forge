package forge.arena.server;

import com.google.gson.*;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.event.GameEventAddLog;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.testng.Assert.*;

public class EventPumpTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    private static void waitFor(BooleanSupplier predicate, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean(), label);
    }

    private static List<JsonObject> messages(WsTestClient client, String type) {
        return client.history().stream().map(WsTestClient.Received::object)
                .filter(e -> type.equals(e.get("type").getAsString())).toList();
    }

    private static void flush(PromptGameHarness h) {
        h.gui.getForwarder().flush(); h.gui.updateGameView();
    }

    private static void cast(PromptGameHarness h, String name, boolean counter) throws Exception {
        h.action(() -> {
            Card card = h.game.getAction().moveToStack(h.card(name, ZoneType.Hand), null);
            SpellAbility spell = card.getFirstSpellAbility(); spell.setActivatingPlayer(h.human.getPlayer());
            if (spell.usesTargeting()) spell.getTargets().add(h.game.getPlayers().get(3));
            h.game.getStack().addAndUnfreeze(spell);
            h.game.getStack().addAllTriggeredAbilitiesToStack();
            flush(h); // Capture the actual spell/trigger stack before resolution.
            if (counter) {
                h.game.getStack().remove(card); // Real removed-from-stack event, with no resolved match.
                h.game.getAction().moveToGraveyard(card, null); flush(h);
            }
        });
        h.advance(input -> input instanceof InputPassPriority && h.game.getStack().isEmpty());
    }

    @Test(timeOut = 180_000)
    public void scriptedGameEmitsEveryKindWithOrderingAndRedaction() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness()) {
            try {
            h.mulliganOnce = true;
            h.traceAdvance = true;
            h.answerBinaryChoices = true;
            h.scenarioLines = lines -> {
                lines.removeIf(line -> line.startsWith("p1battlefield=") || line.startsWith("p2hand="));
                lines.add("p1battlefield=Colossal Dreadmaw;Colossal Dreadmaw;Wall of Wood");
                lines.add("p2hand=Consecrated Sphinx");
            };
            h.start("Grizzly Bears;Runeclaw Bear;Young Pyromancer;Frenetic Efreet;Forest;Forest",
                    "Forest;Raise the Alarm;Contact Other Plane;Shock");
            // Real card scripts: Frenetic Efreet's zero-cost FlipCoin activation and
            // Contact Other Plane's RollDice (Sides$ 20). No fabricated coin/die events.
            h.action(() -> {
                h.human.getPlayer().playLand(h.card("Forest", ZoneType.Hand), null);
                Card forest = h.card("Forest", ZoneType.Battlefield);
                forest.tap(true, null, h.human.getPlayer()); flush(h);
                forest.untap();
                Card bear = h.card("Grizzly Bears", ZoneType.Battlefield);
                bear.addCounterInternal(CounterEnumType.P1P1, 1, h.human.getPlayer(), true,
                        new GameEntityCounterTable(), new HashMap<>());
                bear.addDamageAfterPrevention(1, h.game.getPlayers().get(1).getCardsIn(ZoneType.Battlefield).get(0),
                        null, false, new GameEntityCounterTable());
                h.game.getPlayers().get(3).addDamageAfterPrevention(1, bear, null, false, new GameEntityCounterTable());
                h.game.getAction().becomeMonarch(h.game.getPlayers().get(2), "CN2");
                // Public designation setter plus a changed card ensures a full snapshot;
                // this isolates the designation diff from dungeon-choice UI.
                h.game.setHasInitiative(h.game.getPlayers().get(3));
                Card hidden = h.game.getPlayers().get(2).getCardsIn(ZoneType.Hand).get(0);
                h.game.fireEvent(new GameEventAddLog(GameLogEntryType.ZONE_CHANGE,
                        "Private log: " + hidden.getName(), hidden));
                flush(h);
                hidden.addMayLookTemp(h.human.getPlayer());
                h.gui.showRevealedCards(List.of(hidden.getView()));
                h.gui.hideRevealedCards(); hidden.removeMayLookTemp(h.human.getPlayer());
                flush(h);
            });
            cast(h, "Raise the Alarm", false); // Also triggers Young Pyromancer and creates real tokens.
            cast(h, "Contact Other Plane", false);
            cast(h, "Shock", true);
            h.action(() -> {
                SpellAbility coin = h.card("Frenetic Efreet", ZoneType.Battlefield).getSpellAbilities().stream()
                        .filter(sa -> sa.getApi() == ApiType.FlipCoin).findFirst().orElseThrow();
                coin.setActivatingPlayer(h.human.getPlayer()); h.game.getStack().addAndUnfreeze(coin); flush(h);
            });
            h.advance(input -> input instanceof InputPassPriority && h.game.getStack().isEmpty());
            h.action(() -> {
                // Retain the 1.4a combat pressure: two tramplers versus two bears and low life.
                for (Card card : new ArrayList<>(h.human.getPlayer().getCardsIn(ZoneType.Battlefield))) {
                    if (card.isCreature() && !Set.of("Grizzly Bears", "Runeclaw Bear").contains(card.getName())) {
                        h.game.getAction().moveToGraveyard(card, null);
                    }
                }
                h.human.getPlayer().setLife(2, null);
                flush(h); // Pass priority normally so COMBAT_BEGIN initializes Combat.
            });
            h.advance(input -> input instanceof InputAttack);
            h.action(() -> {
                h.controller.selectPlayer(h.game.getPlayers().get(2).getView(), null);
                h.controller.selectCard(h.card("Runeclaw Bear", ZoneType.Battlefield).getView(), null, null);
                h.controller.selectButtonOk();
            });
            h.advance(input -> input instanceof InputBlock);
            h.action(() -> {
                assertEquals(h.game.getCombat().getAttackingPlayer(), h.game.getPlayers().get(1));
                Card attacker = h.game.getCombat().getAttackers().stream()
                        .filter(c -> h.game.getCombat().getDefenderByAttacker(c) == h.human.getPlayer()).findFirst().orElseThrow();
                h.controller.selectCard(attacker.getView(), null, null);
                h.controller.selectCard(h.card("Grizzly Bears", ZoneType.Battlefield).getView(), null, null);
                h.human.getPlayer().setLife(40, null); // Observe blockers without ending on lethal trample.
                h.controller.selectButtonOk();
            });
            h.advance(input -> input instanceof InputPassPriority);
            finishGame(h);
            assertTrue(h.finished.await(10, TimeUnit.SECONDS));
            h.until(h.match::isMatchOver, "post-game flush");
            waitFor(() -> messages(h.client, "event").stream().anyMatch(e -> "gameOver".equals(e.get("kind").getAsString())), "gameOver on wire");
            h.healthy();
            assertTrue(h.remote.revealDisagreements().isEmpty(), "No reveal visibility disagreements");
            final Map<String, Long> counts = new TreeMap<>();
            for (JsonObject event : messages(h.client, "event")) counts.merge(event.get("kind").getAsString(), 1L, Long::sum);
            for (String kind : List.of("zoneChange", "landPlayed", "spellCast", "resolved", "countered", "tap", "untap",
                    "damage", "counterChange", "tokenCreated", "attack", "block", "shuffle", "coinFlip", "dieRoll",
                    "mulligan", "turnBegan", "reveal", "lifeChange", "triggerFired", "monarchChange", "initiativeChange",
                    "playerEliminated", "gameOver")) {
                System.out.println("[event-kind] " + kind + "=" + counts.getOrDefault(kind, 0L));
                assertTrue(counts.getOrDefault(kind, 0L) > 0, "Missing event kind " + kind);
            }
            assertTrue(messages(h.client, "event").stream().anyMatch(e -> "coinFlip".equals(e.get("kind").getAsString()) && !e.get("startingToss").getAsBoolean()));
            assertTrue(messages(h.client, "event").stream().anyMatch(e -> "dieRoll".equals(e.get("kind").getAsString()) && e.get("sides").getAsInt() == 20));
            assertTrue(messages(h.client, "log").stream().noneMatch(e -> e.get("text").getAsString().contains("Consecrated Sphinx")));
            assertFalse(messages(h.client, "log").isEmpty());
            assertTrue(messages(h.client, "event").stream().anyMatch(e -> "tokenCreated".equals(e.get("kind").getAsString())
                    && !e.getAsJsonArray("cardIds").isEmpty()), "Token marker correlated to actual token ids");
            checkWire(h.client);
            h.action(h.match::endCurrentGame);
            } finally {
                finishGame(h);
            }
        }
    }

    private static void finishGame(PromptGameHarness h) throws Exception {
        if (h.game == null || h.game.isGameOver()) return;
        java.util.concurrent.CompletableFuture<Void> ended = new java.util.concurrent.CompletableFuture<>();
        // Release pending replies before scheduling cleanup; an action prompt may park the EDT.
        h.answer = p -> p.get("default");
        for (JsonObject prompt : h.prompts) h.client.send(PromptAssertions.reply(prompt, prompt.get("default")));
        h.game.getAction().invoke(() -> {
            try {
                assertTrue(Thread.currentThread().getName().startsWith("Game-"));
                for (var player : new ArrayList<>(h.game.getPlayers())) if (player != h.human.getPlayer()) player.concede();
                // Concede sets the outcome only. Forge's normal check also runs onPlayerLost,
                // publishes HasLost/removes players, and then sets the game-over reason.
                h.game.getAction().checkGameOverCondition();
                h.human.getInputQueue().onGameOver(true);
                ended.complete(null);
            } catch (Throwable ex) { ended.completeExceptionally(ex); }
        });
        ended.get(5, TimeUnit.SECONDS);
    }

    static void checkWire(WsTestClient client) {
        Set<JsonElement> seen = new HashSet<>(); long lastStateSeq = 0;
        List<Long> stateTimes = new ArrayList<>();
        for (var received : client.history()) {
            JsonObject message = received.object(); String type = message.get("type").getAsString();
            if (type.equals("state")) {
                PromptAssertions.validate(message, "state"); collectStateCards(message, seen);
                assertTrue(message.get("seq").getAsLong() > lastStateSeq); lastStateSeq = message.get("seq").getAsLong();
                stateTimes.add(received.at());
            } else if (type.equals("event")) {
                PromptAssertions.validate(message, "event");
                assertTrue(message.get("stateSeq").getAsLong() <= lastStateSeq, "Event preceded its coalesced state: " + message);
                Set<JsonElement> available = new HashSet<>(seen);
                if (message.has("cardStubs")) for (JsonElement stub : message.getAsJsonArray("cardStubs")) available.add(stub.getAsJsonObject().get("id"));
                Set<JsonElement> used = new HashSet<>(); EventPump.collectCardRefs(message, used);
                assertTrue(available.containsAll(used), "Missing state/stub for " + used + " in " + message);
            } else if (type.equals("log") || type.equals("logTail")) PromptAssertions.validate(message, type);
        }
        int peak = 0;
        for (int end = 0, start = 0; end < stateTimes.size(); end++) {
            while (stateTimes.get(end) - stateTimes.get(start) >= TimeUnit.SECONDS.toNanos(1)) start++;
            peak = Math.max(peak, end - start + 1);
        }
        System.out.println("[event-wire] states=" + stateTimes.size() + " peak/s=" + peak);
        assertTrue(peak <= 10, "State cap: " + peak);
    }

    private static void collectStateCards(JsonElement value, Set<JsonElement> refs) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(e -> collectStateCards(e, refs)); return; }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("id") && !object.has("life") && (object.has("name") || object.has("hidden") || object.has("faceDown"))) refs.add(object.get("id"));
        // Stack sources and explicit card refs are also introduced by a snapshot.
        for (var field : object.entrySet()) {
            if (Set.of("sourceCardId", "cardId", "commanderId").contains(field.getKey()) && !field.getValue().isJsonNull()) refs.add(field.getValue());
            else collectStateCards(field.getValue(), refs);
        }
    }

    private static JsonObject emptyState() {
        return JsonParser.parseString("{\"type\":\"state\",\"turn\":0,\"activePlayerId\":null,\"priorityPlayerId\":null,"
                + "\"phase\":null,\"monarchId\":null,\"initiativeId\":null,\"players\":[],\"stack\":[],\"activeInput\":{\"kind\":\"waiting\"}}")
                .getAsJsonObject();
    }

    @Test(timeOut = 30_000)
    public void coalescedTransientHasStubAndLogTailKeepsLast200() throws Exception {
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient(); WsTestClient reconnect = new WsTestClient()) {
            server.writer(0).bindPlayer(0); server.start();
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            server.writer(0).send(emptyState()); client.receive();
            // Fill the single pending slot during the next state rate-limit interval.
            JsonObject transientState = emptyState();
            JsonObject stub = JsonParser.parseString("{\"id\":\"h-123456abcdef\",\"hidden\":true}").getAsJsonObject();
            JsonArray cards = new JsonArray(); cards.add(stub); transientState.add("revealed", cards);
            long stateSeq = server.writer(0).send(transientState);
            JsonObject event = new JsonObject(); event.addProperty("type", "event"); event.addProperty("kind", "tap");
            event.addProperty("stateSeq", stateSeq); JsonArray refs = new JsonArray(); refs.add(stub.get("id"));
            event.add("cardIds", refs); event.add("cardStubs", cards.deepCopy()); server.writer(0).send(event);
            JsonObject replacement = emptyState(); server.writer(0).send(replacement);
            waitFor(() -> !messages(client, "event").isEmpty(), "transient event");
            assertEquals(messages(client, "state").size(), 2, "Anchor plus the coalesced state");
            assertTrue(messages(client, "state").stream().noneMatch(s -> s.has("revealed")), "The transient state was never sent");
            checkWire(client);
            for (int i = 0; i < 300; i++) {
                JsonObject log = new JsonObject(); log.addProperty("type", "log"); log.addProperty("text", "log " + i);
                log.addProperty("entryType", "ZONE_CHANGE"); server.writer(0).send(log);
            }
            waitFor(() -> messages(client, "log").size() == 300, "No logs dropped");
            long covering = server.writer(0).send(emptyState());
            waitFor(() -> messages(client, "state").stream().anyMatch(s -> s.get("seq").getAsLong() == covering), "state covering log tail");
            reconnect.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); reconnect.hello(0);
            waitFor(() -> !messages(reconnect, "logTail").isEmpty(), "reconnect logTail");
            JsonObject tail = messages(reconnect, "logTail").get(0); PromptAssertions.validate(tail, "logTail");
            assertEquals(tail.getAsJsonArray("entries").size(), 200);
            for (int i = 0; i < 200; i++) {
                JsonObject entry = tail.getAsJsonArray("entries").get(i).getAsJsonObject();
                assertEquals(entry.get("text").getAsString(), "log " + (100 + i));
                assertEquals(entry.get("seq"), messages(client, "log").get(100 + i).get("seq"));
            }
            server.writer(0).resync();
            waitFor(() -> messages(reconnect, "logTail").size() == 2, "resync logTail");
            assertEquals(messages(reconnect, "logTail").get(1).get("entries"), tail.get("entries"));
        }
    }
}
