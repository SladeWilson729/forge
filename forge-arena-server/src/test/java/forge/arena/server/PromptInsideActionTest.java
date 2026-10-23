package forge.arena.server;

import com.google.gson.*;
import forge.game.ability.ApiType;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputPassPriority;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;
import static org.testng.Assert.*;

public class PromptInsideActionTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @Test(timeOut = 120_000)
    public void replyBypassesBlockedEdtAndSurvivesReconnect() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness()) {
            final WsTestClient reconnected = new WsTestClient();
            h.start("Selesnya Guildmage;Forest;Plains;Plains;Plains", "");
            h.answer = p -> p.get("kind").getAsString().equals("chooseAbility") ? null : p.get("default");
            final long started = System.nanoTime();
            final var action = PromptGameHarness.later(() -> h.controller.selectCard(
                    h.card("Selesnya Guildmage", ZoneType.Battlefield).getView(), null, null));
            JsonObject prompt = next(h.client, "prompt");
            PromptAssertions.validate(prompt, "prompt");
            assertEquals(prompt.get("kind").getAsString(), "chooseAbility");
            assertEquals(prompt.getAsJsonArray("options").size(), 2);
            assertTrue(prompt.getAsJsonArray("options").get(1).getAsJsonObject().get("label").getAsString().contains("+1/+1"));
            assertFalse(action.isDone(), "EDT is blocked inside selectCard");
            h.client.send(PromptAssertions.reply(prompt, new JsonPrimitive(999)));
            JsonObject rejected = next(h.client, "message");
            PromptAssertions.validate(rejected, "message");
            assertEquals(rejected.get("level").getAsString(), "rejected");
            assertTrue(rejected.get("text").getAsString().contains("optionId"));
            assertFalse(action.isDone(), "Bad option leaves the same prompt pending");
            h.client.close();
            h.useClient(reconnected); // Subsequent prompts belong to the replacement socket too.
            reconnected.connect(HttpClient.newHttpClient(), h.server.getPort(), "/ws?seat=0");
            reconnected.hello(0);
            JsonObject cached = reconnected.receive();
            assertEquals(cached.get("type").getAsString(), "state");
            PromptAssertions.validate(cached, "state");
            JsonObject replay = reconnected.receive();
            assertEquals(replay, prompt, "Replay retains the original requestId and option table");
            PromptAssertions.validate(replay, "prompt");
            reconnected.send(PromptAssertions.reply(replay, new JsonPrimitive(1)));
            action.get(10, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "Prompt-inside-action completed within 10 s");
            assertTrue(h.remote.getLastReplyThread().startsWith("nioEventLoopGroup-"));
            // PlaySpellAbility.chooseOptionalAdditionalCosts asks again, on the game worker,
            // even when the only variant is the already-selected 3W PumpAll ability. The old
            // replacement client had no handler and silently left THIS second request pending.
            JsonObject additionalCost = next(reconnected, "prompt");
            assertNotEquals(additionalCost.get("requestId"), prompt.get("requestId"));
            assertEquals(additionalCost.get("kind").getAsString(), "chooseAbility");
            assertEquals(additionalCost.getAsJsonArray("options").size(), 1);
            assertTrue(additionalCost.getAsJsonArray("options").get(0).getAsJsonObject()
                    .get("label").getAsString().contains("+1/+1"));
            PromptGameHarness.edt(() -> {
                h.traceState("after initial reply, additional-cost reply pending");
                assertNull(h.human.getInputQueue().getInput());
                assertTrue(h.game.getStack().isEmpty());
                return null;
            });
            PromptGameHarness.dumpThreads("additional-cost prompt deliberately pending on replacement client");
            h.answer = p -> p.get("default");
            reconnected.send(PromptAssertions.reply(additionalCost, new JsonPrimitive(0)));
            h.waitInput(InputPayMana.class);
            PromptGameHarness.edt(() -> { h.traceState("additional-cost reply completed, paying 3W"); return null; });
            h.action(h.controller::selectButtonOk); // Forge autopays the selected ability's 3W cost.
            h.waitInput(InputPassPriority.class);
            PromptGameHarness.edt(() -> {
                assertEquals(h.game.getStack().peekAbility().getApi(), ApiType.PumpAll,
                        "The second activated ability, not the Saproling ability, is on the stack");
                h.traceState("second activated ability on stack after payment");
                return null;
            });
            h.healthy();
        }
    }

    static JsonObject next(WsTestClient client, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            JsonObject message = client.receive();
            if (message.get("type").getAsString().equals("state")) PromptAssertions.validate(message, "state");
            if (message.get("type").getAsString().equals(type)) return message;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No " + type + " within 5 s");
    }
}
