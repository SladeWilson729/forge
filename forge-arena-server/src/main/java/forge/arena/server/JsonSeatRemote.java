package forge.arena.server;

import com.google.gson.JsonObject;
import forge.arena.server.view.ActiveInputTracker;
import forge.arena.server.view.IdMapper;
import forge.arena.server.view.SeatViewSerializer;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.player.PlayerView;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableObject;
import forge.util.ThreadUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous Forge-to-JSON boundary. Only SeatWriter does socket work. */
public final class JsonSeatRemote implements IRemote {
    private static final System.Logger LOG = System.getLogger(JsonSeatRemote.class.getName());
    @FunctionalInterface
    public interface PromptResponder {
        Object answer(GuiGameEvent event);
    }

    private Game game;
    private PlayerView seat;
    private final HostedMatch match;
    private final int seatIndex;
    private final IdMapper ids = new IdMapper();
    private final SeatViewSerializer serializer = new SeatViewSerializer();
    private final ActiveInputTracker inputTracker = new ActiveInputTracker();
    // Our JSON/bookkeeping only. Never held across a Forge call or a graph walk.
    private final Object stateLock = new Object();
    private boolean buildingSnapshot;
    private final Set<String> unexpectedDisplayThreads = ConcurrentHashMap.newKeySet();
    private final SeatWriter writer;
    private volatile boolean dirty;
    private volatile long lastStateSequence;
    private volatile JsonObject latestState;
    private volatile long lastFullSnapshotSequence;
    private final AtomicLong displayCalls = new AtomicLong();
    private PromptResponder promptResponder = event -> {
        LOG.log(System.Logger.Level.ERROR, "Unexpected blocking seat-view call: {0}", event.getMethod());
        return null; // Task 1.6 supplies the real prompt future.
    };

    public JsonSeatRemote(final Game game, final PlayerView seat, final SeatWriter writer) {
        this.game = game;
        this.seat = seat;
        this.writer = writer;
        this.match = null;
        this.seatIndex = -1;
    }

    public JsonSeatRemote(final HostedMatch match, final int seatIndex, final SeatWriter writer) {
        this.match = Objects.requireNonNull(match);
        this.seatIndex = seatIndex;
        this.writer = writer;
    }

    public void setPromptResponder(final PromptResponder responder) {
        promptResponder = Objects.requireNonNull(responder);
    }

    @Override
    public void send(final NetEvent event) {
        if (!(event instanceof GuiGameEvent gui)) {
            LOG.log(System.Logger.Level.DEBUG, "Ignoring non-GUI event");
            return;
        }
        final Object[] args = gui.getObjects();
        switch (gui.getMethod()) {
            case setSelection, updateButtons, showPromptMessage, setWeaklySelectable,
                    clearWeaklySelectable, showManaPool, hideManaPool, awaitNextInput -> display(gui);
            case setGameView -> snapshot();
            case applyDelta -> {
                final DeltaPacket delta = (DeltaPacket) args[0];
                // Events-only packets may originate on a timer/I/O thread. Even a dirty seat
                // must wait for the next object-bearing delta before walking live Forge state.
                if (!delta.getObjectDeltas().isEmpty() || !delta.getNewObjects().isEmpty()) {
                    snapshot();
                }
            }
            case message, showErrorDialog -> {
                final JsonObject message = new JsonObject();
                message.addProperty("type", "message");
                message.addProperty("level", gui.getMethod() == forge.gamemodes.net.ProtocolMethod.message ? "info" : "error");
                message.addProperty("text", (String) args[0]);
                if (args.length > 1 && args[1] != null) {
                    message.addProperty("title", (String) args[1]);
                }
                writer.send(message);
            }
            case alertUser -> {
                final JsonObject message = new JsonObject();
                message.addProperty("type", "attention");
                writer.send(message);
            }
            default -> LOG.log(System.Logger.Level.DEBUG, "Seat {0}: {1}",
                    seat == null ? seatIndex : seat.getId(), gui.getMethod());
        }
    }

    private void display(final GuiGameEvent event) {
        displayCalls.incrementAndGet();
        String kind = null;
        final List<TrackableObject> defenders = new ArrayList<>();
        // This is deliberately narrower than snapshot(): only display calls inspect InputQueue.
        // A timer's bare showPromptMessage must never inspect the live game or combat.
        if (FThreads.isGuiThread() || ThreadUtil.isGameThread()) {
            Input input = null;
            if (game != null && seat != null
                    && game.getPlayer(seat).getController() instanceof PlayerControllerHuman human) {
                input = human.getInputQueue().getInput();
            }
            kind = inputTracker.classify(input);
            if (input instanceof InputAttack && game.getCombat() != null) {
                game.getCombat().getDefenders().forEach(entity -> defenders.add(GameEntityView.get(entity)));
            }
        } else {
            final String thread = Thread.currentThread().getName();
            if (unexpectedDisplayThreads.add(thread + ":" + event.getMethod())) {
                LOG.log(System.Logger.Level.INFO, "[input] Display {0} on {1}; reusing classification",
                        event.getMethod(), thread);
            }
        }
        inputTracker.display(event, kind, defenders);
        synchronized (stateLock) {
            // A timer can race a full walk. Retain its display in the tracker and let that
            // walk publish it, rather than pairing newly completed ids with the old graph.
            if (latestState == null || buildingSnapshot) return;
            final JsonObject input = inputTracker.toJson(ids);
            if (input.equals(latestState.getAsJsonObject("activeInput"))) return;
            final JsonObject state = latestState.deepCopy();
            state.add("activeInput", input);
            publish(state); // Detached JSON only; SeatWriter assigns a new seq and coalesces.
        }
    }

    private void publish(final JsonObject state) {
        final long seq = writer.send(state);
        latestState = state;
        lastStateSequence = seq;
    }

    private void snapshot() {
        synchronized (stateLock) { buildingSnapshot = true; }
        try {
            if (game == null) {
                game = Objects.requireNonNull(match.getGame());
                seat = game.getPlayers().get(seatIndex).getView();
            }
            // Inline, on Forge's safe caller (plan 2.2 rule 1). Serializer owns the shared
            // CardVisibility/IdMapper policy; never reconstruct visibility in the transport.
            final JsonObject state = serializer.buildState(game, seat, ids, 0);
            synchronized (stateLock) {
                state.add("activeInput", inputTracker.toJson(ids));
                publish(state);
                lastFullSnapshotSequence = lastStateSequence;
                buildingSnapshot = false;
            }
            dirty = false;
        } catch (final RuntimeException | Error ex) {
            synchronized (stateLock) { buildingSnapshot = false; }
            dirty = true;
            LOG.log(System.Logger.Level.ERROR, "Seat " + (seat == null ? seatIndex : seat.getId())
                    + " snapshot failed; retrying on next object delta", ex);
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getLastStateSequence() {
        return lastStateSequence;
    }

    // Test probes only: detached JSON and bookkeeping, never a live Forge graph.
    JsonObject getLatestState() {
        final JsonObject state = latestState;
        return state == null ? null : state.deepCopy();
    }

    IdMapper getIds() {
        return ids;
    }

    long getDisplayCallCount() {
        return displayCalls.get();
    }

    long getLastFullSnapshotSequence() {
        return lastFullSnapshotSequence;
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        if (event instanceof GuiGameEvent gui) {
            return promptResponder.answer(gui);
        }
        LOG.log(System.Logger.Level.ERROR, "Unexpected blocking seat-view call: {0}", event.getClass().getSimpleName());
        return null;
    }
}
