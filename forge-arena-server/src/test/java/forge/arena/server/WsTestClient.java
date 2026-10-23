package forge.arena.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

final class WsTestClient implements WebSocket.Listener, AutoCloseable {
    record Received(String json, long at) {
        JsonObject object() { return JsonParser.parseString(json).getAsJsonObject(); }
    }

    private final BlockingQueue<Received> messages = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Received> history = new ConcurrentLinkedQueue<>();
    private final StringBuilder text = new StringBuilder();
    final CountDownLatch closed = new CountDownLatch(1);
    volatile String closeReason;
    volatile int closeCode;
    volatile Throwable error;
    volatile long openedAt;
    private WebSocket socket;
    volatile java.util.function.Consumer<JsonObject> onMessage;

    void connect(final HttpClient http, final int port, final String path) throws Exception {
        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + path), this).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(final WebSocket webSocket) {
        openedAt = System.nanoTime();
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
        text.append(data);
        if (last) {
            final Received received = new Received(text.toString(), System.nanoTime());
            history.add(received);
            messages.add(received);
            if (onMessage != null) {
                try { onMessage.accept(received.object()); }
                catch (Throwable ex) { error = ex; }
            }
            text.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(final WebSocket webSocket, final int code, final String reason) {
        closeCode = code;
        closeReason = reason;
        closed.countDown();
        return null;
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable cause) {
        error = cause;
    }

    JsonObject receive() throws InterruptedException {
        final Received received = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Expected message; client error: " + error);
        return received.object();
    }

    void hello(final int seat) throws InterruptedException {
        final JsonObject hello = receive();
        assertEquals(hello.get("type").getAsString(), "hello");
        assertEquals(hello.get("protocolVersion").getAsInt(), 1);
        assertEquals(hello.get("serverVersion").getAsString(), "1.4-dev");
        assertEquals(hello.get("seat").getAsInt(), seat);
    }

    List<Received> history() {
        return List.copyOf(history);
    }

    void send(final JsonObject message) {
        socket.sendText(WsServer.GSON.toJson(message), true)
                .whenComplete((ignored, ex) -> { if (ex != null) error = ex; });
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.abort();
        }
    }
}
