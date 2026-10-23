package forge.arena.server.view;

import com.google.gson.*;
import forge.arena.server.PromptAssertions;
import forge.arena.server.JsonSeatRemote;
import forge.arena.server.SeatWriter;
import forge.deck.CardPool;
import forge.arena.server.prompt.PromptCodec;
import forge.game.card.Card;
import forge.game.player.DelayedReveal;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.interfaces.IGuiGame.OrderResult;
import forge.util.FSerializableFunction;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.*;
import static org.testng.Assert.*;

public class PromptCodecTest {
    private final PromptCodec codec = new PromptCodec();
    @BeforeClass public void initialize() { ScenarioGame.initialize(); }

    private PromptCodec.Prompt prompt(ScenarioGame g, ProtocolMethod method, Object... args) {
        PromptCodec.Prompt prompt = codec.encode(new GuiGameEvent(method, args), new PromptCodec.Context(g.game, g.player(0).getView(), g.ids[0]));
        PromptAssertions.validate(prompt.json(), "prompt");
        codec.decode(prompt, PromptAssertions.reply(prompt.json(), prompt.json().get("default")));
        return prompt;
    }
    private Object decode(PromptCodec.Prompt prompt, String result) {
        return codec.decode(prompt, PromptAssertions.reply(prompt.json(), JsonParser.parseString(result)));
    }

