package forge.arena.server;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.trackable.TrackableCollection;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** A real human controller and three AIs play Commander through an in-process seat bridge. */
public class SeatBridgeSmokeTest {
    private static final class RecordingRemote implements IRemote {
        private final Set<ProtocolMethod> methods = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<GuiGameEvent> buttonPrompts = new LinkedBlockingQueue<>();
        private volatile TrackableCollection<PlayerView> myPlayers;

        private void record(final GuiGameEvent event) {
            if (methods.add(event.getMethod())) {
                System.out.println("[bridge] ProtocolMethod: " + event.getMethod());
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(final NetEvent event) {
            final GuiGameEvent guiEvent = (GuiGameEvent) event;
            record(guiEvent);
            switch (guiEvent.getMethod()) {
                case openView -> myPlayers = (TrackableCollection<PlayerView>) guiEvent.getObjects()[0];
                case updateButtons -> buttonPrompts.offer(guiEvent);
                default -> { }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object sendAndWait(final IdentifiableNetEvent event) {
            final GuiGameEvent guiEvent = (GuiGameEvent) event;
            record(guiEvent);
            final Object[] args = guiEvent.getObjects();
            return switch (guiEvent.getMethod()) {
                case getAbilityToPlay -> {
                    final List<SpellAbilityView> abilities = (List<SpellAbilityView>) args[1];
                    SpellAbilityView pick = abilities.get(0);
                    for (final SpellAbilityView ability : abilities) {
                        if (!ability.toString().toLowerCase(java.util.Locale.ROOT).contains("cycling")) {
                            pick = ability;
                            break;
                        }
                    }
                    yield pick;
                }
                case getChoices -> {
                    final List<?> choices = (List<?>) args[3];
                    final int min = (Integer) args[1];
                    final int max = (Integer) args[2];
                    yield new ArrayList<>(choices.subList(0,
                            Math.min(choices.size(), Math.max(min, Math.min(1, max)))));
                }
                case confirm -> args[2];
                case showConfirmDialog, showOptionDialog -> args[4];
                case showInputDialog -> args[3];
                default -> throw new AssertionError("Unscripted blocking call: " + guiEvent.getMethod());
            };
        }
    }

    private static Deck commanderDeck(final String name) {
        // Legal 100-card mono-white decks; basic lands may repeat. Vanilla commanders
        // keep this transport smoke test independent of complicated precon card scripts.
        final Deck deck = new Deck(name);
        deck.getMain().add("Plains", 98);
        deck.getMain().add("Drifting Meadow", 1);
        deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
        return deck;
    }

    @Test(timeOut = 300_000)
    public void commanderGameRunsThroughSeatBridge() throws Exception {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        MyRandom.setRandom(new Random(7));

        final RecordingRemote remote = new RecordingRemote();
        final SeatProtocolGui gui = new SeatProtocolGui(remote);
        final RegisteredPlayer human = RegisteredPlayer.forCommander(commanderDeck("Bridge Human"))
                .setPlayer(new LobbyPlayerHuman("Bridge Human"));
        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(human);
        for (int i = 1; i <= 3; i++) {
            players.add(RegisteredPlayer.forCommander(commanderDeck("AI " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("AI " + i)));
        }

        final HostedMatch match = GuiBase.getInterface().hostMatch();
        final CountDownLatch finished = new CountDownLatch(1);
        match.setEndGameHook(finished::countDown);
        match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, human, gui);
        final Game game = match.getGame();
        int lastLandTurn = -1;
        boolean sawLandOnBattlefield = false;
        boolean sawCommander = false;
        boolean sawStartingLife = false;
        try {
            assertEquals(game.getPlayers().size(), 4);
            assertTrue(game.getRules().hasCommander());
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (!game.isGameOver() && System.nanoTime() < deadline) {
                final GuiGameEvent buttons = remote.buttonPrompts.poll(100, TimeUnit.MILLISECONDS);
                if (buttons == null) {
                    continue;
                }
                final PlayerView owner = (PlayerView) buttons.getObjects()[0];
                if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
                    continue;
                }
                sawCommander |= !owner.getCommand().isEmpty();
                sawStartingLife |= owner.getLife() == 40;
                sawLandOnBattlefield |= !owner.getBattlefield().isEmpty();
                final IGameController controller = gui.getGameController(owner);
                final GameView view = gui.getGameView();
                final boolean myMain = owner.equals(view.getPlayerTurn()) && view.getPhase() == PhaseType.MAIN1;
                if (myMain && lastLandTurn != view.getTurn() && !owner.getHand().isEmpty()) {
                    final CardView land = owner.getHand().get(0);
                    lastLandTurn = view.getTurn();
                    FThreads.invokeInEdtLater(() -> controller.selectCard(land, null, null));
                } else if (Boolean.TRUE.equals(buttons.getObjects()[3])) {
                    // Keep the opening hand, pass priority, and declare no blockers.
                    FThreads.invokeInEdtLater(controller::selectButtonOk);
                }
            }

            assertTrue(game.isGameOver(), "Commander game ended within 180 seconds");
            assertTrue(finished.await(10, TimeUnit.SECONDS), "HostedMatch completed the game");
            assertNotNull(game.getOutcome());
            assertNotNull(game.getOutcome().getWinningLobbyPlayer(), "The game has a winner");
            assertNotNull(remote.myPlayers, "openView reached the remote");
            assertEquals(remote.myPlayers.size(), 1, "Only the human seat belongs to this remote");
            assertTrue(sawStartingLife, "Commander starts at 40 life");
            assertTrue(sawCommander, "The commander reached the command zone through the bridge");
            assertTrue(sawLandOnBattlefield, "A human land play reached the battlefield");
            assertTrue(remote.methods.containsAll(EnumSet.of(ProtocolMethod.setGameView,
                    ProtocolMethod.applyDelta, ProtocolMethod.updateButtons)), "State and prompts crossed the bridge");
            System.out.println("[bridge] Winner: " + game.getOutcome().getWinningLobbyPlayer().getName());
        } finally {
            match.endCurrentGame();
            System.out.println("[bridge] ProtocolMethods seen: " + remote.methods.stream().sorted().toList());
        }
    }
}
