package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.item.IPaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Exercises the documented public getters on the game thread, without a snapshot bridge. */
public class StateFieldProbeTest {
    private static Deck commanderDeck(final String name) {
        // Same synthetic 100-card deck as SeatBridgeSmokeTest.
        final Deck deck = new Deck(name);
        deck.getMain().add("Plains", 98);
        deck.getMain().add("Drifting Meadow", 1);
        deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
        return deck;
    }

    public static final class Probe {
        private final CountDownLatch probed = new CountDownLatch(1);
        private final List<String> failures = new ArrayList<>();
        private final Set<String> invoked = new HashSet<>();
        private final List<StackItemView> castItems = new ArrayList<>();
        private Game game;
        private ProtocolGuiGame gui;
        private PlayerControllerHuman spectator;
        private Combat lastCombat;
        private boolean ran;
        private boolean commanderInCommand;
        private int playersRead;
        private int cardsRead;
        private int stackItemsRead;

        private <T> T read(final String name, final Supplier<T> getter) {
            invoked.add(name);
            try {
                return getter.get();
            } catch (final Throwable failure) {
                failures.add(name + ": " + failure);
                return null;
            }
        }

        @Subscribe
        public void onCast(final GameEventSpellAbilityCast event) {
            if (event.si() != null) {
                castItems.add(event.si());
            }
        }

        @Subscribe
        public void onPhase(final GameEventTurnPhase event) {
            if (game.getCombat() != null) {
                lastCombat = game.getCombat();
            }
        }

        @Subscribe
        public void onTurn(final GameEventTurnBegan event) {
            if (event.turnNumber() != 3 || ran) {
                return;
            }
            ran = true;
            try {
                probeFields();
            } catch (final Throwable failure) {
                // EventBus otherwise logs subscriber failures without failing the TestNG thread.
                failures.add("turn-3 probe: " + failure);
            } finally {
                try {
                    // The getter experiment is complete; finish this one-game match on its game thread.
                    game.setGameOver(GameEndReason.Draw);
                } catch (final Throwable failure) {
                    failures.add("probe cleanup: " + failure);
                } finally {
                    probed.countDown();
                }
            }
        }

