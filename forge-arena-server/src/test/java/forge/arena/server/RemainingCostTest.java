package forge.arena.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.card.mana.ManaCost;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPayMana;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

/** Real socket actions, actual card-script costs, and the existing Forge-thread snapshot guard. */
public class RemainingCostTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @DataProvider public Object[][] payments() {
        return new Object[][] {
                {"Horizon Chimera", List.of("Forest", "Island", "Mountain", "Plains"),
                        List.of("{2}{U}{G}", "{2}{U}", "{2}", "{1}", "")},
                {"Dimir Guildmage", List.of("Island", "Swamp"), List.of("{U/B}{U/B}", "{U/B}", "")},
                {"Thought-Knot Seer", List.of("Wastes", "Forest", "Island", "Mountain"),
                        List.of("{3}{C}", "{3}", "{2}", "{1}", "")}
        };
    }

    @Test(dataProvider = "payments", timeOut = 120_000)
    public void socketRemainingCostShrinksAfterEachManaSource(String name, List<String> sources, List<String> expected) throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            List<JsonObject> built = new CopyOnWriteArrayList<>(); h.stateObserver = built::add;
            h.start(String.join(";", sources), name);
            int spell = PromptGameHarness.edt(() -> h.card(name, ZoneType.Hand).getId());
            h.waitClientKind("priority");
            h.sendAction("selectCard", "cardId", new JsonPrimitive(spell));
            h.waitClientKind("payMana");
            awaitRemaining(h, expected.get(0), false, 0);
            System.out.printf("[remaining-cost] %s start=%s%n", name, expected.get(0));
            for (int i = 0; i < sources.size(); i++) {
                String source = sources.get(i), remaining = expected.get(i + 1);
                int id = PromptGameHarness.edt(() -> h.card(source, ZoneType.Battlefield).getId());
                long before = h.clientState().get("seq").getAsLong();
                h.sendAction("selectCard", "cardId", new JsonPrimitive(id));
                if (!remaining.isEmpty()) awaitRemaining(h, remaining, false, before);
                else h.waitClientKind("priority");
                PromptGameHarness.edt(() -> { assertTrue(h.game.findById(id).isTapped(), source); return null; });
                System.out.printf("[remaining-cost] %s tap=%s remaining=%s%n", name, source, remaining);
            }
            PromptGameHarness.edt(() -> {
                assertEquals(h.game.getStack().peekAbility().getHostCard().getName(), name, "Fully paid spell reached the stack");
                assertNull(h.human.getPlayer().getPaidForSA(), "Finished Input released its payment link");
                return null;
            });
            assertTrue(built.stream().map(s -> s.getAsJsonObject("activeInput")).anyMatch(input ->
                    "payMana".equals(input.get("kind").getAsString()) && input.has("remainingCost")
                            && input.get("remainingCost").getAsString().isEmpty()),
                    "The paid display uses an empty string before the Input stops (wire may coalesce it)");
            for (JsonObject state : built) {
                JsonObject input = state.getAsJsonObject("activeInput");
                if ("payMana".equals(input.get("kind").getAsString())) {
                    assertTrue(input.has("remainingCost") && input.has("xCost"), "Every payMana display carries the cost: " + input);
                    assertFalse(input.get("xCost").getAsBoolean());
                } else assertNoCost(input);
            }
            for (var received : h.client.history()) if ("state".equals(received.object().get("type").getAsString()))
                PromptAssertions.validate(received.object(), "state");
            assertNoCost(h.clientState().getAsJsonObject("activeInput"));
            h.healthy();
        }
    }

    @Test(timeOut = 120_000)
    public void unannouncedXIsExposedAndClearsWhenAnnouncedWithoutParsingThePrompt() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.start("Forest;Island;Mountain", "Mistcutter Hydra");
            ManaCostBeingPaid cost = new ManaCostBeingPaid(new ManaCost("X G"));
            CompletableFuture<Boolean> payment = new CompletableFuture<>();
            // Normal casting announces X before payment. Probe the public controller seam
            // with X still unannounced to test the counter that the live Input exposes via SA.
            h.game.getAction().invoke(() -> {
                try {
                    var ability = h.card("Mistcutter Hydra", ZoneType.Hand).getFirstSpellAbility();
                    ability.setActivatingPlayer(h.human.getPlayer());
                    payment.complete(h.human.applyManaToCost(cost, ability, "Unannounced X probe", null, false));
                } catch (Throwable ex) { payment.completeExceptionally(ex); }
            });
            try {
                h.waitClientKind("payMana"); awaitRemaining(h, "{X}{G}", true, 0);
                long before = h.clientState().get("seq").getAsLong();
                h.action(() -> h.gui.showPromptMessage(h.human.getPlayer().getView(), "Unrelated wording, no mana symbols", null));
                awaitRemaining(h, "{X}{G}", true, before);
                before = h.clientState().get("seq").getAsLong();
                h.action(() -> {
                    cost.setXManaCostPaid(2, null);
                    h.human.getInputQueue().getInput().showMessageInitial();
                });
                awaitRemaining(h, "{2}{G}", false, before);
                h.sendAction("cancel");
                assertFalse(payment.get(5, TimeUnit.SECONDS)); h.waitClientKind("priority");
                assertNoCost(h.clientState().getAsJsonObject("activeInput"));
                h.healthy();
            } finally {
                // Release the probe's nested Input before the harness ends the actual game.
                if (!payment.isDone()) h.action(() -> {
                    if (h.human.getInputQueue().getInput() instanceof InputPayMana) h.controller.selectButtonCancel();
                });
                payment.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitRemaining(PromptGameHarness h, String expected, boolean xCost, long after) throws Exception {
        h.until(() -> {
            JsonObject state = h.clientState();
            if (state == null || state.get("seq").getAsLong() <= after) return false;
            JsonObject input = state.getAsJsonObject("activeInput");
            if (!"payMana".equals(input.get("kind").getAsString()) || !input.has("remainingCost")
                    || !expected.equals(input.get("remainingCost").getAsString())) return false;
            if (!(h.human.getInputQueue().getInput() instanceof InputPayMana pay) || pay.isActivatingManaAbility()) return false;
            var ability = h.human.getPlayer().getPaidForSA();
            assertNotNull(ability); ManaCostBeingPaid live = ability.getManaCostBeingPaid(); assertNotNull(live);
            assertEquals(input.get("xCost").getAsBoolean(), xCost);
            assertEquals(live.getXcounter() > 0, xCost);
            assertEquals(live.toString(), expected.isEmpty() ? "0" : expected, "Wire cost equals the live payment object");
            PromptAssertions.validate(state, "state"); return true;
        }, "wire remainingCost=" + expected + " xCost=" + xCost);
    }

    private static void assertNoCost(JsonObject input) {
        assertFalse(input.has("remainingCost"), input.toString()); assertFalse(input.has("xCost"), input.toString());
    }
}
