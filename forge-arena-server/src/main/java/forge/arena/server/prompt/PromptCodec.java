package forge.arena.server.prompt;

import com.google.gson.*;
import forge.arena.server.view.IdMapper;
import forge.arena.server.view.CardVisibility;
import forge.arena.server.view.PayloadFilter;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerView;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.interfaces.IGuiGame.OrderResult;
import forge.util.FSerializableFunction;

import java.security.SecureRandom;
import java.util.*;

/** Encode on the Forge caller. Decode/defaults use only copied metadata and opaque option slots. */
public final class PromptCodec {
    public record Context(Game game, PlayerView seat, IdMapper ids) { }
    public static final class Prompt {
        private final ProtocolMethod method;
        private final JsonObject json;
        private final List<Object> objects;
        private final JsonElement defaultResult;
        private Prompt(ProtocolMethod method, JsonObject json, List<Object> objects, JsonElement defaultResult) {
            this.method = method;
            this.json = json.deepCopy();
            this.objects = Collections.unmodifiableList(new ArrayList<>(objects)); // combat defender key is null
            this.defaultResult = defaultResult.deepCopy();
        }
        public int requestId() { return json.get("requestId").getAsInt(); }
        public JsonObject json() { return json.deepCopy(); }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    @SuppressWarnings("unchecked")
    public Prompt encode(final GuiGameEvent event, final Context context) {
        final Object[] a = event.getObjects();
        final JsonObject p = new JsonObject();
        p.addProperty("type", "prompt"); p.addProperty("seq", 0); p.addProperty("requestId", event.getId());
        p.addProperty("title", ""); p.addProperty("min", 0); p.addProperty("max", 0);
        final List<Object> choices = new ArrayList<>();
        final Map<Integer, JsonElement> refs = new HashMap<>();
        FSerializableFunction<Object, String> display = null;
        CardView source = null;
        DelayedReveal reveal = null;
        JsonElement defaultResult = JsonNull.INSTANCE;
        switch (event.getMethod()) {
            case getChoices -> {
                kind(p, "choose", a[0]); choices.addAll((List<?>) a[3]);
                // AbstractGuiGame.reveal uses -1/-1: show the list, then acknowledge.
                final boolean revealOnly = (int) a[1] == -1;
                p.addProperty("revealOnly", revealOnly);
                bounds(p, revealOnly ? 0 : (int) a[1], revealOnly ? 0 : (int) a[2], choices.size());
                display = (FSerializableFunction<Object, String>) a[5];
                p.add("selected", indexes(choices, (Iterable<?>) a[4]));
            }
            case chooseSingleEntityForEffect -> {
                kind(p, "chooseEntities", a[0]); choices.addAll((List<?>) a[1]);
                reveal = (DelayedReveal) a[2];
                p.addProperty("single", true); p.addProperty("optional", (boolean) a[3]);
                bounds(p, (boolean) a[3] ? 0 : 1, 1, choices.size());
            }
            case chooseEntitiesForEffect -> {
                kind(p, "chooseEntities", a[0]); choices.addAll((List<?>) a[1]);
                reveal = (DelayedReveal) a[4];
                p.addProperty("single", false); bounds(p, (int) a[2], (int) a[3], choices.size());
            }
            case order -> {
                kind(p, "order", a[0]); p.addProperty("top", (String) a[1]);
                final List<?> src = (List<?>) a[4], dest = (List<?>) a[5];
                if (src != null) choices.addAll(src);
                final int sourceSize = choices.size();
                if (dest != null) choices.addAll(dest);
                p.add("source", range(0, sourceSize)); p.add("destination", range(sourceSize, choices.size()));
                final int remainingMin = Math.max(0, (int) a[2]);
                final int remainingMax = (int) a[3] < 0 ? choices.size() : (int) a[3];
                p.addProperty("remainingMin", remainingMin); p.addProperty("remainingMax", remainingMax);
                bounds(p, Math.max(0, choices.size() - remainingMax), choices.size() - remainingMin, choices.size());
                source = (CardView) a[6];
                p.addProperty("sideboardingMode", (boolean) a[7]); p.addProperty("rememberAllowed", (boolean) a[8]);
                // DualListBox returns DESTINATION; limits constrain the remaining SOURCE.
                // Null-destination full ordering keeps source order. Prepopulated destinations
                // use the smallest legal destination, preserving its order before adding source.
                final List<Integer> selected = new ArrayList<>();
                if (dest == null) {
                    for (int i = 0; i < p.get("max").getAsInt(); i++) selected.add(i);
                } else {
                    for (int i = sourceSize; i < choices.size() && selected.size() < p.get("min").getAsInt(); i++) selected.add(i);
                    for (int i = 0; selected.size() < p.get("min").getAsInt(); i++) selected.add(i);
                }
                final JsonObject result = new JsonObject(); result.add("ordered", ints(selected)); result.addProperty("remember", false);
                defaultResult = result;
            }
            case confirm, showConfirmDialog -> {
                final boolean cardConfirm = event.getMethod() == ProtocolMethod.confirm;
                kind(p, "confirm", cardConfirm ? "Confirm" : a[1]);
                p.addProperty("text", (String) a[cardConfirm ? 1 : 0]);
                if (cardConfirm) { source = (CardView) a[0]; choices.addAll((List<?>) a[3]); }
                else { choices.add(a[2]); choices.add(a[3]); }
                defaultResult = new JsonPrimitive((boolean) a[cardConfirm ? 2 : 4]);
            }
            case showOptionDialog -> {
                kind(p, "option", a[1]); p.addProperty("text", (String) a[0]); choices.addAll((List<?>) a[3]);
                defaultResult = new JsonPrimitive((int) a[4]);
            }
            case showInputDialog -> {
                kind(p, (boolean) a[5] ? "number" : "text", a[1]); p.addProperty("text", (String) a[0]);
                p.addProperty("initialInput", (String) a[3]);
                p.addProperty("restrictedInput", a[4] != null);
                if (a[4] != null) choices.addAll((List<?>) a[4]);
                // A cancelled input is legal; never invent a number outside a caller's range.
                defaultResult = JsonNull.INSTANCE;
            }
            case getAbilityToPlay -> {
                kind(p, "chooseAbility", "Choose ability"); source = (CardView) a[0]; choices.addAll((List<?>) a[1]);
                bounds(p, 1, 1, choices.size()); defaultResult = new JsonPrimitive(0);
            }
            case assignGenericAmount -> {
                kind(p, "assignGeneric", a[4]); source = (CardView) a[0];
                final Map<?, Integer> targets = (Map<?, Integer>) a[1]; choices.addAll(targets.keySet());
                final int amount = (int) a[2]; p.addProperty("amount", amount); p.addProperty("atLeastOne", (boolean) a[3]);
                final JsonArray caps = new JsonArray();
                for (Object target : choices) caps.add(targets.get(target) == null ? amount : targets.get(target));
                p.add("capacities", caps);
                final int[] allocated = new int[choices.size()];
                int left = amount;
                if ((boolean) a[3]) { Arrays.fill(allocated, 1); left -= allocated.length; }
                for (int i = 0; i < allocated.length && left > 0; i++) {
                    final int add = Math.min(left, caps.get(i).getAsInt() - allocated[i]); allocated[i] += add; left -= add;
                }
                require(left == 0, "No legal generic allocation"); defaultResult = allocations(allocated);
            }
            case assignCombatDamage -> {
                kind(p, "assignCombatDamage", "Assign combat damage"); source = (CardView) a[0];
                choices.addAll((List<?>) a[1]); final int damage = (int) a[2];
                final boolean divide = source.getCurrentState().hasDivideDamage() && (boolean) a[4];
                final boolean defender = a[3] != null && (source.getCurrentState().hasTrample() || divide);
                p.addProperty("amount", damage); p.addProperty("overrideOrder", (boolean) a[4]);
                p.addProperty("divideDamage", divide); p.addProperty("maySkip", (boolean) a[5]);
                final JsonArray lethal = new JsonArray();
                for (Object object : choices) {
                    final CardView card = (CardView) object;
                    int amount = Math.max(0, card.getLethalDamage());
                    if (card.getCurrentState().isPlaneswalker()) amount = Integer.parseInt(card.getCurrentState().getLoyalty());
                    else if (source.getCurrentState().hasDeathtouch()) amount = Math.min(amount, 1);
                    lethal.add(amount);
                }
                if (defender) { p.addProperty("defenderOptionId", choices.size()); choices.add(null); lethal.add(0); }
                p.add("lethal", lethal);
                final int[] allocated = new int[choices.size()]; int left = damage;
                for (int i = 0; i < allocated.length; i++) {
                    allocated[i] = i == allocated.length - 1 ? left : Math.min(left, lethal.get(i).getAsInt());
                    left -= allocated[i];
                }
                require(left == 0, "Combat damage has no recipient"); defaultResult = allocations(allocated);
            }
            case manipulateCardList -> {
                kind(p, "manipulateList", a[0]); ((Iterable<?>) a[1]).forEach(choices::add);
                p.add("manipulable", indexes(choices, (Iterable<?>) a[2]));
                p.addProperty("toTop", (boolean) a[3]); p.addProperty("toBottom", (boolean) a[4]); p.addProperty("toAnywhere", (boolean) a[5]);
                bounds(p, choices.size(), choices.size(), choices.size()); defaultResult = range(0, choices.size());
            }
            case sideboard -> throw new IllegalArgumentException("sideboard is answered locally with the unchanged main deck");
            default -> throw new IllegalArgumentException("Not a blocking SERVER prompt: " + event.getMethod());
        }
        final JsonArray options = new JsonArray();
        for (int i = 0; i < choices.size(); i++) {
            final Object value = choices.get(i);
            final JsonObject option = new JsonObject(); option.addProperty("optionId", i);
            cardIdentity(option, value instanceof CardView cv ? cv : value instanceof Card c ? c.getView() : null,
                    display == null, context, refs);
            if (!option.has("label")) {
                // A hidden face-down option already has its generic label. Do not call a
                // display function that could inspect or disclose its alternate identity.
                final String label = display != null ? display.apply(value) : value == null ? "Defender" : value.toString();
                option.addProperty("label", Objects.toString(label, ""));
            }
            options.add(option);
        }
        if (reveal != null) {
            final JsonArray revealed = new JsonArray();
            for (CardView card : reveal.getCards()) {
                final JsonObject item = new JsonObject();
                cardIdentity(item, card, true, context, refs);
                revealed.add(item);
            }
            p.add("revealed", revealed);
            p.addProperty("revealMessage", reveal.getMessagePrefix());
        }
        p.add("options", options);
        if (source != null) p.add("sourceCardId", ref(source, context, refs));
        if (Set.of("choose", "chooseEntities").contains(p.get("kind").getAsString())) {
            int count = p.get("min").getAsInt();
            if (event.getMethod() == ProtocolMethod.chooseSingleEntityForEffect && !choices.isEmpty()) count = 1;
            defaultResult = range(0, count);
        }
        p.add("default", defaultResult.deepCopy());
        final Prompt prompt = new Prompt(event.getMethod(), p, choices, defaultResult);
        defaultAnswer(prompt); // Fail on impossible constraints on the Forge caller, never on I/O.
        return prompt;
    }

