package forge.arena.server;

import com.google.gson.*;
import forge.deck.Deck;
import forge.game.*;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.player.GamePlayerUtil;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class ReconnectHardeningTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test(timeOut = 30_000)
    public void joinProtectsTheHolderAndReplacesOnlyWithTheToken() throws Exception {
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient holder = new WsTestClient()) {
            server.writer(0).bindPlayer(17); server.start(); HttpClient http = HttpClient.newHttpClient();
            holder.connect(http, server.getPort(), "/ws?seat=0");
            assertEquals(holder.receive().get("type").getAsString(), "hello"); holder.assertQuiet();
            holder.send(json("{\"type\":\"join\",\"name\":\"Owner\",\"protocolVersion\":1}"));
            JsonObject joined = holder.receive(); PromptAssertions.validate(joined, "joined");
            String token = joined.get("seatToken").getAsString(); assertTrue(token.matches("[0-9a-f]{64}"));
            assertEquals(holder.receive().get("type").getAsString(), "logTail");
            for (String extra : List.of("", ",\"seatToken\":\"wrong\"")) {
                try (WsTestClient intruder = new WsTestClient()) {
                    intruder.connect(http, server.getPort(), "/ws?seat=0"); intruder.receive();
                    intruder.send(json("{\"type\":\"join\",\"name\":\"Other\",\"protocolVersion\":1" + extra + "}"));
                    JsonObject rejected = intruder.receive(); PromptAssertions.validate(rejected, "message");
                    assertEquals(rejected.get("text").getAsString(), "This seat is already taken");
                    assertEquals(intruder.closeCode(), 4001);
                }
                JsonObject message = json("{\"type\":\"message\",\"level\":\"info\",\"text\":\"holder continues\"}");
                server.writer(0).send(message); assertEquals(holder.receive(), message);
            }
            try (WsTestClient wrongVersion = new WsTestClient(); WsTestClient replacement = new WsTestClient()) {
                wrongVersion.connect(http, server.getPort(), "/ws?seat=0"); wrongVersion.receive();
                wrongVersion.send(json("{\"type\":\"join\",\"name\":\"Old\",\"protocolVersion\":2}"));
                assertEquals(wrongVersion.receive().get("text").getAsString(), "Protocol version 1 is required");
                assertEquals(wrongVersion.closeCode(), 4002);
                replacement.connect(http, server.getPort(), "/ws?seat=0"); replacement.receive();
                JsonObject join = json("{\"type\":\"join\",\"name\":\"Owner\",\"protocolVersion\":1}");
                join.addProperty("seatToken", token); replacement.send(join);
                assertEquals(replacement.receive().get("seatToken").getAsString(), token);
                assertEquals(replacement.receive().get("type").getAsString(), "logTail");
                assertEquals(holder.closeCode(), 1000);
            }
        }
    }

    @Test(timeOut = 30_000)
    public void offlineTrafficHasOnlyOneStateAndABoundedLogTail() throws Exception {
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.writer(0).bindPlayer(17); server.start();
            for (int i = 0; i < 10_000; i++) {
                server.writer(0).send(json("{\"type\":\"event\",\"kind\":\"shuffle\",\"stateSeq\":0}"));
                server.writer(0).send(json("{\"type\":\"log\",\"entryType\":\"INFORMATION\",\"text\":\"offline " + i + "\"}"));
                server.writer(0).send(json("{\"type\":\"state\",\"turn\":" + i + "}"));
                assertEquals(server.writer(0).queuedStates(), 1); assertEquals(server.writer(0).queuedNonStates(), 0);
            }
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            assertEquals(client.history().get(2).object().getAsJsonArray("entries").size(), 200);
            assertEquals(client.receive().get("turn").getAsInt(), 9999); client.assertQuiet();
        }
    }

    private static Game scenario() {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) players.add(new RegisteredPlayer(new Deck("Recovery " + i))
                .setPlayer(GamePlayerUtil.createAiPlayer("Recovery " + i)));
        GameRules rules = new GameRules(GameType.Commander); rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);
        Game game = new Game(players, rules, new Match(rules, players, "Snapshot recovery"));
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved(); return game;
    }

    @Test(timeOut = 30_000)
    public void snapshotFailuresRecoverThenStopAfterThreeWithoutEscapingForge() throws Exception {
        Game game = PromptGameHarness.edt(ReconnectHardeningTest::scenario);
        AtomicInteger failures = new AtomicInteger(), resyncs = new AtomicInteger();
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.start();
            JsonSeatRemote remote = new JsonSeatRemote(game, game.getPlayers().get(0).getView(), server.writer(0));
            new SeatProtocolGui(remote) { @Override public void setResyncPending() { resyncs.incrementAndGet(); } };
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            PromptGameHarness.edt(() -> null); // Join requests a fresh full state at the next safe send.
            resyncs.set(0);
            java.util.function.Consumer<JsonObject> broken = state -> { failures.incrementAndGet(); throw new IllegalStateException("Injected snapshot failure"); };
            remote.setSnapshotBuiltObserver(broken); build(remote, game);
            JsonObject error = client.receive(); PromptAssertions.validate(error, "message");
            assertEquals(error.get("level").getAsString(), "error");
            assertEquals(error.get("text").getAsString(), "Display update failed; resyncing");
            remote.setSnapshotBuiltObserver(state -> { }); build(remote, game);
            PromptAssertions.validate(client.receive(), "state"); assertFalse(remote.isDirty()); assertEquals(resyncs.get(), 1);
            remote.setSnapshotBuiltObserver(broken);
            for (int i = 0; i < 4; i++) build(remote, game);
            assertEquals(failures.get(), 4, "One initial failure, then exactly three in the new burst");
            assertTrue(remote.isDirty()); assertEquals(resyncs.get(), 3, "No fourth retry scheduled");
            assertEquals(client.receive().get("level").getAsString(), "error"); client.assertQuiet();
            JsonSeatRemote other = new JsonSeatRemote(game, game.getPlayers().get(1).getView(), server.writer(1));
            build(other, game); PromptAssertions.validate(other.getLatestState(), "state"); assertFalse(other.isDirty());
            server.writer(1).close(); other.setSnapshotBuiltObserver(broken);
            build(other, game); // Failure reporting during shutdown must not escape into Forge either.
        }
    }

    private static void build(JsonSeatRemote remote, Game game) throws Exception {
        PromptGameHarness.edt(() -> { remote.send(new GuiGameEvent(ProtocolMethod.setGameView, game.getView())); return null; });
        PromptGameHarness.edt(() -> null); // Drain the resync request posted by the preceding safe send.
    }

    @Test(timeOut = 120_000)
    public void describeAbilityAcceptsVisibleCardsAndRejectsHiddenRefs() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.scenarioLines = lines -> { lines.removeIf(line -> line.startsWith("p1hand=")); lines.add("p1hand=Colossal Dreadmaw"); };
            h.start("Selesnya Guildmage;Forest", "Forest"); h.waitClientKind("priority");
            int id = PromptGameHarness.edt(() -> h.card("Selesnya Guildmage", ZoneType.Battlefield).getId());
            h.sendAction("describeAbility", "cardId", new JsonPrimitive(id));
            JsonObject description = next(h.client, "abilityDescription"); PromptAssertions.validate(description, "abilityDescription");
            assertEquals(description.get("cardId").getAsInt(), id);
            JsonObject hidden = h.clientState().getAsJsonArray("players").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(p -> p.get("id").getAsInt() != h.human.getPlayer().getId())
                    .flatMap(p -> p.getAsJsonObject("zones").getAsJsonArray("hand").asList().stream())
                    .map(JsonElement::getAsJsonObject).findFirst().orElseThrow();
            h.sendAction("describeAbility", "cardId", hidden.get("id"));
            assertEquals(next(h.client, "message").get("level").getAsString(), "rejected");
            int real = h.remote.getIds().resolve(hidden.get("id").getAsString()).orElseThrow();
            h.sendAction("describeAbility", "cardId", new JsonPrimitive(real));
            assertEquals(next(h.client, "message").get("level").getAsString(), "rejected");
        }
    }

    private static JsonObject next(WsTestClient client, String type) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { JsonObject message = client.receive(); if (message.get("type").getAsString().equals(type)) return message; }
        throw new AssertionError("Timed out waiting for " + type);
    }
}
