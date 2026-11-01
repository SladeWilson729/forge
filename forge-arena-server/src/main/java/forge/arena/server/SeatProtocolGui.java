package forge.arena.server;

import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.spellability.SpellAbilityView;
import forge.gui.control.GameEventForwarder;
import forge.gui.control.WatchLocalGame;
import forge.util.ITriggerEvent;

import java.util.List;

/** In-process protocol boundary for a seat or spectator. */
public class SeatProtocolGui extends ProtocolGuiGame {
    private final JsonSeatRemote seatRemote;
    public SeatProtocolGui(final IRemote remote) {
        super(remote);
        seatRemote = remote instanceof JsonSeatRemote jsonRemote ? jsonRemote : null;
        if (seatRemote != null) seatRemote.attachGui(this);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
                                             final ITriggerEvent triggerEvent) {
        // Match CMatchUI's immediate-return branches before entering the remote dialog.
        // With no click event, Forge's optional-cost selection also returns its single
        // variant directly, even if it is unplayable or requests a mouse-triggered prompt.
        if (abilities.isEmpty()) return null;
        if (abilities.size() == 1) {
            final SpellAbilityView ability = abilities.get(0);
            if (triggerEvent == null) return ability;
            if (!ability.promptIfOnlyPossibleAbility()) return ability.canPlay() ? ability : null;
        }
        return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
    }

    @Override public void awaitInput(final java.util.concurrent.CountDownLatch done) throws InterruptedException {
        if (seatRemote != null) seatRemote.beforeInput();
        try { super.awaitInput(done); }
        finally { if (seatRemote != null) seatRemote.afterInput(); }
    }

    @Override public void awaitNextInput() {
        super.awaitNextInput();
        if (seatRemote != null) seatRemote.abandonmentSafePoint();
    }

    /** Attach before gameplay starts. Never register these watchers with HostedMatch. */
    public static SeatProtocolGui attach(final Game game, final JsonSeatRemote remote) {
        final SeatProtocolGui gui = new SeatProtocolGui(remote);
        gui.setGameView(game.getView());
        gui.setSpectator(new WatchLocalGame(game, null, gui));
        final GameEventForwarder forwarder = new GameEventForwarder(gui);
        gui.setForwarder(forwarder);
        game.subscribeToEvents(forwarder);
        // HostedMatch does this after its human-seat setup; it enables initialSyncSent.
        gui.openView(null);
        return gui;
    }
}
