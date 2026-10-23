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
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.File;
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

    public static void main(final String[] args) throws InterruptedException {
        initializeForge();
        final List<RegisteredPlayer> players = new ArrayList<>();
        // Fixed, distinct precons make the startup reproducible without relying on directory order.
        for (final String name : List.of("Counterpunch [COM] [2011]", "Devour for Power [COM] [2011]",
                "Heavenly Inferno [COM] [2011]", "Mirror Mastery [COM] [2011]")) {
            players.add(RegisteredPlayer.forCommander(loadCommanderPrecon(name))
                    .setPlayer(GamePlayerUtil.createAiPlayer("AI " + (players.size() + 1))));
        }

        final HostedMatch match = GuiBase.getInterface().hostMatch();
        final CountDownLatch finished = new CountDownLatch(1);
        match.setStartGameHook(() -> {
            // HostedMatch otherwise automatically continues an all-AI match into another game.
            match.getGame().getRules().setGamesPerMatch(1);
            match.getGame().subscribeToEvents(new TurnLogger());
        });
        match.setEndGameHook(finished::countDown);
        // The game type alone does not set GameRules.hasCommander().
        match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Collections.emptyMap());
        final Game game = match.getGame();
        final long limit = Long.getLong("arena.timeoutMinutes", 30L);
        if (!finished.await(limit, TimeUnit.MINUTES) || !game.isGameOver()) {
            System.err.println("[arena] Game did not finish within " + limit + " minutes");
            System.exit(1);
        }
        final GameOutcome outcome = game.getOutcome();
        System.out.println("[arena] Winner: " + (outcome.isDraw() ? "Draw" : outcome.getWinningLobbyPlayer().getName()));
        // Forge owns background executors and the Swing EDT; this standalone process is finished.
        System.exit(0);
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