    private static void cardIdentity(JsonObject option, CardView card, boolean defaultLabel,
                                     Context context, Map<Integer, JsonElement> refs) {
        if (card == null) return;
        option.add("cardRef", ref(card, context, refs));
        // Plan 3.5 E5, corrected for task 1.6: an owner-only prompt reveals offered face-up
        // cards (including library/hand cards), but does not grant permission to look at a
        // face-down card. Use the same view predicates as snapshots before reading identity.
        if (hiddenFaceDown(card, context)) {
            option.addProperty("label", "Face-down card");
            return; // In particular, never read getAlternateState or the live printing here.
        }
        final CardView.CardStateView identity = card.isFaceDown() ? card.getAlternateState() : card.getCurrentState();
        if (identity != null) {
            option.addProperty("name", identity.getName()); option.addProperty("set", identity.getSetCode());
            if (defaultLabel) option.addProperty("label", identity.getName());
        }
        final Card live = context.game() == null ? null : context.game().findById(card.getId());
        if (live != null && live.getPaperCard() != null) option.addProperty("num", live.getPaperCard().getCollectorNumber());
    }

    public Object defaultAnswer(final Prompt prompt) { return result(prompt, prompt.defaultResult); }

    public Object decode(final Prompt prompt, final JsonObject reply) {
        require(reply.has("type") && reply.get("type").isJsonPrimitive()
                && "promptReply".equals(reply.get("type").getAsString()), "Expected promptReply");
        require(integer(reply.get("requestId")) == prompt.requestId(), "Wrong requestId");
        require(reply.has("result"), "Missing result");
        return result(prompt, reply.get("result"));
    }

