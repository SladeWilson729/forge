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
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputPassPriority;
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
    private final RegisteredPlayer registeredPlayer;
    private final IdMapper ids = new IdMapper();
    private final SeatViewSerializer serializer = new SeatViewSerializer();
    private final ActiveInputTracker inputTracker = new ActiveInputTracker();
    // Our JSON/bookkeeping only. Never held across a Forge call or a graph walk.
    private final Object stateLock = new Object();
    private boolean buildingSnapshot;
    private int consecutiveSnapshotFailures;
    private volatile forge.gamemodes.net.ProtocolGuiGame protocolGui;

    void attachGui(forge.gamemodes.net.ProtocolGuiGame gui) { protocolGui = gui; }

    void requestFullResync() {
        FThreads.invokeInEdtLater(() -> {
            var gui = protocolGui;
            if (gui == null && game != null && seat != null) {
                var player = game.getPlayer(seat);
                if (player != null && player.getController() instanceof PlayerControllerHuman human
                        && human.getGui() instanceof forge.gamemodes.net.ProtocolGuiGame humanGui) gui = humanGui;
            }
            if (gui != null) gui.setResyncPending();
        });
    }
    // Dirty work is distinct from the failed-snapshot health bit: queued work has an owner.
    private boolean snapshotRequested;
    private final List<List<?>> pendingSnapshotEvents = new ArrayList<>();
    // Test observation point, after the walk but before publication; never called under stateLock.
    private java.util.function.Consumer<JsonObject> snapshotBuiltObserver = state -> { };

    private final Set<String> unexpectedDisplayThreads = ConcurrentHashMap.newKeySet();
    private final SeatWriter writer;
    private volatile boolean dirty;
    private volatile long lastStateSequence;
    private volatile JsonObject latestState;
    private EventPump eventPump;
    private com.google.gson.JsonArray revealed = new com.google.gson.JsonArray();
    private volatile long revealGeneration;
    private final Set<Integer> revealDisagreements = ConcurrentHashMap.newKeySet();
    private volatile long lastFullSnapshotSequence;
    private final AtomicLong displayCalls = new AtomicLong();
    private volatile PromptResponder promptResponder;
    private final PromptCodec promptCodec = new PromptCodec();
    private final ActionRouter actionRouter;
    private volatile String abandonedReason;
    private final Object abandonmentLock = new Object(); // Leaf bookkeeping only, never a Forge call or wait.
    private int inputWaiters;
    private final java.util.Map<Thread, InputPassPriority> priorityWaiters = new java.util.HashMap<>();
    private List<InputPassPriority> abandonedPriorities = List.of();
    private final CompletableFuture<Void> abandonmentReady = new CompletableFuture<>();
    private final CompletableFuture<Void> abandonmentApplied = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicInteger abandonmentInputWaits = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String seatName = "Unbound seat";
    private volatile boolean gameFinished;
    private volatile boolean hasGameState;
    private final java.util.concurrent.atomic.AtomicInteger abandonmentDefaults = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String abandonmentCompletionThread;
    private final java.util.concurrent.ConcurrentLinkedQueue<forge.gamemodes.net.ProtocolMethod> immediateDefaults = new java.util.concurrent.ConcurrentLinkedQueue<>();
    String abandonedReason() { return abandonedReason; }
    String seatName() { return seatName; }
    boolean isHumanSeat() { return registeredPlayer != null; }
    boolean hasGameState() { return hasGameState; }
    boolean gameFinished() { return gameFinished; }
    HostedMatch hostedMatch() { return match; }
    int abandonmentDefaultCount() { return abandonmentDefaults.get(); }
    int pendingPromptCount() { return pending.size(); }
    String abandonmentCompletionThread() { return abandonmentCompletionThread; }
    List<forge.gamemodes.net.ProtocolMethod> immediateDefaults() { return List.copyOf(immediateDefaults); }

    void beforeInput() {
        // This is the Input's Forge-safe caller, before it parks. Retain the public
        // Input object: stop() removes it from InputQueue before the handoff runs.
        Input input = game != null && seat != null
                && game.getPlayer(seat).getController() instanceof PlayerControllerHuman human
                ? human.getInputQueue().getInput() : null;
        final boolean abandoned;
        synchronized (abandonmentLock) {
            inputWaiters++; abandoned = abandonedReason != null;
            if (input instanceof InputPassPriority priority) priorityWaiters.put(Thread.currentThread(), priority);
        }
        if (abandoned) abandonmentReady.complete(null);
    }

    /** Input-latch boundary only: keep a released Game worker parked during the EDT handoff.
     * Prompts still return their defaults immediately; the EDT never waits on this future.
     */
    void afterInput() throws InterruptedException {
        final boolean abandoned;
        final boolean finished;
        synchronized (abandonmentLock) {
            abandoned = abandonedReason != null;
            if (!abandoned) { inputWaiters--; priorityWaiters.remove(Thread.currentThread()); }
            finished = inputWaiters == 0;
        }
        if (!abandoned) {
            if (finished) { inputTracker.decisionFinished(); writer.clockInput(false); }
            return;
        }
        try {
            if (!FThreads.isGuiThread()) {
                abandonmentInputWaits.incrementAndGet();
                abandonmentReady.complete(null);
                abandonmentApplied.get();
            }
        }
        catch (ExecutionException ex) { throw new IllegalStateException(ex.getCause()); }
        finally { synchronized (abandonmentLock) { inputWaiters--; priorityWaiters.remove(Thread.currentThread()); } }
    }
    void whenAbandonmentReady(Runnable handoff) { abandonmentReady.thenRun(handoff); }
    /** Auto-pass has no Input latch, but this Game-thread boundary is equally safe. */
    void abandonmentSafePoint() {
        if (!ThreadUtil.isGameThread() || abandonedReason == null) return;
        abandonmentReady.complete(null);
        abandonmentApplied.join(); // No lock held; never reached on the EDT.
    }
    int abandonmentInputWaitCount() { return abandonmentInputWaits.get(); }
    void abandonmentApplied() { abandonmentApplied.complete(null); }

    /** Console thread only. No Forge calls, monitor, or wait while releasing blocked callers. */
    void abandon(String reason) {
        final boolean parked;
        synchronized (abandonmentLock) {
            if (abandonedReason != null) throw new IllegalStateException("This seat was already abandoned");
            abandonedReason = reason;
            parked = inputWaiters > 0;
            abandonedPriorities = List.copyOf(priorityWaiters.values());
        }
        actionRouter.abandon(reason);
        writer.clockStop();
        pending.values().forEach(request -> {
            abandonmentCompletionThread = Thread.currentThread().getName();
            lastReplyThread = abandonmentCompletionThread;
            if (request.answer.complete(promptCodec.defaultAnswer(request.prompt))) abandonmentDefaults.incrementAndGet();
        });
        if (parked) abandonmentReady.complete(null);
    }
    /** EDT handoff only, while those priority waiters are still parked on abandonmentApplied.
     * A default ability selected inside the EDT action can survive InputQueue.clearInputs().
     * PhaseHandler changes priority after concession, then plays that old list through the
     * NEXT player's controller. Discard the pending action, preserving the legal prompt reply.
     */
    void discardAbandonedPriorityChoices() {
        FThreads.assertExecutedByEdt(true);
        for (InputPassPriority priority : abandonedPriorities) {
            List<forge.game.spellability.SpellAbility> chosen = priority.getChosenSa();
            if (chosen != null) chosen.clear();
        }
        abandonedPriorities = List.of();
    }
    private final YieldBridge yields = new YieldBridge(this);
    YieldBridge yields() { return yields; }
    void yieldConnected() { FThreads.invokeInEdtLater(yields::connected); }
    private final java.util.Map<Integer, com.google.gson.JsonObject> inputOffers = new java.util.LinkedHashMap<>();
    private final Set<Integer> diagnosedSources = ConcurrentHashMap.newKeySet();
    private static final class PendingPrompt {
        final PromptCodec.Prompt prompt;
        final CompletableFuture<Object> answer = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicReference<String> wire = new java.util.concurrent.atomic.AtomicReference<>();
        PendingPrompt(PromptCodec.Prompt prompt) { this.prompt = prompt; }
    }
    private final ConcurrentHashMap<Integer, PendingPrompt> pending = new ConcurrentHashMap<>();
    private volatile String lastReplyThread;
    private volatile java.util.Map<Integer, forge.game.spellability.SpellAbilityView> abilities = java.util.Map.of();

    forge.game.spellability.SpellAbilityView offeredAbility(int id) { return abilities.get(id); }

    public JsonSeatRemote(final Game game, final PlayerView seat, final SeatWriter writer) {
        this.game = game;
        this.seat = seat;
        this.writer = writer;
        this.match = null;
        this.registeredPlayer = null;
        if (seat != null) writer.bindPlayer(seat.getId(), game.getId());
        actionRouter = new ActionRouter(this, false, writer);
        writer.attachRemote(this);
    }

    public JsonSeatRemote(final HostedMatch match, final RegisteredPlayer registeredPlayer, final SeatWriter writer) {
        this.match = Objects.requireNonNull(match);
        this.registeredPlayer = Objects.requireNonNull(registeredPlayer);
        this.writer = writer;
        actionRouter = new ActionRouter(this, true, writer);
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
            case applyYieldUpdate -> writer.send(yields.encode((forge.gamemodes.match.YieldUpdate) args[0]));
            case setSelection, updateButtons, showPromptMessage, setWeaklySelectable,
                    clearWeaklySelectable, showManaPool, hideManaPool, awaitNextInput -> display(gui);
            case setGameView -> snapshot(List.of());
            case applyDelta -> {
                final DeltaPacket delta = (DeltaPacket) args[0];
                // Only object-bearing packets request a walk. Events that arrive during a
                // build join its queue, so a coalesced request never loses its accompanying events.
                if (!delta.getObjectDeltas().isEmpty() || !delta.getNewObjects().isEmpty()) {
                    snapshot(delta.getEvents());
                } else if (delta.hasEvents()) {
                    synchronized (stateLock) {
                        if (buildingSnapshot) {
                            pendingSnapshotEvents.add(List.copyOf(delta.getEvents()));
                            return;
                        }
                    }
                    if (eventPump != null && !dirty) {
                        eventPump.batch(delta.getEvents(), lastStateSequence).forEach(writer::send);
                    }
                }
            }
            case showRevealedCards -> showRevealed((Iterable<?>) args[0]);
            case hideRevealedCards -> clearReveals();
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
            case flashIncorrectAction -> actionRouter.reject("Forge rejected that action");
            default -> LOG.log(System.Logger.Level.DEBUG, "Seat {0}: {1}",
                    seat == null ? "unbound" : seat.getId(), gui.getMethod());
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
            updateOffers(event, kind);
        } else {
            final String thread = Thread.currentThread().getName();
            if (unexpectedDisplayThreads.add(thread + ":" + event.getMethod())) {
                LOG.log(System.Logger.Level.INFO, "[input] Display {0} on {1}; reusing classification",
                        event.getMethod(), thread);
            }
        }
        inputTracker.display(event, kind, defenders);
        // A selection/highlight push alone is not a visible decision. In particular
        // InputPassPriority can stop itself for a yield before showing any buttons.
        if (kind != null) writer.clockInput(inputTracker.decisionDisplayed());
        synchronized (stateLock) {
            // A timer can race a full walk. Retain its display in the tracker and let that
            // walk publish it, rather than pairing newly completed ids with the old graph.
            if (latestState == null || buildingSnapshot || dirty) return;
            publishInputChange();
        }
    }

    /** stateLock held; detached JSON only. Also catches displays arriving late in a build. */
    private void publishInputChange() {
        if (latestState == null) return;
        final JsonObject input = inputTracker.toJson(ids);
        if (input.equals(latestState.getAsJsonObject("activeInput"))) return;
        final JsonObject state = latestState.deepCopy();
        state.add("activeInput", input);
        publish(state);
    }

    /** Forge-safe display caller only. Offers never grant another seat visibility. */
    private void updateOffers(GuiGameEvent event, String kind) {
        if (game == null || seat == null) return;
        if ("waiting".equals(kind) || event.getMethod() == forge.gamemodes.net.ProtocolMethod.awaitNextInput) {
            inputOffers.clear(); ids.retainOffers(Set.of());
        } else if (event.getMethod() == forge.gamemodes.net.ProtocolMethod.setSelection) {
            final var selection = (forge.gamemodes.match.Selection) event.getObjects()[0];
            inputOffers.clear();
            for (var card : selection.cards()) addOffer(card);
            ids.retainOffers(inputOffers.keySet());
        } else if (event.getMethod() == forge.gamemodes.net.ProtocolMethod.showPromptMessage
                && event.getObjects()[2] instanceof forge.game.card.CardView card && ids.currentRef(card.getId()).isEmpty()) {
            if (diagnosedSources.add(card.getId())) LOG.log(System.Logger.Level.INFO,
                    "[input-source] id={0} name={1} zone={2} kind={3}", card.getId(), card.getName(), card.getZone(), kind);
            addOffer(card);
        }
        com.google.gson.JsonArray offers = new com.google.gson.JsonArray();
        inputOffers.values().forEach(offers::add);
        inputTracker.setOffered(offers);
    }

    private void addOffer(forge.game.card.CardView card) {
        boolean visible = !card.isFaceDown() || forge.arena.server.view.CardVisibility.forSeat(card, seat).identityVisible();
        Object ref = ids.offer(card.getId(), String.valueOf(card.getZone()), card.isFaceDown(), visible);
        inputOffers.put(card.getId(), PromptCodec.offeredCard(card, new PromptCodec.Context(game, seat, ids), ref));
    }

    void action(com.google.gson.JsonObject message, java.util.function.Consumer<String> result) { actionRouter.accept(message, result); }
    long completedActionCount() { return actionRouter.completedCount(); }
    Game actionGame() { return game; } // EDT only
    PlayerView actionSeat() { return seat; } // EDT only
    boolean hasPendingPrompt() { return !pending.isEmpty(); }

    private void publish(final JsonObject state) {
        final long seq = writer.send(state);
        latestState = state;
        lastStateSequence = seq;
    }

    private void showRevealed(Iterable<?> cards) {
        if (latestState == null) return;
        final long generation = revealGeneration;
        final com.google.gson.JsonArray shown = new com.google.gson.JsonArray();
        ids.clearReveals();
        for (Object value : cards) {
            final var card = (forge.game.card.CardView) value;
            final var visibility = forge.arena.server.view.CardVisibility.forSeat(card, seat);
            if (!visibility.identityVisible() && revealDisagreements.add(card.getId())) {
                LOG.log(System.Logger.Level.WARNING,
                        "[reveal] Forge grant disagrees with CardVisibility: seat={0} card={1} zone={2}; retaining filtered variant",
                        seat.getId(), card.getId(), card.getZone());
            }
            final Object ref = ids.reveal(card.getId(), String.valueOf(card.getZone()), card.isFaceDown(), visibility.identityVisible());
            shown.add(SeatViewSerializer.cardStub(card, seat, ref));
        }
        synchronized (stateLock) {
            if (generation != revealGeneration) { ids.clearReveals(); return; }
            revealed = shown;
            JsonObject state = latestState.deepCopy(); state.add("revealed", shown.deepCopy()); publish(state);
            writer.send(eventPump.reveal(shown, lastStateSequence));
        }
    }

    /** Detached bookkeeping only; safe for replacement connections on the writer thread. */
    void clearReveals() {
        synchronized (stateLock) {
            revealGeneration++; ids.clearReveals();
            if (revealed.isEmpty()) return;
            revealed = new com.google.gson.JsonArray();
            if (latestState != null) {
                JsonObject state = latestState.deepCopy(); state.remove("revealed"); publish(state);
            }
        }
    }

    Set<Integer> revealDisagreements() { return Set.copyOf(revealDisagreements); }

    private void snapshot(final List<?> events) {
        synchronized (stateLock) {
            if (consecutiveSnapshotFailures >= 3) return;
            snapshotRequested = true;
            if (events != null && !events.isEmpty()) pendingSnapshotEvents.add(List.copyOf(events));
            if (buildingSnapshot) return;
            buildingSnapshot = true;
        }
        try {
            while (true) {
                final boolean rebuild;
                final List<List<?>> batches;
                synchronized (stateLock) {
                    rebuild = snapshotRequested;
                    snapshotRequested = false;
                    batches = new ArrayList<>(pendingSnapshotEvents);
                    pendingSnapshotEvents.clear();
                    if (!rebuild && batches.isEmpty()) {
                        publishInputChange();
                        buildingSnapshot = false;
                        return; // Ownership release and the empty check are atomic with requests.
                    }
                }
                // The owner stays on its original Forge-safe caller through every rebuild.
                // No bridge monitor is held during a Forge read, callback, or event encoding.
                if (rebuild) {
                    buildSnapshot();
                    synchronized (stateLock) { consecutiveSnapshotFailures = 0; dirty = false; }
                }
                if (eventPump != null) {
                    for (List<?> batch : batches) eventPump.batch(batch, lastStateSequence).forEach(writer::send);
                }
            }
        } catch (final RuntimeException | Error ex) {
            final int failures;
            synchronized (stateLock) {
                dirty = true; buildingSnapshot = false; snapshotRequested = false;
                pendingSnapshotEvents.clear(); failures = ++consecutiveSnapshotFailures;
            }
            if (failures == 1) {
                LOG.log(System.Logger.Level.ERROR, "Seat display update failed; requesting resync", ex);
                JsonObject error = new JsonObject(); error.addProperty("type", "message");
                error.addProperty("level", "error"); error.addProperty("text", "Display update failed; resyncing");
                try { writer.send(error); }
                catch (IllegalStateException closed) {
                    LOG.log(System.Logger.Level.DEBUG, "Seat writer closed during snapshot failure", closed);
                }
            }
            if (failures < 3) requestFullResync();
            else LOG.log(System.Logger.Level.WARNING, "Seat display update failed three consecutive times; retries stopped");
        }
    }

    private void buildSnapshot() {
        if (match != null && game != match.getGame()) {
            final Game running = Objects.requireNonNull(match.getGame());
            // HostedMatch sorts humans first; wire seat numbers are never game-list indexes.
            seat = ArenaServerMain.playerFor(running, registeredPlayer).getView();
            game = running;
            writer.bindPlayer(seat.getId(), game.getId());
            eventPump = null;
        }
        // Inline, on Forge's safe caller (plan 2.2 rule 1). Serializer owns the shared
        // CardVisibility/IdMapper policy; never reconstruct visibility in the transport.
        final JsonObject state = serializer.buildState(game, seat, ids, 0);
        snapshotBuiltObserver.accept(state);
        if (eventPump == null) eventPump = new EventPump(seat, ids);
        final boolean gameOver = game.isGameOver();
        seatName = game.getPlayer(seat).getName();
        gameFinished = gameOver;
        hasGameState = true;
        writer.clockPlayer(game.getId(), seatName, game.getPlayer(seat).getView().getHasLost(), gameOver);
        if (gameOver) writer.flushTranscript();
        synchronized (stateLock) {
            if (gameOver) { revealed = new com.google.gson.JsonArray(); revealGeneration++; ids.clearReveals(); }
            if (!revealed.isEmpty()) state.add("revealed", revealed.deepCopy());
            state.add("activeInput", inputTracker.toJson(ids));
            publish(state);
            lastFullSnapshotSequence = lastStateSequence;
        }
        eventPump.snapshot(game, state, serializer.cardStubs()).forEach(writer::send);
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getLastStateSequence() {
        return lastStateSequence;
    }

    // Test probes only: detached JSON and bookkeeping, never a live Forge graph.
    void setSnapshotBuiltObserver(java.util.function.Consumer<JsonObject> observer) {
        snapshotBuiltObserver = Objects.requireNonNull(observer);
    }

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
            if (gui.getMethod() == forge.gamemodes.net.ProtocolMethod.getAbilityToPlay) {
                java.util.Map<Integer, forge.game.spellability.SpellAbilityView> offered = new java.util.HashMap<>();
                for (Object value : (Iterable<?>) gui.getObjects()[1]) {
                    var ability = (forge.game.spellability.SpellAbilityView) value;
                    offered.put(ability.getId(), ability);
                }
                abilities = java.util.Map.copyOf(offered);
            }
            if (gui.getMethod() == forge.gamemodes.net.ProtocolMethod.sideboard) {
                LOG.log(System.Logger.Level.INFO, "[prompt] sideboard: unchanged main deck (out of v1)");
                return ((CardPool) gui.getObjects()[1]).toFlatList();
            }
            if (abandonedReason == null && promptResponder != null) return promptResponder.answer(gui);
            final PromptCodec.Prompt prompt = promptCodec.encode(gui, new PromptCodec.Context(game, seat, ids));
            if (abandonedReason != null) {
                abandonmentDefaults.incrementAndGet();
                immediateDefaults.add(gui.getMethod());
                lastReplyThread = Thread.currentThread().getName();
                return promptCodec.defaultAnswer(prompt);
            }
            final PendingPrompt request = new PendingPrompt(prompt);
            if (pending.putIfAbsent(prompt.requestId(), request) != null) throw new IllegalStateException("Duplicate requestId");
            writer.clockPrompt(prompt.requestId(), true);
            try {
                final JsonObject message = prompt.json();
                // The console can abandon between encoding and registration. Recheck after
                // insertion so its pending-map scan and this caller cannot miss each other.
                if (abandonedReason != null) {
                    immediateDefaults.add(gui.getMethod());
                    lastReplyThread = Thread.currentThread().getName();
                    if (request.answer.complete(promptCodec.defaultAnswer(prompt))) abandonmentDefaults.incrementAndGet();
                } else writer.send(message);
                // The writer may already have delivered a resequenced envelope. Never
                // overwrite that exact replay copy with this initial allocated sequence.
                request.wire.compareAndSet(null, WsServer.GSON.toJson(message));
                // No bridge lock is held. A reply completes on I/O, even when THIS is the EDT.
                return request.answer.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting prompt " + prompt.requestId(), ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Prompt failed", ex.getCause());
            } finally {
                writer.clockPrompt(prompt.requestId(), false);
                pending.remove(prompt.requestId(), request); // Waiting thread, never Netty I/O.
            }
        }
        LOG.log(System.Logger.Level.ERROR, "Unexpected blocking seat-view call: {0}", event.getClass().getSimpleName());
        return null;
    }

    /** I/O path: lock-free lookup, pure decoding, future completion. Never dispatch to the EDT. */
    String promptReply(final JsonObject reply) {
        if (abandonedReason != null) { actionRouter.reject(abandonedReason); return "rejected"; }
        try {
            final int id = PromptCodec.integer(reply.get("requestId"));
            final PendingPrompt request = pending.get(id);
            if (request == null || request.answer.isDone()) {
                LOG.log(System.Logger.Level.INFO, "[prompt] Ignoring unknown/finished requestId {0}", id);
                return null;
            }
            final Object answer = promptCodec.decode(request.prompt, reply);
            final String thread = Thread.currentThread().getName();
            lastReplyThread = thread;
            if (request.answer.complete(answer)) {
                writer.clockPrompt(id, false);
                LOG.log(System.Logger.Level.INFO, "[prompt] Completed {0} on {1}", id, thread);
            }
            return "accepted";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            final JsonObject message = new JsonObject();
            message.addProperty("type", "message"); message.addProperty("level", "rejected");
            message.addProperty("text", ex.getMessage());
            writer.sendFromIo(message);
            return "rejected";
        }
    }

    /** Writer-thread reconnect replay. Envelopes retain their seq and requestId, like cached state. */
    List<String> pendingPromptMessages() {
        return pendingPromptWires().stream().map(PromptWire::json).toList();
    }

    record PromptWire(int requestId, String json) { }
    List<PromptWire> pendingPromptWires() {
        if (abandonedReason != null) return List.of();
        return pending.values().stream().filter(request -> !request.answer.isDone() && request.wire.get() != null)
                .sorted(java.util.Comparator.comparingInt(request -> request.prompt.requestId()))
                .map(request -> new PromptWire(request.prompt.requestId(), request.wire.get())).toList();
    }

    /** Writer-owned delivery callback; detached JSON/bookkeeping only, no Forge or waiting. */
    void promptDelivered(int requestId, String wire) {
        PendingPrompt request = pending.get(requestId);
        if (request != null) request.wire.set(wire);
    }

    String getLastReplyThread() { return lastReplyThread; }
}
