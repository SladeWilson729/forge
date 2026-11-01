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
            // The selected 3W PumpAll variant is the only additional-cost choice.
            // Like CMatchUI, the seat GUI returns it directly: no second socket prompt.
            h.waitInput(InputPayMana.class);
            assertEquals(h.prompts.stream().map(p -> p.get("requestId")).distinct().count(), 1L,
                    "Only the two-ability choice (and its reconnect replay) contacts the client");
            PromptGameHarness.edt(() -> { h.traceState("single cost variant selected directly, paying 3W"); return null; });
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

    @Test(timeOut = 120_000)
    public void landSpellAndSingleManaAbilityPlayWithoutAbilityPrompts() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.start("Mountain", "Mountain;Shock");
            int land = PromptGameHarness.edt(() -> h.card("Mountain", ZoneType.Hand).getId());
            int source = PromptGameHarness.edt(() -> h.card("Mountain", ZoneType.Battlefield).getId());
            int shock = PromptGameHarness.edt(() -> h.card("Shock", ZoneType.Hand).getId());
            int defender = PromptGameHarness.edt(() -> h.game.getPlayers().get(1).getId());
            h.waitClientKind("priority");
            h.sendAction("selectCard", "cardId", new JsonPrimitive(land));
            h.until(() -> h.game.findById(land).isInZone(ZoneType.Battlefield), "Mountain played");
            h.waitClientKind("priority");
            assertNoAbilityPrompt(h);

            h.sendAction("selectCard", "cardId", new JsonPrimitive(shock));
            h.waitClientKind("target");
            assertNoAbilityPrompt(h);
            h.sendAction("selectPlayer", "playerId", new JsonPrimitive(defender));
            h.waitClientKind("payMana");
            assertNoAbilityPrompt(h); // Includes the Game-worker optional-cost singleton.
            h.sendAction("selectCard", "cardId", new JsonPrimitive(source));
            h.waitClientKind("priority");
            PromptGameHarness.edt(() -> {
                assertTrue(h.game.findById(source).isTapped());
                assertEquals(h.game.getStack().peekAbility().getApi(), ApiType.DealDamage);
                assertEquals(h.game.getStack().peekAbility().getHostCard().getName(), "Shock");
                return null;
            });
            assertNoAbilityPrompt(h); // InputPayMana's single mana ability bypasses the GUI too.
            h.healthy();
        }
    }

    private static void assertNoAbilityPrompt(PromptGameHarness h) {
        assertTrue(h.prompts.stream().noneMatch(p -> "chooseAbility".equals(p.get("kind").getAsString())),
                "Land, single spell/cost variant and single mana source must not open chooseAbility");
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
