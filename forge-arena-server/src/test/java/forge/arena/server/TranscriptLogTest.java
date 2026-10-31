package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import forge.arena.server.view.HiddenInfoAssertions;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gui.FThreads;
import forge.gui.control.WatchLocalGame;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

public class TranscriptLogTest {
    @BeforeClass public void initialize() {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
    }

    @DataProvider public Object[][] directories() { return new Object[][] {{false}, {true}}; }

    @Test(timeOut = 30_000)
    public void sameSecondGamesKeepSeparateFiles() throws Exception {
        Path directory = Files.createTempDirectory("arena-transcript-collision-");
        String oldDirectory = System.getProperty("arena.transcriptDir");
        System.setProperty("arena.transcriptDir", directory.toString());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "Arena-seat-writer-0"));
        try {
            var start = java.time.ZonedDateTime.parse("2026-10-30T12:34:56-04:00");
            executor.submit(() -> {
                for (int game = 1; game <= 3; game++) {
                    try (TranscriptLog log = new TranscriptLog(0, game, start, System.nanoTime())) {
                        log.record("in", "{\"type\":\"ping\"}", "accepted", System.nanoTime());
                    }
                }
                try (TranscriptLog log = new TranscriptLog(1, 4, start, System.nanoTime())) {
                    log.record("in", "{\"type\":\"ping\"}", "accepted", System.nanoTime());
                }
            }).get(5, TimeUnit.SECONDS);
            for (int game = 1; game <= 3; game++) {
                String suffix = game == 1 ? "" : "_" + game;
                Path file = directory.resolve("2026-10-30_ForgeArena_Transcript_123456_seat0" + suffix + ".jsonl");
                List<String> lines = Files.readAllLines(file);
                assertEquals(lines.size(), 2);
                assertEquals(JsonParser.parseString(lines.get(0)).getAsJsonObject().get("playerId").getAsInt(), game);
                assertEquals(JsonParser.parseString(lines.get(1)).getAsJsonObject().get("result").getAsString(), "accepted");
            }
            assertTrue(Files.exists(directory.resolve("2026-10-30_ForgeArena_Transcript_123456_seat1.jsonl")));
            try (var paths = Files.list(directory)) { assertEquals(paths.count(), 4L); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            if (oldDirectory == null) System.clearProperty("arena.transcriptDir");
            else System.setProperty("arena.transcriptDir", oldDirectory);
        }
    }

    @Test(timeOut = 30_000)
    public void recordsConnectionReplacementAndReplay() throws Exception {
        Path directory = Files.createTempDirectory("arena-transcript-reconnect-");
        String oldDirectory = System.getProperty("arena.transcriptDir");
        System.setProperty("arena.transcriptDir", directory.toString());
        try (WsServer server = new WsServer("127.0.0.1", 0);
             WsTestClient first = new WsTestClient(); WsTestClient second = new WsTestClient()) {
            server.start(); server.writer(0).bindPlayer(7, 7010);
            HttpClient http = HttpClient.newHttpClient();
            first.connect(http, server.getPort(), "/ws?seat=0"); first.hello(0);
            JsonObject state = new JsonObject(); state.addProperty("type", "state"); state.addProperty("marker", "cached");
            server.writer(0).send(state); assertEquals(first.receive(), state);
            second.connect(http, server.getPort(), "/ws?seat=0"); second.hello(0);
            assertEquals(second.receive(), state); assertTrue(first.closed.await(5, TimeUnit.SECONDS));
            server.close();
            Path file;
            try (var paths = Files.list(directory)) { file = paths.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow(); }
            List<String> output = new ArrayList<>(), markers = new ArrayList<>();
            List<String> lines = Files.readAllLines(file);
            for (String line : lines.subList(1, lines.size())) {
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                if (record.get("dir").getAsString().equals("out")) output.add(WsServer.GSON.toJson(record.get("msg")));
                if (record.get("dir").getAsString().equals("conn")) markers.add(record.getAsJsonObject("msg").get("type").getAsString());
            }
            List<String> received = new ArrayList<>(first.history().stream().map(WsTestClient.Received::json).toList());
            received.addAll(second.history().stream().map(WsTestClient.Received::json).toList());
            assertEquals(output, received);
            assertEquals(markers, List.of("connected", "replaced", "closed"));
        } finally {
            if (oldDirectory == null) System.clearProperty("arena.transcriptDir");
            else System.setProperty("arena.transcriptDir", oldDirectory);
        }
    }

    @Test(timeOut = 30_000)
    public void startsAnotherFileForAnotherGame() throws Exception {
        Path directory = Files.createTempDirectory("arena-transcript-rotation-");
        String oldDirectory = System.getProperty("arena.transcriptDir");
        System.setProperty("arena.transcriptDir", directory.toString());
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.start(); server.writer(0).bindPlayer(7, 7001);
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            JsonObject first = new JsonObject(); first.addProperty("type", "message");
            first.addProperty("level", "info"); first.addProperty("text", "first game");
            server.writer(0).send(first); assertEquals(client.receive(), first);
            Thread.sleep(1100); // File names contain whole seconds; make both game start times distinct.
            server.writer(0).bindPlayer(7, 7002);
            JsonObject second = first.deepCopy(); second.addProperty("text", "second game");
            server.writer(0).send(second); assertEquals(client.receive(), second);
            server.close();
            List<Path> files;
            try (var paths = Files.list(directory)) { files = paths.filter(p -> p.toString().endsWith(".jsonl")).toList(); }
            assertEquals(files.size(), 2, "One file per game");
            String contents = Files.readString(files.get(0));
            String other = Files.readString(files.get(1));
            assertTrue(contents.contains("first game") != other.contains("first game"));
            assertTrue(contents.contains("second game") != other.contains("second game"));
            assertFalse(contents.contains("first game") && contents.contains("second game"));
            assertFalse(other.contains("first game") && other.contains("second game"));
        } finally {
            if (oldDirectory == null) System.clearProperty("arena.transcriptDir");
            else System.setProperty("arena.transcriptDir", oldDirectory);
        }
    }

    private static final class Stopper {
        Game game;
        boolean conceded;
        final AtomicBoolean stop = new AtomicBoolean();
        @Subscribe public void turn(GameEventTurnBegan event) {
            if (event.turnNumber() >= 12 && !conceded) {
                conceded = true;
                List<Player> players = new ArrayList<>(game.getPlayers());
                for (int i = 1; i < 4; i++) players.get(i).concede();
            }
        }
        @Subscribe public void priority(GameEventPlayerPriority event) {
            if (stop.get() && !game.isGameOver()) game.setGameOver(GameEndReason.Draw);
        }
    }

    @Test(dataProvider = "directories", timeOut = 180_000)
    public void recordsFourSeatsAndSurvivesIoFailure(boolean badDirectory) throws Exception {
        Path directory = Files.createTempDirectory("arena-transcript-");
        Path target = badDirectory ? Files.createFile(directory.resolve("not-a-directory")) : directory;
        String oldDirectory = System.getProperty("arena.transcriptDir");
        System.setProperty("arena.transcriptDir", target.toString());
        MyRandom.setRandom(new Random(7));
        WsServer server = new WsServer("127.0.0.1", 0);
        List<SeatProtocolGui> views = new ArrayList<>();
        List<WsTestClient> clients = new ArrayList<>();
        JsonSeatRemote[] remotes = new JsonSeatRemote[4];
        String[] hiddenRefs = new String[4];
        AtomicReference<PlayerControllerHuman> playback = new AtomicReference<>();
        Stopper stopper = new Stopper();
        CountDownLatch finished = new CountDownLatch(1);
        HostedMatch match = new HostedMatch() {
            @Override public void registerSpectator(IGuiGame gui, PlayerControllerHuman controller) {
                stopper.game = getGame(); getGame().subscribeToEvents(stopper);
                for (int seat = 0; seat < 4; seat++) {
                    remotes[seat] = new JsonSeatRemote(getGame(), getGame().getPlayers().get(seat).getView(), server.writer(seat));
                    views.add(SeatProtocolGui.attach(getGame(), remotes[seat]));
                }
                ((ProtocolGuiGame) gui).pause();
                SeatProtocolGui playbackGui = new SeatProtocolGui(new LoggingRemote()) {
                    @Override public boolean isUiSetToSkipPhase(PlayerView player, PhaseType phase) { return true; }
                };
                playbackGui.pause(); playbackGui.setGameView(getGame().getView());
                WatchLocalGame watcher = new WatchLocalGame(getGame(), null, playbackGui);
                playback.set(watcher); super.registerSpectator(playbackGui, watcher);
            }
        };
        match.setStartGameHook(() -> {
            match.getGame().getRules().setGamesPerMatch(1);
            Player owner = match.getGame().getPlayers().get(0);
            Card secret = owner.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Consecrated Sphinx"))
                    .findFirst().orElseGet(() -> match.getGame().getAction().moveToHand(
                            owner.getCardsIn(ZoneType.Library).stream().filter(c -> c.getName().equals("Consecrated Sphinx"))
                                    .findFirst().orElseThrow(), null));
            for (SeatProtocolGui view : views) { view.getForwarder().flush(); view.updateGameView(); }
            for (int seat = 1; seat < 4; seat++) {
                hiddenRefs[seat] = (String) remotes[seat].getIds().currentRef(secret.getId()).orElseThrow();
            }
            FThreads.invokeInEdtLater(() -> playback.get().selectButtonCancel());
        });
        match.setEndGameHook(() -> {
            for (SeatProtocolGui view : views) { view.getForwarder().flush(); view.updateGameView(); }
            finished.countDown();
        });
        try {
            server.start(); HttpClient http = HttpClient.newHttpClient();
            for (int seat = 0; seat < 4; seat++) {
                WsTestClient client = new WsTestClient(); clients.add(client);
                client.connect(http, server.getPort(), "/ws?seat=" + seat);
            }
            List<RegisteredPlayer> players = new ArrayList<>();
            for (int seat = 0; seat < 4; seat++) {
                Deck deck = new Deck("Transcript " + seat); deck.getMain().add("Plains", 98);
                deck.getMain().add("Drifting Meadow", 1);
                if (seat == 0) deck.getMain().add("Consecrated Sphinx", 1);
                deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
                players.add(RegisteredPlayer.forCommander(deck).setPlayer(GamePlayerUtil.createAiPlayer("Transcript " + seat)));
            }
            match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Collections.emptyMap());
            Game game = match.getGame();
            int[] ids = new int[4];
            for (int seat = 0; seat < 4; seat++) {
                ids[seat] = ArenaServerMain.playerFor(game, players.get(seat)).getId();
                assertEquals(clients.get(seat).hello(seat).get("playerId").getAsInt(), ids[seat]);
                clients.get(seat).send(JsonParser.parseString("{\"type\":\"ping\"}").getAsJsonObject());
                clients.get(seat).send(JsonParser.parseString("{\"type\":\"concede\",\"clientSeq\":1,\"baseSeq\":0}").getAsJsonObject());
            }
            assertTrue(finished.await(120, TimeUnit.SECONDS), "Game finished");
            for (int seat = 0; seat < 4; seat++) {
                long expected = remotes[seat].getLastStateSequence();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    JsonObject message = clients.get(seat).receive();
                    if ("state".equals(message.get("type").getAsString()) && message.get("seq").getAsLong() == expected) break;
                }
            }
            server.close();
            for (WsTestClient client : clients) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.closed.getCount() != 0 && client.error == null && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(client.closed.getCount() == 0 || client.error != null, "Socket reached closure");
            }
            if (!badDirectory) {
                List<Path> files;
                try (var paths = Files.list(directory)) { files = paths.filter(p -> p.toString().endsWith(".jsonl")).toList(); }
                assertEquals(files.size(), 4);
                for (int seat = 0; seat < 4; seat++) {
                    int index = seat;
                    Path file = files.stream().filter(p -> p.toString().endsWith("_seat" + index + ".jsonl")).findFirst().orElseThrow();
                    List<String> lines = Files.readAllLines(file);
                    JsonObject header = JsonParser.parseString(lines.get(0)).getAsJsonObject();
                    assertEquals(header.get("type").getAsString(), "transcriptHeader");
                    assertEquals(header.get("seat").getAsInt(), seat); assertEquals(header.get("playerId").getAsInt(), ids[seat]);
                    List<String> output = new ArrayList<>(); long previous = 0; boolean scannedSecret = false;
                    boolean accepted = false, rejected = false;
                    for (String line : lines.subList(1, lines.size())) {
                        JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                        assertTrue(record.get("t").getAsLong() >= 0);
                        if ("in".equals(record.get("dir").getAsString())) {
                            String type = record.getAsJsonObject("msg").get("type").getAsString();
                            if (type.equals("ping")) accepted = "accepted".equals(record.get("result").getAsString());
                            if (type.equals("concede")) rejected = "rejected".equals(record.get("result").getAsString());
                        }
                        if (!"out".equals(record.get("dir").getAsString())) continue;
                        JsonObject message = record.getAsJsonObject("msg");
                        output.add(WsServer.GSON.toJson(message));
                        if (seat > 0 && output.get(output.size() - 1).contains(hiddenRefs[seat])) {
                            HiddenInfoAssertions.noHiddenName(message, "Consecrated Sphinx"); scannedSecret = true;
                        }
                        String type = message.get("type").getAsString();
                        if (!type.equals("hello") && !type.equals("logTail")) {
                            assertTrue(message.get("seq").getAsLong() > previous); previous = message.get("seq").getAsLong();
                        }
                        if (type.equals("state")) for (var p : message.getAsJsonArray("players")) {
                            JsonObject player = p.getAsJsonObject();
                            if (player.get("id").getAsInt() == ids[seat]) continue;
                            for (var card : player.getAsJsonObject("zones").getAsJsonArray("hand")) {
                                assertEquals(card.getAsJsonObject().keySet(), java.util.Set.of("id", "hidden"));
                            }
                        }
                    }
                    assertEquals(output, clients.get(seat).history().stream().map(WsTestClient.Received::json).toList());
                    assertTrue(accepted && rejected, "Inbound outcomes recorded");
                    if (seat > 0) assertTrue(scannedSecret, "Hidden hand card appeared in the recorded stream");
                    if (seat == 0) System.out.println("[transcript] seat=0 bytes=" + Files.size(file));
                }
            }
        } finally {
            if (stopper.game != null && !stopper.game.isGameOver()) {
                stopper.stop.set(true); finished.await(10, TimeUnit.SECONDS);
            }
            match.endCurrentGame(); server.close(); clients.forEach(WsTestClient::close);
            if (oldDirectory == null) System.clearProperty("arena.transcriptDir");
            else System.setProperty("arena.transcriptDir", oldDirectory);
        }
    }
}