    @Test public void everyWireKindReturnsTheExactForgeObjectsAndLegalDefaults() {
        ScenarioGame g = new ScenarioGame();
        Card guild = g.addCardToZone("Selesnya Guildmage", 0, ZoneType.Battlefield);
        Card a = g.addCardToZone("Grizzly Bears", 1, ZoneType.Battlefield);
        Card b = g.addCardToZone("Runeclaw Bear", 1, ZoneType.Battlefield);
        Card attacker = g.addCardToZone("Colossal Dreadmaw", 0, ZoneType.Battlefield);
        g.snapshots();
        var choose = prompt(g, ProtocolMethod.getChoices, "Pick", 1, 2, List.of(a.getView(), b.getView()),
                List.of(b.getView()), (FSerializableFunction<Object, String>) value -> "label: " + value);
        assertEquals(decode(choose, "[1,0]"), List.of(b.getView(), a.getView()));
        assertTrue(choose.json().getAsJsonArray("options").get(0).getAsJsonObject().get("label").getAsString().startsWith("label: "));
        assertEquals(codec.defaultAnswer(choose), List.of(a.getView()));
        assertEquals(codec.defaultAnswer(prompt(g, ProtocolMethod.getChoices, "Reveal", -1, -1,
                List.of(a.getView()), null, null)), List.of());
        var single = prompt(g, ProtocolMethod.chooseSingleEntityForEffect, "Entity", List.of(a.getView()), null, false);
        assertSame(decode(single, "[0]"), a.getView());
        assertNull(codec.defaultAnswer(prompt(g, ProtocolMethod.chooseSingleEntityForEffect, "Optional", List.of(), null, true)));
        var multi = prompt(g, ProtocolMethod.chooseEntitiesForEffect, "Entities", List.of(a.getView(), g.player(0).getView()),
                1, 2, new DelayedReveal(List.of(a), ZoneType.Battlefield, g.player(1).getView()));
        assertEquals(decode(multi, "[1]"), List.of(g.player(0).getView()));
        assertEquals(multi.json().getAsJsonArray("revealed").size(), 1);
        // Actual CostPart objects are also legal order options, not serializable card identities.
        final List<?> costs = guild.getSpellAbilities().get(0).getPayCosts().getCostParts();
        assertFalse(costs.isEmpty());
        var order = prompt(g, ProtocolMethod.order, "Costs", "Top", 0, 0, costs, null, guild.getView(), false, true);
        OrderResult<?> ordered = (OrderResult<?>) codec.defaultAnswer(order);
        assertEquals(ordered.ordered(), costs); assertFalse(ordered.rememberDecision());
        assertSame(ordered.ordered().get(0), costs.get(0));
        var destination = prompt(g, ProtocolMethod.order, "Destination", "Top", 0, 2,
                List.of(a.getView(), b.getView()), List.of(guild.getView()), null, false, false);
        assertEquals(((OrderResult<?>) codec.defaultAnswer(destination)).ordered(), List.of(guild.getView()));
        assertEquals(((OrderResult<?>) decode(destination, "{\"ordered\":[1],\"remember\":false}")).ordered(), List.of(b.getView()));
        assertEquals(decode(prompt(g, ProtocolMethod.confirm, guild.getView(), "May?", false, List.of("Yes", "No")), "true"), true);
        assertEquals(codec.defaultAnswer(prompt(g, ProtocolMethod.showConfirmDialog, "May?", "Title", "Yes", "No", false)), false);
        assertEquals(decode(prompt(g, ProtocolMethod.showOptionDialog, "Option", "Title", null, List.of("A", "B"), 0), "1"), 1);
        assertEquals(decode(prompt(g, ProtocolMethod.showInputDialog, "Number", "Title", null, "1", null, true), "\"12\""), "12");
        assertEquals(decode(prompt(g, ProtocolMethod.showInputDialog, "Text", "Title", null, "", null, false), "\"hello\""), "hello");
        List<SpellAbilityView> abilities = new ArrayList<>();
        guild.getSpellAbilities().forEach(ability -> abilities.add(ability.getView()));
        var ability = prompt(g, ProtocolMethod.getAbilityToPlay, guild.getView(), abilities, null);
        assertSame(decode(ability, "1"), abilities.get(1));
        var combat = prompt(g, ProtocolMethod.assignCombatDamage, attacker.getView(), List.of(a.getView(), b.getView()),
                6, g.player(1).getView(), false, false);
        Map<?, ?> damage = (Map<?, ?>) decode(combat, "[{\"optionId\":0,\"amount\":2},{\"optionId\":1,\"amount\":2},{\"optionId\":2,\"amount\":2}]");
        assertEquals(damage.get(null), 2, "Forge uses null for defender damage");
        assertNotNull(codec.defaultAnswer(combat));
        Map<Object, Integer> targets = new LinkedHashMap<>(); targets.put(a.getView(), 2); targets.put(g.player(0).getView(), 4);
        var generic = prompt(g, ProtocolMethod.assignGenericAmount, guild.getView(), targets, 3, true, "Counters");
        Map<?, ?> allocation = (Map<?, ?>) codec.defaultAnswer(generic);
        assertEquals(allocation.get(a.getView()), 2); assertEquals(allocation.get(g.player(0).getView()), 1);
        var manipulate = prompt(g, ProtocolMethod.manipulateCardList, "Order", List.of(a.getView(), b.getView()),
                List.of(b.getView()), true, false, false);
        assertEquals(decode(manipulate, "[1,0]"), List.of(b.getView(), a.getView()));
        assertEquals(codec.defaultAnswer(manipulate), List.of(a.getView(), b.getView()));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new GuiGameEvent(ProtocolMethod.sideboard),
                new PromptCodec.Context(g.game, g.player(0).getView(), g.ids[0])));
        try (SeatWriter writer = new SeatWriter(0)) {
            CardPool main = new CardPool(); main.add("Plains", 2);
            JsonSeatRemote remote = new JsonSeatRemote(g.game, g.player(0).getView(), writer);
            assertEquals(remote.sendAndWait(new GuiGameEvent(ProtocolMethod.sideboard, new CardPool(), main, "Sideboard")),
                    main.toFlatList());
        }
    }

    @Test public void rejectsCountsIdsResultTypesIllegalOrdersAndDamageSums() {
        ScenarioGame g = new ScenarioGame();
        Card card = g.addCardToZone("Grizzly Bears", 0, ZoneType.Battlefield);
        var choose = prompt(g, ProtocolMethod.getChoices, "Pick", 1, 1, List.of("one", "two"), null, null);
        for (String bad : List.of("[]", "[0,1]", "[8]", "[0,0]", "\"0\"", "[0.5]")) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(choose,
                    rawReply(choose, bad))); // Malformed shapes need not pass the schema first.
        }
        var order = prompt(g, ProtocolMethod.order, "Order", "Top", 0, 0, List.of("a", "b"), null, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> decode(order, "{\"ordered\":[0],\"remember\":false}"));
        assertThrows(IllegalArgumentException.class, () -> decode(order, "{\"ordered\":[0,1],\"remember\":true}"));
        var assign = prompt(g, ProtocolMethod.assignGenericAmount, card.getView(), Map.of("a", 3, "b", 3), 4, true, "Amount");
        for (String bad : List.of("[{\"optionId\":0,\"amount\":3}]", "[{\"optionId\":0,\"amount\":4}]",
                "[{\"optionId\":9,\"amount\":4}]", "[{\"optionId\":0,\"amount\":2},{\"optionId\":0,\"amount\":2}]")) {
            assertThrows(IllegalArgumentException.class, () -> decode(assign, bad));
        }
        var combat = prompt(g, ProtocolMethod.assignCombatDamage, card.getView(), List.of(card.getView(), card.getView()), 3, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> decode(combat, "[{\"optionId\":1,\"amount\":3}]"));
        var number = prompt(g, ProtocolMethod.showInputDialog, "Number", "Title", null, "0", null, true);
        assertThrows(IllegalArgumentException.class, () -> decode(number, "\"not a number\""));
        var fixed = prompt(g, ProtocolMethod.manipulateCardList, "Fixed", List.of("a", "b", "c"), List.of("b"), false, false, false);
        assertThrows(IllegalArgumentException.class, () -> decode(fixed, "[1,0,2]"));
    }

    @Test public void offeredHiddenIdentityIsRevealedOnlyInTheOwnerPrompt() {
        ScenarioGame g = new ScenarioGame();
        Card hidden = g.addCardToZone("Dreadmaw's Ire", 1, ZoneType.Hand);
        Card library = g.addCardToZone("Storm Crow", 1, ZoneType.Library);
        JsonObject[] before = g.snapshots();
        Object ref = g.ids[0].currentRef(hidden.getId()).orElseThrow();
        var offered = prompt(g, ProtocolMethod.getChoices, "Opponent offers a card", 1, 1,
                List.of(hidden.getView(), library.getView()), null, null);
        JsonObject option = offered.json().getAsJsonArray("options").get(0).getAsJsonObject();
        assertEquals(option.get("name").getAsString(), hidden.getName());
        assertEquals(option.get("cardRef").getAsString(), ref);
        assertTrue(option.has("set") && option.has("num"));
        JsonObject libraryOption = offered.json().getAsJsonArray("options").get(1).getAsJsonObject();
        assertEquals(libraryOption.get("name").getAsString(), library.getName());
        assertTrue(libraryOption.has("set") && libraryOption.has("num"), "Face-up library searches still reveal identity");
        String temporary = libraryOption.get("cardRef").getAsString();
        assertTrue(temporary.matches("h-[0-9a-f]{12}"));
        assertTrue(g.ids[0].resolve(temporary).isEmpty(), "Prompt-only ref does not grant general action access");
        for (int seat : List.of(0, 2, 3)) {
            assertFalse(before[seat].toString().contains(hidden.getName()));
            assertFalse(g.snapshots()[seat].toString().contains(hidden.getName()));
        }
        assertEquals(g.ids[0].currentRef(hidden.getId()).orElseThrow(), ref);
    }

    @Test public void offeredFaceDownManifestKeepsItsIdentityHiddenFromThePromptOwner() {
        ScenarioGame g = new ScenarioGame();
        Card original = g.addCardToZone("Colossal Dreadmaw", 1, ZoneType.Hand);
        SpellAbility cause = new SpellAbility.EmptySa(original);
        cause.setCardState(original.getCurrentState());
        Card manifest = original.manifest(g.player(1), cause, new HashMap<>());
        assertNotNull(manifest);
        assertTrue(manifest.isManifested());
        g.snapshots();
        assertFalse(CardVisibility.forSeat(manifest.getView(), g.player(0).getView()).identityVisible());
        var offered = prompt(g, ProtocolMethod.getChoices, "Choose a creature", 1, 1,
                List.of(manifest.getView()), null, (FSerializableFunction<Object, String>) ignored -> {
                    throw new AssertionError("Hidden face-down options must not invoke the identity formatter");
                });
        JsonObject option = offered.json().getAsJsonArray("options").get(0).getAsJsonObject();
        assertEquals(option.get("label").getAsString(), "Face-down card");
        assertFalse(option.has("name") || option.has("set") || option.has("num"));
        assertEquals(option.get("cardRef").getAsString(), g.ids[0].currentRef(manifest.getId()).orElseThrow());
        HiddenInfoAssertions.noIdentity(offered.json(), manifest);
        assertEquals(decode(offered, "[0]"), List.of(manifest.getView()), "Hidden option remains selectable");
        JsonObject leaked = offered.json();
        leaked.getAsJsonArray("options").get(0).getAsJsonObject().addProperty("cardRef", manifest.getId());
        assertThrows(AssertionError.class, () -> HiddenInfoAssertions.noIdentity(leaked, manifest));

        // Neither a missing snapshot ref nor an old visible ref may expose the real id.
        IdMapper stale = new IdMapper();
        stale.beginSnapshot(); stale.wireRef(manifest.getId(), "Battlefield", false, true); stale.endSnapshot();
        for (IdMapper ids : List.of(new IdMapper(), stale)) {
            var pending = codec.encode(new GuiGameEvent(ProtocolMethod.chooseSingleEntityForEffect,
                    "Choose a creature", List.of(manifest.getView()),
                    new DelayedReveal(List.of(manifest), ZoneType.Battlefield, g.player(1).getView()), false),
                    new PromptCodec.Context(g.game, g.player(0).getView(), ids));
            PromptAssertions.validate(pending.json(), "prompt");
            HiddenInfoAssertions.noIdentity(pending.json(), manifest); // Includes delayed-reveal metadata.
            JsonObject hiddenOption = pending.json().getAsJsonArray("options").get(0).getAsJsonObject();
            assertEquals(hiddenOption.get("label").getAsString(), "Face-down card");
            assertTrue(hiddenOption.get("cardRef").getAsString().matches("h-[0-9a-f]{12}"));
            assertSame(decode(pending, "[0]"), manifest.getView());
        }

        // Permission from Forge still permits the controller to inspect its own manifest.
        var own = codec.encode(new GuiGameEvent(ProtocolMethod.getChoices, "Choose a creature", 1, 1,
                List.of(manifest.getView()), null, null),
                new PromptCodec.Context(g.game, g.player(1).getView(), g.ids[1]));
        PromptAssertions.validate(own.json(), "prompt");
        assertEquals(own.json().getAsJsonArray("options").get(0).getAsJsonObject().get("name").getAsString(),
                original.getPaperCard().getName());
    }

    private static JsonObject rawReply(PromptCodec.Prompt prompt, String result) {
        JsonObject reply = new JsonObject(); reply.addProperty("type", "promptReply");
        reply.addProperty("requestId", prompt.requestId()); reply.add("result", JsonParser.parseString(result)); return reply;
    }
}