    private Object result(final Prompt prompt, final JsonElement result) {
        final JsonObject p = prompt.json;
        final String kind = p.get("kind").getAsString();
        switch (kind) {
            case "confirm": require(result.isJsonPrimitive() && result.getAsJsonPrimitive().isBoolean(), "Expected boolean"); return result.getAsBoolean();
            case "option": {
                final int index = integer(result); require(index >= -1 && index < prompt.objects.size(), "Invalid option index"); return index;
            }
            case "text", "number": {
                if (result.isJsonNull()) return null;
                require(result.isJsonPrimitive() && result.getAsJsonPrimitive().isString(), "Expected string or null");
                final String text = result.getAsString();
                if (kind.equals("number")) require(text.matches("[+-]?[0-9]+"), "Expected an integer string");
                require(!p.get("restrictedInput").getAsBoolean() || prompt.objects.contains(text), "Input is not an offered value");
                return text;
            }
            case "chooseAbility": return prompt.objects.get(optionId(result, prompt.objects.size()));
            case "assignCombatDamage", "assignGeneric": {
                if (result.isJsonNull() && kind.equals("assignCombatDamage") && p.get("maySkip").getAsBoolean()) return null;
                require(result.isJsonArray(), "Expected allocations");
                final int[] amounts = new int[prompt.objects.size()]; final Set<Integer> used = new HashSet<>();
                long sum = 0;
                for (JsonElement element : result.getAsJsonArray()) {
                    require(element.isJsonObject(), "Expected allocation object"); final JsonObject item = element.getAsJsonObject();
                    final int id = optionId(item.get("optionId"), amounts.length); require(used.add(id), "Duplicate optionId");
                    final int amount = integer(item.get("amount")); require(amount >= 0, "Negative amount");
                    amounts[id] = amount; sum += amount;
                }
                require(sum == p.get("amount").getAsInt(), "Allocation sum must equal amount");
                if (kind.equals("assignGeneric")) {
                    for (int i = 0; i < amounts.length; i++) {
                        require(amounts[i] <= p.getAsJsonArray("capacities").get(i).getAsInt(), "Target capacity exceeded");
                        require(!p.get("atLeastOne").getAsBoolean() || amounts[i] >= 1, "Each target requires at least one");
                    }
                } else if (!p.get("divideDamage").getAsBoolean()) {
                    boolean alive = false;
                    for (int i = 0; i < amounts.length; i++) {
                        final boolean defender = p.has("defenderOptionId") && i == p.get("defenderOptionId").getAsInt();
                        require(!alive || amounts[i] == 0 || (p.get("overrideOrder").getAsBoolean() && !defender), "Assign lethal before later recipients");
                        alive |= amounts[i] < p.getAsJsonArray("lethal").get(i).getAsInt();
                    }
                }
                final Map<Object, Integer> map = new LinkedHashMap<>();
                for (int i = 0; i < amounts.length; i++) map.put(prompt.objects.get(i), amounts[i]);
                return map;
            }
            default: {
                JsonElement selected = result;
                boolean remember = false;
                if (kind.equals("order")) {
                    require(result.isJsonObject(), "Expected ordered/remember object");
                    selected = result.getAsJsonObject().get("ordered");
                    final JsonElement flag = result.getAsJsonObject().get("remember");
                    require(flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean(), "Expected remember boolean");
                    remember = flag.getAsBoolean(); require(!remember || p.get("rememberAllowed").getAsBoolean(), "Remember is not offered");
                }
                final List<Integer> picked = selectedIds(selected, prompt.objects.size());
                require(picked.size() >= p.get("min").getAsInt() && picked.size() <= p.get("max").getAsInt(), "Wrong selection count");
                if (kind.equals("manipulateList")) validateManipulation(p, picked);
                final List<Object> objects = new ArrayList<>(); picked.forEach(id -> objects.add(prompt.objects.get(id)));
                if (kind.equals("order")) return new OrderResult<>(objects, remember);
                if (prompt.method == ProtocolMethod.chooseSingleEntityForEffect) return objects.isEmpty() ? null : objects.get(0);
                return objects;
            }
        }
    }

