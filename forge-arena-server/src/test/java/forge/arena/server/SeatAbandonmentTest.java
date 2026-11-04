package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventSpellResolved;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.*;
import forge.gamemodes.net.ProtocolMethod;
import org.testng.annotations.*;

import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.testng.Assert.*;

public class SeatAbandonmentTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    public static final class EndOnPriority {
        final AtomicBoolean finish = new AtomicBoolean(), foreseeResolved = new AtomicBoolean(), ponderResolved = new AtomicBoolean();
        final AtomicInteger priorities = new AtomicInteger();
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch held = new CountDownLatch(1), resume = new CountDownLatch(1);
        final Game game;
        EndOnPriority(Game game) { this.game = game; }
        @Subscribe public void priority(GameEventPlayerPriority event) {
            priorities.incrementAndGet();
            if (holdNext.compareAndSet(true, false)) {
                held.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS), "Release held game thread"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }
            if (finish.get() && !game.isGameOver()) game.setGameOver(GameEndReason.Draw);
        }
        @Subscribe public void resolved(GameEventSpellResolved event) {
            if (event.spell().getHostCard().getName().equals("Foresee")) foreseeResolved.set(true);
            if (event.spell().getHostCard().getName().equals("Ponder")) ponderResolved.set(true);
        }
    }

    private static SeatAbandonment console(PromptGameHarness h, ByteArrayOutputStream output) {
        return new SeatAbandonment(h.server, new StringReader(""), new PrintStream(output, true, StandardCharsets.UTF_8),
                180, System::nanoTime);
    }
    private static EndOnPriority watch(PromptGameHarness h) throws Exception {
        return PromptGameHarness.edt(() -> { EndOnPriority end = new EndOnPriority(h.game); h.game.subscribeToEvents(end); return end; });
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), message);
    }
    private static void disconnect(PromptGameHarness h) throws Exception {
        h.client.close(); await(() -> !h.server.writer(0).connected(), "Socket became disconnected");
    }
    private static void handoff(PromptGameHarness h, SeatAbandonment console, boolean bot) throws Exception {
        disconnect(h);
        assertEquals(console.submit((bot ? "bot" : "concede") + " 0").get(10, TimeUnit.SECONDS), bot ? "Bot handoff complete" : "Seat conceded");
        assertNotNull(h.remote.abandonedReason());
        assertEquals(h.remote.abandonmentCompletionThread(), "Arena-console", "Pending futures release before the EDT task");
        PromptGameHarness.edt(() -> {
            if (bot) {
                assertTrue(h.human.getPlayer().getController() instanceof PlayerControllerAi);
                assertTrue(h.human.getPlayer().getRegisteredPlayer().getPlayer() instanceof LobbyPlayerAi);
            } else assertTrue(h.human.getPlayer().conceded());
            return null;
        });
    }
    private static void viewer(PromptGameHarness h, boolean bot) throws Exception {
        try (WsTestClient client = new WsTestClient()) {
            h.useClient(client);
            client.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=0"); client.hello(0);
            JsonObject state = PromptInsideActionTest.next(client, "state"); PromptAssertions.validate(state, "state");
            JsonObject action = JsonParser.parseString("{\"type\":\"ok\",\"clientSeq\":100,\"baseSeq\":0}").getAsJsonObject();
            client.send(action);
            String expected = bot ? "This seat is now played by a bot" : "You conceded this game";
            JsonObject rejected;
            do { rejected = PromptInsideActionTest.next(client, "message"); } while (!rejected.get("level").getAsString().equals("rejected"));
            assertEquals(rejected.get("text").getAsString(), expected); PromptAssertions.validate(rejected, "message");
            assertTrue(client.history().stream().noneMatch(m -> m.object().get("type").getAsString().equals("prompt")), "Viewer receives no pending prompts");
        }
    }

    private static void finish(PromptGameHarness h, SeatAbandonment console, EndOnPriority end) throws Exception {
        end.finish.set(true);
        if (!PromptGameHarness.edt(h.game::isGameOver) && h.remote.abandonedReason() == null) {
            disconnect(h); console.submit("concede 0").get(10, TimeUnit.SECONDS);
        }
        boolean ended = h.finished.await(10, TimeUnit.SECONDS);
        if (!ended) {
            PromptGameHarness.dumpThreads("Abandonment game did not end on its next priority");
            // Failure cleanup is still on a Game pool worker, never the TestNG thread.
            FutureTask<Void> cleanup = new FutureTask<>(() -> {
                h.game.setGameOver(GameEndReason.Draw); h.human.getInputQueue().onGameOver(true); return null;
            });
            h.game.getAction().invoke(cleanup); cleanup.get(5, TimeUnit.SECONDS);
            h.finished.await(5, TimeUnit.SECONDS);
        }
        assertTrue(ended, "Game ended normally on its game thread");
        assertTrue(PromptGameHarness.edt(h.game::isGameOver));
    }

    private static JsonObject castForOrder(PromptGameHarness h, String name) throws Exception {
        h.answer = prompt -> prompt.get("kind").getAsString().equals("order") ? null : prompt.get("default");
        int spell = PromptGameHarness.edt(() -> h.card(name, ZoneType.Hand).getId());
        h.sendAction("selectCard", "cardId", new JsonPrimitive(spell)); h.waitInput(InputPayMana.class);
        List<Integer> islands = PromptGameHarness.edt(() -> h.human.getPlayer().getCardsIn(ZoneType.Battlefield).stream().map(c -> c.getId()).toList());
        for (int id : islands) {
            h.waitClientKind("payMana"); h.sendAction("selectCard", "cardId", new JsonPrimitive(id));
            h.until(() -> h.game.findById(id).isTapped() && !(h.human.getInputQueue().getInput() instanceof InputPayMana pay && pay.isActivatingManaAbility()), "Island paid");
        }
        h.waitClientKind("priority");
        // PCH.arrangeForScry otherwise takes UI_SELECT_FROM_CARD_DISPLAYS' single
        // manipulateCardList path. Select its real order branch only after mana
        // payment. Ponder also needs this flag for its subsequent blocking confirm.
        h.action(() -> h.blockingConfirm = true);
        h.sendAction("ok");
        await(() -> h.prompts.stream().anyMatch(p -> p.get("kind").getAsString().equals("order")), name + "'s blocking order opened");
        assertNull(h.remote.abandonedReason(), "First prompt opens before abandonment");
        assertEquals(h.remote.pendingPromptCount(), 1, "First prompt is genuinely pending");
        return h.prompts.stream().filter(p -> p.get("kind").getAsString().equals("order")).findFirst().orElseThrow();
    }

    @Test(timeOut = 120_000)
    public void botTakesOverAnOpenOrder() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); SeatAbandonment console = console(h, new ByteArrayOutputStream())) {
            h.start("Island;Island;Island;Island", "Foresee"); EndOnPriority end = watch(h);
            try {
                JsonObject order = castForOrder(h, "Foresee"); assertEquals(order.getAsJsonArray("options").size(), 4);
                handoff(h, console, true);
                await(end.foreseeResolved::get, "Foresee completed after its order default"); viewer(h, true);
            } finally { finish(h, console, end); }
        }
    }

    @Test(timeOut = 120_000)
    public void botTakesOverDeclareBlockers() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); SeatAbandonment console = console(h, new ByteArrayOutputStream())) {
            h.scenarioLines = lines -> { lines.removeIf(s -> s.startsWith("p0life=") || s.startsWith("p1battlefield="));
                lines.add("p0life=2"); lines.add("p1battlefield=Colossal Dreadmaw;Colossal Dreadmaw;Wall of Wood"); };
            h.start("Grizzly Bears;Runeclaw Bear;Forest", ""); EndOnPriority end = watch(h);
            try {
                // Five seconds passed three isolated class runs but timed out under suite load.
                // Use the same 30-second allowance as the harness's cold first-input wait.
                Set<Input> answered = Collections.newSetFromMap(new IdentityHashMap<>());
                h.advance(input -> {
                    if (input instanceof InputAttack && answered.add(input)) h.sendAction("ok");
                    return input instanceof InputBlock;
                }, 30);
                assertTrue(PromptGameHarness.edt(() -> !h.game.getCombat().getAttackers().isEmpty()));
                disconnect(h);
                assertEquals(console.submit("bot 0").get(10, TimeUnit.SECONDS), "Bot handoff complete");
                assertTrue(PromptGameHarness.edt(() -> h.human.getPlayer().getController() instanceof PlayerControllerAi));
                viewer(h, true);
            } finally { finish(h, console, end); }
        }
    }

    @DataProvider public Object[][] choices() { return new Object[][] {{true}, {false}}; }
    @Test(dataProvider = "choices", timeOut = 120_000)
    public void consoleReleasesAbilityChoiceInsideTheEdtAction(boolean bot) throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); SeatAbandonment console = console(h, new ByteArrayOutputStream())) {
            h.start("Selesnya Guildmage;Forest;Forest;Forest;Forest;Plains", ""); EndOnPriority end = watch(h);
            try {
                h.answer = prompt -> prompt.get("kind").getAsString().equals("chooseAbility") ? null : prompt.get("default");
                int card = PromptGameHarness.edt(() -> h.card("Selesnya Guildmage", ZoneType.Battlefield).getId());
                long completed = h.remote.completedActionCount();
                h.sendAction("selectCard", "cardId", new JsonPrimitive(card));
                await(() -> h.prompts.stream().anyMatch(p -> p.get("kind").getAsString().equals("chooseAbility")), "Ability prompt opened inside selectCard");
                JsonObject prompt = h.prompts.get(h.prompts.size() - 1); assertEquals(prompt.getAsJsonArray("options").size(), 2);
                assertEquals(h.remote.completedActionCount(), completed, "EDT action is still blocked");
                handoff(h, console, bot);
                await(() -> h.remote.completedActionCount() > completed, "Blocked EDT action returned without deadlock");
                await(() -> h.remote.abandonmentInputWaitCount() > 0, "Released game worker checked the handoff gate");
                viewer(h, bot);
            } finally { finish(h, console, end); }
        }
    }

    @Test(timeOut = 120_000)
    public void secondPromptInTheSameResolutionDefaultsWithoutASocket() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); SeatAbandonment console = console(h, new ByteArrayOutputStream())) {
            h.start("Island", "Ponder"); EndOnPriority end = watch(h);
            try {
                JsonObject first = castForOrder(h, "Ponder");
                assertEquals(first.getAsJsonArray("options").size(), 3);
                // Force the legal race: the console releases the Game thread while
                // its one controller-swap task is still queued on the EDT. Ponder's
                // orderMoveToZoneList is always followed by confirmAction (MayShuffle).
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                FutureTask<Void> parkedEdt = PromptGameHarness.later(() -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS), "Release held EDT"); return null;
                });
                CompletableFuture<String> handoff;
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    disconnect(h); handoff = console.submit("bot 0");
                    await(() -> h.remote.immediateDefaults().contains(ProtocolMethod.confirm), "Ponder's second prompt defaulted immediately");
                    assertFalse(handoff.isDone(), "Second prompt returns before the queued controller swap");
                } finally { release.countDown(); }
                parkedEdt.get(5, TimeUnit.SECONDS);
                assertEquals(handoff.get(10, TimeUnit.SECONDS), "Bot handoff complete");
                await(end.ponderResolved::get, "Ponder resolved through both choices");
                assertTrue(h.remote.abandonmentDefaultCount() >= 2); assertEquals(h.remote.pendingPromptCount(), 0);
                assertEquals(h.prompts.stream().filter(p -> p.get("kind").getAsString().equals("confirm")).count(), 0L,
                        "Second prompt never contacts the disconnected client");
            } finally { finish(h, console, end); }
        }
    }

    @Test(timeOut = 120_000)
    public void aLateInputOnTheConcededControllerIsReleased() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); SeatAbandonment console = console(h, new ByteArrayOutputStream())) {
            h.start("Forest", ""); EndOnPriority end = watch(h);
            try {
                // Hold the game at its next safe boundary so this forced late Input
                // reproduces the old controller's race without concurrent game reads.
                end.holdNext.set(true);
                disconnect(h); assertEquals(console.submit("concede 0").get(10, TimeUnit.SECONDS), "Seat conceded");
                assertTrue(end.held.await(5, TimeUnit.SECONDS));
                PromptGameHarness.edt(() -> {
                    h.human.getInputQueue().setInput(new InputPassPriority(h.human)); return null;
                });
                h.until(() -> h.human.getInputQueue().getInput() == null, "Late Input was released on the EDT");
            } finally { end.finish.set(true); end.resume.countDown(); finish(h, console, end); }
        }
    }

    @Test(timeOut = 120_000)
    public void warningWaitAndTokenReconnectResumeTheHuman() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true); WsTestClient observer = new WsTestClient(); WsTestClient replacement = new WsTestClient()) {
            h.start("Selesnya Guildmage;Forest;Forest;Forest;Forest", "Forest"); EndOnPriority end = watch(h);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); AtomicLong clock = new AtomicLong(System.nanoTime());
            try (SeatAbandonment console = new SeatAbandonment(h.server, new StringReader(""), new PrintStream(output, true, StandardCharsets.UTF_8), 0, clock::get)) {
                try {
                    observer.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=1"); observer.hello(1);
                    disconnect(h); clock.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
                    String warning = h.remote.seatName() + " disconnected; waiting for the host";
                    await(() -> observer.history().stream().map(WsTestClient.Received::object).anyMatch(m -> m.get("type").getAsString().equals("message") && m.get("text").getAsString().equals(warning)), "Other seats got the warning");
                    assertTrue(output.toString(StandardCharsets.UTF_8).contains("wait 0, concede 0, or bot 0"));
                    assertTrue(console.submit("wait 0").get(5, TimeUnit.SECONDS).contains("three more minutes"));
                    clock.addAndGet(TimeUnit.MINUTES.toNanos(2)); console.submit("seats").get(5, TimeUnit.SECONDS);
                    assertEquals(output.toString(StandardCharsets.UTF_8).split("disconnected: wait", -1).length - 1, 1, "Warning is not repeated while waiting");
                    h.useClient(replacement); replacement.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=0"); replacement.hello(0);
                    PromptInsideActionTest.next(replacement, "state"); assertNull(h.remote.abandonedReason());
                    int forest = PromptGameHarness.edt(() -> h.card("Forest", ZoneType.Hand).getId());
                    JsonObject play = JsonParser.parseString("{\"type\":\"selectCard\",\"clientSeq\":50,\"baseSeq\":0}").getAsJsonObject(); play.addProperty("cardId", forest); replacement.send(play);
                    h.until(() -> h.game.findById(forest).isInZone(ZoneType.Battlefield), "Reconnected human played its land");
                    assertTrue(console.submit("seats").get(5, TimeUnit.SECONDS).contains("connected"));
                    replacement.close(); await(() -> !h.server.writer(0).connected(), "Replacement disconnected for cleanup");
                } finally { finish(h, console, end); }
            }
        }
    }
}
