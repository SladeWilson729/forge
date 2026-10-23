package forge.arena.server.view;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.card.Card;

import java.util.List;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

final class HiddenInfoAssertions {
    private HiddenInfoAssertions() { }

    static void noIdentity(final JsonObject payload, final Card card) {
        final String json = ScenarioGame.JSON.toJson(payload);
        final var paper = card.getPaperCard();
        assertNotNull(paper);
        for (final String secret : List.of(paper.getName(), paper.getEdition(), paper.getCollectorNumber())) {
            assertFalse(secret.isBlank(), "Leak scan must have a meaningful sentinel");
            assertFalse(json.contains(secret), "Hidden identity leaked: " + secret + " in " + json);
        }
        noRealId(payload, card.getId());
    }

    static void noRealId(final JsonElement payload, final int realId) {
        scanIds(payload, realId, false, "$", payload);
    }

    private static void scanIds(final JsonElement value, final int realId, final boolean reference,
                                final String path, final JsonElement payload) {
        if (value.isJsonObject()) {
            final JsonObject object = value.getAsJsonObject();
            // Player and stack ids have separate namespaces and may equal a hidden card id.
            // All three card variants have hidden or faceDown; EntityRef tags its namespace.
            final boolean cardObject = object.has("kind")
                    ? "card".equals(object.get("kind").getAsString())
                    : object.has("hidden") || object.has("faceDown");
            object.entrySet().forEach(entry -> {
                final String key = entry.getKey();
                final boolean cardReference = switch (key) {
                    case "id" -> cardObject;
                    case "cardRef", "cardId", "sourceCardId", "commanderId", "cardIds", "blockingIds" -> true;
                    default -> false;
                };
                scanIds(entry.getValue(), realId, cardReference, path + "." + key, payload);
            });
        } else if (value.isJsonArray()) {
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                scanIds(value.getAsJsonArray().get(index), realId, reference, path + "[" + index + "]", payload);
            }
        } else if (reference && value.isJsonPrimitive()) {
            assertFalse(value.getAsString().equals(Integer.toString(realId)),
                    "Hidden real id leaked: " + realId + " at " + path + " in " + payload);
        }
    }
}
