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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import forge.player.LobbyPlayerHuman;



/** Boots a persistent headless four-player Commander match server. */
public final class ArenaServerMain {
    private ArenaServerMain() { }

    static void initializeForge() {
        GuiBase.setInterface(new HeadlessGui());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
            // YieldController's static APINA helpers bypass per-seat overrides (task 1.13 audit).
            preferences.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
            preferences.setPref(FPref.UI_AUTO_DECISION_MODE, forge.localinstance.properties.ForgeConstants.AUTO_DECISION_PER_ABILITY);
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

    public static void main(final String[] args) { System.exit(runServer()); }

    /** Testable lifecycle owner; only main terminates the JVM. Every path shares this finally. */
    static int runServer() {
        final RunSummary run = new RunSummary();
        RunSummary.Exit exit = new RunSummary.Exit("startupError", 1, "Startup did not complete");
        WsServer opened = null;
        final MatchLifecycle[] lifecycle = new MatchLifecycle[1];
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        boolean running = false;
        try {
        run.configureSeed();
        ChessClock.minutes(System.getProperty("arena.clockMinutes", "0"));
        initializeForge();
        final ArenaPuzzle puzzle;
        try { puzzle = System.getProperty("arena.puzzle") == null ? null : ArenaPuzzle.read(java.nio.file.Path.of(System.getProperty("arena.puzzle"))); }
        catch (IOException | IllegalArgumentException ex) { throw new IllegalArgumentException("Cannot load puzzle: " + ex.getMessage(), ex); }
        final Set<Integer> humanSeats = humanSeats(System.getProperty("arena.humanSeats", ""));
        final List<RegisteredPlayer> players = new ArrayList<>();
        // Fixed, distinct precons make the startup reproducible without relying on directory order.
        for (final String name : List.of("Counterpunch [COM] [2011]", "Devour for Power [COM] [2011]",
                "Heavenly Inferno [COM] [2011]", "Mirror Mastery [COM] [2011]")) {
            String path = System.getProperty("arena.deck.seat" + players.size());
            Deck deck;
            if (path == null) deck = loadCommanderPrecon(name);
            else {
                final String text;
                try { text = java.nio.file.Files.readString(java.nio.file.Path.of(path)); }
                catch (IOException ex) {
                    throw new IllegalArgumentException("Cannot read deck file " + path + ": " + ex.getMessage(), ex);
                }
                DeckImport.Result imported = DeckImport.parse(text, List.of());
                if (!imported.ok()) {
                    throw new IllegalArgumentException(WsServer.GSON.toJson(imported.json()));
                }
                deck = imported.deck();
            }
            players.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(humanSeats.contains(players.size()) ? new LobbyPlayerHuman("Human " + players.size())
                            : GamePlayerUtil.createAiPlayer("AI " + (players.size() + 1))));
        }

        run.seats(players);
        final String host = System.getProperty("arena.bind", "127.0.0.1");
        final WsServer wsServer = new WsServer(host, Integer.getInteger("arena.port", 36800));
        opened = wsServer;
        wsServer.runSummary(run);
        try {
            wsServer.start();
        } catch (final Exception ex) {
            System.err.println("[arena] WebSocket bind failed: " + ex);
            throw new IllegalStateException("WebSocket bind failed: " + ex, ex);
        }
        System.out.println("[arena] WebSocket listening on ws://" + host + ":" + wsServer.getPort() + "/ws");

        try (SeatAbandonment console = new SeatAbandonment(wsServer)) {
            final List<SeatProtocolGui> seatViews = new ArrayList<>();
            final HostedMatch match = new HostedMatch() {
                @Override public void startGame() {
                    run.beforeGame();
                    lifecycle[0].started();
                    super.startGame();
                }
                @Override
                public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman controller) {
                    // This runs before gameplay is scheduled. The start-game hook is too late:
                    // PhaseHandler fires turn 1 before calling that hook.
                    seatViews.clear();
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
            lifecycle[0] = new MatchLifecycle(wsServer, match, humanSeats);
            console.matchCommand = lifecycle[0]::command;
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
                if (previousHandler != null) previousHandler.uncaughtException(thread, failure);
                else failure.printStackTrace(System.err);
                lifecycle[0].failure(failure);
            });
            match.setStartGameHook(() -> {
                run.capture(match.getGame());
                if (!humanSeats.isEmpty()) seatViews.clear();
                // HostedMatch otherwise automatically continues an all-AI match into another game.
                match.getGame().getRules().setGamesPerMatch(1);
                if (puzzle != null) puzzle.apply(match.getGame(), players);
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
                // HostedMatch normally flushes humans after this hook returns.
                // Flush here too, before releasing the lifecycle thread to finalize
                // the meter, so the report includes the final state/events for humans.
                if (Boolean.getBoolean("arena.meter")) for (Player player : match.getGame().getRegisteredPlayers()) {
                    if (player.getController() instanceof PlayerControllerHuman human
                            && human.getGui() instanceof ProtocolGuiGame gui && gui.getForwarder() != null) {
                        gui.getForwarder().flush(); gui.updateGameView();
                    }
                }
                final GameOutcome outcome = match.getGame().getOutcome();
                System.out.println("[arena] Winner: " + (outcome.isDraw() ? "Draw" : outcome.getWinningLobbyPlayer().getName()));
                run.finished(match.getGame());
                wsServer.finishMeters();
                lifecycle[0].finished();
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
            // Abort/timeout must also release startup if an EDT prompt is already blocking.
            java.util.concurrent.CompletableFuture.anyOf(started, lifecycle[0].shutdown()).join();
            running = true;
            try (MatchLifecycle owner = lifecycle[0]) { owner.awaitShutdown(); exit = owner.result(); }
        }
        } catch (Throwable ex) {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            System.err.println("[arena] " + cause);
            exit = new RunSummary.Exit(running ? "error" : "startupError", 1, cause.toString());
        } finally {
            if (lifecycle[0] != null) lifecycle[0].close();
            if (opened != null) try { opened.close(); }
            catch (Throwable closeError) {
                System.err.println("[arena] Shutdown failed: " + closeError);
                exit = new RunSummary.Exit("error", 1, closeError.toString());
            }
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            try { run.write(exit); }
            catch (IOException | RuntimeException ex) { System.err.println("[arena] Cannot write run summary: " + ex); }
        }
        return exit.code();
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
