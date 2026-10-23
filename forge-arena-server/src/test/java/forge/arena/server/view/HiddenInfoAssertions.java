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
        scanIds(payload, realId, false);
    }

    private static void scanIds(final JsonElement value, final int realId, final boolean reference) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> {
                final String key = entry.getKey();
                scanIds(entry.getValue(), realId, reference || key.endsWith("id") || key.endsWith("Id")
                        || (key.endsWith("Ids") && entry.getValue().isJsonArray()));
            });
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(item -> scanIds(item, realId, reference));
        } else if (reference && value.isJsonPrimitive()) {
            assertFalse(value.getAsString().equals(Integer.toString(realId)), "Hidden real id leaked: " + realId);
        }
    }
}
