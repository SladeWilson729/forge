package forge.arena.server;

import com.google.gson.*;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.*;

public class ActionRoundTripTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @Test(timeOut = 180_000)
    public void socketActionsPlayPayAndDeclareAcrossFourDefenders() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); WsTestClient bot = new WsTestClient()) {
            h.scenarioLines = lines -> {
                lines.removeIf(line -> line.matches("p[123](life|battlefield)=.*"));
                for (int i = 1; i < 4; i++) {
                    lines.add("p" + i + "life=2");
                    lines.add("p" + i + "battlefield=Craw Wurm;Craw Wurm" + (i == 1 ? ";Garruk Wildspeaker|Counters:LOYALTY=3" : ""));
                }
            };
            h.start("Grizzly Bears;Runeclaw Bear;Balduvian Bears;Bear Cub;Forest Bear;Forest;Forest", "Forest;Grizzly Bears");
            assertEquals(h.botViews.size(), 3, "Human-mode bot views attached in the start hook");
            assertTrue(h.client.history().stream().anyMatch(r -> r.object().get("type").getAsString().equals("state")
                    && r.object().getAsJsonObject("activeInput").get("kind").getAsString().equals("mulligan")), "Opening hand arrived before socket OK");
            bot.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=1"); bot.hello(1);

            Card land = PromptGameHarness.edt(() -> h.card("Forest", ZoneType.Hand));
            h.waitClientKind("priority");
            send(h, "selectCard", "cardId", ref(h, land));
            h.until(() -> h.game.findById(land.getId()).isInZone(ZoneType.Battlefield), "land played through socket");
            Card spell = PromptGameHarness.edt(() -> h.card("Grizzly Bears", ZoneType.Hand));
            h.waitClientKind("priority");
            send(h, "selectCard", "cardId", ref(h, spell));
            h.waitInput(InputPayMana.class);
            List<Card> forests = PromptGameHarness.edt(() -> h.human.getPlayer().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).toList());
            for (int i = 0; i < 2; i++) {
                Card source = forests.get(i);
                h.waitClientKind("payMana");
                send(h, "selectCard", "cardId", ref(h, source));
                h.until(() -> source.isTapped() && !(h.human.getInputQueue().getInput() instanceof InputPayMana pay && pay.isActivatingManaAbility()), "land mana paid");
            }
            // GameAction copies Cards across zones. The old hand/stack object retains its LKI zone.
            h.advance(input -> input instanceof InputPassPriority && h.game.findById(spell.getId()).isInZone(ZoneType.Battlefield));
            h.advance(input -> input instanceof InputAttack);
            List<Card> attackers = PromptGameHarness.edt(() -> h.human.getPlayer().getCreaturesInPlay().stream()
                    .filter(c -> c.getId() != spell.getId()).toList());
            assertEquals(attackers.size(), 5);
            Card walker = PromptGameHarness.edt(() -> h.game.getPlayers().get(1).getCardsIn(ZoneType.Battlefield).stream().filter(Card::isPlaneswalker).findFirst().orElseThrow());
            List<GameEntity> defenders = PromptGameHarness.edt(() -> List.of(h.game.getPlayers().get(1), h.game.getPlayers().get(2), h.game.getPlayers().get(3), walker, h.game.getPlayers().get(1)));
            for (int i = 0; i < 5; i++) {
                send(h, "declareAttacker", "attackerId", ref(h, attackers.get(i)), "defenderId", entity(h, defenders.get(i)));
                assertCombat(h);
            }
            JsonObject before = combatFields(h.remote.getLatestState());
            send(h, "declareAttacker", "attackerId", ref(h, attackers.get(0)), "defenderId", entity(h, defenders.get(0)));
            assertEquals(combatFields(h.remote.getLatestState()), before, "Repeated pair is idempotent");
            // Move an existing attacker to another defender, then restore it: removal and both clicks are one task.
            send(h, "declareAttacker", "attackerId", ref(h, attackers.get(0)), "defenderId", entity(h, defenders.get(1)));
            assertCombat(h);
            send(h, "declareAttacker", "attackerId", ref(h, attackers.get(0)), "defenderId", entity(h, defenders.get(0)));
            Card removed = attackers.get(4); JsonElement oldRef = ref(h, removed);
            send(h, "undeclareAttacker", "attackerId", oldRef); assertCombat(h);
            h.action(() -> h.game.getAction().moveToHand(removed, null));
            long messages = h.client.history().size();
            h.sendAction("declareAttacker", "attackerId", oldRef, "defenderId", entity(h, defenders.get(0)));
            awaitMessage(h, h.client, messages, "message", "battlefield");

            JsonObject wrongSeat = JsonParser.parseString("{\"type\":\"selectCard\",\"cardId\":1,\"clientSeq\":1,\"baseSeq\":0}").getAsJsonObject();
            PromptAssertions.validate(wrongSeat, "ClientMessage"); bot.send(wrongSeat);
            awaitMessage(h, bot, 0, "message", "This seat is not yours to play");

            send(h, "ok");
            h.advance(input -> input instanceof InputPassPriority && h.game.getPhaseHandler().getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS);
            assertCombat(h);
            assertTrue(PromptGameHarness.edt(() -> h.game.getCombat().getAllBlockers().size() >= 2), "At least two AI blocks");
            Set<Integer> blockingPlayers = PromptGameHarness.edt(() -> {
                Set<Integer> ids = new HashSet<>(); h.game.getCombat().getAllBlockers().forEach(c -> ids.add(c.getController().getId())); return ids;
            });
            assertTrue(blockingPlayers.size() >= 2, "Blocks from multiple defenders");
            for (var r : h.client.history()) PromptAssertions.validate(r.object(), "ServerMessage");
            for (var r : bot.history()) PromptAssertions.validate(r.object(), "ServerMessage");
            h.healthy();
        }
    }

    @Test(timeOut = 90_000)
    public void humanSeatTwoAndBotSeatZeroKeepTheirRegisteredIdentity() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true, 2); WsTestClient bot = new WsTestClient()) {
            // GameState p0 is the human after HostedMatch sorts; p1 is original wire seat 0.
            h.scenarioLines = lines -> { lines.remove("p1hand="); lines.add("p1hand=Shock"); };
            h.start("Forest", "Grizzly Bears");
            PromptGameHarness.edt(() -> {
                assertSame(h.game.getPlayer(h.remote.actionSeat()).getRegisteredPlayer(), h.players.get(2));
                assertTrue(h.game.getPlayer(h.remote.actionSeat()).getController() instanceof forge.player.PlayerControllerHuman);
                assertFalse(ArenaServerMain.playerFor(h.game, h.players.get(0)).getController() instanceof forge.player.PlayerControllerHuman);
                return null;
            });
            bot.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=0");
            JsonObject botHello = bot.hello(0);
            JsonObject botState = bot.receive(); PromptAssertions.validate(botState, "state");
            assertEquals(botState.getAsJsonObject("activeInput").get("kind").getAsString(), "waiting");
            int botId = PromptGameHarness.edt(() -> ArenaServerMain.playerFor(h.game, h.players.get(0)).getId());
            int humanId = PromptGameHarness.edt(() -> h.human.getPlayer().getId());
            assertEquals(botHello.get("playerId").getAsInt(), botId);
            assertNotEquals(humanId, h.humanSeat, "Human-first sorting differs from wire seat 2");
            assertNotEquals(botId, 0, "Bot seat 0 was sorted after the human");
            for (JsonElement p : botState.getAsJsonArray("players")) {
                JsonObject player = p.getAsJsonObject();
                JsonArray hand = player.getAsJsonObject("zones").getAsJsonArray("hand");
                if (player.get("id").getAsInt() == botId) {
                    assertEquals(hand.size(), 1);
                    assertEquals(hand.get(0).getAsJsonObject().get("name").getAsString(), "Shock");
                } else if (player.get("id").getAsInt() == humanId) {
                    assertEquals(hand.size(), 1);
                    assertTrue(hand.get(0).getAsJsonObject().get("hidden").getAsBoolean());
                }
            }
            JsonObject action = JsonParser.parseString("{\"type\":\"ok\",\"clientSeq\":1,\"baseSeq\":0}").getAsJsonObject();
            PromptAssertions.validate(action, "ClientMessage"); bot.send(action);
            awaitMessage(h, bot, 0, "message", "This seat is not yours to play");
            for (var r : bot.history()) PromptAssertions.validate(r.object(), "ServerMessage");
        }
    }

    @Test(timeOut = 90_000)
    public void libraryInputOffersResolveAndExpire() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.start("Forest", "");
            AtomicReference<InputSelectCardsFromList> input = new AtomicReference<>();
            AtomicReference<Card> selected = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            h.action(() -> h.game.getAction().invoke(() -> {
                try {
                    CardCollection cards = new CardCollection(h.human.getPlayer().getCardsIn(ZoneType.Library));
                    InputSelectCardsFromList choose = new InputSelectCardsFromList(h.human, 1, cards);
                    input.set(choose); choose.setMessage("Choose a library card"); choose.showAndWait();
                    selected.set(choose.getSelected().iterator().next());
                } finally { done.countDown(); }
            }));
            h.waitInput(InputSelectCardsFromList.class);
            h.waitClientKind("selectCards");
            JsonObject state = h.clientState(); PromptAssertions.validate(state, "state");
            JsonObject active = state.getAsJsonObject("activeInput");
            assertFalse(active.getAsJsonArray("offered").isEmpty());
            JsonObject offer = active.getAsJsonArray("offered").get(0).getAsJsonObject();
            JsonElement wire = offer.get("cardRef");
            assertTrue(wire.getAsJsonPrimitive().isString(), "Library offer uses opaque Input-scoped ref");
            assertTrue(offer.has("name") && offer.has("set") && offer.has("num"), "Owner-only face-up reveal");
            int real = h.remote.getIds().resolve(wire.getAsString()).orElseThrow();
            assertTrue(PromptGameHarness.edt(() -> h.game.findById(real).isInZone(ZoneType.Library)));
            System.out.println("[action-test] library offer ref=" + wire + " realId=" + real + " zone=Library");
            send(h, "selectCard", "cardId", wire);
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(selected.get().getId(), real);
            h.waitInput(InputPassPriority.class);
            assertTrue(h.remote.getIds().resolve(wire.getAsString()).isEmpty(), "Offer retired after Input");
            assertTrue(h.remote.getLatestState().getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonObject("zones").getAsJsonObject("library").has("count"));
        }
    }

    private static void send(PromptGameHarness h, String type, Object... fields) throws Exception {
        if (type.equals("declareAttacker") || type.equals("undeclareAttacker") || type.equals("ok"))
            h.waitClientKind("declareAttackers");
        long before = h.remote.completedActionCount(); h.sendAction(type, fields);
        h.until(() -> h.remote.completedActionCount() > before, "socket action " + type);
        h.healthy();
    }
    private static void awaitMessage(PromptGameHarness h, WsTestClient client, long after, String type, String text) throws Exception {
        h.until(() -> client.history().stream().skip(after).map(WsTestClient.Received::object)
                .anyMatch(m -> m.get("type").getAsString().equals(type) && (!m.has("text") || m.get("text").getAsString().contains(text))), "message " + text);
    }
    private static JsonElement ref(PromptGameHarness h, Card card) {
        // Click only identity-visible cards actually delivered to this client.
        for (JsonElement p : h.clientState().getAsJsonArray("players")) {
            for (var zone : p.getAsJsonObject().getAsJsonObject("zones").entrySet()) {
                if (!zone.getValue().isJsonArray()) continue;
                for (JsonElement c : zone.getValue().getAsJsonArray()) {
                    JsonObject value = c.getAsJsonObject();
                    if (value.get("id").equals(new JsonPrimitive(card.getId())) && value.has("name")) return value.get("id");
                }
            }
        }
        throw new AssertionError("Card is not identity-visible in received state: " + card.getId());
    }
    private static JsonObject entity(PromptGameHarness h, GameEntity entity) {
        JsonObject value = new JsonObject(); value.addProperty("kind", entity instanceof Player ? "player" : "card");
        value.add("id", entity instanceof Player ? new JsonPrimitive(entity.getId()) : ref(h, (Card) entity)); return value;
    }
    private static JsonObject combatFields(JsonObject state) {
        JsonObject result = new JsonObject();
        for (JsonElement p : state.getAsJsonArray("players")) for (JsonElement c : p.getAsJsonObject().getAsJsonObject("zones").getAsJsonArray("battlefield")) {
            JsonObject card = c.getAsJsonObject(), fields = new JsonObject();
            for (String field : List.of("attacking", "attackTarget", "blocking", "blockingIds")) fields.add(field, card.get(field));
            result.add(card.get("id").getAsString(), fields);
        }
        return result;
    }
    private static void assertCombat(PromptGameHarness h) throws Exception {
        PromptGameHarness.edt(() -> {
            JsonObject state = h.remote.getLatestState(); PromptAssertions.validate(state, "state");
            var combat = h.game.getCombat();
            for (JsonElement p : state.getAsJsonArray("players")) for (JsonElement element : p.getAsJsonObject().getAsJsonObject("zones").getAsJsonArray("battlefield")) {
                JsonObject card = element.getAsJsonObject(); Card live = h.game.findById(card.get("id").getAsInt());
                assertEquals(card.get("attacking").getAsBoolean(), combat.isAttacking(live));
                GameEntity defender = combat.getDefenderByAttacker(live);
                assertEquals(card.get("attackTarget"), defender == null ? JsonNull.INSTANCE : entity(h, defender));
                assertEquals(card.get("blocking").getAsBoolean(), combat.isBlocking(live));
                JsonArray blocking = new JsonArray(); combat.getAttackersBlockedBy(live).forEach(a -> blocking.add(ref(h, a)));
                assertEquals(card.get("blockingIds"), blocking);
            }
            return null;
        });
    }
}
