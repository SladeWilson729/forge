package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameType;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.RegisteredPlayer;
import forge.game.player.Player;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import forge.player.LobbyPlayerHuman;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Boots Forge and plays one four-player Commander game without a window. */
public final class ArenaServerMain {
    private ArenaServerMain() { }

    static void initializeForge() {
        GuiBase.setInterface(new HeadlessGui());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
    }

    private static Deck loadCommanderPrecon(final String name) {
        final File file = new File(ForgeConstants.COMMANDER_PRECON_DIR, name + ".dck");
        if (!file.isFile()) {
            throw new IllegalArgumentException("Commander precon not found: " + file.getAbsolutePath());
        }
        final Deck deck = DeckSerializer.fromFile(file);
        if (deck == null || deck.getCommanders().isEmpty()) {
            throw new IllegalStateException("Invalid Commander precon: " + file.getAbsolutePath());
        }
        System.out.println("[arena] Deck: " + deck.getName());
        return deck;
    }

    public static void main(final String[] args) throws InterruptedException, IOException {
        initializeForge();
        final Set<Integer> humanSeats = humanSeats(System.getProperty("arena.humanSeats", ""));
        final List<RegisteredPlayer> players = new ArrayList<>();
        // Fixed, distinct precons make the startup reproducible without relying on directory order.
        for (final String name : List.of("Counterpunch [COM] [2011]", "Devour for Power [COM] [2011]",
                "Heavenly Inferno [COM] [2011]", "Mirror Mastery [COM] [2011]")) {
            players.add(RegisteredPlayer.forCommander(loadCommanderPrecon(name))
                    .setPlayer(humanSeats.contains(players.size()) ? new LobbyPlayerHuman("Human " + players.size())
                            : GamePlayerUtil.createAiPlayer("AI " + (players.size() + 1))));
        }

        final String host = System.getProperty("arena.bind", "127.0.0.1");
        final WsServer wsServer = new WsServer(host, Integer.getInteger("arena.port", 36800));
        try {
            wsServer.start();
        } catch (final Exception ex) {
            System.err.println("[arena] WebSocket bind failed: " + ex);
            wsServer.close();
            System.exit(1);
            return;
        }
        System.out.println("[arena] WebSocket listening on ws://" + host + ":" + wsServer.getPort() + "/ws");

        try {
            final List<SeatProtocolGui> seatViews = new ArrayList<>();
            final HostedMatch match = new HostedMatch() {
                @Override
                public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman controller) {
                    // This runs before gameplay is scheduled. The start-game hook is too late:
                    // PhaseHandler fires turn 1 before calling that hook.
                    getGame().subscribeToEvents(new TurnLogger());
                    for (int seat = 0; seat < 4; seat++) {
                        final Player player = playerFor(getGame(), players.get(seat));
                        seatViews.add(SeatProtocolGui.attach(getGame(),
                                new JsonSeatRemote(getGame(), player.getView(), wsServer.writer(seat))));
                    }
                    // Keep HostedMatch's original watcher for playback bookkeeping only. Its
                    // desktop handler must not walk a protocol view concurrently on the EDT.
                    ((ProtocolGuiGame) gui).pause();
                    super.registerSpectator(gui, controller);
                }
            };
            final CountDownLatch finished = new CountDownLatch(1);
            match.setStartGameHook(() -> {
                // HostedMatch otherwise automatically continues an all-AI match into another game.
                match.getGame().getRules().setGamesPerMatch(1);
                if (!humanSeats.isEmpty()) {
                    match.getGame().subscribeToEvents(new TurnLogger());
                    // PhaseHandler.setupFirstTurn runs this on Game-* before mainGameLoop's
                    // first priority. HostedMatch never registers a spectator with human seats.
                    seatViews.addAll(attachBotSeatViews(match, wsServer, players));
                }
            });
            match.setEndGameHook(() -> {
                // HostedMatch flushes only its own registered human controllers. These extra
                // watchers must flush on this same game thread, including the final outcome.
                for (final SeatProtocolGui gui : seatViews) {
                    gui.getForwarder().flush();
                    gui.updateGameView();
                }
                finished.countDown();
            });
            // The game type alone does not set GameRules.hasCommander().
            final Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
            for (int seat : humanSeats) guis.put(players.get(seat),
                    new SeatProtocolGui(new JsonSeatRemote(match, players.get(seat), wsServer.writer(seat))));
            final java.util.concurrent.CompletableFuture<Void> started = new java.util.concurrent.CompletableFuture<>();
            forge.gui.FThreads.invokeInEdtLater(() -> {
                try { match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, guis); started.complete(null); }
                catch (RuntimeException | Error ex) { started.completeExceptionally(ex); }
            });
            started.join(); // Lifecycle thread only; initial human setGameView snapshots run on EDT.
            final Game game = match.getGame();
            final long limit = Long.getLong("arena.timeoutMinutes", 30L);
            if (!finished.await(limit, TimeUnit.MINUTES) || !game.isGameOver()) {
                System.err.println("[arena] Game did not finish within " + limit + " minutes");
                wsServer.close();
                System.exit(1);
                return;
            }
            final GameOutcome outcome = game.getOutcome();
            System.out.println("[arena] Winner: " + (outcome.isDraw() ? "Draw" : outcome.getWinningLobbyPlayer().getName()));
            if (Boolean.getBoolean("arena.hold")) {
                System.out.println("[arena] Holding server open. Press Enter to exit.");
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
        } finally {
            wsServer.close();
        }
        // Forge owns background executors and the Swing EDT; this standalone process is finished.
        System.exit(0);
    }

    static Set<Integer> humanSeats(String value) {
        Set<Integer> seats = new HashSet<>();
        if (!value.isBlank()) for (String part : value.split(",", -1)) {
            int seat = Integer.parseInt(part.trim());
            if (seat < 0 || seat > 3 || !seats.add(seat)) throw new IllegalArgumentException("arena.humanSeats must contain distinct seats 0..3");
        }
        return Set.copyOf(seats);
    }

    /** Game-thread start hook in human mode; also observe Inputs so bots' views flush before parking. */
    static Player playerFor(Game game, RegisteredPlayer registered) {
        return game.getRegisteredPlayers().stream().filter(p -> p.getRegisteredPlayer() == registered)
                .findFirst().orElseThrow(() -> new IllegalStateException("Seat player is not in this game"));
    }

    static List<SeatProtocolGui> attachBotSeatViews(HostedMatch match, WsServer server, List<RegisteredPlayer> seats) {
        List<SeatProtocolGui> views = new ArrayList<>();
        Game game = match.getGame();
        for (int seat = 0; seat < seats.size(); seat++) {
            Player player = playerFor(game, seats.get(seat));
            if (player.getController() instanceof PlayerControllerHuman) continue;
            SeatProtocolGui view = SeatProtocolGui.attach(game, new JsonSeatRemote(game, player.getView(), server.writer(seat)));
            for (Player human : game.getPlayers()) if (human.getController() instanceof PlayerControllerHuman controller)
                controller.getInputQueue().addObserver(view.getForwarder());
            views.add(view);
        }
        return views;
    }

    public static final class TurnLogger {
        @Subscribe
        public void onTurn(final GameEventTurnBegan event) {
            System.out.println("[arena] " + event);
        }

        @Subscribe
        public void onPhase(final GameEventTurnPhase event) {
            System.out.println("[arena] " + event);
        }
    }
}
