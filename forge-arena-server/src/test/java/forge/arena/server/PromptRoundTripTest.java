package forge.arena.server;

import com.google.gson.*;
import forge.card.MagicColor;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputPassPriority;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.testng.Assert.*;

public class PromptRoundTripTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @Test(timeOut = 120_000)
    public void chooseOrderAndMayTriggerConfirmOverSocket() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness()) {
            final List<String> orderedNames = new CopyOnWriteArrayList<>();
            h.answer = p -> {
                switch (p.get("kind").getAsString()) {
                    case "choose": {
                        for (JsonElement option : p.getAsJsonArray("options")) {
                            if (option.getAsJsonObject().get("label").getAsString().equalsIgnoreCase("blue")) {
                                JsonArray ids = new JsonArray(); ids.add(option.getAsJsonObject().get("optionId")); return ids;
                            }
                        }
                        return p.get("default");
                    }
                    case "confirm": return new JsonPrimitive(true);
                    case "order": {
                        JsonArray ids = new JsonArray();
                        for (int i = p.getAsJsonArray("options").size() - 1; i >= 0; i--) {
                            JsonObject option = p.getAsJsonArray("options").get(i).getAsJsonObject();
                            ids.add(option.get("optionId")); orderedNames.add(option.get("name").getAsString());
                        }
                        JsonObject result = new JsonObject(); result.add("ordered", ids); result.addProperty("remember", false);
                        return result;
                    }
                    default: return p.get("default");
                }
            };
            h.start("Soul's Attendant;Birds of Paradise;Forest;Island", "Balduvian Bears;Index");
            int lifeBefore = PromptGameHarness.edt(() -> {
                // Same flag as 1.4a: keep each tapped source's mana in the pool so the test
                // can inspect the chosen color before explicitly paying. It does not choose
                // that color; InputPayMana checks it only after the mana ability resolves.
                h.human.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
                return h.human.getPlayer().getLife();
            });
            h.action(() -> h.controller.selectCard(h.card("Balduvian Bears", ZoneType.Hand).getView(), null, null));
            h.waitInput(InputPayMana.class);
            h.action(() -> h.controller.selectCard(h.card("Forest", ZoneType.Battlefield).getView(), null, null));
            h.waitInput(InputPayMana.class);
            h.action(() -> h.controller.useMana(MagicColor.GREEN));
            h.waitInput(InputPayMana.class);
            h.action(() -> h.controller.selectCard(h.card("Birds of Paradise", ZoneType.Battlefield).getView(), null, null));
            h.until(() -> h.ready(h.human.getInputQueue().getInput())
                    && h.human.getPlayer().getManaPool().getAmountOfColor(MagicColor.BLUE) == 1
                    && owner(h.remote.getLatestState()).getAsJsonObject("manaPool").get("U").getAsInt() == 1,
                    "chosen blue mana in live pool and snapshot");
            PromptGameHarness.edt(() -> {
                assertEquals(owner(h.remote.getLatestState()).getAsJsonObject("manaPool").get("U").getAsInt(), 1);
                assertTrue(h.prompts.stream().anyMatch(p -> "choose".equals(p.get("kind").getAsString())
                        && p.getAsJsonArray("options").asList().stream().anyMatch(option ->
                        "blue".equalsIgnoreCase(option.getAsJsonObject().get("label").getAsString()))),
                        "Birds really offered its color through getChoices");
                System.out.println("[prompt-mana] desktop payment, NoPaymentFromManaAbility=true, chosen blue=1");
                return null;
            });
            h.action(() -> h.controller.useMana(MagicColor.BLUE));
            h.waitInput(InputPassPriority.class);
            // Only the may-trigger resolution needs the public mobile-style blocking confirm
            // path; mana payment above must retain the desktop path used in 1.4a.
            h.action(() -> h.blockingConfirm = true);
            h.advance(input -> input instanceof InputPassPriority && h.game.getStack().isEmpty()
                    && h.human.getPlayer().getLife() == lifeBefore + 1);
            PromptGameHarness.edt(() -> {
                assertEquals(owner(h.remote.getLatestState()).get("life").getAsInt(), lifeBefore + 1);
                h.blockingConfirm = false;
                h.human.getFullControl().remove(FullControlFlag.NoPaymentFromManaAbility); return null;
            });
            h.action(() -> h.controller.selectCard(h.card("Index", ZoneType.Hand).getView(), null, null));
            h.waitInput(InputPayMana.class);
            h.action(h.controller::selectButtonOk);
            h.waitInput(InputPassPriority.class);
            h.advance(input -> input instanceof InputPassPriority && h.game.getStack().isEmpty() && !orderedNames.isEmpty());
            PromptGameHarness.edt(() -> {
                List<String> actual = new ArrayList<>();
                for (var card : h.human.getPlayer().getCardsIn(ZoneType.Library)) {
                    if (actual.size() == 5) break; actual.add(card.getName());
                }
                assertEquals(actual, orderedNames, "Index applied the returned destination order");
                assertTrue(owner(h.remote.getLatestState()).getAsJsonObject("zones").getAsJsonArray("graveyard")
                        .toString().contains("Index"), "Snapshot records resolved spell");
                return null;
            });
            Set<String> kinds = new HashSet<>(); h.prompts.forEach(p -> kinds.add(p.get("kind").getAsString()));
            assertTrue(kinds.containsAll(Set.of("choose", "order", "confirm")), "Observed " + kinds);
            h.healthy();
        }
    }

    private static JsonObject owner(JsonObject state) { return state.getAsJsonArray("players").get(0).getAsJsonObject(); }
}
