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

/** One daemon per seat; no Forge objects or mutable JSON cross to this thread. */
public final class SeatWriter implements AutoCloseable {
    private record Outbound(long seq, String json, boolean state) { }

    // Slight headroom below 10/s also avoids a local client's timer jitter counting 11 arrivals.
    private static final long STATE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(125);
    private final Object queueLock = new Object();
    private final ArrayDeque<Outbound> messages = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Channel> connections = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private final String hello;
    private Outbound pendingState;
    private long sequence;
    private volatile boolean closed;
    private volatile String lastState;
    // Only the writer thread accesses the channel and send timing.
    private Channel channel;
    private long nextStateAt;

    public SeatWriter(final int seat) {
        final JsonObject greeting = new JsonObject();
        greeting.addProperty("type", "hello");
        greeting.addProperty("protocolVersion", 1);
        greeting.addProperty("serverVersion", "1.4-dev");
        greeting.addProperty("seat", seat);
        hello = WsServer.GSON.toJson(greeting);
        thread = new Thread(this::run, "Arena-seat-writer-" + seat);
        thread.setDaemon(true);
        thread.start();
    }

    /** Serialize on the calling Forge-safe thread. The short leaf lock never calls Forge or Netty. */
    public long send(final JsonObject message) {
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

    private void run() {
        while (!closed) {
            Channel connected;
            while ((connected = connections.poll()) != null) {
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
