package forge.arena.server;

import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;

/** Logging endpoint for the all-AI match's spectator, which makes no decisions. */
public class LoggingRemote implements IRemote {
    @Override
    public void send(final NetEvent event) {
        System.out.println("[protocol] " + ((GuiGameEvent) event).getMethod());
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        send(event);
        return null;
    }
}
