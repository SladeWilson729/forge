package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventSpellResolved;
import forge.game.GameEndReason;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.*;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.annotations.*;

import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

import static org.testng.Assert.*;
import static forge.arena.server.ChessClockTest.*;

/** Real sockets/Inputs, but all elapsed clock time advances explicitly; no timer sleeps. */
public class ChessClockRoundTripTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }
    private static final long BUDGET = TimeUnit.MINUTES.toMillis(30);
    private static JsonPrimitive str(String value) { return new JsonPrimitive(value); }
    private static JsonPrimitive num(int value) { return new JsonPrimitive(value); }
    private static JsonPrimitive bool(boolean value) { return new JsonPrimitive(value); }
    private static JsonObject receive(WsTestClient client, Predicate<JsonObject> match) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            JsonObject message = client.receive();
            if (message.get("type").getAsString().equals("clock")) PromptAssertions.validate(message, "clock");
            if (match.test(message)) return message;
        }
        throw new AssertionError("Expected clock/prompt message");
    }
    private static JsonObject clock(WsTestClient client, Predicate<JsonObject> match) throws Exception {
        return receive(client, m -> m.get("type").getAsString().equals("clock") && match.test(m));
    }
    private static JsonObject prompt(WsTestClient client, String kind) throws Exception {
        return receive(client, m -> m.get("type").getAsString().equals("prompt") && m.get("kind").getAsString().equals(kind));
    }
    private static void validateClocks(WsTestClient... clients) {
        for (WsTestClient client : clients) for (var wire : client.history()) {
            JsonObject message = wire.object();
            if (!message.get("type").getAsString().equals("clock")) continue;
            PromptAssertions.validate(message, "clock");
            assertTrue(message.getAsJsonArray("seats").asList().stream().filter(s -> s.getAsJsonObject().get("running").getAsBoolean()).count() <= 1);
        }
    }

    @Test(timeOut = 120_000)
    public void twoHumanSeatsPauseRejoinAndAutoPassedPriorityCostsNothing() throws Exception {
        AtomicLong time = new AtomicLong();
        String previous = FModel.getPreferences().getPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS);
        FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, true, new WsServer("127.0.0.1", 0, 30, time::get, false));
             WsTestClient replacement = new WsTestClient()) {
            h.scenarioLines = lines -> lines.add("p1battlefield=Mountain");
            h.start("Selesnya Guildmage;Forest;Forest;Forest;Forest", "Forest");
            ChessClock timer = h.server.clock(); JsonObject first = pulse(timer);
            assertTrue(row(first, 0).get("running").getAsBoolean()); assertFalse(row(first, 1).get("running").getAsBoolean());
            time.addAndGet(TimeUnit.SECONDS.toNanos(7)); assertEquals(ms(pulse(timer), 0), BUDGET - 7000);
            clock(h.secondaryClient, m -> ms(m, 0) == BUDGET - 7000);
            h.client.close();
            JsonObject paused = clock(h.secondaryClient, m -> !row(m, 0).get("running").getAsBoolean() && ms(m, 0) == BUDGET - 7000);
            time.addAndGet(TimeUnit.MINUTES.toNanos(10)); assertEquals(ms(pulse(timer), 0), ms(paused, 0));
            h.useClient(replacement); replacement.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=0"); replacement.hello(0);
            clock(replacement, m -> row(m, 0).get("running").getAsBoolean());
            time.addAndGet(TimeUnit.SECONDS.toNanos(4)); assertEquals(ms(pulse(timer), 0), BUDGET - 11000);
            AtomicInteger autoPasses = new AtomicInteger();
            CountDownLatch autoPassObserved = new CountDownLatch(1);
            h.secondaryAutoPassHook = () -> {
                try {
                    JsonObject before = pulse(timer);
                    assertFalse(row(before, 1).get("running").getAsBoolean(), "Auto-pass never displays a decision");
                    time.addAndGet(TimeUnit.SECONDS.toNanos(5));
                    assertEquals(ms(pulse(timer), 1), ms(before, 1)); autoPasses.incrementAndGet(); autoPassObserved.countDown();
                } catch (Exception ex) { throw new AssertionError(ex); }
            };
            h.sendAction("setYield", "kind", str("untilEndOfTurn"), "active", bool(true));
            assertTrue(autoPassObserved.await(5, TimeUnit.SECONDS), "Observed a real auto-passed priority window");
            assertTrue(autoPasses.get() > 0); assertEquals(ms(pulse(timer), 1), BUDGET);
            assertEquals(ms(timer.snapshot(), 2), BUDGET); assertEquals(ms(timer.snapshot(), 3), BUDGET);
            validateClocks(h.client, h.secondaryClient, replacement); h.healthy();
        } finally { FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, previous); }
    }

    public static final class HoldResolution {
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        @Subscribe public void resolved(GameEventSpellResolved event) {
            if (!event.spell().getHostCard().getName().equals("Ponder")) return;
            reached.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        }
    }
    @Test(timeOut = 120_000)
    public void aResolutionPromptRunsAndItsReplyStopsTheClock() throws Exception {
        AtomicLong time = new AtomicLong();
        try (PromptGameHarness h = new PromptGameHarness(true, 0, -1, false, new WsServer("127.0.0.1", 0, 30, time::get, false))) {
            h.start("Island", "Ponder"); HoldResolution hold = new HoldResolution();
            h.action(() -> h.game.subscribeToEvents(hold));
            try {
                h.answer = p -> List.of("order", "confirm").contains(p.get("kind").getAsString()) ? null : p.get("default");
                int spell = PromptGameHarness.edt(() -> h.card("Ponder", ZoneType.Hand).getId());
                h.sendAction("selectCard", "cardId", num(spell)); h.waitInput(InputPayMana.class); h.waitClientKind("payMana");
                int island = PromptGameHarness.edt(() -> h.card("Island", ZoneType.Battlefield).getId());
                h.sendAction("selectCard", "cardId", num(island)); h.waitClientKind("priority");
                h.action(() -> h.blockingConfirm = true); h.sendAction("ok");
                JsonObject order = prompt(h.client, "order"); assertEquals(h.remote.pendingPromptCount(), 1);
                assertTrue(row(pulse(h.server.clock()), 0).get("running").getAsBoolean());
                time.addAndGet(TimeUnit.SECONDS.toNanos(3)); assertEquals(ms(pulse(h.server.clock()), 0), BUDGET - 3000);
                h.client.send(PromptAssertions.reply(order, order.get("default")));
                JsonObject confirm = prompt(h.client, "confirm");
                time.addAndGet(TimeUnit.SECONDS.toNanos(2)); assertEquals(ms(pulse(h.server.clock()), 0), BUDGET - 5000);
                h.client.send(PromptAssertions.reply(confirm, new JsonPrimitive(false)));
                assertTrue(hold.reached.await(5, TimeUnit.SECONDS));
                JsonObject answered = pulse(h.server.clock()); assertFalse(row(answered, 0).get("running").getAsBoolean());
                time.addAndGet(TimeUnit.SECONDS.toNanos(20)); assertEquals(ms(pulse(h.server.clock()), 0), BUDGET - 5000);
                assertEquals(h.remote.pendingPromptCount(), 0); validateClocks(h.client);
            } finally { hold.release.countDown(); h.answer = p -> p.get("default"); }
        }
    }

    public static final class Progress {
        final PromptGameHarness harness; final CountDownLatch continued = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        Progress(PromptGameHarness h) { harness = h; }
        @Subscribe public void priority(GameEventPlayerPriority event) {
            if (harness.human.getPlayer().conceded() && event.priority().getId() != harness.human.getPlayer().getId()) {
                continued.countDown();
                try { assertTrue(finish.await(10, TimeUnit.SECONDS), "Release the continued game's priority boundary"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                // End on the main game thread after the test verifies continuation.
                // A separate cleanup worker can race this loop while it installs Input.
                for (var player : new java.util.ArrayList<>(harness.game.getPlayers()))
                    if (player != harness.secondaryHuman.getPlayer()) player.concede();
                harness.game.setGameOver(GameEndReason.AllOpponentsLost);
            }
        }
    }
    @DataProvider public Object[][] choices() { return new Object[][] {{true}, {false}}; }
    @Test(dataProvider = "choices", timeOut = 120_000)
    public void zeroOffersKeepOrTheExistingConcessionPath(boolean keep) throws Exception {
        AtomicLong time = new AtomicLong(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, true, new WsServer("127.0.0.1", 0, 30, time::get, false));
             SeatAbandonment console = new SeatAbandonment(h.server, new StringReader(""), new PrintStream(output, true, StandardCharsets.UTF_8), 180, System::nanoTime)) {
            h.start("Selesnya Guildmage;Forest;Forest;Forest;Forest;Plains", "Forest"); h.secondaryAutoPriority = true;
            Progress progress = new Progress(h); h.action(() -> h.game.subscribeToEvents(progress));
            try {
            h.answer = p -> p.get("kind").getAsString().equals("chooseAbility") ? null : p.get("default");
            int guildmage = PromptGameHarness.edt(() -> h.card("Selesnya Guildmage", ZoneType.Battlefield).getId());
            long actions = h.remote.completedActionCount(); h.sendAction("selectCard", "cardId", num(guildmage));
            JsonObject choice = prompt(h.client, "chooseAbility");
            pulse(h.server.clock()); time.addAndGet(TimeUnit.MINUTES.toNanos(30)); assertEquals(ms(pulse(h.server.clock()), 0), 0L);
            String warning = h.remote.seatName() + " is out of time; waiting for the host";
            for (WsTestClient client : List.of(h.client, h.secondaryClient)) receive(client,
                    m -> m.get("type").getAsString().equals("message") && m.get("text").getAsString().equals(warning));
            console.submit("seats").get(5, TimeUnit.SECONDS);
            // A second console turn is a barrier after draining the timeout notice.
            // The EDT is deliberately blocked in chooseAbility here; never query it.
            console.submit("seats").get(5, TimeUnit.SECONDS);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("keep 0 or concede 0"), "Clock menu printed");
            if (keep) {
                assertTrue(console.submit("keep 0").get(5, TimeUnit.SECONDS).contains("continues untimed"));
                time.addAndGet(TimeUnit.HOURS.toNanos(1)); assertFalse(row(pulse(h.server.clock()), 0).get("running").getAsBoolean());
                assertNull(h.remote.abandonedReason()); h.client.send(PromptAssertions.reply(choice, choice.get("default")));
                h.until(() -> h.remote.completedActionCount() > actions, "Kept seat's action continues");
            } else {
                assertEquals(console.submit("concede 0").get(10, TimeUnit.SECONDS), "Seat conceded");
                assertEquals(h.remote.abandonedReason(), "You conceded this game");
                assertEquals(h.remote.abandonmentCompletionThread(), "Arena-console");
                PromptGameHarness.edt(() -> {
                    System.out.printf("[clock-concede] gameOver=%s players=%d priority=%s inputs0=%s inputs1=%s%n",
                            h.game.isGameOver(), h.game.getPlayers().size(), h.game.getPhaseHandler().getPriorityPlayer(),
                            h.human.getInputQueue().printInputStack(), h.secondaryHuman.getInputQueue().printInputStack());
                    assertFalse(h.game.isGameOver()); assertEquals(h.game.getPlayers().size(), 3);
                    assertTrue(h.human.getPlayer().conceded()); return null;
                });
                assertEquals(h.remote.pendingPromptCount(), 0);
                assertFalse(row(pulse(h.server.clock()), 0).get("running").getAsBoolean());
                assertTrue(progress.continued.await(5, TimeUnit.SECONDS), "Other players continue after the host's concession");
                assertTrue(PromptGameHarness.edt(() -> h.human.getPlayer().conceded()));
            }
            pulse(h.server.clock());
            for (WsTestClient client : List.of(h.client, h.secondaryClient)) assertEquals(client.history().stream().map(WsTestClient.Received::object)
                    .filter(m -> m.get("type").getAsString().equals("message") && m.get("text").getAsString().equals(warning)).count(), 1L);
            validateClocks(h.client, h.secondaryClient); h.healthy(); h.answer = p -> p.get("default");
            } finally {
                progress.finish.countDown();
                if (!keep && h.remote.abandonedReason() != null)
                    assertTrue(h.finished.await(10, TimeUnit.SECONDS), "Conceded-seat game ended on its main game thread");
            }
        }
    }
}
