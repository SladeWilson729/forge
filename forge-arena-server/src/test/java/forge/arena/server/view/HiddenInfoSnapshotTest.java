package forge.arena.server.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class HiddenInfoSnapshotTest {
    @BeforeClass
    public void initialize() {
        ScenarioGame.initialize();
    }

    private static JsonArray zone(final JsonObject state, final int owner, final String zone) {
        return state.getAsJsonArray("players").get(owner).getAsJsonObject()
                .getAsJsonObject("zones").getAsJsonArray(zone);
    }

    private static JsonObject mappedCard(final ScenarioGame scenario, final JsonObject state,
                                        final int seat, final int owner, final String zone, final Card card) {
        for (final JsonElement entry : zone(state, owner, zone)) {
            final JsonObject object = entry.getAsJsonObject();
            final JsonElement ref = object.get("id");
            final Object wire = ref.getAsJsonPrimitive().isString() ? ref.getAsString() : Integer.valueOf(ref.getAsInt());
            if (scenario.ids[seat].resolve(wire).orElse(-1) == card.getId()) {
                return object;
            }
        }
        throw new AssertionError("Missing mapped card " + card.getId() + " for seat " + seat + " in " + zone);
    }

    private static void noIdentity(final JsonObject state, final Card card) {
        final String json = ScenarioGame.JSON.toJson(state);
        final var paper = card.getPaperCard();
        assertNotNull(paper);
        for (final String secret : List.of(paper.getName(), paper.getEdition(), paper.getCollectorNumber())) {
            assertFalse(secret.isBlank(), "Leak scan must have a meaningful sentinel");
            assertFalse(json.contains(secret), "Hidden identity leaked: " + secret + " in " + json);
        }
        assertFalse(Pattern.compile("\"id\":" + card.getId() + "(?=[,}])").matcher(json).find(),
                "Hidden real id leaked: " + card.getId());
    }

    private static void visible(final JsonObject object, final Card card) {
        assertEquals(object.get("id").getAsInt(), card.getId());
        assertEquals(object.get("name").getAsString(), card.getPaperCard().getName());
        assertEquals(object.get("set").getAsString(), card.getPaperCard().getEdition());
        assertEquals(object.get("num").getAsString(), card.getPaperCard().getCollectorNumber());
        assertFalse(object.has("hidden"));
    }

    private static void hidden(final JsonObject object) {
        assertEquals(object.size(), 2, "HiddenCard has only id and hidden");
        assertTrue(object.get("hidden").getAsBoolean());
        assertTrue(object.get("id").getAsString().matches("h-[0-9a-f]{12}"));
    }

    @Test
    public void opponentHandsArePrivate() {
        final ScenarioGame scenario = new ScenarioGame();
        final String[] names = {"Aetherling", "Abominable Treefolk", "Borborygmos Enraged", "Cryptic Serpent"};
        final Card[] cards = new Card[4];
        for (int seat = 0; seat < 4; seat++) {
            cards[seat] = scenario.addCardToZone(names[seat], seat, ZoneType.Hand);
        }
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            for (int owner = 0; owner < 4; owner++) {
                final JsonObject object = mappedCard(scenario, states[seat], seat, owner, "hand", cards[owner]);
                if (seat == owner) {
                    visible(object, cards[owner]);
                } else {
                    hidden(object);
                    noIdentity(states[seat], cards[owner]);
                    assertFalse(scenario.ids[seat].resolve(cards[owner].getId()).isPresent());
                }
            }
        }
    }

    @Test
    public void manifestAndMorphKeepOnlyPublicCharacteristicsForOpponents() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card original = scenario.addCardToZone("Colossal Dreadmaw", 0, ZoneType.Hand);
        final SpellAbility cause = new SpellAbility.EmptySa(original);
        // getFacedownImageKey reads the manifesting ability's card state for its edition.
        cause.setCardState(original.getCurrentState());
        final Card manifest = original.manifest(scenario.player(0), cause, new HashMap<>());
        assertNotNull(manifest);
        assertTrue(manifest.isManifested());
        final Card morph = scenario.addCardToZone("Willbender", 0, ZoneType.Battlefield);
        assertTrue(morph.turnFaceDown());
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            for (final Card card : List.of(manifest, morph)) {
                final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "battlefield", card);
                assertTrue(object.get("faceDown").getAsBoolean());
                assertEquals(object.get("power").getAsInt(), 2);
                assertEquals(object.get("toughness").getAsInt(), 2);
                assertFalse(object.get("tapped").getAsBoolean());
                assertTrue(object.has("counters"));
                assertTrue(object.has("blockingIds"));
                if (seat == 0) {
                    visible(object, card);
                } else {
                    assertTrue(object.get("id").getAsString().matches("h-[0-9a-f]{12}"));
                    assertFalse(object.has("name") || object.has("set") || object.has("num"));
                    noIdentity(states[seat], card);
                    assertFalse(scenario.ids[seat].resolve(card.getId()).isPresent());
                }
            }
        }
    }

    @Test
    public void faceDownStackSpellHasGenericTextForOpponents() {
        final ScenarioGame scenario = new ScenarioGame();
        Card card = scenario.addCardToZone("Vesuvan Shapeshifter", 0, ZoneType.Hand);
        SpellAbility morph = null;
        for (final SpellAbility ability : card.getSpellAbilities()) {
            if (ability.isCastFaceDown()) {
                morph = ability;
                break;
            }
        }
        assertNotNull(morph, "The real morph casting ability exists");
        morph.setActivatingPlayer(scenario.player(0));
        card = scenario.game.getAction().moveToStack(card, morph);
        // moveToStack applies the morph casting state while creating the new zone object.
        assertTrue(card.isFaceDown());
        morph.setHostCard(card);
        // Include identity in the source text to prove the entire description is replaced,
        // not merely a card-name substring. All setters and stack insertion are public APIs.
        morph.setStackDescription(card.getPaperCard().getName() + " / " + card.getPaperCard().getEdition()
                + " / " + card.getPaperCard().getCollectorNumber());
        scenario.game.getStack().add(morph);
        assertTrue(card.isFaceDown());
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            assertEquals(states[seat].getAsJsonArray("stack").size(), 1);
            final JsonObject item = states[seat].getAsJsonArray("stack").get(0).getAsJsonObject();
            if (seat == 0) {
                assertEquals(item.get("sourceCardId").getAsInt(), card.getId());
                assertTrue(item.get("text").getAsString().contains(card.getPaperCard().getName()));
            } else {
                assertEquals(item.get("text").getAsString(), "Face-down spell");
                assertTrue(item.get("sourceCardId").getAsString().matches("h-[0-9a-f]{12}"));
                assertFalse(scenario.ids[seat].resolve(card.getId()).isPresent());
                noIdentity(states[seat], card);
            }
        }
    }

    @Test
    public void revealedShuffledAndRedrawnCardGetsFreshOpaqueIds() {
        final ScenarioGame scenario = new ScenarioGame();
        Card card = scenario.addCardToZone("Consecrated Sphinx", 0, ZoneType.Hand);
        final List<List<JsonElement>> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(new ArrayList<>());
        }
        JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "hand", card);
            seen.get(seat).add(object.get("id"));
            if (seat != 0) {
                hidden(object);
                noIdentity(states[seat], card);
            }
        }
        card.addMayLookTemp(scenario.player(1));
        states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "hand", card);
            seen.get(seat).add(object.get("id"));
            if (seat <= 1) {
                visible(object, card);
            } else {
                hidden(object);
                noIdentity(states[seat], card);
            }
        }
        card.removeMayLookTemp(scenario.player(1));
        card = scenario.game.getAction().moveToLibrary(card, null);
        scenario.addCardToZone("Plains", 0, ZoneType.Library);
        scenario.player(0).shuffle(null);
        states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject library = states[seat].getAsJsonArray("players").get(0).getAsJsonObject()
                    .getAsJsonObject("zones").getAsJsonObject("library");
            assertEquals(library.size(), 1);
            assertEquals(library.get("count").getAsInt(), 2);
            noIdentity(states[seat], card);
            assertFalse(scenario.ids[seat].resolve(card.getId()).isPresent());
            for (final JsonElement ref : seen.get(seat)) {
                if (ref.getAsJsonPrimitive().isString()) {
                    assertFalse(scenario.ids[seat].resolve(ref.getAsString()).isPresent());
                }
            }
        }
        // Draw both shuffled cards so this tests a real shuffle/draw, without fixing its order.
        final int targetId = card.getId();
        for (final Card drawn : scenario.player(0).drawCards(2)) {
            if (drawn.getId() == targetId) {
                card = drawn;
            }
        }
        assertEquals(card.getZone().getZoneType(), ZoneType.Hand);
        states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "hand", card);
            if (seat == 0) {
                visible(object, card);
            } else {
                hidden(object);
                noIdentity(states[seat], card);
                for (final JsonElement old : seen.get(seat)) {
                    assertNotEquals(object.get("id"), old, "Redraw cannot be linked to any earlier reference");
                }
            }
        }
    }

    @Test
    public void bouncedKnownCardBecomesOpaqueInOpponentsHands() {
        final ScenarioGame scenario = new ScenarioGame();
        Card card = scenario.addCardToZone("Tarmogoyf", 0, ZoneType.Battlefield);
        JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            visible(mappedCard(scenario, states[seat], seat, 0, "battlefield", card), card);
            assertTrue(scenario.ids[seat].resolve(card.getId()).isPresent());
        }
        final int oldId = card.getId();
        card = scenario.game.getAction().moveToHand(card, null);
        states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "hand", card);
            if (seat == 0) {
                visible(object, card);
            } else {
                hidden(object);
                noIdentity(states[seat], card);
                assertNotEquals(object.get("id").getAsString(), Integer.toString(oldId));
                assertFalse(scenario.ids[seat].resolve(oldId).isPresent());
            }
        }
    }

    @Test
    public void temporaryLookPermissionIsSeatSpecificAndRevocable() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card card = scenario.addCardToZone("Emrakul, the Promised End", 0, ZoneType.Hand);
        card.addMayLookTemp(scenario.player(1));
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            assertEquals(card.getView().mayPlayerLook(scenario.player(seat).getView()), seat == 1);
            final JsonObject object = mappedCard(scenario, states[seat], seat, 0, "hand", card);
            if (seat <= 1) {
                visible(object, card);
            } else {
                hidden(object);
                noIdentity(states[seat], card);
            }
        }
        card.removeMayLookTemp(scenario.player(1));
        final JsonObject[] revoked = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject object = mappedCard(scenario, revoked[seat], seat, 0, "hand", card);
            if (seat == 0) {
                visible(object, card);
            } else {
                hidden(object);
                noIdentity(revoked[seat], card);
                assertFalse(scenario.ids[seat].resolve(card.getId()).isPresent());
            }
        }
    }
}
