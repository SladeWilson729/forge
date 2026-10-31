package forge.arena.server;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** One daemon per seat; only detached JSON and bookkeeping cross to this thread. */
public final class SeatWriter implements AutoCloseable {
    private static final ClientMessageSchema CLIENT_MESSAGES = new ClientMessageSchema();
    private static final System.Logger LOG = System.getLogger(SeatWriter.class.getName());
    private record Outbound(long seq, String json, boolean state, long stateSeq, JsonObject payload) { }
    record GameStart(java.time.ZonedDateTime time, long nanos) { }

    // Slight headroom below 10/s also avoids a local client's timer jitter counting 11 arrivals.
    private static final long STATE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(125);
    private final Object queueLock = new Object();
    private final ArrayDeque<Outbound> messages = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Channel> connections = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> ioMessages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> transcriptWork = new ConcurrentLinkedQueue<>();
    private TranscriptLog transcript;
    private volatile forge.deck.Deck acceptedDeck;

    forge.deck.Deck acceptedDeck() { return acceptedDeck; }

    void submitDeck(JsonObject message) {
        final long when = System.nanoTime();
        forge.gui.FThreads.invokeInEdtLater(() -> {
            java.util.List<String> commander = new java.util.ArrayList<>();
            if (message.has("commander")) message.getAsJsonArray("commander").forEach(name -> commander.add(name.getAsString()));
            DeckImport.Result result = DeckImport.parse(message.get("text").getAsString(), commander);
            if (result.ok()) acceptedDeck = result.deck();
            send(result.json());
            transcriptInput(message, result.ok() ? "accepted" : "rejected", when);
        });
    }
    private int transcriptGame = -1;
    private volatile JsonSeatRemote remote;
    private final Thread thread;
    private final int seat;
    private final java.util.concurrent.ConcurrentMap<Integer, GameStart> gameStarts;
    private volatile String hello;
    private Outbound pendingState;
    private long sequence;
    private volatile boolean closed;
    private volatile String lastState;
    private JsonObject lastStatePayload; // Writer-owned companion to the serialized cache.
    private final java.util.concurrent.atomic.AtomicBoolean resyncRequested = new java.util.concurrent.atomic.AtomicBoolean();
    // Only the writer changes the bound channel; I/O reads it to gate seat traffic.
    private volatile Channel channel;
    private volatile long disconnectedAtNanos;
    boolean connected() { Channel current = channel; return current != null && current.isActive(); }
    long disconnectedAtNanos() { return disconnectedAtNanos; }
    JsonSeatRemote remote() { return remote; }
    private final java.util.Set<Channel> awaitingJoin = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private String seatToken;

    boolean isBound(Channel candidate) { return channel == candidate && candidate.isActive(); }

    int queuedNonStates() { synchronized (queueLock) { return messages.size(); } }
    int queuedStates() { synchronized (queueLock) { return pendingState == null ? 0 : 1; } }

    void join(Channel candidate, JsonObject message) {
        final JsonObject copy = message.deepCopy();
        transcriptWork.add(() -> acceptJoin(candidate, copy));
        LockSupport.unpark(thread);
    }

    private void acceptJoin(Channel candidate, JsonObject message) {
        if (!candidate.isActive() || !awaitingJoin.contains(candidate)) return;
        if (!message.has("protocolVersion") || !new com.google.gson.JsonPrimitive(1).equals(message.get("protocolVersion"))) {
            transcriptInput(message, "rejected", System.nanoTime());
            rejectJoin(candidate, "Protocol version 1 is required", 4002); return;
        }
        try { CLIENT_MESSAGES.validate(message); }
        catch (IllegalArgumentException ex) {
            transcriptInput(message, "rejected", System.nanoTime());
            rejectJoin(candidate, "Invalid join message", 4002); return;
        }
        if (seatToken != null && (!message.has("seatToken") || !seatToken.equals(message.get("seatToken").getAsString()))) {
            transcriptInput(message, "rejected", System.nanoTime());
            rejectJoin(candidate, "This seat is already taken", 4001); return;
        }
        if (seatToken == null) {
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            seatToken = java.util.HexFormat.of().formatHex(bytes);
        }
        awaitingJoin.remove(candidate);
        bindConnection(candidate);
        JsonObject joined = new JsonObject(); joined.addProperty("type", "joined"); joined.addProperty("seq", 0);
        joined.addProperty("seat", seat); joined.addProperty("seatToken", seatToken); write(WsServer.GSON.toJson(joined));
        transcriptInput(message, "accepted", System.nanoTime());
        replayPending = true;
    }

