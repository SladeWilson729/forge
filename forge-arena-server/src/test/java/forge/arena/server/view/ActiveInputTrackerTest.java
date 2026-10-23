package forge.arena.server.view;

import com.google.gson.JsonObject;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.input.InputBase;
import forge.gamemodes.match.input.InputLockUI;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class ActiveInputTrackerTest {
    private static final class UnlistedInput extends InputBase {
        UnlistedInput() { super(null); }
        @Override protected void showMessage() { }
        @Override public String getActivateAction(final Card card) { return null; }
    }

    @Test
    public void fallbackReferencesDuplicatesAndWaiting() {
        final List<String> diagnostics = new ArrayList<>();
        final ActiveInputTracker tracker = new ActiveInputTracker(diagnostics::add);
        final IdMapper ids = new IdMapper();
        ids.beginSnapshot();
        final Object hidden = ids.wireRef(42, "Hand", false, false);
        ids.wireRef(43, "Battlefield", false, true);
        ids.endSnapshot();
        final CardView card = new CardView(42, null);
        final CardView visible = new CardView(43, null);
        final CardView library = new CardView(44, null);
        final PlayerView player = new PlayerView(42, null); // Deliberate card/player id collision.
        final UnlistedInput unknown = new UnlistedInput();
        final String kind = tracker.classify(unknown);
        assertEquals(kind, "select");
        assertEquals(tracker.classify(unknown), "select");
        assertEquals(diagnostics.size(), 1, "Unknown class logged once");
        assertTrue(diagnostics.get(0).contains(UnlistedInput.class.getName()));
        tracker.display(new GuiGameEvent(ProtocolMethod.setSelection,
                new Selection(List.of(card, visible, library), 1, 2, List.of(player, card), List.of(card))), kind, List.of());
        tracker.display(new GuiGameEvent(ProtocolMethod.showPromptMessage, player, "Pick a card", card), kind, List.of());
        tracker.display(new GuiGameEvent(ProtocolMethod.updateButtons, player, "Choose", "Cancel", true, false, true), kind, List.of());
        tracker.display(new GuiGameEvent(ProtocolMethod.setWeaklySelectable, List.of(card, card, card, visible, library)), kind, List.of());
        tracker.display(new GuiGameEvent(ProtocolMethod.showManaPool, player), kind, List.of());
        final JsonObject json = tracker.toJson(ids);
        assertEquals(json.get("kind").getAsString(), "select");
        assertEquals(json.get("min").getAsInt(), 1);
        assertEquals(json.get("max").getAsInt(), 2);
        assertEquals(json.getAsJsonArray("selectable").size(), 2);
        assertEquals(json.getAsJsonArray("selectable").get(0).getAsString(), hidden);
        assertEquals(json.get("sourceCardId").getAsString(), hidden);
        assertEquals(json.getAsJsonArray("chosen").get(0).getAsJsonObject().get("id").getAsString(), hidden);
        assertEquals(json.getAsJsonArray("targets").get(0).getAsJsonObject().get("id").getAsInt(), 42);
        assertEquals(json.getAsJsonArray("targets").get(1).getAsJsonObject().get("id").getAsString(), hidden);
        assertEquals(json.getAsJsonArray("highlighted").get(0).getAsJsonObject().get("strength").getAsInt(), 2);
        assertEquals(json.getAsJsonArray("highlighted").size(), 2);
        assertTrue(json.get("manaPoolOpen").getAsBoolean());
        assertTrue(json.getAsJsonObject("buttons").getAsJsonObject("ok").get("enabled").getAsBoolean());
        validate(json);
        tracker.toJson(ids);
        assertEquals(diagnostics.stream().filter(line -> line.contains("Omitting card 44")).count(), 1L);
        // Null classification is the timer path: new prompt, same kind and Selection.
        tracker.display(new GuiGameEvent(ProtocolMethod.showPromptMessage, player, "Timer", null), null, List.of());
        assertEquals(tracker.toJson(ids).get("kind").getAsString(), "select");
        assertFalse(tracker.toJson(ids).has("sourceCardId"));
        tracker.display(new GuiGameEvent(ProtocolMethod.clearWeaklySelectable), null, List.of());
        tracker.display(new GuiGameEvent(ProtocolMethod.hideManaPool, player), null, List.of());
        assertTrue(tracker.toJson(ids).getAsJsonArray("highlighted").isEmpty());
        assertFalse(tracker.toJson(ids).get("manaPoolOpen").getAsBoolean());
        // An opaque ref retired by the next completed snapshot must not survive in input JSON.
        ids.beginSnapshot(); ids.endSnapshot();
        assertTrue(tracker.toJson(ids).getAsJsonArray("selectable").isEmpty());
        tracker.display(new GuiGameEvent(ProtocolMethod.awaitNextInput), kind, List.of());
        assertEquals(tracker.toJson(ids).toString(), "{\"kind\":\"waiting\"}");
        assertEquals(tracker.classify(new InputLockUI(null, null)), "waiting");
        assertEquals(tracker.classify(null), "waiting");
        validate(tracker.toJson(ids));
    }

    private static void validate(final JsonObject input) {
        final var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                SchemaLocation.of(Path.of("../protocol/schema.json").toAbsolutePath().normalize().toUri() + "#/$defs/ActiveInput"));
        assertTrue(schema.validate(input.toString(), InputFormat.JSON).isEmpty(), input.toString());
    }
}
