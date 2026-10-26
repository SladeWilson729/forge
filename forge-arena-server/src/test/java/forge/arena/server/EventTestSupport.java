package forge.arena.server;

import com.google.gson.JsonObject;
import forge.arena.server.view.IdMapper;
import java.util.Set;

/** Test-only access to detached probes from the view-package privacy scenarios. */
public final class EventTestSupport {
    private EventTestSupport() { }
    public static JsonObject state(JsonSeatRemote remote) { return remote.getLatestState(); }
    public static IdMapper ids(JsonSeatRemote remote) { return remote.getIds(); }
    public static Set<Integer> disagreements(JsonSeatRemote remote) { return remote.revealDisagreements(); }
    public static void replacement(JsonSeatRemote remote) { remote.clearReveals(); }
}