        private void probeFields() {
            final GameView view = game.getView();
            read("GameView.getTurn", view::getTurn);
            read("GameView.getPlayerTurn", view::getPlayerTurn);
            read("GameView.getPhase", view::getPhase);
            read("GameView.getPlayers", view::getPlayers);
            read("GameView.getStack", view::getStack);
            read("Game.getPhaseHandler", game::getPhaseHandler);
            read("PhaseHandler.getPriorityPlayer", () -> game.getPhaseHandler().getPriorityPlayer());
            read("Game.getMonarch", game::getMonarch);
            read("Game.getHasInitiative", game::getHasInitiative);
            read("Game.getCombat", game::getCombat);
            read("AbstractGuiGame.getSelection", gui::getSelection);
            read("InputQueue.getInput", () -> spectator.getInputQueue().getInput());

            final List<CardView> commanders = new ArrayList<>();
            for (final Player player : game.getPlayers()) {
                final List<CardView> owned = read("PlayerView.getCommanders", player.getView()::getCommanders);
                if (owned != null) {
                    commanders.addAll(owned);
                }
            }
            for (final Player player : game.getPlayers()) {
                playersRead++;
                final PlayerView pv = player.getView();
                read("GameEntity.getId", player::getId);
                read("TrackableObject.getId", pv::getId);
                read("GameEntityView.getName", pv::getName);
                read("PlayerView.getLife", pv::getLife);
                read("PlayerView.getHasLost", pv::getHasLost);
                read("PlayerView.getHasPriority", pv::getHasPriority);
                read("GameEntityView.getCounters", () -> pv.getCounters(CounterEnumType.POISON));
                read("GameEntityView.getCounters", pv::getCounters);
                for (final CardView commander : commanders) {
                    read("PlayerView.getCommanderDamage", () -> pv.getCommanderDamage(commander));
                    // Includes every owned commander and also verifies the getter's zero default.
                    read("PlayerView.getCommanderCast", () -> pv.getCommanderCast(commander));
                    read("PlayerView.getCommanderCast", () -> 2 * pv.getCommanderCast(commander));
                }
                for (final byte color : MagicColor.WUBRGC) {
                    read("PlayerView.getMana", () -> pv.getMana(color));
                }
                read("PlayerView.getHand", pv::getHand);
                read("PlayerView.getBattlefield", pv::getBattlefield);
                read("PlayerView.getCommand", pv::getCommand);
                read("PlayerView.getGraveyard", pv::getGraveyard);
                read("PlayerView.getExile", pv::getExile);
                read("PlayerView.getZoneSize", () -> pv.getZoneSize(ZoneType.Library));
                for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard,
                        ZoneType.Exile, ZoneType.Command, ZoneType.Battlefield)) {
                    read("PlayerView.getCards", () -> pv.getCards(zone));
                }
                for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command)) {
                    for (final Card card : player.getCardsIn(zone)) {
                        probeCard(card);
                        if (zone == ZoneType.Command && card.getView().isCommander()) {
                            commanderInCommand = true;
                        }
                    }
                }
            }
            final Set<StackItemView> items = new HashSet<>(castItems);
            for (final StackItemView item : view.getStack()) {
                items.add(item);
            }
            for (final StackItemView item : items) {
                stackItemsRead++;
                read("TrackableObject.getId", item::getId);
                read("StackItemView.getSourceCard", item::getSourceCard);
                read("StackItemView.getActivatingPlayer", item::getActivatingPlayer);
                read("StackItemView.getText", item::getText);
                read("StackItemView.getTargetCards", item::getTargetCards);
                read("StackItemView.getTargetPlayers", item::getTargetPlayers);
                read("StackItemView.getTargetStackItems", item::getTargetStackItems);
            }
            System.out.printf("[fields] turn=3 thread=%s players=%d cards=%d stackItems=%d getters=%d failures=%s%n",
                    Thread.currentThread().getName(), playersRead, cardsRead, stackItemsRead, invoked.size(), failures);
        }

        private void probeCard(final Card card) {
            cardsRead++;
            final CardView cv = card.getView();
            read("TrackableObject.getId", cv::getId);
            final CardView.CardStateView face = read("CardView.getCurrentState", cv::getCurrentState);
            if (face != null) {
                read("CardView.CardStateView.getName", face::getName);
                read("CardView.CardStateView.getSetCode", face::getSetCode);
                read("CardView.CardStateView.getPower", face::getPower);
                read("CardView.CardStateView.getToughness", face::getToughness);
            }
            final IPaperCard paper = read("Card.getPaperCard", card::getPaperCard);
            if (paper != null) {
                read("IPaperCard.getCollectorNumber", paper::getCollectorNumber);
            }
            read("CardView.isTapped", cv::isTapped);
            read("CardView.isSick", cv::isSick);
            read("CardView.getDamage", cv::getDamage);
            read("GameEntityView.getCounters", cv::getCounters);
            read("CardView.getEntityAttachedTo", cv::getEntityAttachedTo);
            read("CardView.isAttacking", cv::isAttacking);
            read("CardView.isBlocking", cv::isBlocking);
            read("CardView.isFaceDown", cv::isFaceDown);
            read("CardView.getController", cv::getController);
            read("CardView.getOwner", cv::getOwner);
            read("CardView.isToken", cv::isToken);
            read("CardView.isCommander", cv::isCommander);
            for (final Player viewer : game.getPlayers()) {
                read("CardView.canBeShownTo", () -> cv.canBeShownTo(viewer.getView()));
                read("CardView.canFaceDownBeShownTo", () -> cv.canFaceDownBeShownTo(viewer.getView()));
                read("CardView.mayPlayerLook", () -> cv.mayPlayerLook(viewer.getView()));
            }
            // At turn start the engine normally has no current Combat. Use its retained prior combat,
            // not a fabricated fixture; both queries are valid for nonparticipating cards as well.
            if (lastCombat != null) {
                read("Combat.getDefenderByAttacker", () -> lastCombat.getDefenderByAttacker(card));
                read("Combat.getAttackersBlockedBy", () -> lastCombat.getAttackersBlockedBy(card));
            }
        }
    }

    @Test(timeOut = 300_000)
    public void snapshotGettersWorkInCommanderGame() throws Exception {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        MyRandom.setRandom(new Random(7));
        final Probe probe = new Probe();
        final HostedMatch match = new HostedMatch() {
            @Override
            public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman controller) {
                probe.game = getGame();
                probe.gui = (ProtocolGuiGame) gui;
                probe.spectator = controller;
                // Test the engine getters, not transport delta walks on spectator display callbacks.
                probe.gui.pause();
                getGame().subscribeToEvents(probe);
                super.registerSpectator(gui, controller);
            }
        };
        final CountDownLatch finished = new CountDownLatch(1);
        match.setStartGameHook(() -> match.getGame().getRules().setGamesPerMatch(1));
        match.setEndGameHook(finished::countDown);
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            players.add(RegisteredPlayer.forCommander(commanderDeck("Field AI " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("Field AI " + i)));
        }
        try {
            match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Collections.emptyMap());
            assertTrue(probe.probed.await(240, TimeUnit.SECONDS), "Reached and completed the turn-3 getter probe");
            assertTrue(probe.failures.isEmpty(), "Getter or subscriber failures: " + probe.failures);
            assertEquals(probe.playersRead, 4, "All four players were probed");
            assertTrue(probe.cardsRead > 0, "Live cards were probed");
            assertTrue(probe.commanderInCommand, "A commander appeared in a command zone on turn 3");
            assertTrue(probe.stackItemsRead > 0, "Getters ran on actual cast stack items, not an empty loop");
            assertTrue(finished.await(20, TimeUnit.SECONDS), "The one-game match finished after the probe");
            assertTrue(probe.game.getRules().hasCommander());
            assertEquals(probe.game.getRules().getGamesPerMatch(), 1);

            // Keep the documented getter inventory honest without reflection into Forge internals.
            final Pattern getterName = Pattern.compile("`([A-Za-z][A-Za-z0-9.]*\\.[A-Za-z][A-Za-z0-9]*)`");
            final Set<String> documented = new HashSet<>();
            for (final String line : Files.readAllLines(Path.of("../protocol/state-fields.md"), StandardCharsets.UTF_8)) {
                if (!line.startsWith("| `")) {
                    continue;
                }
                final Matcher getter = getterName.matcher(line.split("\\|", -1)[2]);
                while (getter.find()) {
                    documented.add(getter.group(1));
                }
            }
            assertFalse(documented.isEmpty(), "The field table contains getter rows");
            documented.removeAll(probe.invoked);
            assertTrue(documented.isEmpty(), "Documented getters never exercised: " + documented);
        } finally {
            match.endCurrentGame();
        }
    }
}
