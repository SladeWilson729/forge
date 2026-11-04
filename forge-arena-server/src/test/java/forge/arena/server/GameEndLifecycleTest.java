package forge.arena.server;

import com.google.gson.*;
import forge.game.GameEndReason;
import forge.player.PlayerControllerHuman;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class GameEndLifecycleTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @DataProvider public Object[][] abandonments() { return new Object[][]{{true}, {false}}; }

    @Test(dataProvider = "abandonments", timeOut = 120_000)
    public void abandonedSeatVotesYesAndReturnsAsAnAiViewer(boolean bot) throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, false)) {
            // Keep the former failing board for diagnostics; abandonment needs no Input on seat 1.
            h.scenarioLines = lines -> lines.add("p1battlefield=Seeker of Skybreak");
            h.start("Forest", "");
            JsonSeatRemote oldRemote = h.secondaryRemote;
            try (MatchLifecycle lifecycle = new MatchLifecycle(h.server, h.match, Set.of(0, 1));
                 SeatAbandonment console = new SeatAbandonment(h.server, new java.io.StringReader(""), System.out, 180, System::nanoTime)) {
                lifecycle.started();
                PromptGameHarness.edt(() -> {
                    var seat1 = ArenaServerMain.playerFor(h.game, h.players.get(1));
                    assertSame(seat1, h.game.getPlayers().get(1), "p1 is wire seat 1 in this two-human scenario");
                    var seeker = seat1.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                            .filter(c -> c.getName().equals("Seeker of Skybreak")).findFirst().orElseThrow();
                    System.out.printf("[abandon-setup] seat1=%s sickness=%s primaryInput=%s secondaryInput=%s skipOpponentMain=%s%n",
                            seat1.getName(), seeker.hasSickness(), h.human.getInputQueue().getInput(),
                            h.secondaryHuman.getInputQueue().getInput(), h.secondaryHuman.isUiSetToSkipPhase(
                                    h.game.getPhaseHandler().getPlayerTurn().getView(), h.game.getPhaseHandler().getPhase()));
                    assertFalse(seeker.hasSickness(), "Puzzle explicitly removes summoning sickness");
                    return null;
                });
                h.secondaryClient.close();
                h.until(() -> !h.server.writer(1).connected(), "Seat 1 disconnected");
                assertTrue(oldRemote.hasGameState()); assertTrue(oldRemote.isHumanSeat());
                var handoff = console.submit((bot ? "bot" : "concede") + " 1");
                h.until(() -> oldRemote.abandonedReason() != null, "Console flags disconnected seat before the EDT handoff");
                // As in SeatAbandonmentTest, flag first, then let the current input resume.
                // awaitNextInput's safe point handles auto-passed seats without an Input latch.
                h.advance(input -> handoff.isDone());
                assertEquals(handoff.get(10, TimeUnit.SECONDS),
                        bot ? "Bot handoff complete" : "Seat conceded");
                assertNotNull(oldRemote.abandonedReason());
                var oldLobby = h.players.get(1).getPlayer();
                var first = h.game;
                finish(h);
                lifecycle.finished();
                h.until(lifecycle::ended, "Lifecycle marked game 1 ended");
                // Wait beyond the quit grace period and several lifecycle polls: results must stay open.
                Thread.sleep(1000);
                assertFalse(lifecycle.shutdown().isDone(), "Abandonment is not a match quit");
                assertSame(h.match.getGame(), first, "Results remain until the human votes");
                assertEquals(h.client.closed.getCount(), 1L);
                h.sendAction("nextGame", "decision", new JsonPrimitive("rematch"));
                h.until(() -> h.match.getGame() != null && h.match.getGame() != first, "Seat 0 alone starts rematch");
                PromptGameHarness.edt(() -> {
                    h.game = h.match.getGame();
                    h.human = (PlayerControllerHuman) ArenaServerMain.playerFor(h.game, h.players.get(0)).getController();
                    assertTrue(ArenaServerMain.playerFor(h.game, h.players.get(1)).getController() instanceof forge.ai.PlayerControllerAi);
                    assertTrue(h.players.get(1).getPlayer() instanceof forge.ai.LobbyPlayerAi);
                    if (bot) assertSame(h.players.get(1).getPlayer(), oldLobby, "Existing AI lobby persists");
                    return null;
                });
                assertNull(oldRemote.abandonedReason(), "Old game's abandonment reason retired");
                assertEquals(oldRemote.abandonmentDefaultCount(), 0);
                h.waitInput(forge.gamemodes.match.input.InputSelectEntitiesFromList.class);
                h.sendAction("selectPlayer", "playerId", new JsonPrimitive(h.human.getPlayer().getId()));
                h.waitInput(forge.gamemodes.match.input.InputConfirmMulligan.class);
                h.sendAction("ok");
                h.until(() -> h.server.writer(1).remote() != oldRemote, "New AI seat view attached");
                JsonSeatRemote aiRemote = h.server.writer(1).remote();
                assertNull(aiRemote.abandonedReason()); assertFalse(aiRemote.isHumanSeat());
                try (WsTestClient viewer = new WsTestClient()) {
                    viewer.connect(java.net.http.HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=1");
                    viewer.hello(1); // Reuses the token minted for this exact seat/socket endpoint.
                    PromptAssertions.validate(PromptInsideActionTest.next(viewer, "state"), "state");
                    viewer.send(JsonParser.parseString("{\"type\":\"ok\",\"clientSeq\":1000,\"baseSeq\":0}").getAsJsonObject());
                    JsonObject rejected = PromptInsideActionTest.next(viewer, "message");
                    assertEquals(rejected.get("level").getAsString(), "rejected");
                    assertEquals(rejected.get("text").getAsString(), "This seat is now played by a bot");
                    assertEquals(viewer.closed.getCount(), 1L, "Original token remains valid");
                    assertTrue(console.submit("seats").get(5, TimeUnit.SECONDS).contains("Seat 1:"));
                    finish(h); lifecycle.finished();
                    h.until(lifecycle::ended, "Lifecycle marked game 2 ended");
                    h.sendAction("nextGame", "decision", new JsonPrimitive("quit"));
                    lifecycle.shutdown().get(5, TimeUnit.SECONDS);
                    h.until(() -> viewer.history().stream().anyMatch(m -> m.object().has("text")
                            && m.object().get("text").getAsString().endsWith(" left; the match is over")), "Explicit quit reaches AI viewer too");
                }
            } finally {
                // If a rematch assertion fails, cleanup must target the current game, not game 1.
                if (h.match.getGame() != null) PromptGameHarness.edt(() -> {
                    h.game = h.match.getGame();
                    h.human = (PlayerControllerHuman) ArenaServerMain.playerFor(h.game, h.players.get(0)).getController(); return null;
                });
            }
        }
    }
    private static void finish(PromptGameHarness h) throws Exception {
        PromptGameHarness.edt(() -> {
            h.game.getAction().invoke(() -> {
                for (var player : new ArrayList<>(h.game.getPlayers())) if (player != h.human.getPlayer()) player.concede();
                h.game.getAction().checkGameOverCondition();
                h.human.getInputQueue().onGameOver(true);
                if (h.secondaryHuman != null && h.game.getRegisteredPlayers().contains(h.secondaryHuman.getPlayer()))
                    h.secondaryHuman.getInputQueue().onGameOver(true);
            }); return null;
        });
        h.until(() -> h.match.getGame() == null || h.match.getGame().isGameOver(), "Current game ended normally");
    }

    @Test(timeOut = 120_000)
    public void socketsSurviveResultsAndRematchThenQuit() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, false)) {
            h.start("Forest", "");
            try (MatchLifecycle lifecycle = new MatchLifecycle(h.server, h.match, Set.of(0, 1))) {
                lifecycle.started();
                var first = h.game;
                List<Integer> order = new ArrayList<>();
                PromptGameHarness.edt(() -> {
                    h.game.getAction().invoke(() -> {
                        var players = new ArrayList<>(h.game.getPlayers());
                        order.add(players.get(0).getId()); order.add(players.get(1).getId());
                        order.add(players.get(3).getId()); order.add(players.get(2).getId());
                        // Separate eliminations, rather than three losses in one state-based check.
                        for (int seat : new int[]{2, 3, 1}) {
                            players.get(seat).concede(); h.game.getAction().checkGameOverCondition();
                        }
                        h.human.getInputQueue().onGameOver(true); h.secondaryHuman.getInputQueue().onGameOver(true);
                    }); return null;
                });
                h.until(h.match::isMatchOver, "Native game ended");
                lifecycle.finished();
                h.until(lifecycle::ended, "Lifecycle marked original game ended");
                h.until(() -> gameOver(h.client) != null, "gameOver received");
                JsonObject over = gameOver(h.client); PromptAssertions.validate(over, "event");
                assertEquals(over.getAsJsonArray("placements").asList().stream()
                        .map(p -> p.getAsJsonObject().get("playerId").getAsInt()).toList(), order);
                assertEquals(over.getAsJsonArray("placements").get(1).getAsJsonObject().get("reason").getAsString(), "Conceded");
                assertEquals(h.client.closed.getCount(), 1L); assertFalse(lifecycle.shutdown().isDone());
                int primaryBefore = h.client.history().size(), secondaryBefore = h.secondaryClient.history().size();
                // New game opens on its real mulligan Input; inspect the new view before answering it.
                h.client.onMessage = null; h.secondaryClient.onMessage = null;
                h.sendAction("nextGame", "decision", new JsonPrimitive("rematch"));
                h.until(() -> h.remote.completedActionCount() > 0, "First rematch vote");
                assertSame(h.match.getGame(), first, "One vote cannot restart a two-human match");
                h.secondaryAction("nextGame", "decision", new JsonPrimitive("rematch"));
                h.until(() -> h.match.getGame() != null && h.match.getGame() != first, "Fresh native NEW game");
                h.until(() -> newHelloAndState(h.client, primaryBefore) && newHelloAndState(h.secondaryClient, secondaryBefore), "Both existing sockets receive new hello and state");
                assertEquals(h.client.closed.getCount(), 1L); assertEquals(h.secondaryClient.closed.getCount(), 1L);
                assertFalse(h.remote.isDirty()); assertFalse(h.secondaryRemote.isDirty());
                // Refresh harness-owned references for bounded cleanup of the new live game.
                PromptGameHarness.edt(() -> {
                    h.game = h.match.getGame();
                    h.human = (PlayerControllerHuman) ArenaServerMain.playerFor(h.game, h.players.get(0)).getController();
                    h.secondaryHuman = (PlayerControllerHuman) ArenaServerMain.playerFor(h.game, h.players.get(1)).getController();
                    return null;
                });
                PromptGameHarness.edt(() -> {
                    h.game.getAction().invoke(() -> {
                        for (var player : new ArrayList<>(h.game.getPlayers())) if (player != h.human.getPlayer()) player.concede();
                        h.game.setGameOver(GameEndReason.AllOpponentsLost);
                        h.human.getInputQueue().onGameOver(true); h.secondaryHuman.getInputQueue().onGameOver(true);
                    }); return null;
                });
                h.until(() -> h.match.getGame() == null || h.match.getGame().isGameOver(), "Second game ended"); lifecycle.finished();
                h.until(lifecycle::ended, "Lifecycle marked rematch ended");
                h.sendAction("nextGame", "decision", new JsonPrimitive("quit"));
                lifecycle.shutdown().get(5, TimeUnit.SECONDS);
                h.until(() -> h.client.history().stream().anyMatch(m -> m.object().has("text")
                        && m.object().get("text").getAsString().equals(h.remote.seatName() + " left; the match is over")), "Quit notice delivered before shutdown");
            }
        }
    }
    private static JsonObject gameOver(WsTestClient client) {
        return client.history().stream().map(WsTestClient.Received::object)
                .filter(m -> m.has("kind") && m.get("kind").getAsString().equals("gameOver")).findFirst().orElse(null);
    }
    private static boolean newHelloAndState(WsTestClient client, int before) {
        var messages = client.history().stream().skip(before).map(WsTestClient.Received::object).toList();
        int hello = -1;
        for (int i = 0; i < messages.size(); i++) {
            String type = messages.get(i).get("type").getAsString();
            if (type.equals("hello")) hello = i;
            if (type.equals("state") && hello >= 0) { PromptAssertions.validate(messages.get(i), "state"); return true; }
        }
        return false;
    }
}
