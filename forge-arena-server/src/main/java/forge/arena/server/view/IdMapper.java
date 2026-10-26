package forge.arena.server.view;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private volatile Map<Integer, Entry> current = Map.of();
    private volatile Map<Object, Integer> reverse = Map.of();
    private volatile Map<Integer, Entry> offered = Map.of();
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
        final Map<Object, Integer> nextReverse = new HashMap<>();
        pending.forEach((id, entry) -> {
            nextReverse.put(entry.ref(), id);
        });
        current = Map.copyOf(pending);
        reverse = Map.copyOf(nextReverse);
        pending = null;
    }

    /** A failed Forge walk must not publish a partial visibility/reverse map (plan rule 1). */
    public synchronized void abortSnapshot() {
        pending = null;
    }

    /** Read-only reference from the latest completed snapshot, never from an in-progress walk. */
    public Optional<Object> currentRef(final int realId) {
        final Entry entry = current.get(realId);
        return entry == null ? Optional.empty() : Optional.of(entry.ref());
    }

    /** Netty reads immutable published maps without taking even the bookkeeping leaf lock. */
    public OptionalInt resolve(final Object wireRef) {
        if (!(wireRef instanceof Integer) && !(wireRef instanceof String)) return OptionalInt.empty();
        Integer id = reverse.get(wireRef);
        if (id != null) return OptionalInt.of(id);
        for (var entry : offered.entrySet()) if (entry.getValue().ref().equals(wireRef)) return OptionalInt.of(entry.getKey());
        return OptionalInt.empty();
    }

    /** Offers are Input-scoped, separate from snapshots (libraries remain count-only). */
    public synchronized Object offer(int id, String zone, boolean faceDown, boolean visible) {
        Entry existing = current.get(id);
        if (existing == null || !Objects.equals(existing.zone(), zone) || existing.faceDown() != faceDown
                || (!visible && existing.ref() instanceof Integer)) existing = offered.get(id);
        Object ref = existing != null && Objects.equals(existing.zone(), zone) && existing.faceDown() == faceDown
                && (visible || existing.ref() instanceof String) ? existing.ref() : newOpaqueId();
        Map<Integer, Entry> next = new HashMap<>(offered);
        next.put(id, new Entry(zone, faceDown, visible, ref));
        offered = Map.copyOf(next);
        return ref;
    }

    public synchronized void retainOffers(Set<Integer> ids) {
        Map<Integer, Entry> next = new HashMap<>(offered);
        next.keySet().retainAll(ids);
        offered = Map.copyOf(next);
    }

    public Optional<Object> inputRef(int id) {
        Entry offer = offered.get(id);
        return offer == null ? currentRef(id) : Optional.of(offer.ref());
    }
}
