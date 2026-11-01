package forge.arena.server.view;

import forge.arena.server.SeatProtocolGui;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.util.ITriggerEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class SingleAbilityGuiTest {
    private static final ITriggerEvent CLICK = new ITriggerEvent() {
        public int getButton() { return 1; }
        public int getX() { return 0; }
        public int getY() { return 0; }
    };

    @BeforeClass public void initialize() { ScenarioGame.initialize(); }

    private static final class RecordingRemote implements IRemote {
        final List<ProtocolMethod> blocking = new ArrayList<>();
        public void send(NetEvent event) { }
        public Object sendAndWait(IdentifiableNetEvent event) {
            GuiGameEvent gui = (GuiGameEvent) event;
            blocking.add(gui.getMethod());
            return switch (gui.getMethod()) {
                case getAbilityToPlay -> ((List<?>) gui.getObjects()[1]).get(0);
                case getChoices -> List.of(((List<?>) gui.getObjects()[3]).get(0));
                default -> throw new AssertionError("Unexpected blocking call " + gui.getMethod());
            };
        }
    }

    @Test
    public void desktopBranchesAndExplicitPromptFlagLeaveOtherPromptKindsAlone() {
        ScenarioGame game = new ScenarioGame();
        Card host = game.addCardToZone("Selesnya Guildmage", 0, ZoneType.Battlefield);
        RecordingRemote remote = new RecordingRemote();
        SeatProtocolGui gui = new SeatProtocolGui(remote);
        assertNull(gui.getAbilityToPlay(host.getView(), List.of(), null));
        assertNull(gui.getAbilityToPlay(host.getView(), List.of(), CLICK));

        // All production implementations of this flag currently return false. A controlled
        // ability on a real card exercises the opt-in contract without inventing a card rule.
        for (boolean playable : List.of(false, true)) {
            SpellAbility ordinary = new SpellAbility.EmptySa(host) {
                @Override public boolean canPlay() { return playable; }
            };
            SpellAbilityView view = ordinary.getView(); view.updateCanPlay(ordinary);
            assertFalse(view.promptIfOnlyPossibleAbility());
            assertSame(gui.getAbilityToPlay(host.getView(), List.of(view), null), view,
                    "No trigger: return singleton regardless of canPlay");
            assertSame(gui.getAbilityToPlay(host.getView(), List.of(view), CLICK), playable ? view : null,
                    "Mouse trigger: return singleton only if playable");
            assertTrue(remote.blocking.isEmpty(), "Ordinary singleton never contacts the remote");
        }
        SpellAbility flagged = new SpellAbility.EmptySa(host) {
            @Override public boolean promptIfOnlyPossibleAbility() { return true; }
        };
        SpellAbilityView flaggedView = flagged.getView(); flaggedView.updateCanPlay(flagged);
        assertTrue(flaggedView.promptIfOnlyPossibleAbility());
        assertFalse(flaggedView.canPlay());
        assertSame(gui.getAbilityToPlay(host.getView(), List.of(flaggedView), null), flaggedView,
                "No trigger returns singleton even when explicitly flagged and unplayable");
        assertTrue(remote.blocking.isEmpty());
        assertSame(gui.getAbilityToPlay(host.getView(), List.of(flaggedView), CLICK), flaggedView);
        assertEquals(remote.blocking, List.of(ProtocolMethod.getAbilityToPlay), "Flag forces the mouse-triggered prompt");

        // The full card list also contains its creature-casting ability. On the battlefield
        // the menu offers the two activated abilities, as in the real socket regression.
        List<SpellAbilityView> guildmage = host.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility)
                .map(SpellAbility::getView).toList();
        assertEquals(guildmage.size(), 2);
        assertSame(gui.getAbilityToPlay(host.getView(), guildmage, null), guildmage.get(0));
        assertSame(gui.getAbilityToPlay(host.getView(), guildmage, CLICK), guildmage.get(0));
        assertEquals(remote.blocking.size(), 3, "Two abilities prompt with or without a trigger");

        assertEquals(gui.getChoices("One offered option still needs a reply", 1, 1, List.of("only")), List.of("only"));
        assertEquals(remote.blocking.get(3), ProtocolMethod.getChoices,
                "The singleton shortcut is specific to getAbilityToPlay");
    }
}
