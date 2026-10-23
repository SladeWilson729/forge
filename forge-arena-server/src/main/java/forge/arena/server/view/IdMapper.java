package forge.arena.server.view;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One seat's card identities. All arguments are copied values, never Forge objects.
 * The monitor is a leaf lock: no Forge calls, callbacks, other seat locks or waits.
 * Build a snapshot on one Forge-safe caller; resolve sees the last completed snapshot.
 */
public final class IdMapper {
    private record Entry(String zone, boolean faceDown, boolean visible, Object ref) { }

    private final SecureRandom random = new SecureRandom();
    private final Set<String> issued = new HashSet<>();
    private Map<Integer, Entry> current = new HashMap<>();
    private Map<String, Integer> opaque = new HashMap<>();
    private Map<Integer, Entry> pending;

    public synchronized void beginSnapshot() {
        if (pending != null) {
            throw new IllegalStateException("A snapshot is already in progress");
        }
        pending = new HashMap<>();
    }

    public synchronized Object wireRef(final int realId, final String zone,
                                       final boolean faceDown, final boolean identityVisible) {
        if (pending == null) {
            throw new IllegalStateException("beginSnapshot must precede wireRef");
        }
        final Entry already = pending.get(realId);
        if (already != null) {
            if (!Objects.equals(already.zone(), zone) || already.faceDown() != faceDown
                    || already.visible() != identityVisible) {
                throw new IllegalStateException("Card state changed during a snapshot");
            }
            return already.ref();
        }
        final Entry previous = current.get(realId);
        final Object ref;
        if (identityVisible) {
            ref = Integer.valueOf(realId);
        } else if (previous == null || previous.visible() || !Objects.equals(previous.zone(), zone)
                || (!previous.faceDown() && faceDown)) {
            ref = newOpaqueId();
        } else {
            ref = previous.ref();
        }
        pending.put(realId, new Entry(zone, faceDown, identityVisible, ref));
        return ref;
    }

    private String newOpaqueId() {
        final byte[] bytes = new byte[6];
        String ref;
        do {
            random.nextBytes(bytes);
            ref = "h-" + HexFormat.of().formatHex(bytes);
        } while (!issued.add(ref));
        return ref;
    }

    /** Publishes the new reverse map and retires every card absent from this snapshot. */
    public synchronized void endSnapshot() {
        if (pending == null) {
            throw new IllegalStateException("No snapshot is in progress");
        }
        final Map<String, Integer> nextOpaque = new HashMap<>();
        pending.forEach((id, entry) -> {
            if (entry.ref() instanceof String hidden) {
                nextOpaque.put(hidden, id);
            }
        });
        current = pending;
        opaque = nextOpaque;
        pending = null;
    }

    /** A failed Forge walk must not publish a partial visibility/reverse map (plan rule 1). */
    public synchronized void abortSnapshot() {
        pending = null;
    }

    public synchronized OptionalInt resolve(final Object wireRef) {
        if (wireRef instanceof Integer id) {
            final Entry entry = current.get(id);
            return entry != null && entry.visible() ? OptionalInt.of(id) : OptionalInt.empty();
        }
        if (wireRef instanceof String hidden) {
            final Integer id = opaque.get(hidden);
            return id == null ? OptionalInt.empty() : OptionalInt.of(id);
        }
        return OptionalInt.empty();
    }
}