    private void rejectJoin(Channel candidate, String reason, int code) {
        awaitingJoin.remove(candidate);
        JsonObject message = new JsonObject(); message.addProperty("type", "message"); message.addProperty("seq", 0);
        message.addProperty("level", "rejected"); message.addProperty("text", reason);
        writeTo(candidate, WsServer.GSON.toJson(message));
        candidate.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
    }

    void rejectUnbound(Channel candidate, JsonObject input, String reason) {
        if (input != null) transcriptInput(input, "rejected", System.nanoTime());
        transcriptWork.add(() -> {
            JsonObject message = new JsonObject(); message.addProperty("type", "message"); message.addProperty("seq", 0);
            message.addProperty("level", "rejected"); message.addProperty("text", reason);
            writeTo(candidate, WsServer.GSON.toJson(message));
        });
        LockSupport.unpark(thread);
    }
    private boolean connectionClosed;
    private long nextStateAt;
    private long lastStateSeq;
    private boolean replayPending;
    private final ArrayDeque<JsonObject> logTail = new ArrayDeque<>();

    public SeatWriter(final int seat) {
        this(seat, new java.util.concurrent.ConcurrentHashMap<>());
    }

    SeatWriter(final int seat, final java.util.concurrent.ConcurrentMap<Integer, GameStart> gameStarts) {
        this.seat = seat;
        this.gameStarts = gameStarts;
        thread = new Thread(this::run, "Arena-seat-writer-" + seat);
        thread.setDaemon(true);
        thread.start();
    }

    /** Forge-safe caller supplies only the resolved player id; writer/I/O never reads Forge. */
    void bindPlayer(final int playerId) {
        final JsonObject greeting = new JsonObject();
        greeting.addProperty("type", "hello");
        // Connection preamble precedes cached messages; normal seat sequencing starts at 1.
        greeting.addProperty("seq", 0L);
        greeting.addProperty("protocolVersion", 1);
        greeting.addProperty("serverVersion", "1.4-dev");
        greeting.addProperty("seat", seat);
        greeting.addProperty("playerId", playerId);
        hello = WsServer.GSON.toJson(greeting);
        LockSupport.unpark(thread);
    }

    void bindPlayer(final int playerId, final int gameId) {
        final GameStart start = gameStarts.computeIfAbsent(gameId,
                ignored -> new GameStart(java.time.ZonedDateTime.now(), System.nanoTime()));
        synchronized (queueLock) {
            transcriptWork.add(() -> {
                if (transcriptGame != gameId) {
                    disconnectedAtNanos = connected() ? 0 : System.nanoTime();
                    if (transcript != null) transcript.close();
                    transcriptGame = gameId;
                    transcript = new TranscriptLog(seat, playerId, start.time(), start.nanos());
                }
                bindPlayer(playerId);
            });
        }
        LockSupport.unpark(thread);
    }

    void transcriptInput(JsonObject message, String result, long when) {
        final String json = WsServer.GSON.toJson(message);
        transcriptWork.add(() -> { if (transcript != null) transcript.record("in", json, result, when); });
        LockSupport.unpark(thread);
    }

    void disconnected(Channel disconnected) {
        transcriptWork.add(() -> {
            awaitingJoin.remove(disconnected);
            if (channel == disconnected && !connectionClosed) {
                disconnectedAtNanos = System.nanoTime();
                connectionClosed = true;
                if (transcript != null) transcript.record("conn", "{\"type\":\"closed\"}", null, System.nanoTime());
                synchronized (queueLock) {
                    for (Outbound queued : messages) { rememberLog(queued); rememberPrompt(queued.payload(), queued.json()); }
                    messages.clear();
                }
            }
        });
        LockSupport.unpark(thread);
    }

