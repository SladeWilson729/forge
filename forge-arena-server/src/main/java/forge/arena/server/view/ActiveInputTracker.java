package forge.arena.server.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.input.*;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.trackable.TrackableObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Per-seat display bookkeeping. Retains copied values, never an Input or a live game graph. */
public final class ActiveInputTracker {
    private static final System.Logger LOG = System.getLogger(ActiveInputTracker.class.getName());
    // Specific classes precede their bases for the subclass pass (notably cards vs entities).
    private static final Map<Class<? extends Input>, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put(InputPassPriority.class, "priority");
        KINDS.put(InputAttack.class, "declareAttackers");
        KINDS.put(InputBlock.class, "declareBlockers");
        KINDS.put(InputSelectTargets.class, "target");
        KINDS.put(InputPayManaOfCostPayment.class, "payMana");
        KINDS.put(InputPayMana.class, "payMana");
        KINDS.put(InputSelectCardsFromList.class, "selectCards");
        KINDS.put(InputSelectEntitiesFromList.class, "selectEntities");
        KINDS.put(InputSelectCardsForConvokeOrImprovise.class, "convoke");
        KINDS.put(InputSelectStackItems.class, "selectStack");
        KINDS.put(InputConfirm.class, "confirm");
        KINDS.put(InputConfirmMulligan.class, "mulligan");
        KINDS.put(InputLondonMulligan.class, "mulligan");
        KINDS.put(InputChooseStartingHand.class, "mulligan");
        KINDS.put(InputLockUI.class, "waiting");
    }

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Set<Integer> omitted = ConcurrentHashMap.newKeySet();
    private final Consumer<String> diagnostic;
    private final JsonObject values = new JsonObject();
    private com.google.gson.JsonArray offered = new JsonArray();

    public synchronized void setOffered(JsonArray cards) { offered = cards.deepCopy(); }

    public ActiveInputTracker() {
        this(message -> LOG.log(System.Logger.Level.INFO, message));
    }

    ActiveInputTracker(final Consumer<String> diagnostic) {
        this.diagnostic = diagnostic;
        values.addProperty("kind", "waiting");
        values.addProperty("manaPoolOpen", false);
    }

    /** Exact class first, then known subclasses, finally Selection's generic fallback. */
    public String classify(final Input input) {
        final String name = input == null ? "<no input>" : input.getClass().getName();
        String kind = input == null ? "waiting" : KINDS.get(input.getClass());
        if (kind == null) {
            for (var entry : KINDS.entrySet()) {
                if (entry.getKey().isInstance(input)) {
                    kind = entry.getValue();
                    break;
                }
            }
        }
        if (kind == null) kind = "select";
        if (seen.add(name)) diagnostic.accept("[input] " + name + " -> " + kind);
        return kind;
    }

    /**
     * Decode the display's public payload before taking our leaf lock. A null kind means an
     * unexpected-thread display: retain the previous classification and legal defenders.
     * Only immutable ids are read from payload views; no view properties or live objects are read.
     */
    public void display(final GuiGameEvent event, final String kind,
                        final List<? extends TrackableObject> defenders) {
        final JsonObject update = new JsonObject();
        if (kind != null) {
            update.addProperty("kind", kind);
            if ("declareAttackers".equals(kind)) update.add("attackTargets", entities(defenders));
        }
        final Object[] args = event.getObjects();
        switch (event.getMethod()) {
            case showPromptMessage -> {
                update.addProperty("prompt", (String) args[1]);
                update.add("sourceCardId", args[2] == null ? null : new JsonPrimitive(((CardView) args[2]).getId()));
            }
            case updateButtons -> {
                update.addProperty("okLabel", (String) args[1]);
                update.addProperty("cancelLabel", (String) args[2]);
                final JsonObject buttons = new JsonObject();
                for (int i = 0; i < 2; i++) {
                    final JsonObject button = new JsonObject();
                    button.addProperty("enabled", (Boolean) args[3 + i]);
                    buttons.add(i == 0 ? "ok" : "cancel", button);
                }
                update.add("buttons", buttons);
            }
            case setSelection -> {
                final Selection selection = (Selection) args[0];
                final JsonArray cards = new JsonArray();
                selection.cards().forEach(card -> cards.add(card.getId()));
                update.add("selectable", cards);
                update.addProperty("min", selection.min());
                update.addProperty("max", selection.max());
                update.add("targets", entities(selection.targets()));
                update.add("chosen", entities(selection.chosen()));
            }
            case setWeaklySelectable -> {
                final Map<Integer, Integer> strengths = new LinkedHashMap<>();
                for (Object card : (Iterable<?>) args[0]) {
                    strengths.merge(((CardView) card).getId(), 1, (a, b) -> 2);
                }
                final JsonArray highlighted = new JsonArray();
                strengths.forEach((id, strength) -> {
                    final JsonObject item = new JsonObject();
                    item.addProperty("id", id);
                    item.addProperty("strength", strength);
                    highlighted.add(item);
                });
                update.add("highlighted", highlighted);
            }
            case clearWeaklySelectable -> update.add("highlighted", new JsonArray());
            case showManaPool -> update.addProperty("manaPoolOpen", true);
            case hideManaPool -> update.addProperty("manaPoolOpen", false);
            case awaitNextInput -> update.addProperty("kind", "waiting");
            default -> throw new IllegalArgumentException("Not an input display: " + event.getMethod());
        }
        synchronized (this) {
            update.entrySet().forEach(entry -> values.add(entry.getKey(), entry.getValue()));
        }
    }

    private static JsonArray entities(final List<? extends TrackableObject> entities) {
        final JsonArray refs = new JsonArray();
        for (TrackableObject entity : entities) {
            final String kind = entity instanceof CardView ? "card" : entity instanceof PlayerView ? "player"
                    : entity instanceof StackItemView ? "stack" : null;
            if (kind == null) throw new IllegalArgumentException("Unsupported Selection entity: " + entity.getClass());
            final JsonObject ref = new JsonObject();
            ref.addProperty("kind", kind);
            ref.addProperty("id", entity.getId());
            refs.add(ref);
        }
        return refs;
    }

    /** Map every card position using the latest COMPLETED snapshot, outside the tracker lock. */
    public JsonObject toJson(final IdMapper ids) {
        final JsonObject result;
        synchronized (this) {
            result = values.deepCopy();
            if (!offered.isEmpty()) result.add("offered", offered.deepCopy());
        }
        final String kind = result.get("kind").getAsString();
        if ("waiting".equals(kind)) {
            // Waiting exposes no stale actions or previous prompt. Keep the recorded display for
            // subsequent calls; no-input AI seat views use this same minimal representation.
            final JsonObject waiting = new JsonObject();
            waiting.addProperty("kind", "waiting");
            return waiting;
        }
        if (!"declareAttackers".equals(kind)) result.remove("attackTargets");
        if (result.has("sourceCardId")) {
            final JsonElement source = result.remove("sourceCardId");
            if (!source.isJsonNull()) {
                final JsonElement ref = cardRef(source.getAsInt(), ids);
                if (ref != null) result.add("sourceCardId", ref);
            }
        }
        for (String field : List.of("selectable", "highlighted", "targets", "chosen", "attackTargets")) {
            if (!result.has(field)) continue;
            final JsonArray mapped = new JsonArray();
            for (JsonElement element : result.getAsJsonArray(field)) {
                if (field.equals("selectable")) {
                    final JsonElement ref = cardRef(element.getAsInt(), ids);
                    if (ref != null) mapped.add(ref);
                } else {
                    final JsonObject ref = element.getAsJsonObject();
                    if (field.equals("highlighted") || "card".equals(ref.get("kind").getAsString())) {
                        final JsonElement id = cardRef(ref.get("id").getAsInt(), ids);
                        if (id == null) continue;
                        ref.add("id", id);
                    }
                    mapped.add(ref);
                }
            }
            result.add(field, mapped);
        }
        return result;
    }

    private JsonElement cardRef(final int id, final IdMapper ids) {
        final Object ref = ids.inputRef(id).orElse(null);
        if (ref instanceof Integer visible) return new JsonPrimitive(visible);
        if (ref instanceof String hidden) return new JsonPrimitive(hidden);
        if (omitted.add(id)) diagnostic.accept("[input] Omitting card " + id + " without a current ref; prompt-offered ids: task 1.7");
        return null;
    }
}
