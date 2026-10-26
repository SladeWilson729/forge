package forge.arena.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** One daemon per seat; no Forge objects or mutable JSON cross to this thread. */
public final class SeatWriter implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(SeatWriter.class.getName());
    private record Outbound(long seq, String json, boolean state) { }

    // Slight headroom below 10/s also avoids a local client's timer jitter counting 11 arrivals.
    private static final long STATE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(125);
    private final Object queueLock = new Object();
    private final ArrayDeque<Outbound> messages = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Channel> connections = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> ioMessages = new ConcurrentLinkedQueue<>();
    private volatile JsonSeatRemote remote;
    private final Thread thread;
    private final int seat;
    private volatile String hello;
    private Outbound pendingState;
    private long sequence;
    private volatile boolean closed;
    private volatile String lastState;
    private final java.util.concurrent.atomic.AtomicBoolean resyncRequested = new java.util.concurrent.atomic.AtomicBoolean();
    // Only the writer thread accesses the channel and send timing.
    private Channel channel;
    private long nextStateAt;

    public SeatWriter(final int seat) {
        this.seat = seat;
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
            final Outbound outbound = new Outbound(seq, WsServer.GSON.toJson(message), state);
            if (state) {
                pendingState = outbound;
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
        ioMessages.add(WsServer.GSON.toJson(message));
        LockSupport.unpark(thread);
    }

    void promptReply(final JsonObject reply) {
        final JsonSeatRemote receiver = remote;
        if (receiver != null) receiver.promptReply(reply);
    }

    void action(final JsonObject message) {
        final JsonSeatRemote receiver = remote;
        if (receiver != null) receiver.action(message);
        else {
            JsonObject rejected = new JsonObject(); rejected.addProperty("type", "message");
            rejected.addProperty("level", "rejected"); rejected.addProperty("text", "The seat is not ready"); sendFromIo(rejected);
        }
    }

    void resync() { resyncRequested.set(true); LockSupport.unpark(thread); }

    private void replayCached() {
        if (lastState != null) write(lastState);
        JsonSeatRemote receiver = remote;
        if (receiver != null) receiver.pendingPromptMessages().forEach(this::write);
    }

    private void run() {
        while (!closed) {
            if (resyncRequested.getAndSet(false)) replayCached();
            String ioMessage;
            while ((ioMessage = ioMessages.poll()) != null) send(JsonParser.parseString(ioMessage).getAsJsonObject());
            Channel connected;
            // Startup connections remain queued until RegisteredPlayer has a real Forge id.
            // No socket output can precede the bound hello; no I/O thread waits for it.
            while (hello != null && (connected = connections.poll()) != null) {
                if (channel != null && channel != connected) {
                    channel.writeAndFlush(new CloseWebSocketFrame(1000, "Replaced by a new connection for this seat"))
                            .addListener(ChannelFutureListener.CLOSE);
                }
                channel = connected;
                write(hello);
                if (lastState != null) {
                    write(lastState);
                    nextStateAt = System.nanoTime() + STATE_INTERVAL;
                }
                final JsonSeatRemote receiver = remote;
                if (receiver != null) {
                    for (String prompt : receiver.pendingPromptMessages()) {
                        final int id = JsonParser.parseString(prompt).getAsJsonObject().get("requestId").getAsInt();
                        synchronized (queueLock) {
                            // A prompt queued during disconnect is replayed here exactly once.
                            messages.removeIf(message -> {
                                final JsonObject json = JsonParser.parseString(message.json()).getAsJsonObject();
                                return "prompt".equals(json.get("type").getAsString()) && json.get("requestId").getAsInt() == id;
                            });
                        }
                        write(prompt);
                    }
                }
            }
            final Outbound next;
            long delay = TimeUnit.SECONDS.toNanos(1);
            final boolean connectedNow = channel != null && channel.isActive();
            synchronized (queueLock) {
                final Outbound first = messages.peekFirst();
                final Outbound candidate = pendingState != null && (first == null || pendingState.seq() < first.seq())
                        ? pendingState : first;
                if (candidate == null) {
                    next = null;
                } else if (candidate.state() && System.nanoTime() < nextStateAt) {
                    delay = nextStateAt - System.nanoTime();
                    next = null;
                } else if (!candidate.state() && !connectedNow) {
                    // Keep non-state messages while disconnected. Do not advance the cache past
                    // them: reconnect's hello/cache must still precede these accepted sequences.
                    next = null;
                } else {
                    next = candidate;
                    if (next.state()) {
                        pendingState = null;
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
                    nextStateAt = System.nanoTime() + STATE_INTERVAL;
                }
                write(next.json());
            }
        }
    }

    private void write(final String json) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
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
