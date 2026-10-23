package forge.arena.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.eventbus.Subscribe;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.GameEndReason;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
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
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.*;

public class SeatStreamTest {
    /** Normal termination and failure cleanup both execute only on the live game thread. */
    public static final class Stopper {
        private final AtomicBoolean requested = new AtomicBoolean();
        private Game game;
        private boolean conceded;

        @Subscribe
        public void onTurn(final GameEventTurnBegan event) {
            if (event.turnNumber() >= 12 && !conceded && !game.isGameOver()) {
                conceded = true;
                // Capture the opponents before conceding; subsequent state checks remove losers.
                final List<Player> players = new ArrayList<>(game.getPlayers());
                for (int seat = 1; seat < 4; seat++) {
                    players.get(seat).concede();
                }
            }
        }

        @Subscribe
        public void onPriority(final GameEventPlayerPriority event) {
            if (requested.get() && !game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }
    }

    @BeforeClass
    public void initialize() {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
    }

    private static Deck deck(final String name) {
        // Same legal synthetic decks as SeatBridgeSmokeTest.
        final Deck deck = new Deck(name);
        deck.getMain().add("Plains", 98);
        deck.getMain().add("Drifting Meadow", 1);
        deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
        return deck;
    }

    @Test(timeOut = 120_000)
    public void streamsPrivateStatesAndReplaysCacheAfterGameOver() throws Exception {
        MyRandom.setRandom(new Random(7));
        final JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                SchemaLocation.of(Path.of("../protocol/schema.json").toAbsolutePath().normalize().toUri() + "#/$defs/state"));
        final WsServer server = new WsServer("127.0.0.1", 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<SeatProtocolGui> views = new ArrayList<>();
        final JsonSeatRemote[] remotes = new JsonSeatRemote[4];
        final AtomicReference<PlayerControllerHuman> playback = new AtomicReference<>();
        final Stopper stopper = new Stopper();
        final HostedMatch match = new HostedMatch() {
            @Override
            public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman controller) {
                stopper.game = getGame();
                getGame().subscribeToEvents(stopper);
                for (int seat = 0; seat < 4; seat++) {
                    remotes[seat] = new JsonSeatRemote(getGame(), getGame().getPlayers().get(seat).getView(), server.writer(seat));
                    views.add(SeatProtocolGui.attach(getGame(), remotes[seat]));
                }
                ((ProtocolGuiGame) gui).pause();
                // ProtocolGuiGame's default isUiSetToSkipPhase always returns false. The
                // playback spectator otherwise spends minutes displaying a land-heavy stalemate.
                // Skip only its display pauses; all four seat views and AI decisions are real.
                final SeatProtocolGui playbackGui = new SeatProtocolGui(new LoggingRemote()) {
                    @Override
                    public boolean isUiSetToSkipPhase(final PlayerView player, final PhaseType phase) {
                        return true;
                    }
                };
                playbackGui.pause();
                playbackGui.setGameView(getGame().getView());
                final WatchLocalGame watcher = new WatchLocalGame(getGame(), null, playbackGui);
                playback.set(watcher);
                super.registerSpectator(playbackGui, watcher); // Only the playback spectator.
            }
        };
        final CountDownLatch finished = new CountDownLatch(1);
        match.setStartGameHook(() -> {
            match.getGame().getRules().setGamesPerMatch(1);
            // Use the public playback control's normal 10x-speed button, not engine changes.
            FThreads.invokeInEdtLater(() -> playback.get().selectButtonCancel());
        });
        match.setEndGameHook(() -> {
            for (final SeatProtocolGui gui : views) {
                gui.getForwarder().flush();
                gui.updateGameView();
            }
            finished.countDown();
        });
        try (WsTestClient zero = new WsTestClient(); WsTestClient two = new WsTestClient();
             WsTestClient reconnect = new WsTestClient()) {
            server.start();
            final HttpClient http = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5)).build();
            zero.connect(http, server.getPort(), "/ws?seat=0");
            two.connect(http, server.getPort(), "/ws?seat=2");
            zero.hello(0);
            two.hello(2);
            final List<RegisteredPlayer> players = new ArrayList<>();
            for (int seat = 0; seat < 4; seat++) {
                players.add(RegisteredPlayer.forCommander(deck("Stream " + seat))
                        .setPlayer(GamePlayerUtil.createAiPlayer("Stream " + seat)));
            }
            match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Collections.emptyMap());
            final Game game = match.getGame();
            final int ownerZero = game.getPlayers().get(0).getId();
            assertTrue(finished.await(120, TimeUnit.SECONDS), "Four AIs finished within 120 seconds");
            assertTrue(game.isGameOver());
            assertEquals(game.getRules().getGamesPerMatch(), 1);
            // Wait for the final coalesced state on both clients, with bounded socket waits.
            awaitState(zero, remotes[0].getLastStateSequence());
            awaitState(two, remotes[2].getLastStateSequence());
            for (final JsonSeatRemote remote : remotes) {
                assertFalse(remote.isDirty(), "Final snapshot succeeded");
            }
            final long observedUntil = System.nanoTime();
            checkStream(zero, 0, ownerZero, schema, observedUntil);
            checkStream(two, 2, -1, schema, observedUntil);
            reconnect.connect(http, server.getPort(), "/ws?seat=0");
            reconnect.hello(0);
            final JsonObject cached = reconnect.receive();
            assertEquals(cached, JsonParser.parseString(server.writer(0).getLastSentState()));
            assertEquals(cached.get("seq").getAsLong(), remotes[0].getLastStateSequence());
            assertTrue(schema.validate(WsServer.GSON.toJson(cached), InputFormat.JSON).isEmpty());
            assertTrue(zero.closed.await(5, TimeUnit.SECONDS), "Reconnect replaced old seat-0 socket");
        } finally {
            try {
                if (stopper.game != null && !stopper.game.isGameOver()) {
                    stopper.requested.set(true);
                    assertTrue(finished.await(10, TimeUnit.SECONDS), "Stopped the game before closing its writers");
                }
            } finally {
                match.endCurrentGame();
                server.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void awaitState(final WsTestClient client, final long seq) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final JsonObject message = client.receive();
            if ("state".equals(message.get("type").getAsString()) && message.get("seq").getAsLong() == seq) {
                return;
            }
        }
        fail("Final state " + seq + " was not delivered");
    }

    private static void checkStream(final WsTestClient client, final int seat, final int privateOwner,
                                    final JsonSchema schema, final long until) {
        assertNull(client.error);
        long previous = -1;
        final List<Long> times = new ArrayList<>();
        for (final WsTestClient.Received received : client.history()) {
            final JsonObject message = received.object();
            if ("hello".equals(message.get("type").getAsString())) {
                continue;
            }
            final long seq = message.get("seq").getAsLong();
            assertTrue(seq > previous, "Seat " + seat + " sequence strictly increases: " + previous + " -> " + seq);
            previous = seq;
            if (!"state".equals(message.get("type").getAsString())) {
                continue;
            }
            final var errors = schema.validate(received.json(), InputFormat.JSON);
            assertTrue(errors.isEmpty(), "State schema: " + errors);
            times.add(received.at());
            if (privateOwner >= 0) {
                for (final JsonElement player : message.getAsJsonArray("players")) {
                    if (player.getAsJsonObject().get("id").getAsInt() == privateOwner) {
                        continue;
                    }
                    for (final JsonElement card : player.getAsJsonObject().getAsJsonObject("zones").getAsJsonArray("hand")) {
                        assertEquals(card.getAsJsonObject().keySet(), Set.of("id", "hidden"));
                        assertTrue(card.getAsJsonObject().get("hidden").getAsBoolean());
                        assertTrue(card.getAsJsonObject().get("id").getAsString().matches("h-[0-9a-f]{12}"));
                    }
                }
            }
        }
        assertTrue(times.size() >= 5, "Seat " + seat + " received at least five states");
        int first = 0;
        int peak = 0;
        for (int last = 0; last < times.size(); last++) {
            while (times.get(last) - times.get(first) >= TimeUnit.SECONDS.toNanos(1)) {
                first++;
            }
            peak = Math.max(peak, last - first + 1);
        }
        final double average = times.size() / ((until - client.openedAt) / 1_000_000_000.0);
        System.out.printf(Locale.ROOT, "[stream] seat=%d states=%d average=%.3f/s peak=%d/s%n", seat, times.size(), average, peak);
        assertTrue(peak <= 10, "Seat " + seat + " sliding one-second peak was " + peak);
    }
}
