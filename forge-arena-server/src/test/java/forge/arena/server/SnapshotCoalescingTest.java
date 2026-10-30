package forge.arena.server;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventAddLog;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.FThreads;
import forge.player.GamePlayerUtil;
import forge.trackable.TrackableProperty;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class SnapshotCoalescingTest {
    @BeforeClass public void initialize() { ArenaServerMain.initializeForge(); }

    @DataProvider public Object[][] owners() { return new Object[][] {{false}, {true}}; }

    private static FutureTask<Void> dispatch(Game game, boolean edt, Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        if (edt) FThreads.invokeInEdtLater(task);
        else game.getAction().invoke(task);
        return task;
    }

    private static Game scenario() {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            players.add(new RegisteredPlayer(new Deck("Overlap " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("Overlap " + i)));
        }
        GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);
        Game game = new Game(players, rules, new Match(rules, players, "Snapshot overlap"));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        return game; // No game loop: only the two explicitly coordinated Forge-safe callers.
    }

    @Test(dataProvider = "owners", timeOut = 30_000)
    public void overlappingRequestsRebuildOnTheOwnerAndKeepEvents(boolean edtOwner) throws Exception {
        Game game = PromptGameHarness.edt(SnapshotCoalescingTest::scenario);
        CountDownLatch firstBuilt = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger(), inBuild = new AtomicInteger(), peak = new AtomicInteger();
        List<String> threads = new CopyOnWriteArrayList<>();
        List<JsonObject> built = new CopyOnWriteArrayList<>();
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.start();
            JsonSeatRemote remote = new JsonSeatRemote(game, game.getPlayers().get(0).getView(), server.writer(0));
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            remote.setSnapshotBuiltObserver(state -> {
                peak.accumulateAndGet(inBuild.incrementAndGet(), Math::max);
                threads.add(Thread.currentThread().getName());
                built.add(state.deepCopy());
                try {
                    if (builds.incrementAndGet() == 1) {
                        firstBuilt.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS), "Release first build");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new AssertionError(ex);
                } finally { inBuild.decrementAndGet(); }
            });
            FutureTask<Void> owner = dispatch(game, edtOwner,
                    () -> remote.send(new GuiGameEvent(ProtocolMethod.setGameView, game.getView())));
            try {
                assertTrue(firstBuilt.await(5, TimeUnit.SECONDS), "First build entered");
                // The first walk has finished and is latched before publication. Mutate only
                // on the other allowed thread; every request must return while the owner waits.
                dispatch(game, !edtOwner, () -> {
                    for (int life : List.of(25, 26, 27)) {
                        var player = game.getPlayers().get(0);
                        player.setLife(life, null);
                        DeltaPacket delta = new DeltaPacket(life,
                                Map.of(DeltaPacket.makeDeltaKey(player.getView()), Map.of(TrackableProperty.Life, life)),
                                Map.of(), 0, new int[0]);
                        delta.setEvents(List.of(new GameEventAddLog(GameLogEntryType.INFORMATION, "overlap " + life)));
                        remote.send(new GuiGameEvent(ProtocolMethod.applyDelta, delta));
                    }
                    remote.send(new GuiGameEvent(ProtocolMethod.applyDelta, DeltaPacket.eventsOnly(
                            List.of(new GameEventAddLog(GameLogEntryType.INFORMATION, "events only")))));
                }).get(5, TimeUnit.SECONDS);
                assertEquals(builds.get(), 1, "Contenders must not start another build");
            } finally { release.countDown(); owner.get(5, TimeUnit.SECONDS); }
            assertFalse(remote.isDirty(), "No failed or abandoned snapshot");
            assertEquals(builds.get(), 2, "A burst of dirty requests needs exactly one extra build");
            assertEquals(peak.get(), 1, "Builds never overlap");
            assertEquals(threads.get(1), threads.get(0), "The owner performs the rebuild");
            assertTrue(threads.get(0).startsWith(edtOwner ? "AWT-EventQueue" : "Game-"));
            assertNotEquals(built.get(0).getAsJsonArray("players").get(0).getAsJsonObject().get("life").getAsInt(), 27);
            List<String> logs = new ArrayList<>();
            long finalSeq = remote.getLastStateSequence();
            JsonObject lastState = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((lastState == null || lastState.get("seq").getAsLong() < finalSeq || logs.size() < 4)
                    && System.nanoTime() < deadline) {
                JsonObject message = client.receive();
                if (message.get("type").getAsString().equals("state")) {
                    PromptAssertions.validate(message, "state"); lastState = message;
                } else if (message.get("type").getAsString().equals("log")) {
                    PromptAssertions.validate(message, "log"); logs.add(message.get("text").getAsString());
                }
            }
            assertNotNull(lastState);
            assertEquals(lastState.get("seq").getAsLong(), finalSeq);
            assertEquals(lastState.getAsJsonArray("players").get(0).getAsJsonObject().get("life").getAsInt(), 27);
            assertEquals(logs, List.of("overlap 25", "overlap 26", "overlap 27", "events only"));
            assertNull(client.error);
            System.out.printf("[snapshot-overlap] owner=%s builds=%d peak=%d finalLife=27 logs=%d%n",
                    threads.get(0), builds.get(), peak.get(), logs.size());
        } finally { release.countDown(); }
    }
}
