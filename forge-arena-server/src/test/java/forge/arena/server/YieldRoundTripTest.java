package forge.arena.server;

import com.google.gson.*;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.*;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.*;
import static org.testng.Assert.*;

public class YieldRoundTripTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @Test(timeOut = 180_000)
    public void twoHumanSeatsKeepIndependentStopsAndInterruptTargetedYields() throws Exception {
        String previous = FModel.getPreferences().getPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS);
        FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, true)) {
            h.scenarioLines = lines -> {
                lines.removeIf(line -> line.startsWith("p1hand=") || line.startsWith("p1battlefield="));
                lines.add("p1hand=Shock"); lines.add("p1battlefield=Mountain");
            };
            h.start("Selesnya Guildmage;Craw Wurm;Forest;Forest;Forest;Forest", "");
            h.until(() -> h.secondaryState != null && h.secondaryRemote.actionSeat() != null, "second human state");
            assertNotSame(h.human.getYieldController(), h.secondaryHuman.getYieldController());
            h.until(() -> h.human.getYieldController().getBoolPref(FPref.YIELD_INTERRUPT_ON_TARGETING)
                    && h.secondaryHuman.getYieldController().getBoolPref(FPref.YIELD_INTERRUPT_ON_TARGETING), "seeded interrupt defaults");
            for (var player : h.game.getPlayers()) if (player != h.secondaryHuman.getPlayer()) {
                h.secondaryAction("setYield", "kind", str("skipPhase"), "turnPlayerId", num(player.getId()), "phase", str("END_OF_TURN"), "skip", bool(true));
            }
            h.until(() -> h.secondaryHuman.getYieldController().isSkippingPhase(h.human.getPlayer().getView(), PhaseType.END_OF_TURN), "seat one end-step stop disabled");
            assertFalse(PromptGameHarness.edt(() -> h.human.getYieldController().isSkippingPhase(h.secondaryHuman.getPlayer().getView(), PhaseType.END_OF_TURN)));

            // Deliberately invalid input must bypass the positive-action helper's schema assertion.
            JsonObject unsupported = JsonParser.parseString("{\"type\":\"setYield\",\"kind\":\"pref\",\"name\":\"UI_LANGUAGE\",\"value\":\"en-US\",\"clientSeq\":0,\"baseSeq\":0}").getAsJsonObject();
            h.client.send(unsupported);
            h.until(() -> rejected(h.client, "not a synced"), "unsupported preference rejection");
            h.sendAction("setYield", "kind", str("pref"), "name", str("YIELD_AUTO_PASS_NO_ACTIONS"), "value", str("false"));
            h.until(() -> rejected(h.client, "server-wide"), "global-only preference rejection");
            JsonElement hidden = h.clientState().getAsJsonArray("players").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(p -> p.get("id").getAsInt() == h.secondaryHuman.getPlayer().getId())
                    .findFirst().orElseThrow().getAsJsonObject("zones").getAsJsonArray("hand").get(0).getAsJsonObject().get("id");
            assertTrue(hidden.getAsJsonPrimitive().isString());
            h.sendAction("setYield", "kind", str("cardAutoYield"), "cardId", hidden, "abilityId", num(0), "active", bool(true));
            h.until(() -> rejected(h.client, "Hidden card"), "hidden yield reference rejection");

            h.sendAction("setYield", "kind", str("stackYield"), "active", bool(true), "respectsInterrupts", bool(true));
            h.until(() -> echoed(h.client, "stackYield", true), "stack-yield round trip");
            // An empty stack immediately ends this yield through Forge's applyYieldUpdate push.
            h.until(() -> echoed(h.client, "stackYield", false), "empty-stack yield cancellation");
            h.sendAction("setYield", "kind", str("untilEndOfTurn"), "active", bool(true));
            h.until(() -> echoed(h.client, "untilEndOfTurn", true), "end-of-turn yield round trip");
            h.until(() -> secondReady(h, InputPassPriority.class, "priority"), "opponent can cast Shock");
            int shock = PromptGameHarness.edt(() -> h.secondaryHuman.getPlayer().getCardsIn(ZoneType.Hand).getFirst().getId());
            int target = PromptGameHarness.edt(() -> h.card("Craw Wurm", ZoneType.Battlefield).getId());
            int mountain = PromptGameHarness.edt(() -> h.secondaryHuman.getPlayer().getCardsIn(ZoneType.Battlefield).getFirst().getId());
            h.secondaryAction("selectCard", "cardId", num(shock));
            h.until(() -> secondReady(h, InputSelectTargets.class, "target"), "Shock target input");
            h.secondaryAction("selectCard", "cardId", num(target));
            h.until(() -> secondReady(h, InputPayMana.class, "payMana"), "Shock payment input");
            h.secondaryAction("selectCard", "cardId", num(mountain));
            h.until(() -> echoed(h.client, "untilEndOfTurn", false), "targeting interrupts seat zero's yield");
            h.waitClientKind("priority");
            assertTrue(PromptGameHarness.edt(() -> !h.game.getStack().isEmpty()), "The targeted spell is still on the stack at the interrupt");
            assertFalse(PromptGameHarness.edt(() -> h.human.getYieldController().isYieldActive()));
            h.secondaryAutoPriority = true;
            Set<Input> attackStops = Collections.newSetFromMap(new IdentityHashMap<>());
            h.advance(input -> {
                if (input instanceof InputAttack && attackStops.add(input)) {
                    assertEquals(h.game.getPhaseHandler().getPhase(), PhaseType.COMBAT_DECLARE_ATTACKERS);
                    assertFalse(h.human.getYieldController().isSkippingPhase(h.human.getPlayer().getView(), PhaseType.COMBAT_DECLARE_ATTACKERS),
                            "The seeded own-turn declare-attackers stop remains enabled");
                    assertTrue(h.game.getCombat().getAttackers().isEmpty(), "This scenario declares no attackers");
                    h.sendAction("ok"); // Same socket action as the client's labeled Done button.
                }
                return input instanceof InputPassPriority && h.game.getPhaseHandler().getPhase() == PhaseType.END_OF_TURN
                        && h.game.getPhaseHandler().getPlayerTurn() == h.secondaryHuman.getPlayer();
            });
            assertFalse(attackStops.isEmpty(), "The human stopped at its seeded declare-attackers step");
            h.waitClientKind("priority");
            h.until(() -> h.clientState().get("phase").getAsString().equals("END_OF_TURN")
                    && h.clientState().get("activePlayerId").getAsInt() == h.secondaryHuman.getPlayer().getId(),
                    "end-step state reaches the socket after coalescing");
            assertEquals(h.clientState().get("phase").getAsString(), "END_OF_TURN");
            assertEquals(h.secondaryOpponentEndInputs.get(), 0, "Seat one never opened an opponent-end-step priority input");
            assertTrue(h.secondaryNoActionPasses.get() > 0, "No-action priority windows pass without a socket action");
            assertFalse(h.secondaryRemote.isDirty());
            for (WsTestClient transport : List.of(h.client, h.secondaryClient)) {
                for (var received : transport.history()) if (received.object().get("type").getAsString().equals("yieldState")) PromptAssertions.validate(received.object(), "yieldState");
            }
            System.out.println("[yield] separate end-step stops verified; no-action passes=" + h.secondaryNoActionPasses.get()
                    + "; secondary priority inputs=" + h.secondaryPriorityInputs.get());
        } finally { FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, previous); }
    }

    private static boolean secondReady(PromptGameHarness h, Class<? extends Input> type, String kind) {
        return type.isInstance(h.secondaryHuman.getInputQueue().getInput()) && h.secondaryState != null
                && h.secondaryState.getAsJsonObject("activeInput").get("kind").getAsString().equals(kind);
    }
    private static boolean rejected(WsTestClient client, String text) {
        return client.history().stream().map(WsTestClient.Received::object).anyMatch(m -> m.get("type").getAsString().equals("message") && m.get("level").getAsString().equals("rejected") && m.get("text").getAsString().contains(text));
    }
    private static boolean echoed(WsTestClient client, String kind, boolean active) {
        return client.history().stream().map(WsTestClient.Received::object).anyMatch(m -> m.get("type").getAsString().equals("yieldState") && m.get("kind").getAsString().equals(kind) && m.get("active").getAsBoolean() == active);
    }
    private static JsonPrimitive str(String value) { return new JsonPrimitive(value); }
    private static JsonPrimitive num(int value) { return new JsonPrimitive(value); }
    private static JsonPrimitive bool(boolean value) { return new JsonPrimitive(value); }
}