    void flushTranscript() {
        transcriptWork.add(() -> { if (transcript != null) transcript.flush(); });
        LockSupport.unpark(thread);
    }

    /** Serialize on the calling Forge-safe thread. The short leaf lock never calls Forge or Netty. */
    public long send(final JsonObject message) {
        if ("prompt".equals(message.get("type").getAsString())) {
            LOG.log(System.Logger.Level.INFO, "[prompt] SeatWriter received requestId={0} kind={1} options={2} thread={3}",
                    message.get("requestId"), message.get("kind").getAsString(),
                    message.getAsJsonArray("options").size(), Thread.currentThread().getName());
        }
        final long seq;
        synchronized (queueLock) {
            if (closed) {
                throw new IllegalStateException("Seat writer is closed");
            }
            seq = ++sequence;
            message.addProperty("seq", seq);
            final boolean state = "state".equals(message.get("type").getAsString());
            final long dependency = "event".equals(message.get("type").getAsString())
                    ? message.get("stateSeq").getAsLong() : 0;
            final JsonObject copy = message.deepCopy();
            final Outbound outbound = new Outbound(seq, WsServer.GSON.toJson(copy), state, dependency, copy);
            if (state) {
                pendingState = outbound;
            } else if (channel == null || !channel.isActive()) {
                rememberLog(outbound);
                rememberPrompt(outbound.payload(), outbound.json());
            } else {
                messages.addLast(outbound);
            }
        }
        LockSupport.unpark(thread);
        return seq;
    }

    /** Netty handshake path: lock-free, no waiting. Hello/cache and replacement run on the writer. */
    void connect(final Channel connected) {
        connections.add(connected);
        LockSupport.unpark(thread);
    }

    public String getLastSentState() {
        return lastState;
    }

    void attachRemote(final JsonSeatRemote remote) { this.remote = remote; }

    /** Netty never takes queueLock; sequencing/queue insertion happens on the writer thread. */
    void sendFromIo(final JsonObject message) {
        ioMessages.add(message.deepCopy());
        LockSupport.unpark(thread);
    }

    void promptReply(final JsonObject reply) {
        final JsonSeatRemote receiver = remote;
        long when = System.nanoTime();
        String result = receiver != null ? receiver.promptReply(reply) : null;
        transcriptInput(reply, result, when);
    }

    void action(final JsonObject message) {
        if (message.has("type") && "submitDeck".equals(message.get("type").getAsString())) {
            try { CLIENT_MESSAGES.validate(message); submitDeck(message); }
            catch (IllegalArgumentException ex) {
                JsonObject rejected = new JsonObject(); rejected.addProperty("type", "message");
                rejected.addProperty("level", "rejected"); rejected.addProperty("text", ex.getMessage()); sendFromIo(rejected);
                transcriptInput(message, "rejected", System.nanoTime());
            }
            return;
        }
        final JsonSeatRemote receiver = remote;
        final long when = System.nanoTime();
        if (receiver != null) receiver.action(message, result -> transcriptInput(message, result, when));
        else {
            JsonObject rejected = new JsonObject(); rejected.addProperty("type", "message");
            rejected.addProperty("level", "rejected"); rejected.addProperty("text", "The seat is not ready"); sendFromIo(rejected);
            transcriptInput(message, "rejected", when);
        }
    }

    void resync() { resyncRequested.set(true); LockSupport.unpark(thread); }

