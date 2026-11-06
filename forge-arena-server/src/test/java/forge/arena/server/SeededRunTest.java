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
import org.junit.*;

import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** JUnit lifecycle tests; actual Forge games and sockets use the existing server harness. */
public class SeededRunTest {
    private final Map<String, String> properties = new HashMap<>();
    private Path directory;

    @BeforeClass public static void initialize() { PromptGameHarness.initialize(); }
    @Before public void setup() throws Exception {
        PromptGameHarness.initialize();
        for (String key : List.of("arena.seed", "arena.commit", "arena.transcriptDir", "arena.summary", "arena.deck.seat0")) properties.put(key, System.getProperty(key));
        directory = Files.createTempDirectory("arena-seeded-run-");
        System.setProperty("arena.seed", "123456789"); System.setProperty("arena.commit", "test-commit");
        System.setProperty("arena.transcriptDir", directory.toString());
        System.setProperty("arena.summary", directory.resolve("summary.json").toString());
    }
    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private RunSummary summary() { RunSummary run = new RunSummary(); run.configureSeed(); return run; }
    private JsonObject written(RunSummary run, RunSummary.Exit exit) throws Exception {
        run.write(exit);
        JsonObject json = JsonParser.parseString(Files.readString(run.path())).getAsJsonObject();
        assertEquals(exit.reason(), json.get("exitReason").getAsString()); assertEquals(exit.code(), json.get("exitCode").getAsInt());
        assertEquals("test-commit", json.get("serverCommit").getAsString());
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
            assertTrue("Initial shuffle game ended", ended.await(30, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!match.isMatchOver() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("Native post-game flush finished before the next seeded run", match.isMatchOver());
            assertEquals(4, capture.libraries.size());
            capture.libraries.values().forEach(cards -> assertEquals(99, cards.size()));
            return Map.copyOf(capture.libraries);
        } finally {
            if (capture.game != null && !capture.game.isGameOver()) {
                FutureTask<Void> stop = new FutureTask<>(() -> { capture.game.setGameOver(GameEndReason.Draw); return null; });
                capture.game.getAction().invoke(stop); stop.get(5, TimeUnit.SECONDS); ended.await(5, TimeUnit.SECONDS);
            }
            PromptGameHarness.edt(() -> { match.endCurrentGame(); return null; });
        }
    }

    @Test(timeout = 120_000) public void openingShufflesRepeatForEverySeat() throws Exception {
        Map<Integer, List<String>> first = opening(34567), second = opening(34567), different = opening(-98765);
        assertEquals(first, second);
        for (int seat = 0; seat < 4; seat++) assertNotEquals("Different seed changes seat " + seat + "'s shuffle", first.get(seat), different.get(seat));
    }

    @Test public void gameIndexesAndHeadersIncludeRematchSeedsAndOverflow() throws Exception {
        System.setProperty("arena.seed", Long.toString(Long.MAX_VALUE)); RunSummary run = summary();
        for (int index = 0; index < 2; index++) {
            run.beforeGame();
            JsonObject game = run.snapshot(new RunSummary.Exit("quit", 0, "test")).getAsJsonArray("games").get(index).getAsJsonObject();
            long seed = Long.MAX_VALUE + index;
            assertEquals(index, game.get("gameIndex").getAsInt()); assertEquals(seed, game.get("seed").getAsLong());
            try (TranscriptLog log = new TranscriptLog(2, 9, java.time.ZonedDateTime.now(), System.nanoTime(), index, seed, "test-commit", file -> { })) { log.flush(); }
        }
        try (var files = Files.list(directory)) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList(); assertEquals(2, paths.size());
            Set<Long> seeds = new HashSet<>(); Set<Integer> indexes = new HashSet<>();
            for (Path path : paths) {
                JsonObject header = JsonParser.parseString(Files.readAllLines(path).get(0)).getAsJsonObject();
                seeds.add(header.get("seed").getAsLong()); indexes.add(header.get("gameIndex").getAsInt());
                assertEquals("test-commit", header.get("serverCommit").getAsString());
            }
            assertEquals(Set.of(Long.MAX_VALUE, Long.MIN_VALUE), seeds); assertEquals(Set.of(0, 1), indexes);
        }
    }

    @Test(timeout = 150_000) public void summariesCoverAllLifecycleExitReasons() throws Exception {
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
                    assertEquals(reason, lifecycle.result().reason());
                    if (reason.equals("aborted")) {
                        assertEquals(3, lifecycle.result().code()); assertTrue(h.server.writer(0).connected());
                        assertNull(h.remote.abandonedReason());
                        assertFalse(PromptGameHarness.edt(() -> h.human.getPlayer().conceded()));
                        assertFalse(h.game.isGameOver());
                    }
                    JsonObject json = written(run, lifecycle.result());
                    assertEquals(1, json.getAsJsonArray("games").size());
                    JsonObject game = json.getAsJsonArray("games").get(0).getAsJsonObject();
                    assertEquals(4, game.getAsJsonArray("seats").size()); assertEquals(4, game.getAsJsonArray("placements").size());
                    assertEquals(123456789L, game.get("seed").getAsLong());
                    assertTrue(game.get("randomCalls").getAsLong() > 0);
                    if (reason.equals("finished")) { assertEquals(0, game.get("winnerSeat").getAsInt()); assertFalse(game.get("draw").getAsBoolean()); }
                    assertEquals(4, json.getAsJsonArray("transcripts").size());
                    for (var value : json.getAsJsonArray("transcripts")) {
                        JsonObject file = value.getAsJsonObject(); assertEquals(0, file.get("gameIndex").getAsInt());
                        JsonObject header = JsonParser.parseString(Files.readAllLines(directory.resolve(file.get("file").getAsString())).get(0)).getAsJsonObject();
                        assertEquals(file.get("seat"), header.get("seat")); assertEquals(game.get("seed"), header.get("seed"));
                        assertEquals("test-commit", header.get("serverCommit").getAsString());
                    }
                }
            }
        }
    }

    @Test(timeout = 90_000) public void badDeckStartupUsesTheMainShutdownSummary() throws Exception {
        System.setProperty("arena.deck.seat0", directory.resolve("missing-deck.txt").toString());
        assertEquals(1, ArenaServerMain.runServer());
        JsonObject json = JsonParser.parseString(Files.readString(directory.resolve("summary.json"))).getAsJsonObject();
        assertEquals("startupError", json.get("exitReason").getAsString());
        assertTrue(json.get("message").getAsString().contains("Cannot read deck file"));
        assertTrue(json.getAsJsonArray("games").isEmpty());
    }
}
