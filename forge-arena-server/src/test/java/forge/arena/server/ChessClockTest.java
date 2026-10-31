package forge.arena.server;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.*;

public class ChessClockTest {
    private static final long BUDGET = TimeUnit.MINUTES.toMillis(30);
    static JsonObject row(JsonObject clock, int seat) { return clock.getAsJsonArray("seats").get(seat).getAsJsonObject(); }
    static long ms(JsonObject clock, int seat) { return row(clock, seat).get("remainingMs").getAsLong(); }
    static JsonObject pulse(ChessClock clock) throws Exception {
        JsonObject value = clock.tick().get(5, TimeUnit.SECONDS); PromptAssertions.validate(value, "clock"); return value;
    }
    private static void setup(ChessClock clock) {
        for (int seat = 0; seat < 4; seat++) { clock.player(seat, 1, "Clock " + seat, false, false); clock.connected(seat, true); }
    }
    private static long warnings(List<JsonObject> messages) {
        return messages.stream().filter(message -> message.get("type").getAsString().equals("message")).count();
    }

    @Test public void decisionsAreExclusiveIdempotentAndAutoPassIsFree() throws Exception {
        AtomicLong time = new AtomicLong(); List<JsonObject> messages = new CopyOnWriteArrayList<>();
        try (ChessClock clock = new ChessClock(30, time::get, messages::add, false)) {
            setup(clock); clock.input(0, true); pulse(clock);
            int count = messages.size(); clock.input(0, true); pulse(clock);
            assertEquals(messages.size(), count + 1, "Duplicate input adds only the explicit scheduler pulse");
            time.addAndGet(TimeUnit.SECONDS.toNanos(4)); JsonObject first = pulse(clock);
            assertEquals(ms(first, 0), BUDGET - 4000); assertEquals(ms(first, 1), BUDGET);
            clock.prompt(1, 10, true); JsonObject nested = pulse(clock);
            assertFalse(row(nested, 0).get("running").getAsBoolean()); assertTrue(row(nested, 1).get("running").getAsBoolean());
            time.addAndGet(TimeUnit.SECONDS.toNanos(3)); assertEquals(ms(pulse(clock), 1), BUDGET - 3000);
            clock.connected(1, false); JsonObject paused = pulse(clock);
            time.addAndGet(TimeUnit.SECONDS.toNanos(20)); JsonObject disconnected = pulse(clock);
            assertEquals(ms(disconnected, 0), ms(paused, 0), "A disconnected decision never starts a stale earlier input");
            assertEquals(ms(disconnected, 1), ms(paused, 1));
            clock.connected(1, true); clock.prompt(1, 10, false); clock.input(0, false); pulse(clock);
            long[] before = {ms(clock.snapshot(), 0), ms(clock.snapshot(), 1)};
            // An auto-passed priority supplies waiting only, with no displayed decision.
            clock.input(0, false); clock.input(1, false); time.addAndGet(TimeUnit.SECONDS.toNanos(50));
            JsonObject automatic = pulse(clock); assertEquals(ms(automatic, 0), before[0]); assertEquals(ms(automatic, 1), before[1]);
            assertEquals(automatic.getAsJsonArray("seats").asList().stream().filter(s -> s.getAsJsonObject().get("running").getAsBoolean()).count(), 0L);
        }
    }

