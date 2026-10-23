package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
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
        final List<RegisteredPlayer> players = new ArrayList<>();
        // Fixed, distinct precons make the startup reproducible without relying on directory order.
        for (final String name : List.of("Counterpunch [COM] [2011]", "Devour for Power [COM] [2011]",
                "Heavenly Inferno [COM] [2011]", "Mirror Mastery [COM] [2011]")) {
            players.add(RegisteredPlayer.forCommander(loadCommanderPrecon(name))
                    .setPlayer(GamePlayerUtil.createAiPlayer("AI " + (players.size() + 1))));
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
            final HostedMatch match = new HostedMatch() {
                @Override
                public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman controller) {
                    // This runs before gameplay is scheduled. The start-game hook is too late:
                    // PhaseHandler fires turn 1 before calling that hook.
                    getGame().subscribeToEvents(new TurnLogger(getGame(), wsServer));
                    super.registerSpectator(gui, controller);
                }
            };
            final CountDownLatch finished = new CountDownLatch(1);
            match.setStartGameHook(() -> {
                // HostedMatch otherwise automatically continues an all-AI match into another game.
                match.getGame().getRules().setGamesPerMatch(1);
            });
            match.setEndGameHook(finished::countDown);
            // The game type alone does not set GameRules.hasCommander().
            match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Collections.emptyMap());
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

    public static final class TurnLogger {
        private final Game game;
        private final WsServer wsServer;
        private boolean sentOpeningHand;

        public TurnLogger(final Game game, final WsServer wsServer) {
            this.game = game;
            this.wsServer = wsServer;
        }

        @Subscribe
        public void onTurn(final GameEventTurnBegan event) {
            System.out.println("[arena] " + event);
            if (event.turnNumber() == 1 && !sentOpeningHand) {
                sentOpeningHand = true;
                // Phase 0 placeholder, not the Section 3.3 snapshot. Read live Cards here
                // on the game thread; only the finished JSON string goes to Netty.
                // Dev-only: everyone connecting receives seat 0's hand. Per-seat filtering
                // arrives in task 1.3.
                final JsonObject state = new JsonObject();
                state.addProperty("type", "state");
                state.addProperty("seq", 1);
                state.addProperty("seat", 0);
                state.addProperty("turn", 1);
                final JsonArray hand = new JsonArray();
                for (final Card card : game.getPlayers().get(0).getCardsIn(ZoneType.Hand)) {
                    final JsonObject entry = new JsonObject();
                    entry.addProperty("name", card.getName());
                    hand.add(entry);
                }
                state.add("hand", hand);
                wsServer.broadcastState(WsServer.GSON.toJson(state));
            }
        }

        @Subscribe
        public void onPhase(final GameEventTurnPhase event) {
            System.out.println("[arena] " + event);
        }
    }
}
