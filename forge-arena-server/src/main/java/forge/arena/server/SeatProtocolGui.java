package forge.arena.server;

import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;

/** In-process protocol boundary for a seat or spectator. */
public class SeatProtocolGui extends ProtocolGuiGame {
    public SeatProtocolGui(final IRemote remote) {
        super(remote);
    }
}
