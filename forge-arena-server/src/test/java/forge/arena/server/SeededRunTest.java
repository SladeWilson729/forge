package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.game.event.GameEventShuffle;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.*;

import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.*;

/** TestNG lifecycle tests; actual Forge games and sockets use the existing server harness. */
public class SeededRunTest {
    private final Map<String, String> properties = new HashMap<>();
    private Path directory;

    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }
    @BeforeMethod public void setup() throws Exception {
        PromptGameHarness.initialize();
        for (String key : List.of("arena.seed", "arena.commit", "arena.transcriptDir", "arena.summary", "arena.deck.seat0")) properties.put(key, System.getProperty(key));
        directory = Files.createTempDirectory("arena-seeded-run-");
        System.setProperty("arena.seed", "123456789"); System.setProperty("arena.commit", "test-commit");
        System.setProperty("arena.transcriptDir", directory.toString());
        System.setProperty("arena.summary", directory.resolve("summary.json").toString());
    }
    @AfterMethod(alwaysRun = true) public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private RunSummary summary() { RunSummary run = new RunSummary(); run.configureSeed(); return run; }
    private JsonObject written(RunSummary run, RunSummary.Exit exit) throws Exception {
        run.write(exit);
        JsonObject json = JsonParser.parseString(Files.readString(run.path())).getAsJsonObject();
        assertEquals(json.get("exitReason").getAsString(), exit.reason()); assertEquals(json.get("exitCode").getAsInt(), exit.code());
        assertEquals(json.get("serverCommit").getAsString(), "test-commit");
        assertTrue(json.get("durationMs").getAsLong() >= 0);
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
        return json;
    }

    public static final class OpeningShuffles {
        Game game;
        final Map<Integer, List<String>> libraries = new TreeMap<>();
        @Subscribe public void shuffle(GameEventShuffle event) {
            var player = game.getPlayer(event.player());
            libraries.computeIfAbsent(player.getId(), ignored -> player.getCardsIn(ZoneType.Library).stream().map(c -> c.getName()).toList());
        }
    }
    private Map<Integer, List<String>> opening(long seed) throws Exception {
        System.setProperty("arena.seed", Long.toString(seed)); RunSummary run = summary();
        OpeningShuffles capture = new OpeningShuffles(); CountDownLatch ended = new CountDownLatch(1);
        HostedMatch match = new HostedMatch() {
            @Override public void startGame() { run.beforeGame(); super.startGame(); }
            @Override public void registerSpectator(IGuiGame gui, PlayerControllerHuman controller) {
                capture.game = getGame(); getGame().subscribeToEvents(capture);
                ((ProtocolGuiGame) gui).pause(); super.registerSpectator(gui, controller);
            }
        };
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            Deck deck = new Deck("Seed probe " + seat);
            for (String name : List.of("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes", "Drifting Meadow", "Lonely Sandbar", "Barren Moor", "Forgotten Cave", "Tranquil Thicket")) deck.getMain().add(name, 9);
            deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
            players.add(RegisteredPlayer.forCommander(deck).setPlayer(GamePlayerUtil.createAiPlayer("Seed " + seat)));
        }
        match.setStartGameHook(() -> {
            Game game = match.getGame(); game.getRules().setGamesPerMatch(1);
            for (var player : new ArrayList<>(game.getPlayers()).subList(1, 4)) player.concede();
            game.setGameOver(GameEndReason.AllOpponentsLost);
        });
        match.setEndGameHook(ended::countDown);
        try {
            PromptGameHarness.edt(() -> { match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Map.of()); return null; });
            assertTrue(ended.await(30, TimeUnit.SECONDS), "Initial shuffle game ended");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!match.isMatchOver() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(match.isMatchOver(), "Native post-game flush finished before the next seeded run");
            assertEquals(capture.libraries.size(), 4);
            capture.libraries.values().forEach(cards -> assertEquals(cards.size(), 99));
            return Map.copyOf(capture.libraries);
        } finally {
            if (capture.game != null && !capture.game.isGameOver()) {
                FutureTask<Void> stop = new FutureTask<>(() -> { capture.game.setGameOver(GameEndReason.Draw); return null; });
                capture.game.getAction().invoke(stop); stop.get(5, TimeUnit.SECONDS); ended.await(5, TimeUnit.SECONDS);
            }
            PromptGameHarness.edt(() -> { match.endCurrentGame(); return null; });
        }
    }

    @Test(timeOut = 120_000) public void openingShufflesRepeatForEverySeat() throws Exception {
        Map<Integer, List<String>> first = opening(34567), second = opening(34567), different = opening(-98765);
        assertEquals(second, first);
        for (int seat = 0; seat < 4; seat++) assertNotEquals(different.get(seat), first.get(seat), "Different seed changes seat " + seat + "'s shuffle");
    }

    @Test public void gameIndexesAndHeadersIncludeRematchSeedsAndOverflow() throws Exception {
        System.setProperty("arena.seed", Long.toString(Long.MAX_VALUE)); RunSummary run = summary();
        for (int index = 0; index < 2; index++) {
            run.beforeGame();
            JsonObject game = run.snapshot(new RunSummary.Exit("quit", 0, "test")).getAsJsonArray("games").get(index).getAsJsonObject();
            long seed = Long.MAX_VALUE + index;
            assertEquals(game.get("gameIndex").getAsInt(), index); assertEquals(game.get("seed").getAsLong(), seed);
            try (TranscriptLog log = new TranscriptLog(2, 9, java.time.ZonedDateTime.now(), System.nanoTime(), index, seed, "test-commit", file -> { })) { log.flush(); }
        }
        try (var files = Files.list(directory)) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList(); assertEquals(paths.size(), 2);
            Set<Long> seeds = new HashSet<>(); Set<Integer> indexes = new HashSet<>();
            for (Path path : paths) {
                JsonObject header = JsonParser.parseString(Files.readAllLines(path).get(0)).getAsJsonObject();
                seeds.add(header.get("seed").getAsLong()); indexes.add(header.get("gameIndex").getAsInt());
                assertEquals(header.get("serverCommit").getAsString(), "test-commit");
            }
            assertEquals(seeds, Set.of(Long.MAX_VALUE, Long.MIN_VALUE)); assertEquals(indexes, Set.of(0, 1));
        }
    }

    @Test(timeOut = 150_000) public void summariesCoverAllLifecycleExitReasons() throws Exception {
        for (String reason : List.of("finished", "quit", "aborted", "timeout", "error")) {
            RunSummary run = summary(); AtomicLong time = new AtomicLong(100);
            try (PromptGameHarness h = new PromptGameHarness(true)) {
                run.seats(h.players); h.server.runSummary(run); run.beforeGame();
                try (MatchLifecycle lifecycle = new MatchLifecycle(h.server, h.match, Set.of(0), time::get, 10, false);
                     SeatAbandonment console = new SeatAbandonment(h.server, new StringReader(""), System.out, 180, System::nanoTime)) {
                    console.matchCommand = lifecycle::command;
                    h.start("Forest", ""); lifecycle.started();
                    assertTrue(h.server.writer(0).connected());
                    if (reason.equals("finished")) {
                        FutureTask<Void> end = new FutureTask<>(() -> {
                            for (var p : new ArrayList<>(h.game.getPlayers())) if (p != h.human.getPlayer()) p.concede();
                            h.game.setGameOver(GameEndReason.AllOpponentsLost); run.finished(h.game);
                            h.human.getInputQueue().onGameOver(true); return null;
                        });
                        h.game.getAction().invoke(end); end.get(5, TimeUnit.SECONDS);
                        assertTrue(h.finished.await(10, TimeUnit.SECONDS)); lifecycle.finished(); h.until(lifecycle::ended, "Lifecycle ended");
                        console.submit("quit").get(5, TimeUnit.SECONDS);
                    } else if (reason.equals("timeout")) { time.addAndGet(11); lifecycle.tick(); }
                    else if (reason.equals("error")) lifecycle.failure(new IllegalStateException("Injected game failure"));
                    else console.submit(reason.equals("aborted") ? "abort" : "quit").get(5, TimeUnit.SECONDS);
                    lifecycle.shutdown().get(5, TimeUnit.SECONDS);
                    assertEquals(lifecycle.result().reason(), reason);
                    if (reason.equals("aborted")) {
                        assertEquals(lifecycle.result().code(), 3); assertTrue(h.server.writer(0).connected());
                        assertNull(h.remote.abandonedReason());
                        assertFalse(PromptGameHarness.edt(() -> h.human.getPlayer().conceded()));
                        assertFalse(h.game.isGameOver());
                    }
                    JsonObject json = written(run, lifecycle.result());
                    assertEquals(json.getAsJsonArray("games").size(), 1);
                    JsonObject game = json.getAsJsonArray("games").get(0).getAsJsonObject();
                    assertEquals(game.getAsJsonArray("seats").size(), 4); assertEquals(game.getAsJsonArray("placements").size(), 4);
                    assertEquals(game.get("seed").getAsLong(), 123456789L);
                    assertTrue(game.get("randomCalls").getAsLong() > 0);
                    if (reason.equals("finished")) { assertEquals(game.get("winnerSeat").getAsInt(), 0); assertFalse(game.get("draw").getAsBoolean()); }
                    assertEquals(json.getAsJsonArray("transcripts").size(), 4);
                    for (var value : json.getAsJsonArray("transcripts")) {
                        JsonObject file = value.getAsJsonObject(); assertEquals(file.get("gameIndex").getAsInt(), 0);
                        JsonObject header = JsonParser.parseString(Files.readAllLines(directory.resolve(file.get("file").getAsString())).get(0)).getAsJsonObject();
                        assertEquals(header.get("seat"), file.get("seat")); assertEquals(header.get("seed"), game.get("seed"));
                        assertEquals(header.get("serverCommit").getAsString(), "test-commit");
                    }
                }
            }
        }
    }

    @Test(timeOut = 120_000) public void consoleTakeoversRecordTheirKindAndTurn() throws Exception {
        for (boolean bot : List.of(true, false)) {
            RunSummary run = summary();
            try (PromptGameHarness h = new PromptGameHarness();
                 SeatAbandonment console = new SeatAbandonment(h.server, new StringReader(""), System.out, 180, System::nanoTime)) {
                run.seats(h.players); h.server.runSummary(run); run.beforeGame();
                SeatAbandonmentTest.EndOnPriority[] end = new SeatAbandonmentTest.EndOnPriority[1];
                h.extraSetup = game -> { end[0] = new SeatAbandonmentTest.EndOnPriority(game); game.subscribeToEvents(end[0]); };
                h.start("Forest", "");
                int turn = PromptGameHarness.edt(() -> h.game.getPhaseHandler().getTurn());
                h.client.close(); h.until(() -> !h.server.writer(0).connected(), "Disconnected before takeover");
                end[0].finish.set(true);
                var handoff = console.submit((bot ? "bot" : "concede") + " 0");
                h.until(() -> h.remote.abandonedReason() != null, "Abandonment flagged");
                // The handoff retires this human's Input; there is no next Input to advance to.
                assertEquals(handoff.get(10, TimeUnit.SECONDS), bot ? "Bot handoff complete" : "Seat conceded");
                assertTrue(h.finished.await(10, TimeUnit.SECONDS));
                JsonObject json = written(run, new RunSummary.Exit("finished", 0, "Takeover game ended"));
                JsonObject seat = json.getAsJsonArray("games").get(0).getAsJsonObject().getAsJsonArray("seats").get(0).getAsJsonObject();
                assertEquals(seat.get("kind").getAsString(), "human"); assertEquals(seat.get("takeover").getAsString(), bot ? "bot" : "conceded");
                assertEquals(seat.get("takeoverTurn").getAsInt(), turn);
            }
        }
    }

    @Test(timeOut = 90_000) public void badDeckStartupUsesTheMainShutdownSummary() throws Exception {
        System.setProperty("arena.deck.seat0", directory.resolve("missing-deck.txt").toString());
        assertEquals(ArenaServerMain.runServer(), 1);
        JsonObject json = JsonParser.parseString(Files.readString(directory.resolve("summary.json"))).getAsJsonObject();
        assertEquals(json.get("exitReason").getAsString(), "startupError");
        assertTrue(json.get("message").getAsString().contains("Cannot read deck file"));
        assertTrue(json.getAsJsonArray("games").isEmpty());
    }
}