    private void replayCached() {
        // Freeze this connection's cutoff and retire its covered queue prefix atomically.
        // Offline logs are still represented in logTail, even if never written individually.
        synchronized (queueLock) {
            if (pendingState != null && pendingState.seq() > lastStateSeq) {
                lastState = pendingState.json(); lastStatePayload = pendingState.payload();
                lastStateSeq = pendingState.seq(); pendingState = null;
            }
            while (!messages.isEmpty() && messages.peekFirst().seq() <= lastStateSeq) {
                Outbound covered = messages.removeFirst();
                rememberLog(covered);
                // sendAndWait registers before enqueueing, but may not yet have saved its
                // envelope. Preserve a covered prompt before dropping its queue copy.
                rememberPrompt(covered.payload(), covered.json());
            }
        }
        JsonObject tail = new JsonObject(); tail.addProperty("type", "logTail"); tail.addProperty("seq", 0L);
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        synchronized (queueLock) {
            logTail.stream().filter(entry -> entry.get("seq").getAsLong() <= lastStateSeq)
                    .forEach(entry -> entries.add(entry.deepCopy()));
        }
        tail.add("entries", entries); write(WsServer.GSON.toJson(tail));
        if (lastState != null) {
            write(lastState); nextStateAt = System.nanoTime() + STATE_INTERVAL;
        }
        java.util.List<JsonObject> newerLogs;
        synchronized (queueLock) {
            newerLogs = logTail.stream().filter(entry -> entry.get("seq").getAsLong() > lastStateSeq)
                    .map(JsonObject::deepCopy).toList();
        }
        JsonSeatRemote receiver = remote;
        if (receiver != null) {
            for (JsonSeatRemote.PromptWire prompt : receiver.pendingPromptWires()) {
                final int id = prompt.requestId();
                synchronized (queueLock) {
                    messages.removeIf(message -> {
                        JsonObject json = message.payload();
                        return "prompt".equals(json.get("type").getAsString()) && json.get("requestId").getAsInt() == id;
                    });
                }
                writeTo(channel, prompt.json(), id, false);
            }
        }
        for (JsonObject log : newerLogs) { log.addProperty("type", "log"); write(WsServer.GSON.toJson(log)); }
        replayPending = false;
        if (receiver != null) { receiver.requestFullResync(); receiver.yieldConnected(); }
    }

    private void rememberLog(Outbound message) {
        synchronized (queueLock) {
            JsonObject sent = message.payload();
            if ("log".equals(sent.get("type").getAsString())) {
                sent = sent.deepCopy();
                sent.remove("type"); logTail.addLast(sent);
                while (logTail.size() > 200) logTail.removeFirst();
            }
        }
    }

    /** Under queueLock: retain live events/logs in FIFO order when a coalesced state overtakes them.
     * Only their envelope seq changes; stateSeq and prompt requestId remain stable. Future states
     * allocate above the entire resequenced queue, so neither snapshots nor action baseSeq change.
     */
    private void sequenceAfter(long stateSeq) {
        if (messages.isEmpty() || messages.peekFirst().seq() > stateSeq) return;
        ArrayDeque<Outbound> ordered = new ArrayDeque<>();
        for (Outbound message : messages) {
            JsonObject json = message.payload();
            long seq = ++sequence; json.addProperty("seq", seq);
            ordered.addLast(new Outbound(seq, WsServer.GSON.toJson(json), false, message.stateSeq(), json));
        }
        messages.clear(); messages.addAll(ordered);
    }