    @Test public void promptsPauseReconnectExpireOnceAndKeepUntimed() throws Exception {
        AtomicLong time = new AtomicLong(); List<JsonObject> messages = new CopyOnWriteArrayList<>();
        try (ChessClock clock = new ChessClock(30, time::get, messages::add, false)) {
            setup(clock); clock.prompt(0, 42, true); pulse(clock);
            time.addAndGet(TimeUnit.SECONDS.toNanos(5)); assertEquals(ms(pulse(clock), 0), BUDGET - 5000);
            clock.connected(0, false); pulse(clock); time.addAndGet(TimeUnit.MINUTES.toNanos(10));
            assertEquals(ms(pulse(clock), 0), BUDGET - 5000);
            clock.connected(0, true); clock.prompt(0, 42, false); JsonObject answered = pulse(clock);
            assertFalse(row(answered, 0).get("running").getAsBoolean());
            time.addAndGet(TimeUnit.SECONDS.toNanos(6)); assertEquals(ms(pulse(clock), 0), BUDGET - 5000);
            clock.prompt(0, 43, true); pulse(clock); time.addAndGet(TimeUnit.MINUTES.toNanos(30));
            assertEquals(ms(pulse(clock), 0), 0L); assertTrue(clock.expired(0)); assertEquals(warnings(messages), 1L);
            pulse(clock); assertEquals(warnings(messages), 1L);
            assertTrue(clock.keep(0).get(5, TimeUnit.SECONDS)); clock.prompt(0, 43, false); clock.input(0, true);
            time.addAndGet(TimeUnit.HOURS.toNanos(1)); JsonObject kept = pulse(clock);
            assertEquals(ms(kept, 0), 0L); assertFalse(row(kept, 0).get("running").getAsBoolean()); assertEquals(warnings(messages), 1L);
            assertEquals(messages.stream().filter(m -> m.has("level")).findFirst().orElseThrow().get("text").getAsString(),
                    "Clock 0 is out of time; waiting for the host");
        }
    }

    @Test public void terminalSeatsAndGameEndNeverRestartAndANewGameResets() throws Exception {
        AtomicLong time = new AtomicLong(); List<JsonObject> messages = new CopyOnWriteArrayList<>();
        try (ChessClock clock = new ChessClock(45, time::get, messages::add, false)) {
            setup(clock); clock.input(0, true); pulse(clock); time.addAndGet(TimeUnit.SECONDS.toNanos(1));
            clock.stop(0); JsonObject stopped = pulse(clock); clock.input(0, true); clock.prompt(0, 5, true); clock.connected(0, true);
            time.addAndGet(TimeUnit.SECONDS.toNanos(8)); assertEquals(ms(pulse(clock), 0), ms(stopped, 0));
            clock.input(1, true); pulse(clock); clock.player(1, 1, "Clock 1", true, false); JsonObject eliminated = pulse(clock);
            clock.input(1, true); time.addAndGet(TimeUnit.SECONDS.toNanos(9)); assertEquals(ms(pulse(clock), 1), ms(eliminated, 1));
            clock.input(2, true); pulse(clock); clock.player(2, 1, "Clock 2", false, true); JsonObject ended = pulse(clock);
            time.addAndGet(TimeUnit.MINUTES.toNanos(50)); assertEquals(ms(pulse(clock), 2), ms(ended, 2)); assertEquals(warnings(messages), 0L);
            clock.player(0, 2, "New game", false, false); assertEquals(ms(pulse(clock), 0), TimeUnit.MINUTES.toMillis(45));
        }
    }

    @Test public void untimedIsSilentAndInvalidMinutesFailStartup() throws Exception {
        List<JsonObject> messages = new CopyOnWriteArrayList<>();
        try (ChessClock clock = new ChessClock(0, () -> 0L, messages::add, false)) {
            setup(clock); clock.input(0, true); clock.prompt(1, 3, true); clock.stop(2); pulse(clock); assertTrue(messages.isEmpty());
        }
        for (String value : List.of("-1", "1", "20", "31", "60", "bad", "30.0", ""))
            assertThrows(IllegalArgumentException.class, () -> ChessClock.minutes(value));
        assertEquals(ChessClock.minutes("0"), 0); assertEquals(ChessClock.minutes("30"), 30); assertEquals(ChessClock.minutes("45"), 45);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), "-Darena.clockMinutes=31", ArenaServerMain.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Invalid startup exits without booting Forge");
            assertEquals(child.exitValue(), 1);
            assertTrue(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("arena.clockMinutes must be 0 (untimed), 30, or 45; got '31'"));
        } finally { child.destroyForcibly(); }
    }
}