    private static void validateManipulation(JsonObject p, List<Integer> order) {
        final Set<Integer> movable = new HashSet<>(selectedIds(p.get("manipulable"), order.size()));
        final List<Integer> fixedBefore = new ArrayList<>(), fixedAfter = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) if (!movable.contains(i)) fixedBefore.add(i);
        for (int id : order) if (!movable.contains(id)) fixedAfter.add(id);
        require(fixedBefore.equals(fixedAfter), "Cannot reorder fixed cards");
        if (p.get("toAnywhere").getAsBoolean()) return;
        // A legal top/bottom move leaves a middle subsequence of the original list unchanged.
        for (int start = 0; start <= order.size(); start++) for (int end = start; end <= order.size(); end++) {
            if (start > 0 && !p.get("toTop").getAsBoolean()) continue;
            if (end < order.size() && !p.get("toBottom").getAsBoolean()) continue;
            final Set<Integer> moved = new HashSet<>(order.subList(0, start)); moved.addAll(order.subList(end, order.size()));
            if (!movable.containsAll(moved)) continue;
            final List<Integer> middle = new ArrayList<>(); for (int i = 0; i < order.size(); i++) if (!moved.contains(i)) middle.add(i);
            if (middle.equals(order.subList(start, end))) return;
        }
        throw new IllegalArgumentException("Illegal card-list movement");
    }

