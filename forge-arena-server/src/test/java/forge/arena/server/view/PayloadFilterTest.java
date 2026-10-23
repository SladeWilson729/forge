package forge.arena.server.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

import static forge.arena.server.view.HiddenInfoAssertions.noIdentity;
import static forge.arena.server.view.HiddenInfoAssertions.noRealId;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class PayloadFilterTest {
    private final PayloadFilter filter = new PayloadFilter();

    @BeforeClass
    public void initialize() {
        ScenarioGame.initialize();
    }

    private JsonObject log(final ScenarioGame scenario, final int seat, final GameLogEntry entry) {
        final JsonObject result = filter.log(entry, scenario.player(seat).getView(), scenario.game, scenario.ids[seat]);
        ScenarioGame.validate(result, "log");
        assertEquals(result.get("seq").getAsLong(), 0L);
        assertEquals(result.get("entryType").getAsString(), entry.type().name());
        return result;
    }

    private JsonObject event(final ScenarioGame scenario, final int seat, final GameEventCardChangeZone change,
                             final JsonObject state) {
        final JsonObject result = filter.zoneChange(change, scenario.player(seat).getView(),
                scenario.ids[seat], state.get("seq").getAsLong());
        ScenarioGame.validate(result, "event");
        assertEquals(result.get("kind").getAsString(), "zoneChange");
        assertEquals(result.get("seq").getAsLong(), 0L);
        assertEquals(result.get("stateSeq"), state.get("seq"));
        return result;
    }

    private static JsonObject cardIn(final JsonObject state, final int owner, final String zone,
                                     final JsonElement ref) {
        for (final JsonElement card : state.getAsJsonArray("players").get(owner).getAsJsonObject()
                .getAsJsonObject("zones").getAsJsonArray(zone)) {
            if (card.getAsJsonObject().get("id").equals(ref)) {
                return card.getAsJsonObject();
            }
        }
        throw new AssertionError("Missing card reference " + ref);
    }

    @Test
    public void drawEventsUseTheCompletedSnapshotsReference() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card original = scenario.addCardToZone("Consecrated Sphinx", 1, ZoneType.Library);
        final ZoneView library = original.getZone().getView();
        scenario.snapshots();
        final Card drawn = scenario.player(1).drawCards(1).get(0);
        assertEquals(drawn.getId(), original.getId());
        final GameEventCardChangeZone change = new GameEventCardChangeZone(drawn.getView(), library,
                drawn.getZone().getView());
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject payload = event(scenario, seat, change, states[seat]);
            final JsonArray cards = payload.getAsJsonArray("cardIds");
            assertEquals(cards.size(), 1);
            assertEquals(cardIn(states[seat], 1, "hand", cards.get(0)).get("id"), cards.get(0));
            assertEquals(payload.getAsJsonObject("from").get("zoneType").getAsString(), "Library");
            assertEquals(payload.getAsJsonObject("to").get("zoneType").getAsString(), "Hand");
            assertEquals(payload.getAsJsonObject("to").get("player").getAsInt(), scenario.player(1).getId());
            if (seat == 1) {
                assertEquals(cards.get(0).getAsInt(), drawn.getId());
            } else {
                assertTrue(cards.get(0).getAsString().matches("h-[0-9a-f]{12}"));
                noIdentity(payload, drawn);
                noIdentity(states[seat], drawn);
            }
        }

        final ZoneView hand = drawn.getZone().getView();
        final Card returned = scenario.game.getAction().moveToLibrary(drawn, null);
        final JsonObject[] after = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            final JsonObject payload = event(scenario, seat,
                    new GameEventCardChangeZone(returned.getView(), hand, returned.getZone().getView()), after[seat]);
            assertTrue(payload.getAsJsonArray("cardIds").isEmpty(), "No event ID for a library card");
            noIdentity(payload, returned);
        }
    }

    @Test
    public void promptsAreOwnerOnlyAndEveryOptionReferenceUsesTheReceivingMapper() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card card = scenario.addCardToZone("Emrakul, the Promised End", 1, ZoneType.Hand);
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            for (int owner = 0; owner < 4; owner++) {
                assertEquals(filter.promptFor(owner, seat), owner == seat);
            }
            // Unit-level option construction only; nonowners would never receive the prompt.
            final JsonObject option = new JsonObject();
            option.add("cardId", filter.promptCardRef(card.getId(), scenario.ids[seat]).orElseThrow());
            assertEquals(cardIn(states[seat], 1, "hand", option.get("cardId")).get("id"), option.get("cardId"));
            assertTrue(filter.promptCardRef(Integer.MAX_VALUE, scenario.ids[seat]).isEmpty());
            if (seat == 1) {
                assertEquals(option.get("cardId").getAsInt(), card.getId());
            } else {
                noIdentity(option, card);
            }
        }
    }

    @Test
    public void faceDownExileRequiresLookPermissionAndPlotIsPublic() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card foretold = scenario.addCardToZone("Doomskar", 1, ZoneType.Exile);
        final Card hideaway = scenario.addCardToZone("Worldspine Wurm", 1, ZoneType.Exile);
        final Card unpermitted = scenario.addCardToZone("Progenitus", 1, ZoneType.Exile);
        final Card plotted = scenario.addCardToZone("Raven of Fell Omens", 1, ZoneType.Exile);
        assertTrue(foretold.turnFaceDown(true));
        foretold.setForetold(true);
        assertTrue(hideaway.turnFaceDown(true));
        assertTrue(unpermitted.turnFaceDown(true));
        // Match CardFactoryUtil's foretell and ChangeZoneEffect's WithMayLook/ExileFaceDown.
        foretold.addMayLookFaceDownExile(scenario.player(1));
        hideaway.addMayLookFaceDownExile(scenario.player(1));
        plotted.setPlotted(true); // Plot never turns the card face down (CardFactoryUtil).
        assertFalse(plotted.isFaceDown());
        assertFalse(unpermitted.getView().canBeShownTo(scenario.player(1).getView()), "Ownership alone is insufficient");
        assertFalse(unpermitted.getView().canFaceDownBeShownTo(scenario.player(1).getView()));
        final JsonObject[] states = scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            for (final Card card : new Card[] {foretold, hideaway, unpermitted, plotted}) {
                final boolean visible = card == plotted || (seat == 1 && card != unpermitted);
                assertEquals(CardVisibility.forSeat(card.getView(), scenario.player(seat).getView()).identityVisible(), visible);
                final JsonElement ref = filter.promptCardRef(card.getId(), scenario.ids[seat]).orElseThrow();
                final JsonObject encoded = cardIn(states[seat], 1, "exile", ref);
                final GameLogEntry entry = new GameLogEntry(GameLogEntryType.ZONE_CHANGE,
                        "Exiled " + card.getPaperCard().getName(), card.getView());
                final JsonObject logged = log(scenario, seat, entry);
                if (visible) {
                    assertEquals(encoded.get("id").getAsInt(), card.getId());
                    assertEquals(encoded.get("name").getAsString(), card.getPaperCard().getName());
                    assertEquals(logged.get("text").getAsString(), entry.message());
                } else {
                    assertEquals(encoded.size(), 2);
                    assertTrue(encoded.get("hidden").getAsBoolean());
                    noIdentity(states[seat], card);
                    noIdentity(logged, card);
                    // The source-less fallback must know the real face, not the anonymous face.
                    noIdentity(log(scenario, seat, new GameLogEntry(entry.type(), entry.message(), null)), card);
                }
            }
        }
    }

    @Test
    public void hiddenSourceIsRedactedForEveryLogTypeEvenWithAPublicCopy() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card secret = scenario.addCardToZone("Cryptic Serpent", 1, ZoneType.Hand);
        scenario.addCardToZone("Cryptic Serpent", 2, ZoneType.Battlefield);
        scenario.snapshots();
        for (final GameLogEntryType type : GameLogEntryType.values()) {
            final GameLogEntry entry = new GameLogEntry(type, "Action: " + secret.getPaperCard().getName(), secret.getView());
            for (int seat = 0; seat < 4; seat++) {
                final JsonObject payload = log(scenario, seat, entry);
                if (seat == 1) {
                    assertEquals(payload.get("text").getAsString(), entry.message());
                } else {
                    noIdentity(payload, secret);
                    assertEquals(payload.get("text").getAsString(), generic(type));
                }
            }
        }
    }

    @Test
    public void sourceLessTutorNamesAreRedactedForEveryLogType() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card libraryCard = scenario.addCardToZone("Borborygmos Enraged", 1, ZoneType.Library);
        final Card secret = scenario.game.getAction().moveToHand(libraryCard, null);
        scenario.snapshots();
        for (final GameLogEntryType type : GameLogEntryType.values()) {
            final GameLogEntry entry = new GameLogEntry(type, "Tutored " + secret.getPaperCard().getName() + " into hand", null);
            for (int seat = 0; seat < 4; seat++) {
                final JsonObject payload = log(scenario, seat, entry);
                if (seat == 1) {
                    assertEquals(payload.get("text").getAsString(), entry.message());
                } else {
                    noIdentity(payload, secret);
                    assertEquals(payload.get("text").getAsString(), generic(type));
                }
            }
        }
    }

    @Test
    public void publicNamesAreKeptAndLibrariesAreNotScanned() {
        final ScenarioGame scenario = new ScenarioGame();
        final Card secret = scenario.addCardToZone("Aetherling", 1, ZoneType.Hand);
        scenario.addCardToZone("Aetherling", 2, ZoneType.Battlefield);
        final Card library = scenario.addCardToZone("Abominable Treefolk", 1, ZoneType.Library);
        scenario.snapshots();
        for (int seat = 0; seat < 4; seat++) {
            for (final Card named : new Card[] {secret, library}) {
                final GameLogEntry entry = new GameLogEntry(GameLogEntryType.STACK_RESOLVE,
                        "Named " + named.getPaperCard().getName(), null);
                assertEquals(log(scenario, seat, entry).get("text").getAsString(), entry.message());
            }
        }
    }

    @Test
    public void leakScannerRejectsNestedReferencesAndArrays() {
        for (final String leak : new String[] {"{\"id\":12345,\"hidden\":true}", "{\"sourceCardId\":12345}",
                "{\"id\":12345,\"faceDown\":false}", "{\"commanderId\":12345}",
                "{\"target\":{\"kind\":\"card\",\"id\":12345}}", "{\"cardIds\":[12345]}",
                "{\"blockingIds\":[[12345]]}", "{\"nested\":[{\"cardId\":\"12345\"}]}"}) {
            assertThrows(AssertionError.class, () -> noRealId(JsonParser.parseString(leak), 12345));
        }
        noRealId(JsonParser.parseString("{\"amount\":12345,\"id\":\"h-123456789abc\",\"cardIds\":[]}"), 12345);
        for (final String key : List.of("stackId", "controllerId", "ownerId", "playerId", "activePlayerId",
                "priorityPlayerId", "monarchId", "initiativeId")) {
            noRealId(JsonParser.parseString("{\"" + key + "\":12345}"), 12345);
        }
        noRealId(JsonParser.parseString("{\"players\":[{\"id\":12345,\"name\":\"Seat 0\"}],"
                + "\"targets\":[{\"kind\":\"player\",\"id\":12345},{\"kind\":\"stack\",\"id\":12345}]}"), 12345);
    }

    private static String generic(final GameLogEntryType type) {
        return switch (type) {
            case ZONE_CHANGE -> "A hidden card changed zones";
            case STACK_ADD -> "A hidden card was added to the stack";
            case STACK_RESOLVE -> "An action involving a hidden card resolved";
            default -> "An action involving a hidden card occurred";
        };
    }
}
