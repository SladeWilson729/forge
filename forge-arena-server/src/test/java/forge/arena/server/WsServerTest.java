package forge.arena.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class WsServerTest {
    private static final class Client implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Throwable error;

        @Override
        public void onOpen(final WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(final WebSocket socket, final CharSequence data, final boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket socket, final int statusCode, final String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(final WebSocket socket, final Throwable cause) {
            error = cause;
        }

        private String receive() throws InterruptedException {
            final String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Expected WebSocket message; client error: " + error);
            return message;
        }
    }

    private static void assertHello(final String json) {
        final JsonObject hello = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(hello.get("type").getAsString(), "hello");
        assertEquals(hello.get("protocolVersion").getAsInt(), 1);
        assertEquals(hello.get("serverVersion").getAsString(), "0.8-dev");
        assertEquals(hello.get("seat").getAsInt(), 0);
    }

    @Test(timeOut = 30_000)
    public void sendsHelloBroadcastAndCachedState() throws Exception {
        final WsServer server = new WsServer("127.0.0.1", 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        WebSocket first = null;
        WebSocket second = null;
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    server.start();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(ex);
                }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(server.getPort() > 0, "Port 0 was assigned a bound port");
            final URI uri = URI.create("ws://127.0.0.1:" + server.getPort() + "/ws");
            final HttpClient http = HttpClient.newBuilder().executor(executor)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            final Client firstClient = new Client();
            first = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, firstClient).get(5, TimeUnit.SECONDS);
            assertHello(firstClient.receive());

            final String state = WsServer.GSON.toJson(Map.of("type", "state", "seq", 1, "seat", 0,
                    "turn", 1, "hand", List.of(Map.of("name", "Urza's Tower"))));
            assertTrue(state.contains("Urza's Tower"), "Apostrophes remain readable");
            server.broadcastState(state);
            assertEquals(firstClient.receive(), state);

            final Client secondClient = new Client();
            second = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, secondClient).get(5, TimeUnit.SECONDS);
            assertHello(secondClient.receive());
            assertEquals(secondClient.receive(), state, "Late connection receives the cached state after hello");

            first.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            second.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            assertTrue(firstClient.closed.await(5, TimeUnit.SECONDS), "First client closed");
            assertTrue(secondClient.closed.await(5, TimeUnit.SECONDS), "Second client closed");
        } finally {
            if (first != null) {
                first.abort();
            }
            if (second != null) {
                second.abort();
            }
            executor.shutdownNow();
            try {
                CompletableFuture.runAsync(server::close).get(5, TimeUnit.SECONDS);
            } finally {
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Client executor stopped");
            }
        }
    }
}
