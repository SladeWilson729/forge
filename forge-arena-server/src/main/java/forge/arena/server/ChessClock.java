package forge.arena.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One actor per timed game server. Its queue contains primitives only, never Forge objects.
 * Producers timestamp transitions; only Arena-clock accounts time and publishes public values.
 */
final class ChessClock implements AutoCloseable {
    private final long budget;
    private final LongSupplier now;
    private final Consumer<JsonObject> output;
    private final ScheduledExecutorService scheduler;
    private final long[] remaining = new long[4], inputOrder = new long[4], promptOrder = new long[4];
    private final boolean[] connected = new boolean[4], input = new boolean[4], stopped = new boolean[4], kept = new boolean[4];
    private final String[] names = {"Seat 0", "Seat 1", "Seat 2", "Seat 3"};
    private final List<Set<Integer>> prompts = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
    private final Set<Integer> expired = new HashSet<>();
    private volatile Set<Integer> publicExpired = Set.of();
    private volatile JsonObject latest;
    private volatile BiConsumer<Integer, String> timeout = (seat, name) ->
            System.out.println("[arena] " + name + " is out of time: keep " + seat + " or concede " + seat);
    private int gameId = -1, running = -1;
    private long last, order;
    private boolean ended;
    private volatile boolean closed;

    static int minutes(String value) {
        if (!Set.of("0", "30", "45").contains(value))
            throw new IllegalArgumentException("arena.clockMinutes must be 0 (untimed), 30, or 45; got '" + value + "'");
        return Integer.parseInt(value);
    }

    ChessClock(int minutes, LongSupplier now, Consumer<JsonObject> output, boolean automaticTicks) {
        minutes(Integer.toString(minutes));
        budget = TimeUnit.MINUTES.toNanos(minutes); this.now = now; this.output = output;
        Arrays.fill(remaining, budget); last = now.getAsLong(); publish();
        scheduler = minutes == 0 ? null : Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "Arena-clock"); thread.setDaemon(true); return thread;
        });
        if (scheduler != null && automaticTicks)
            scheduler.scheduleAtFixedRate(() -> apply(now.getAsLong(), () -> false, true), 1, 1, TimeUnit.SECONDS);
    }

    void onTimeout(BiConsumer<Integer, String> listener) { timeout = Objects.requireNonNull(listener); }
    boolean expired(int seat) { return publicExpired.contains(seat); }
    JsonObject snapshot() { return latest.deepCopy(); }

    private void post(java.util.function.BooleanSupplier change) {
        if (scheduler == null || closed) return;
        long at = now.getAsLong();
        try { scheduler.execute(() -> apply(at, change, false)); }
        catch (RejectedExecutionException ex) { if (!closed) throw ex; }
    }

    void player(int seat, int game, String name, boolean eliminated, boolean gameOver) {
        post(() -> {
            boolean fresh = gameId != game;
            if (fresh) {
                gameId = game; ended = false; running = -1; order = 0;
                Arrays.fill(remaining, budget); Arrays.fill(input, false); Arrays.fill(stopped, false); Arrays.fill(kept, false);
                Arrays.fill(inputOrder, 0); Arrays.fill(promptOrder, 0); prompts.forEach(Set::clear); expired.clear();
            }
            names[seat] = name;
            if (eliminated) { stopped[seat] = true; input[seat] = false; prompts.get(seat).clear(); expired.remove(seat); }
            if (gameOver) { ended = true; expired.clear(); }
            return fresh;
        });
    }
    void connected(int seat, boolean value) {
        post(() -> { boolean changed = connected[seat] != value; connected[seat] = value; return changed; });
    }
    void input(int seat, boolean value) {
        post(() -> {
            if (ended || stopped[seat] || input[seat] == value) return false;
            input[seat] = value; if (value) inputOrder[seat] = ++order;
            return false;
        });
    }
    void prompt(int seat, int requestId, boolean open) {
        post(() -> {
            if (ended || stopped[seat]) return false;
            if (open && prompts.get(seat).add(requestId)) promptOrder[seat] = ++order;
            else if (!open) prompts.get(seat).remove(requestId);
            return false;
        });
    }
    void stop(int seat) {
        post(() -> { stopped[seat] = true; input[seat] = false; prompts.get(seat).clear(); expired.remove(seat); return false; });
    }

    CompletableFuture<Boolean> keep(int seat) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (scheduler == null || closed) { result.complete(false); return result; }
        long at = now.getAsLong();
        scheduler.execute(() -> {
            boolean[] accepted = {false};
            apply(at, () -> {
                accepted[0] = !ended && !stopped[seat] && expired.remove(seat);
                if (accepted[0]) kept[seat] = true;
                return accepted[0];
            }, false);
            result.complete(accepted[0]);
        });
        return result;
    }

    /** Manual scheduler pulse/barrier for injected-time tests; never called by Forge or Netty. */
    CompletableFuture<JsonObject> tick() {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        if (scheduler == null || closed) { result.complete(snapshot()); return result; }
        scheduler.execute(() -> { apply(now.getAsLong(), () -> false, true); result.complete(snapshot()); });
        return result;
    }

    private void apply(long at, java.util.function.BooleanSupplier change, boolean pulse) {
        if (closed) return;
        at = Math.max(last, at);
        int before = running;
        Integer zero = null;
        if (before >= 0) {
            remaining[before] = Math.max(0, remaining[before] - (at - last));
            if (remaining[before] == 0 && expired.add(before)) zero = before;
        }
        last = at;
        boolean force = change.getAsBoolean();
        // Select the current DECISION first, even if its owner is paused/out of time.
        // Never charge an older stale input just because that decision disconnected.
        int owner = -1; long newest = -1;
        if (!ended && gameId >= 0) for (int seat = 0; seat < 4; seat++) {
            if (stopped[seat]) continue;
            long candidate = Math.max(input[seat] ? inputOrder[seat] : -1, !prompts.get(seat).isEmpty() ? promptOrder[seat] : -1);
            if (candidate > newest) { newest = candidate; owner = seat; }
        }
        running = owner >= 0 && connected[owner] && !kept[owner] && remaining[owner] > 0 ? owner : -1;
        publish();
        if (gameId >= 0 && (force || before != running || zero != null || (pulse && running >= 0))) output.accept(snapshot());
        if (zero != null && !ended && !stopped[zero] && expired.contains(zero)) {
            JsonObject warning = new JsonObject(); warning.addProperty("type", "message");
            warning.addProperty("level", "warning"); warning.addProperty("text", names[zero] + " is out of time; waiting for the host");
            output.accept(warning); timeout.accept(zero, names[zero]);
        }
    }

    private void publish() {
        JsonObject message = new JsonObject(); message.addProperty("type", "clock"); message.addProperty("seq", 0);
        JsonArray seats = new JsonArray();
        for (int seat = 0; seat < 4; seat++) {
            JsonObject row = new JsonObject(); row.addProperty("seat", seat);
            row.addProperty("remainingMs", (remaining[seat] + 999_999) / 1_000_000);
            row.addProperty("running", running == seat); seats.add(row);
        }
        message.add("seats", seats); latest = message; publicExpired = Set.copyOf(expired);
    }

    @Override public void close() {
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Clock scheduler did not stop"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
