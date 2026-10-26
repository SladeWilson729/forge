package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.Selection;
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
import forge.player.PlayerControllerHuman;
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
import static org.testng.Assert.fail;

/** A real human controller and three AIs play Commander through an in-process seat bridge. */
public class SeatBridgeSmokeTest {
    /** Finish on Forge's game thread; this transport test does not depend on AI combat decisions. */
    public static final class Stopper {
        private final List<Player> bots;
        private boolean conceded;

        Stopper(final List<Player> bots) {
            this.bots = bots;
        }

        @Subscribe
        public void onTurn(final GameEventTurnBegan event) {
            if (event.turnNumber() >= 12 && !conceded) {
                conceded = true;
                System.out.println("[bridge] AI concessions at turn " + event.turnNumber()
                        + " on " + Thread.currentThread().getName());
                for (final Player bot : bots) {
                    bot.concede();
                }
            }
        }
    }

    private static final class RecordingRemote implements IRemote {
        private final Set<ProtocolMethod> methods = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<GuiGameEvent> displays = new LinkedBlockingQueue<>();
        private volatile TrackableCollection<PlayerView> myPlayers;
        volatile String unscripted;

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
                case updateButtons -> {
                    System.out.println("[bridge-buttons] " + java.util.Arrays.toString(guiEvent.getObjects()));
                    displays.offer(guiEvent);
                }
                case setSelection -> displays.offer(guiEvent);
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
                default -> {
                    unscripted = guiEvent.getMethod().name();
                    throw new AssertionError("Unscripted blocking call: " + guiEvent.getMethod());
                }
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

    @Test(timeOut = 120_000)
    public void commanderGameRunsThroughSeatBridge() throws Exception {
        ArenaServerMain.initializeForge();
        final boolean highlights = Boolean.parseBoolean(System.getProperty("arena.smokeHighlights", "true"));
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, highlights);
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        MyRandom.setRandom(new Random(7));

        final RecordingRemote remote = new RecordingRemote();
        final HighlightProbe timings = new HighlightProbe();
        final SeatProtocolGui gui = new SeatProtocolGui(remote) {
            @Override public void setWeaklySelectable(Iterable<CardView> cards) {
                timings.timed("setWeaklySelectable", () -> super.setWeaklySelectable(cards));
            }
            @Override public void clearWeaklySelectable() {
                timings.timed("clearWeaklySelectable", super::clearWeaklySelectable);
            }
        };
        final RegisteredPlayer human = RegisteredPlayer.forCommander(commanderDeck("Bridge Human"))
                .setPlayer(new LobbyPlayerHuman("Bridge Human") {
                    @Override public Player createIngamePlayer(Game game, int id) {
                        Player player = new Player(forge.util.GuiDisplayUtil.personalizeHuman(getName()), game, id);
                        player.setFirstController(timings.controller(game, player, this));
                        return player;
                    }
                });
        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(human);
        for (int i = 1; i <= 3; i++) {
            players.add(RegisteredPlayer.forCommander(commanderDeck("AI " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("AI " + i)));
        }

        final HostedMatch match = GuiBase.getInterface().hostMatch();
        final CountDownLatch finished = new CountDownLatch(1);
        match.setStartGameHook(() -> {
            final Game running = match.getGame();
            running.getRules().setGamesPerMatch(1);
            // Capture the registered AI identities before concessions remove any players.
            final List<Player> bots = players.subList(1, 4).stream()
                    .map(registered -> ArenaServerMain.playerFor(running, registered)).toList();
            running.subscribeToEvents(new Stopper(bots));
        });
        match.setEndGameHook(() -> {
            // HostedMatch calls this hook BEFORE its final forwarder flush. Drain on
            // the game thread before test cleanup can collect those same deltas.
            gui.getForwarder().flush();
            gui.updateGameView();
            finished.countDown();
        });
        match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, human, gui);
        final Game game = match.getGame();
        final long started = System.nanoTime();
        long nextProgress = started;
        int lastLandTurn = -1;
        boolean sawLandOnBattlefield = false;
        boolean sawCommander = false;
        boolean sawStartingLife = false;
        try {
            assertEquals(game.getPlayers().size(), 4);
            assertTrue(game.getRules().hasCommander());
            // Highlights measured 2.116 ms median scan + push (off: 0.252 ms).
            // End at turn 12 instead of depending on these synthetic decks attacking.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!game.isGameOver() && System.nanoTime() < deadline) {
                if (remote.unscripted != null) fail("Unscripted blocking call: " + remote.unscripted);
                if (System.nanoTime() >= nextProgress) {
                    nextProgress += TimeUnit.SECONDS.toNanos(10);
                    FThreads.invokeInEdtLater(() -> {
                        PlayerControllerHuman controller = (PlayerControllerHuman) ArenaServerMain.playerFor(game, human).getController();
                        System.out.printf("[bridge-progress] highlights=%s elapsed=%ds turn=%d phase=%s input=%s%n",
                                highlights, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started),
                                gui.getGameView().getTurn(), gui.getGameView().getPhase(), controller.getInputQueue().printInputStack());
                    });
                }
                final GuiGameEvent buttons = remote.displays.poll(100, TimeUnit.MILLISECONDS);
                if (buttons == null) {
                    continue;
                }
                if (buttons.getMethod() == ProtocolMethod.setSelection) {
                    final Selection selection = (Selection) buttons.getObjects()[0];
                    // Winning the multiplayer coin toss opens an Input with BOTH buttons
                    // disabled until a player is picked. The old button-only driver stalled.
                    if (gui.getGameView().getTurn() == 0 && selection.min() == 1 && selection.max() == 1
                            && selection.chosen().isEmpty() && !selection.targets().isEmpty()) {
                        final PlayerView owner = remote.myPlayers.get(0);
                        assertTrue(selection.targets().contains(owner), "Starting-player input offers the human");
                        FThreads.invokeInEdtLater(() -> {
                            System.out.println("[bridge] Selecting human as starting player on " + Thread.currentThread().getName());
                            gui.getGameController(owner).selectPlayer(owner, null);
                        });
                    }
                    continue;
                }
                final PlayerView owner = (PlayerView) buttons.getObjects()[0];
                if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
                    System.out.println("[bridge] Ignored button owner=" + owner + " myPlayers=" + remote.myPlayers);
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

            assertTrue(game.isGameOver(), "Commander game ended within 120 seconds");
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
            try {
                FThreads.invokeInEdtAndWait(match::endCurrentGame);
            } finally {
                System.out.printf("[bridge] highlights=%s elapsed=%.3fs%n", highlights, (System.nanoTime() - started) / 1_000_000_000.0);
                timings.reportTotals();
                System.out.println("[bridge] ProtocolMethods seen: " + remote.methods.stream().sorted().toList());
            }
        }
    }
}
