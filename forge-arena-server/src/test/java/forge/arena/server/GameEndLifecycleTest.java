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
                h.until(h.match::isMatchOver, "Second game ended"); lifecycle.finished();
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
