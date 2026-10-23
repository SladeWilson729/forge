package forge.arena.server;

import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.game.Game;
import forge.gui.control.GameEventForwarder;
import forge.gui.control.WatchLocalGame;

/** In-process protocol boundary for a seat or spectator. */
public class SeatProtocolGui extends ProtocolGuiGame {
    public SeatProtocolGui(final IRemote remote) {
        super(remote);
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
