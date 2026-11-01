package forge.arena.server;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import static org.testng.Assert.*;

public class BandwidthMeterTest {
    @Test(timeOut = 15_000) public void disabledMeterHasNoAccounting() throws Exception {
        String previous = System.getProperty("arena.meter"); System.clearProperty("arena.meter");
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.start(); server.writer(0).bindPlayer(10, 15151);
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            assertNull(server.writer(0).meterSnapshot().get(5, TimeUnit.SECONDS));
        } finally { if (previous == null) System.clearProperty("arena.meter"); else System.setProperty("arena.meter", previous); }
    }
    @Test public void fixedBucketsIncludeIdleTimeAndStateSizes() {
        var report = new BandwidthMeter.Report(ZonedDateTime.now(), 0);
        var row = report.seats[2];
        row.wrote(100, true, 10); row.wrote(250, false, 999_999_999L); row.wrote(80, true, 1_000_000_000L);
        var value = row.snapshot(2_000_000_000L);
        assertEquals(value.total(), 430L); assertEquals(value.peak(), 350L); assertEquals(value.average(), 215.0);
        assertEquals(value.states(), 2L); assertEquals(value.stateBytes(), 180L);
        row.wrote(50, false, 500_000_000L);
        assertEquals(row.snapshot(2_000_000_000L).peak(), 400L, "Late completion belongs to its original bucket");
    }
    @Test(timeOut = 30_000) public void countsOnlyFramesReceivedIncludingReplaysAndUnicode() throws Exception {
        String previous = System.getProperty("arena.meter"); System.setProperty("arena.meter", "true");
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient(); WsTestClient replacement = new WsTestClient()) {
            server.start();
            for (int seat = 0; seat < 4; seat++) server.writer(seat).bindPlayer(10 + seat, 15150);
            for (int index = 0; index < 10; index++) {
                JsonObject state = new JsonObject(); state.addProperty("type", "state"); state.addProperty("index", index);
                server.writer(0).send(state);
            }
            HttpClient http = HttpClient.newHttpClient(); client.connect(http, server.getPort(), "/ws?seat=0"); client.hello(0);
            assertEquals(client.receive().get("index").getAsInt(), 9);
            JsonObject message = new JsonObject(); message.addProperty("type", "message"); message.addProperty("text", "Éowyn 🧙 — It's alive");
            server.writer(0).send(message); assertEquals(client.receive(), message);
            replacement.connect(http, server.getPort(), "/ws?seat=0"); replacement.hello(0);
            assertEquals(replacement.receive().get("index").getAsInt(), 9);
            assertTrue(client.closed.await(5, TimeUnit.SECONDS));
            JsonObject finalState = new JsonObject(); finalState.addProperty("type", "state"); finalState.addProperty("index", 10);
            server.writer(0).send(finalState);
            server.finishMeters(); // Finalization must wait for the pending, rate-limited frame.
            assertEquals(replacement.receive(), finalState);
            // Close payload = two status-code bytes and the UTF-8 reason; text includes preambles/replay.
            long received = bytes(client) + bytes(replacement) + 2 + client.closeReason.getBytes(StandardCharsets.UTF_8).length;
            BandwidthMeter.Snapshot measured = server.writer(0).meterSnapshot().get(5, TimeUnit.SECONDS);
            assertEquals(measured.total(), received); assertEquals(measured.states(), 3L);
            long states = java.util.stream.Stream.concat(client.history().stream(), replacement.history().stream())
                    .filter(w -> w.object().get("type").getAsString().equals("state"))
                    .mapToLong(w -> w.json().getBytes(StandardCharsets.UTF_8).length).sum();
            assertEquals(measured.stateBytes(), states);
            server.close();
            Path file = Path.of("target/perf/1.15b-" + server.writer(0).meterRow().report.started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            String report = Files.readString(file);
            assertTrue(report.contains("| " + received + " | 3 |"));
            assertTrue(report.contains("250,000")); assertTrue(report.contains("1,000,000"));
            System.out.println("[meter-test] received=" + received + " metered=" + measured.total() + " states=" + measured.states());
        } finally { if (previous == null) System.clearProperty("arena.meter"); else System.setProperty("arena.meter", previous); }
    }
    private static long bytes(WsTestClient client) {
        return client.history().stream().mapToLong(w -> w.json().getBytes(StandardCharsets.UTF_8).length).sum();
    }
}
