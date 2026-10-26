package forge.arena.server.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Per-seat detached payloads, built on the game thread or while it is parked (plan 2.2 rule 1).
 * No network work. The future SeatWriter assigns seq and sends stateSeq's snapshot before its
 * event; the future EventPump must finish that snapshot before calling zoneChange (task 1.8).
 */
public final class PayloadFilter {
    public JsonObject zoneChange(final GameEventCardChangeZone ev, final PlayerView seat,
                                 final IdMapper ids, final long stateSeq) {
        final JsonObject result = message("event");
        result.addProperty("kind", "zoneChange");
        result.addProperty("stateSeq", stateSeq);
        final JsonArray cards = new JsonArray();
        // The completed snapshot already made the seat's visibility decision. Do not issue IDs
        // here: cards absent from that snapshot (normally library cards) have no event reference.
        ids.currentRef(ev.card().getId()).ifPresent(ref -> cards.add(jsonRef(ref)));
        result.add("cardIds", cards);
        result.add("from", zone(ev.from()));
        result.add("to", zone(ev.to()));
        return result;
    }

    public JsonObject log(final GameLogEntry entry, final PlayerView seat, final Game game,
                          final IdMapper ids) {
        return log(entry, captureLogVisibility(game, seat));
    }

    /** Detached visibility data lets events-only packets redact without a live graph walk. */
    public record LogVisibility(Set<Integer> visibleIds, Set<String> privateNames) {
        public static LogVisibility empty() { return new LogVisibility(Set.of(), Set.of()); }
    }

    public JsonObject log(final GameLogEntry entry, final LogVisibility visibility) {
        final JsonObject result = message("log");
        // Provenance wins even if a public copy has the same name: this entry describes a
        // particular hidden object and its text may include other private details.
        final boolean hiddenSource = entry.sourceCard() != null
                && !visibility.visibleIds().contains(entry.sourceCard().getId());
        // Many GameLogFormatter paths have no sourceCard (tutors, random logs, combat).
        // Scan names as a fallback, but a name also visible elsewhere is already public.
        final boolean redact = hiddenSource || visibility.privateNames().stream().anyMatch(entry.message()::contains);
        result.addProperty("text", redact ? genericText(entry.type()) : entry.message());
        result.addProperty("entryType", entry.type().name());
        return result;
    }

    public boolean promptFor(final int ownerSeat, final int seat) {
        return ownerSeat == seat;
    }

    /**
     * Task 1.6's typed option codec must use this for EVERY CardRef, including nested options,
     * after promptFor. No arbitrary JSON key guessing or labels containing card identities.
     * Missing references are omitted/rejected, never replaced with a raw Forge ID. Prompt-only
     * offered references not present in the snapshot are scoped by PromptCodec (task 1.6).
     */
    public Optional<JsonElement> promptCardRef(final int realId, final IdMapper ids) {
        return ids.currentRef(realId).map(PayloadFilter::jsonRef);
    }

    /** Called only alongside a full snapshot, on its Forge-safe caller. */
    public LogVisibility captureLogVisibility(final Game game, final PlayerView seat) {
        final Set<String> hidden = new HashSet<>();
        final Set<String> visible = new HashSet<>();
        final Set<Integer> visibleIds = new HashSet<>();
        final EnumSet<ZoneType> zones = EnumSet.allOf(ZoneType.class);
        // Never enumerate decklists: a public name can coincidentally occur in a library.
        // Flashback is a virtual zone whose accessor can walk cards in other zones, including it.
        zones.remove(ZoneType.Library);
        zones.remove(ZoneType.Flashback);
        for (final ZoneType zone : zones) {
            for (final Card card : game.getCardsIncludePhasingIn(zone)) {
                final CardView view = card.getView();
                final CardVisibility visibility = CardVisibility.forSeat(view, seat);
                if (visibility.identityVisible()) visibleIds.add(view.getId());
                final Set<String> names = visibility.identityVisible() ? visible : hidden;
                final CardView.CardStateView identity = visibility.faceDown()
                        ? view.getAlternateState() : view.getCurrentState();
                if (identity != null && !identity.getName().isBlank()) {
                    names.add(identity.getName());
                }
            }
        }
        hidden.removeAll(visible);
        return new LogVisibility(Set.copyOf(visibleIds), Set.copyOf(hidden));
    }

    private static String genericText(final GameLogEntryType type) {
        return switch (type) {
            case ZONE_CHANGE -> "A card changed zones";
            case STACK_ADD -> "A card was added to the stack";
            case STACK_RESOLVE -> "An action involving a card resolved";
            default -> "An action involving a card occurred";
        };
    }

    private static JsonObject message(final String type) {
        final JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("seq", 0); // SeatWriter supplies the ordered sequence.
        return result;
    }

    private static JsonElement zone(final ZoneView zone) {
        if (zone == null) {
            return JsonNull.INSTANCE;
        }
        final JsonObject result = new JsonObject();
        // Retain schema.json's ZoneRef field names; final event catalog is task 1.8.
        result.addProperty("zoneType", zone.zoneType().name());
        result.add("player", zone.player() == null ? JsonNull.INSTANCE : new JsonPrimitive(zone.player().getId()));
        return result;
    }

    private static JsonElement jsonRef(final Object ref) {
        return ref instanceof Integer id ? new JsonPrimitive(id) : new JsonPrimitive((String) ref);
    }
}