    public static int integer(final JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException("Expected 32-bit integer"); }
    }
    private static int optionId(JsonElement value, int size) { int id = integer(value); require(id >= 0 && id < size, "Unknown optionId: " + id); return id; }
    private static List<Integer> selectedIds(JsonElement value, int size) {
        require(value != null && value.isJsonArray(), "Expected optionId array");
        final List<Integer> result = new ArrayList<>(); final Set<Integer> seen = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) { int id = optionId(element, size); require(seen.add(id), "Duplicate optionId"); result.add(id); }
        return result;
    }
    private static void bounds(JsonObject p, int min, int max, int size) {
        max = max < 0 ? size : Math.min(max, size);
        require(min >= 0 && min <= max, "Impossible selection bounds"); p.addProperty("min", min); p.addProperty("max", max);
    }
    private static void kind(JsonObject p, String kind, Object title) { p.addProperty("kind", kind); p.addProperty("title", Objects.toString(title, "")); }
    private static JsonArray range(int start, int end) { final JsonArray a = new JsonArray(); for (int i = start; i < end; i++) a.add(i); return a; }
    private static JsonArray ints(Iterable<Integer> values) { final JsonArray a = new JsonArray(); values.forEach(a::add); return a; }
    private static JsonArray indexes(List<?> choices, Iterable<?> values) {
        final JsonArray a = new JsonArray(); if (values != null) for (Object value : values) { int id = choices.indexOf(value); if (id >= 0) a.add(id); } return a;
    }
    private static JsonArray allocations(int[] amounts) {
        final JsonArray a = new JsonArray(); for (int i = 0; i < amounts.length; i++) { final JsonObject item = new JsonObject(); item.addProperty("optionId", i); item.addProperty("amount", amounts[i]); a.add(item); } return a;
    }
    private static JsonElement ref(CardView card, Context context, Map<Integer, JsonElement> refs) {
        return refs.computeIfAbsent(card.getId(), id -> new PayloadFilter().promptCardRef(id, context.ids())
                // A completed snapshot can predate this face-down transition. Never reuse
                // its real id when the prompt's current visibility decision hides identity.
                .filter(value -> !hiddenFaceDown(card, context) || value.getAsJsonPrimitive().isString()).orElseGet(() -> {
            final byte[] bytes = new byte[6]; RANDOM.nextBytes(bytes);
            return new JsonPrimitive("h-" + HexFormat.of().formatHex(bytes));
        }));
    }
    private static boolean hiddenFaceDown(CardView card, Context context) {
        return card.isFaceDown() && !CardVisibility.forSeat(card, context.seat()).identityVisible();
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