    private void run() {
        while (!closed) {
            Runnable work;
            while ((work = transcriptWork.poll()) != null) work.run();
            if (transcript != null) transcript.flushIfDue();
            if (resyncRequested.getAndSet(false)) replayPending = true;
            JsonObject ioMessage;
            while ((ioMessage = ioMessages.poll()) != null) send(ioMessage);
            Channel connected;
            // Startup connections remain queued until RegisteredPlayer has a real Forge id.
            // No socket output can precede the bound hello; no I/O thread waits for it.
            while (hello != null && (connected = connections.poll()) != null) {
                if (!connected.isActive()) continue;
                awaitingJoin.add(connected);
                writeTo(connected, hello);
            }
            if (replayPending && channel != null && channel.isActive()) {
                if (lastState != null && System.nanoTime() < nextStateAt) {
                    LockSupport.parkNanos(this, nextStateAt - System.nanoTime()); continue;
                }
                replayCached();
            }
            final Outbound next;
            long delay = TimeUnit.SECONDS.toNanos(1);
            final boolean connectedNow = channel != null && channel.isActive();
            synchronized (queueLock) {
                final Outbound first = messages.peekFirst();
                // A newer coalesced snapshot may overtake an event's original envelope.
                // Its dependency and inline stubs stay unchanged; resequence the live queue.
                final Outbound candidate = pendingState != null && (first == null || pendingState.seq() < first.seq()
                        || first.stateSeq() > lastStateSeq)
                        ? pendingState : first;
                if (!transcriptWork.isEmpty()) {
                    delay = 1;
                    next = null;
                } else if (candidate == null || !connectedNow) {
                    next = null;
                } else if (candidate.state() && System.nanoTime() < nextStateAt) {
                    delay = nextStateAt - System.nanoTime();
                    next = null;
                } else {
                    next = candidate;
                    if (next.state()) {
                        pendingState = null;
                        sequenceAfter(next.seq());
                    } else {
                        messages.removeFirst();
                    }
                }
            }
            if (next == null) {
                LockSupport.parkNanos(this, Math.max(1, delay));
            } else {
                if (next.state()) {
                    lastState = next.json();
                    lastStatePayload = next.payload();
                    lastStateSeq = next.seq();
                    nextStateAt = System.nanoTime() + STATE_INTERVAL;
                } else {
                    rememberLog(next);
                }
                JsonObject payload = next.payload();
                Integer requestId = "prompt".equals(payload.get("type").getAsString()) ? payload.get("requestId").getAsInt() : null;
                writeTo(channel, next.json(), requestId, payload.has("kind") && "gameOver".equals(payload.get("kind").getAsString()));
            }
        }
        Runnable work;
        while ((work = transcriptWork.poll()) != null) work.run();
        if (transcript != null) {
            if (channel != null && !connectionClosed) {
                transcript.record("conn", "{\"type\":\"closed\"}", null, System.nanoTime());
            }
            transcript.close();
        }
    }

    private void bindConnection(Channel connected) {
        final boolean replacement = channel != null;
        if (replacement && !connectionClosed && !channel.isActive()) {
            connectionClosed = true;
            if (transcript != null) transcript.record("conn", "{\"type\":\"closed\"}", null, System.nanoTime());
        }
        final boolean liveReplacement = replacement && !connectionClosed;
        if (channel != null && channel != connected) {
            channel.writeAndFlush(new CloseWebSocketFrame(1000, "Replaced by a new connection for this seat"))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        channel = connected;
        disconnectedAtNanos = 0;
        connectionClosed = false;
        if (transcript != null) transcript.record("conn", liveReplacement
                ? "{\"type\":\"replaced\"}" : "{\"type\":\"connected\"}", null, System.nanoTime());
        if (replacement) {
            if (remote != null) remote.clearReveals();
            // Cached replay must not briefly reopen the previous connection's reveal.
            if (lastState != null) {
                JsonObject cached = lastStatePayload.deepCopy();
                cached.remove("revealed"); lastStatePayload = cached; lastState = WsServer.GSON.toJson(cached);
            }
        }
    }

    private void write(final String json) { writeTo(channel, json); }

    private void writeTo(Channel target, final String json) {
        writeTo(target, json, null, false);
    }

    private void writeTo(Channel target, final String json, Integer requestId, boolean gameOver) {
        if (target != null && target.isActive()) {
            if (remote != null && requestId != null) remote.promptDelivered(requestId, json);
            target.writeAndFlush(new TextWebSocketFrame(json));
            if (transcript != null) transcript.record("out", json, null, System.nanoTime());
            if (transcript != null && gameOver) transcript.flush();
        }
    }

    private void rememberPrompt(JsonObject message, String json) {
        if (remote == null) return;
        if ("prompt".equals(message.get("type").getAsString())) {
            remote.promptDelivered(message.get("requestId").getAsInt(), json);
        }
    }

    /** Lifecycle thread only, after Forge has stopped producing messages. */
    @Override
    public void close() {
        closed = true;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (final InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
