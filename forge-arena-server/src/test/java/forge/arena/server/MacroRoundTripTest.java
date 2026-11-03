package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.game.event.GameEventSpellResolved;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputSelectTargets;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

public class MacroRoundTripTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }
    public static final class Resolutions {
        final AtomicInteger count = new AtomicInteger();
        @Subscribe public void resolved(GameEventSpellResolved event) {
            if (event.spell().getHostCard().getName().equals("Seeker of Skybreak")) count.incrementAndGet();
        }
    }

    @Test(timeOut = 120_000)
    public void recordsReplaysRepeatsAndStopsOnAnIllegalAction() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            // Real repeatable loop: cardsfolder/s/seeker_of_skybreak.txt, T: untap target creature (itself).
            h.start("Seeker of Skybreak", "");
            Resolutions resolutions = new Resolutions();
            int card = PromptGameHarness.edt(() -> {
                h.game.subscribeToEvents(resolutions); return h.card("Seeker of Skybreak", ZoneType.Battlefield).getId();
            });
            action(h, "macroRecord"); state(h, true, false, false);
            h.sendAction("selectCard", "cardId", new JsonPrimitive(card));
            h.waitInput(InputSelectTargets.class); h.waitClientKind("target");
            h.sendAction("selectCard", "cardId", new JsonPrimitive(card));
            h.advance(input -> input instanceof InputPassPriority && !h.game.getStack().isEmpty());
            h.sendAction("ok");
            h.until(() -> resolutions.count.get() == 1 && h.game.getStack().isEmpty()
                    && h.human.getInputQueue().getInput() instanceof InputPassPriority, "Recorded self-untap resolves");
            action(h, "macroRecord"); state(h, false, false, true);
            assertTrue(h.prompts.stream().noneMatch(p -> p.get("kind").getAsString().equals("number")), "Stop-record dialog answered locally with zero");

            action(h, "macroNext");
            h.until(() -> resolutions.count.get() == 2 && !h.human.macros().isReplaying(), "One native playback iteration");
            state(h, false, false, true);
            action(h, "macroRepeat", "count", new JsonPrimitive(3));
            h.until(() -> resolutions.count.get() == 5 && !h.human.macros().isReplaying(), "Exactly three repeat iterations");
            state(h, false, false, true);
            assertTrue(h.prompts.stream().noneMatch(p -> p.get("kind").getAsString().equals("number")), "Repeat count did not become a socket prompt");
            for (int invalid : new int[]{0, 100}) {
                int before = h.client.history().size();
                JsonObject message = JsonParser.parseString("{\"type\":\"macroRepeat\",\"clientSeq\":100,\"baseSeq\":0,\"count\":" + invalid + "}").getAsJsonObject();
                h.client.send(message);
                rejected(h, before, "Macro repeat count must be between 1 and 99");
            }
            PromptGameHarness.edt(() -> { h.game.getAction().exile(h.game.findById(card), null, null); return null; });
            int before = h.client.history().size();
            action(h, "macroNext");
            rejected(h, before, forge.util.Localizer.getInstance().getMessage("lblMacroPlaybackStopped"));
            h.until(() -> !h.human.macros().isReplaying(), "Illegal replay stopped");
            assertEquals(resolutions.count.get(), 5);
            for (var message : h.client.history()) PromptAssertions.validate(message.object(), "ServerMessage");
            h.healthy();
            System.out.println("[macro] recorded=1 next=1 repeat=3 illegal=stopped count0/count100=rejected");
        }
    }

    private static void action(PromptGameHarness h, String type, Object... fields) throws Exception {
        long before = h.remote.completedActionCount(); h.sendAction(type, fields);
        h.until(() -> h.remote.completedActionCount() > before, type);
    }

    @Test(timeOut = 90_000)
    public void eliminatedSeatCannotStartMacros() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, false)) {
            h.start("Forest", "");
            h.secondaryAction("concede");
            h.until(() -> h.secondaryHuman.getPlayer().hasLost(), "Seat 1 conceded");
            for (String type : new String[]{"macroRecord", "macroNext", "macroRepeat"}) {
                int before = h.secondaryClient.history().size();
                h.secondaryAction(type, "count", new JsonPrimitive(3));
                h.until(() -> h.secondaryClient.history().stream().skip(before).map(WsTestClient.Received::object).anyMatch(m ->
                        m.get("type").getAsString().equals("message") && m.get("text").getAsString().equals("You conceded this game")), type + " rejected after elimination");
            }
            assertFalse(PromptGameHarness.edt(() -> h.secondaryHuman.macros().isRecording()));
        }
    }

    @Test(timeOut = 30_000)
    public void abandonedMacroActionsKeepTheViewerRejection() throws Exception {
        for (String reason : new String[]{"You conceded this game", "This seat is now played by a bot"}) {
            try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
                JsonSeatRemote remote = new JsonSeatRemote((forge.game.Game) null, null, server.writer(0));
                remote.abandon(reason); server.writer(0).bindPlayer(0); server.start();
                client.connect(java.net.http.HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
                for (String type : new String[]{"macroRecord", "macroNext", "macroRepeat"}) {
                    JsonObject message = JsonParser.parseString("{\"type\":\"" + type + "\",\"clientSeq\":1,\"baseSeq\":0,\"count\":3}").getAsJsonObject();
                    client.send(message);
                    JsonObject rejected = client.receive(); PromptAssertions.validate(rejected, "message");
                    assertEquals(rejected.get("level").getAsString(), "rejected"); assertEquals(rejected.get("text").getAsString(), reason);
                }
            }
        }
    }
    private static void state(PromptGameHarness h, boolean recording, boolean replaying, boolean hasActions) throws Exception {
        h.until(() -> {
            JsonObject last = null;
            for (var item : h.client.history()) if (item.object().get("type").getAsString().equals("macroState")) last = item.object();
            return last != null && last.get("recording").getAsBoolean() == recording
                    && last.get("replaying").getAsBoolean() == replaying && last.get("hasActions").getAsBoolean() == hasActions;
        }, "macroState");
    }
    private static void rejected(PromptGameHarness h, int after, String text) throws Exception {
        h.until(() -> h.client.history().stream().skip(after).map(WsTestClient.Received::object).anyMatch(m ->
                m.get("type").getAsString().equals("message") && m.get("level").getAsString().equals("rejected")
                        && m.get("text").getAsString().equals(text)), text);
    }
}
