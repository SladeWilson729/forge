package forge.arena.server;

import com.google.gson.JsonObject;
import forge.arena.server.view.ActiveInputTracker;
import forge.arena.server.view.IdMapper;
import forge.arena.server.view.SeatViewSerializer;
import forge.arena.server.prompt.PromptCodec;
import forge.deck.CardPool;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private volatile PromptResponder promptResponder;
    private final PromptCodec promptCodec = new PromptCodec();
    private static final class PendingPrompt {
        final PromptCodec.Prompt prompt;
        final CompletableFuture<Object> answer = new CompletableFuture<>();
        volatile String wire;
        PendingPrompt(PromptCodec.Prompt prompt) { this.prompt = prompt; }
    }
    private final ConcurrentHashMap<Integer, PendingPrompt> pending = new ConcurrentHashMap<>();
    private volatile String lastReplyThread;

    public JsonSeatRemote(final Game game, final PlayerView seat, final SeatWriter writer) {
        this.game = game;
        this.seat = seat;
        this.writer = writer;
        this.match = null;
        this.seatIndex = -1;
        writer.attachRemote(this);
    }

    public JsonSeatRemote(final HostedMatch match, final int seatIndex, final SeatWriter writer) {
        this.match = Objects.requireNonNull(match);
        this.seatIndex = seatIndex;
        this.writer = writer;
        writer.attachRemote(this);
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
            LOG.log(System.Logger.Level.INFO, "[prompt] sendAndWait method={0} thread={1} requestId={2}",
                    gui.getMethod(), Thread.currentThread().getName(), gui.getId());
            if (promptResponder != null) return promptResponder.answer(gui);
            if (gui.getMethod() == forge.gamemodes.net.ProtocolMethod.sideboard) {
                LOG.log(System.Logger.Level.INFO, "[prompt] sideboard: unchanged main deck (out of v1)");
                return ((CardPool) gui.getObjects()[1]).toFlatList();
            }
            final PromptCodec.Prompt prompt = promptCodec.encode(gui, new PromptCodec.Context(game, seat, ids));
            final PendingPrompt request = new PendingPrompt(prompt);
            if (pending.putIfAbsent(prompt.requestId(), request) != null) throw new IllegalStateException("Duplicate requestId");
            try {
                final JsonObject message = prompt.json();
                writer.send(message);
                request.wire = WsServer.GSON.toJson(message);
                // No bridge lock is held. A reply completes on I/O, even when THIS is the EDT.
                return request.answer.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting prompt " + prompt.requestId(), ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Prompt failed", ex.getCause());
            } finally {
                pending.remove(prompt.requestId(), request); // Waiting thread, never Netty I/O.
            }
        }
        LOG.log(System.Logger.Level.ERROR, "Unexpected blocking seat-view call: {0}", event.getClass().getSimpleName());
        return null;
    }

    /** I/O path: lock-free lookup, pure decoding, future completion. Never dispatch to the EDT. */
    void promptReply(final JsonObject reply) {
        try {
            final int id = PromptCodec.integer(reply.get("requestId"));
            final PendingPrompt request = pending.get(id);
            if (request == null || request.answer.isDone()) {
                LOG.log(System.Logger.Level.INFO, "[prompt] Ignoring unknown/finished requestId {0}", id);
                return;
            }
            final Object answer = promptCodec.decode(request.prompt, reply);
            final String thread = Thread.currentThread().getName();
            lastReplyThread = thread;
            if (request.answer.complete(answer)) LOG.log(System.Logger.Level.INFO, "[prompt] Completed {0} on {1}", id, thread);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            final JsonObject message = new JsonObject();
            message.addProperty("type", "message"); message.addProperty("level", "rejected");
            message.addProperty("text", ex.getMessage());
            writer.sendFromIo(message);
        }
    }

    /** Writer-thread reconnect replay. Envelopes retain their seq and requestId, like cached state. */
    List<String> pendingPromptMessages() {
        return pending.values().stream().filter(request -> !request.answer.isDone() && request.wire != null)
                .sorted(java.util.Comparator.comparingInt(request -> request.prompt.requestId()))
                .map(request -> request.wire).toList();
    }

    String getLastReplyThread() { return lastReplyThread; }
}
