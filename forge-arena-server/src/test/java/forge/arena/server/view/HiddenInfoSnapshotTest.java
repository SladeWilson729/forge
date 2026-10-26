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

import static forge.arena.server.view.HiddenInfoAssertions.noIdentity;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class HiddenInfoSnapshotTest {
    @Test
    public void eventStubsPreserveDrawAndFaceDownPrivacy() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card libraryCard = scenario.addCardToZone("Consecrated Sphinx", 1, ZoneType.Library);
        final var from = libraryCard.getZone().getView();
        final Card drawn = scenario.player(1).drawCards(1).get(0);
        final Card down = scenario.addCardToZone("Willbender", 1, ZoneType.Battlefield);
        assertTrue(down.turnFaceDown());
        for (int seat = 0; seat < 4; seat++) {
            final SeatViewSerializer serializer = new SeatViewSerializer();
            final JsonObject state = serializer.buildState(scenario.game, scenario.player(seat).getView(), scenario.ids[seat], 7);
            ScenarioGame.validate(state, "state");
            final var pump = new forge.arena.server.EventPump(scenario.player(seat).getView(), scenario.ids[seat]);
            pump.snapshot(scenario.game, state, serializer.cardStubs());
            final var payloads = pump.batch(List.of(
                    new forge.game.event.GameEventCardChangeZone(drawn.getView(), from, drawn.getZone().getView()),
                    new forge.game.event.GameEventCardChangeZone(down.getView(), null, down.getZone().getView())), 7);
            assertEquals(payloads.size(), 2);
            for (int i = 0; i < payloads.size(); i++) {
                JsonObject payload = payloads.get(i); ScenarioGame.validate(payload, "event");
                assertEquals(payload.getAsJsonArray("cardStubs").size(), 1);
                assertEquals(payload.getAsJsonArray("cardStubs").get(0).getAsJsonObject().get("id"),
                        payload.getAsJsonArray("cardIds").get(0));
                if (seat != 1) noIdentity(payload, i == 0 ? drawn : down);
            }
        }
    }

    @Test
    public void libraryRevealIsSeatScopedAndReferencesExpire() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card card = scenario.addCardToZone("Consecrated Sphinx", 1, ZoneType.Library);
        final List<forge.arena.server.SeatWriter> writers = new ArrayList<>();
        final List<forge.arena.server.JsonSeatRemote> remotes = new ArrayList<>();
        try {
            for (int seat = 0; seat < 4; seat++) {
                var writer = new forge.arena.server.SeatWriter(seat); writers.add(writer);
                var remote = new forge.arena.server.JsonSeatRemote(scenario.game, scenario.player(seat).getView(), writer);
                remotes.add(remote);
                remote.send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.setGameView, scenario.game.getView()));
            }
            // Forge's tempShowCards adds this exact may-look permission before the GUI call.
            card.addMayLookTemp(scenario.player(0));
            remotes.get(0).send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.showRevealedCards, List.of(card.getView())));
            for (int seat = 0; seat < 4; seat++) {
                JsonObject state = forge.arena.server.EventTestSupport.state(remotes.get(seat));
                ScenarioGame.validate(state, "state");
                if (seat == 0) assertEquals(state.getAsJsonArray("revealed").get(0).getAsJsonObject().get("name").getAsString(), card.getName());
                else { assertFalse(state.has("revealed")); noIdentity(state, card); }
                assertTrue(forge.arena.server.EventTestSupport.disagreements(remotes.get(seat)).isEmpty());
            }
            var ref = forge.arena.server.EventTestSupport.state(remotes.get(0)).getAsJsonArray("revealed")
                    .get(0).getAsJsonObject().get("id").getAsString();
            assertTrue(forge.arena.server.EventTestSupport.ids(remotes.get(0)).resolve(ref).isPresent());
            remotes.get(0).send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.hideRevealedCards));
            assertFalse(forge.arena.server.EventTestSupport.state(remotes.get(0)).has("revealed"));
            assertTrue(forge.arena.server.EventTestSupport.ids(remotes.get(0)).resolve(ref).isEmpty());
            remotes.get(0).send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.showRevealedCards, List.of(card.getView())));
            var replacementRef = forge.arena.server.EventTestSupport.state(remotes.get(0)).getAsJsonArray("revealed")
                    .get(0).getAsJsonObject().get("id").getAsString();
            assertNotEquals(replacementRef, ref);
            forge.arena.server.EventTestSupport.replacement(remotes.get(0));
            assertTrue(forge.arena.server.EventTestSupport.ids(remotes.get(0)).resolve(replacementRef).isEmpty());
            assertFalse(forge.arena.server.EventTestSupport.state(remotes.get(0)).has("revealed"));
            remotes.get(0).send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.showRevealedCards, List.of(card.getView())));
            String finalRef = forge.arena.server.EventTestSupport.state(remotes.get(0)).getAsJsonArray("revealed")
                    .get(0).getAsJsonObject().get("id").getAsString();
            scenario.game.setGameOver(forge.game.GameEndReason.Draw);
            remotes.get(0).send(new forge.gamemodes.net.event.GuiGameEvent(forge.gamemodes.net.ProtocolMethod.setGameView, scenario.game.getView()));
            assertFalse(forge.arena.server.EventTestSupport.state(remotes.get(0)).has("revealed"));
            assertTrue(forge.arena.server.EventTestSupport.ids(remotes.get(0)).resolve(finalRef).isEmpty());
        } finally { writers.forEach(forge.arena.server.SeatWriter::close); }
    }
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

    private static void visible(final JsonObject object, final Card card) {
        assertEquals(object.get("id").getAsInt(), card.getId());
        assertEquals(object.get("name").getAsString(), card.getPaperCard().getName());
        assertEquals(object.get("types").getAsString(), (card.isFaceDown()
                ? card.getView().getAlternateState() : card.getView().getCurrentState()).getType().toString());
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
    public void staleCardViewsPreserveVisibilityWithoutLiveCards() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card hand = scenario.addCardToZone("Aetherling", 0, ZoneType.Hand);
        final Card battlefield = scenario.addCardToZone("Cryptic Serpent", 0, ZoneType.Battlefield);
        // Zone.removeAllCards(true) clears the live collection without events/view updates,
        // reproducing the stale views left behind when a player concedes.
        scenario.player(0).getZone(ZoneType.Hand).removeAllCards(true);
        scenario.player(0).getZone(ZoneType.Battlefield).removeAllCards(true);
        assertTrue(scenario.player(0).getView().getHand().contains(hand.getView()));
        assertTrue(scenario.player(0).getView().getBattlefield().contains(battlefield.getView()));
        for (final Card card : List.of(hand, battlefield)) {
            assertNull(scenario.game.findByView(card.getView()));
            assertNull(scenario.game.findById(card.getId()));
        }
        final JsonObject[] states = scenario.snapshots(); // Validates every seat against the schema.
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject publicCard = mappedCard(scenario, states[seat], seat, 0, "battlefield", battlefield);
            assertEquals(publicCard.get("id").getAsInt(), battlefield.getId());
            assertEquals(publicCard.get("name").getAsString(), battlefield.getName());
            assertEquals(publicCard.get("set").getAsString(), battlefield.getPaperCard().getEdition());
            for (final String liveOnly : List.of("num", "attackTarget", "blockingIds")) {
                assertFalse(publicCard.has(liveOnly), "Missing live card must omit " + liveOnly);
            }
            final JsonObject privateCard = mappedCard(scenario, states[seat], seat, 0, "hand", hand);
            if (seat == 0) {
                assertEquals(privateCard.get("id").getAsInt(), hand.getId());
                assertEquals(privateCard.get("name").getAsString(), hand.getName());
                assertEquals(privateCard.get("set").getAsString(), hand.getPaperCard().getEdition());
                for (final String liveOnly : List.of("num", "attackTarget", "blockingIds")) {
                    assertFalse(privateCard.has(liveOnly));
                }
            } else {
                hidden(privateCard);
                noIdentity(states[seat], hand);
                assertFalse(scenario.ids[seat].resolve(hand.getId()).isPresent());
            }
        }
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
                    assertEquals(object.get("types").getAsString(), card.getView().getCurrentState().getType().toString());
                    assertEquals(object.get("types").getAsString(), "Creature");
                    assertNotEquals(object.get("types").getAsString(), card.getView().getAlternateState().getType().toString(),
                            "Dinosaur/Human Wizard identity types must not leak through a face-down card");
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
