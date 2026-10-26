package forge.arena.server;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class WsServerTest {
    private static JsonObject state(final String marker) {
        // Transport fixture only; complete live states are schema-validated in SeatStreamTest.
        final JsonObject state = new JsonObject();
        state.addProperty("type", "state");
        state.addProperty("marker", marker);
        return state;
    }

    @Test(timeOut = 30_000)
    public void sendsHelloSeatStateAndCachedStateOnReplacement() throws Exception {
        final WsServer server = new WsServer("127.0.0.1", 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        try (WsTestClient first = new WsTestClient(); WsTestClient second = new WsTestClient();
             WsTestClient otherSeat = new WsTestClient()) {
            server.start();
            assertTrue(server.getPort() > 0);
            final HttpClient http = HttpClient.newBuilder().executor(executor)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            first.connect(http, server.getPort(), "/ws?seat=0");
            otherSeat.connect(http, server.getPort(), "/ws?seat=2");
            final var premature = new java.util.concurrent.CountDownLatch(1);
            first.onMessage = ignored -> premature.countDown();
            assertFalse(premature.await(200, TimeUnit.MILLISECONDS), "No hello before the player is bound");
            assertTrue(first.history().isEmpty());
            // Model human-first sorting: player ids are intentionally not wire seat numbers.
            server.writer(0).bindPlayer(2);
            server.writer(2).bindPlayer(0);
            assertEquals(first.hello(0).get("playerId").getAsInt(), 2);
            assertEquals(otherSeat.hello(2).get("playerId").getAsInt(), 0);
            final JsonObject zero = state("Urza's Tower");
            final JsonObject two = state("Seat two only");
            server.writer(0).send(zero);
            server.writer(2).send(two);
            assertTrue(WsServer.GSON.toJson(zero).contains("Urza's Tower"));
            assertEquals(first.receive(), zero);
            assertEquals(otherSeat.receive(), two);
            second.connect(http, server.getPort(), "/ws?seat=0");
            assertEquals(second.hello(0).get("playerId").getAsInt(), 2);
            assertEquals(second.receive(), zero);
            assertTrue(first.closed.await(5, TimeUnit.SECONDS));
            assertEquals(first.closeCode, 1000);
            assertTrue(first.closeReason.contains("Replaced"));
            final JsonObject next = state("Updated seat zero");
            server.writer(0).send(next);
            assertEquals(second.receive(), next);
            assertEquals(otherSeat.history().size(), 2, "No seat-zero state reached seat two");
        } finally {
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeOut = 30_000)
    public void rejectsMissingMalformedAndDuplicateSeats() throws Exception {
        final WsServer server = new WsServer("127.0.0.1", 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        try {
            server.start();
            final HttpClient http = HttpClient.newBuilder().executor(executor)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            for (final String path : new String[] {"/ws", "/ws?seat=", "/ws?seat=-1", "/ws?seat=4",
                    "/ws?seat=no", "/ws?seat=0&seat=2", "/ws/extra?seat=0"}) {
                try (WsTestClient client = new WsTestClient()) {
                    client.connect(http, server.getPort(), path);
                    assertTrue(client.closed.await(5, TimeUnit.SECONDS), path);
                    assertEquals(client.closeCode, 1008);
                    assertTrue(client.closeReason.contains("seat"));
                    assertTrue(client.history().isEmpty());
                }
            }
        } finally {
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeOut = 30_000)
    public void coalescingPreservesAcceptedMessageOrderAcrossConnection() throws Exception {
        final WsServer server = new WsServer("127.0.0.1", 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        try (WsTestClient client = new WsTestClient()) {
            server.writer(3).bindPlayer(1);
            server.start();
            // Queue non-state messages while offline. Coalescing must not lose or reorder them.
            for (int i = 0; i < 25; i++) {
                final JsonObject message = new JsonObject();
                message.addProperty("type", "message");
                message.addProperty("level", "info");
                message.addProperty("text", "Message " + i);
                server.writer(3).send(message);
                server.writer(3).send(state("State " + i));
            }
            final HttpClient http = HttpClient.newBuilder().executor(executor)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            client.connect(http, server.getPort(), "/ws?seat=3");
            client.hello(3);
            long previous = 0;
            for (int i = 0; i < 25; i++) {
                final JsonObject message = client.receive();
                assertEquals(message.get("text").getAsString(), "Message " + i);
                assertTrue(message.get("seq").getAsLong() > previous);
                previous = message.get("seq").getAsLong();
            }
            final JsonObject last = client.receive();
            assertEquals(last.get("marker").getAsString(), "State 24");
            assertTrue(last.get("seq").getAsLong() > previous);
            assertEquals(client.history().size(), 27, "Hello, all messages, and only the latest state");
        } finally {
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
