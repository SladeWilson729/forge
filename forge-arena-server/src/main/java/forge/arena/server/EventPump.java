package forge.arena.server;

import com.google.gson.*;
import forge.arena.server.view.CardVisibility;
import forge.arena.server.view.IdMapper;
import forge.arena.server.view.PayloadFilter;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.event.*;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

import java.util.*;

/** Per-seat encoder. Consumes the in-process forwarder's events after their snapshot.
 * No socket work; callers publish the returned detached messages through SeatWriter.
 */
public final class EventPump {
    private final PlayerView seat;
    private final IdMapper ids;
    private final PayloadFilter filter = new PayloadFilter();
    private final Set<Integer> resolved = new HashSet<>();
    private final Map<Integer, CardView> onStack = new LinkedHashMap<>();
    private JsonObject previous;
    private Set<Integer> previousTriggers = Set.of();
    private Map<JsonElement, JsonObject> stubs = Map.of();
    private PayloadFilter.LogVisibility logVisibility = PayloadFilter.LogVisibility.empty();

    public EventPump(PlayerView seat, IdMapper ids) {
        this.seat = seat;
        this.ids = ids;
    }

    /** Only called where the full snapshot is built: never on a timer or I/O thread. */
    public List<JsonObject> snapshot(Game game, JsonObject state, Map<JsonElement, JsonObject> cardStubs) {
        stubs = cardStubs;
        logVisibility = filter.captureLogVisibility(game, seat);
        final List<JsonObject> result = new ArrayList<>();
        final long seq = state.get("seq").getAsLong();
        final Map<Integer, JsonObject> before = players(previous);
        final Map<Integer, JsonObject> after = players(state);
        if (previous != null) {
            for (var entry : after.entrySet()) {
                final JsonObject old = before.get(entry.getKey()), now = entry.getValue();
                if (old == null) continue;
                final int delta = now.get("life").getAsInt() - old.get("life").getAsInt();
                if (delta != 0) {
                    JsonObject event = event("lifeChange", seq);
                    player(event, entry.getKey());
                    event.addProperty("amount", delta);
                    event.add("oldValue", old.get("life")); event.add("newValue", now.get("life"));
                    result.add(event);
                }
                if (!old.get("eliminated").getAsBoolean() && now.get("eliminated").getAsBoolean()) {
                    JsonObject event = event("playerEliminated", seq); player(event, entry.getKey()); result.add(event);
                }
            }
            for (var entry : before.entrySet()) {
                if (!after.containsKey(entry.getKey()) && !entry.getValue().get("eliminated").getAsBoolean()) {
                    JsonObject event = event("playerEliminated", seq); player(event, entry.getKey()); result.add(event);
                }
            }
            for (String designation : List.of("monarch", "initiative")) {
                JsonElement old = previous.get(designation + "Id"), now = state.get(designation + "Id");
                if (!Objects.equals(old, now)) {
                    JsonObject event = event(designation + "Change", seq);
                    event.add("previousPlayerId", old); event.add("playerId", now);
                    result.add(event);
                }
            }
        }
        final Set<Integer> triggers = new HashSet<>();
        for (StackItemView item : game.getView().getStack()) {
            if (!item.isTrigger()) continue;
            triggers.add(item.getId());
            if (!previousTriggers.contains(item.getId())) {
                JsonObject event = event("triggerFired", seq);
                cards(event, item.getSourceCard()); event.addProperty("stackId", item.getId());
                result.add(event);
            }
        }
        previousTriggers = Set.copyOf(triggers);
        previous = state.deepCopy();
        result.forEach(this::addStubs);
        return result;
    }

    public List<JsonObject> batch(List<?> events, long stateSeq) {
        final List<JsonObject> messages = new ArrayList<>();
        // A removal can precede the resolution notification within a batch. Pre-index both;
        // retain resolved ids across batches because an Input can flush during resolution.
        final Set<Integer> batchResolved = new HashSet<>();
        for (Object event : events) if (event instanceof GameEventSpellResolved r && r.spell() != null) {
            batchResolved.add(r.spell().getId());
        }
        final Visitor visitor = new Visitor(messages, stateSeq, batchResolved);
        final Set<Integer> assignedTokenChanges = new HashSet<>();
        int previousMarker = -1;
        for (int i = 0; i < events.size(); i++) {
            Object raw = events.get(i);
            if (!(raw instanceof GameEvent event)) continue;
            if (event instanceof GameEventTokenCreated) {
                JsonObject token = event("tokenCreated", stateSeq);
                JsonArray refs = new JsonArray();
                // TokenEffect.resolve currently fires its marker AFTER makeTokenTable and
                // triggerChangesZoneAll. Also support marker-first batches from the plan.
                boolean preceded = false;
                for (int j = previousMarker + 1; j < i; j++) {
                    preceded |= tokenChange(events.get(j), j, assignedTokenChanges, refs);
                }
                if (!preceded) for (int j = i + 1; j < events.size(); j++) {
                    if (events.get(j) instanceof GameEventTokenCreated) break;
                    tokenChange(events.get(j), j, assignedTokenChanges, refs);
                }
                previousMarker = i;
                token.add("cardIds", refs); messages.add(token);
            } else event.visit(visitor);
        }
        messages.stream().filter(m -> "event".equals(m.get("type").getAsString())).forEach(this::addStubs);
        return messages;
    }

    private boolean tokenChange(Object event, int index, Set<Integer> assigned, JsonArray refs) {
        if (event instanceof GameEventCardChangeZone change && change.card().isToken()
                && change.to() != null && change.to().zoneType() == ZoneType.Battlefield && assigned.add(index)) {
            JsonElement ref = cardRef(change.card());
            if (!ref.isJsonNull() && !refs.contains(ref)) refs.add(ref);
            return true;
        }
        return false;
    }

    public JsonObject reveal(JsonArray revealed, long stateSeq) {
        JsonObject event = event("reveal", stateSeq);
        JsonArray refs = new JsonArray();
        for (JsonElement card : revealed) refs.add(card.getAsJsonObject().get("id"));
        event.add("cardIds", refs);
        event.add("cardStubs", revealed.deepCopy());
        return event;
    }

    private void addStubs(JsonObject event) {
        Set<JsonElement> refs = new LinkedHashSet<>(); collectCardRefs(event, refs);
        JsonArray inline = new JsonArray();
        for (JsonElement ref : refs) {
            JsonObject stub = stubs.get(ref);
            if (stub != null) inline.add(stub.deepCopy());
        }
        event.add("cardStubs", inline);
    }

    /** Only card positions, never overlapping player/stack integer namespaces. */
    static void collectCardRefs(JsonElement value, Set<JsonElement> refs) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(v -> collectCardRefs(v, refs)); return; }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("kind") && "card".equals(object.get("kind").getAsString())) refs.add(object.get("id"));
        for (var field : object.entrySet()) {
            if (field.getKey().equals("cardStubs")) continue;
            if (Set.of("cardId", "sourceCardId", "attackerId", "blockerId").contains(field.getKey())
                    && !field.getValue().isJsonNull()) refs.add(field.getValue());
            else if (field.getKey().equals("cardIds")) field.getValue().getAsJsonArray().forEach(refs::add);
            else collectCardRefs(field.getValue(), refs);
        }
    }

    private final class Visitor extends IGameEventVisitor.Base<Void> {
        private final List<JsonObject> output;
        private final long seq;
        private final Set<Integer> batchResolved;
        Visitor(List<JsonObject> output, long seq, Set<Integer> batchResolved) {
            this.output = output; this.seq = seq; this.batchResolved = batchResolved;
        }
        private JsonObject emit(String kind) { JsonObject e = event(kind, seq); output.add(e); return e; }

        @Override public Void visit(GameEventCardChangeZone ev) {
            JsonObject e = filter.zoneChange(ev, seat, ids, seq);
            // Library shuffles cannot link identities, including a temporarily revealed top card.
            if (ev.from() != null && ev.to() != null && ev.from().zoneType() == ZoneType.Library
                    && ev.to().zoneType() == ZoneType.Library) e.add("cardIds", new JsonArray());
            else cards(e, ev.card());
            output.add(e); return null;
        }
        @Override public Void visit(GameEventLandPlayed ev) {
            JsonObject e = emit("landPlayed"); cards(e, ev.land()); player(e, ev.player().getId()); return null;
        }
        @Override public Void visit(GameEventSpellAbilityCast ev) {
            JsonObject e = emit("spellCast"); cards(e, ev.sa().getHostCard());
            e.addProperty("ability", !ev.sa().isSpell());
            if (ev.si() != null) { e.addProperty("stackId", ev.si().getId()); player(e, ev.si().getActivatingPlayer().getId()); }
            resolved.remove(ev.sa().getId()); onStack.put(ev.sa().getId(), ev.sa().getHostCard()); return null;
        }
        @Override public Void visit(GameEventSpellResolved ev) {
            JsonObject e = emit(ev.hasFizzled() ? "countered" : "resolved");
            if (ev.spell() != null) { cards(e, ev.spell().getHostCard()); resolved.add(ev.spell().getId()); }
            else e.add("cardIds", new JsonArray());
            if (ev.hasFizzled()) e.addProperty("reason", "fizzled");
            return null;
        }
        @Override public Void visit(GameEventSpellRemovedFromStack ev) {
            if (ev.sa() == null) {
                for (var entry : onStack.entrySet()) removed(entry.getKey(), entry.getValue());
                onStack.clear(); resolved.clear();
            } else {
                removed(ev.sa().getId(), ev.sa().getHostCard()); onStack.remove(ev.sa().getId());
            }
            return null;
        }
        private void removed(int id, CardView card) {
            boolean wasResolved = resolved.remove(id);
            if (!wasResolved && !batchResolved.contains(id)) {
                JsonObject e = emit("countered"); cards(e, card); e.addProperty("reason", "removed");
            }
        }
        @Override public Void visit(GameEventCardTapped ev) {
            cards(emit(ev.tapped() ? "tap" : "untap"), ev.card()); return null;
        }
        @Override public Void visit(GameEventCardDamaged ev) {
            JsonObject e = emit("damage"); e.add("sourceCardId", cardRef(ev.source()));
            e.add("target", entity(ev.card())); e.addProperty("amount", ev.amount());
            e.addProperty("damageType", ev.type().name()); return null;
        }
        @Override public Void visit(GameEventPlayerDamaged ev) {
            JsonObject e = emit("damage"); e.add("sourceCardId", cardRef(ev.source()));
            e.add("target", entity(ev.target())); e.addProperty("amount", ev.amount());
            e.addProperty("combat", ev.combat()); e.addProperty("infect", ev.infect()); return null;
        }
        private void counters(GameEntityView target, CounterType type, int oldValue, int newValue) {
            JsonObject e = emit("counterChange"); e.add("target", entity(target));
            e.addProperty("counterType", type == null ? null : type.toString());
            e.addProperty("oldValue", oldValue); e.addProperty("newValue", newValue);
        }
        @Override public Void visit(GameEventCardCounters ev) { counters(ev.card(), ev.type(), ev.oldValue(), ev.newValue()); return null; }
        @Override public Void visit(GameEventPlayerCounters ev) {
            // Player.setCounters passes the NEW TOTAL in the misleadingly named amount field.
            counters(ev.receiver(), ev.type(), ev.oldValue(), ev.amount()); return null;
        }
        @Override public Void visit(GameEventAttackersDeclared ev) {
            JsonObject e = emit("attack"); player(e, ev.player().getId()); JsonArray pairs = new JsonArray();
            for (var pair : ev.attackersMap().entries()) {
                JsonElement card = cardRef(pair.getValue()); if (card.isJsonNull()) continue;
                JsonObject attack = new JsonObject(); attack.add("cardId", card); attack.add("defender", entity(pair.getKey())); pairs.add(attack);
            }
            e.add("attacks", pairs); return null;
        }
        @Override public Void visit(GameEventBlockersDeclared ev) {
            JsonObject e = emit("block"); player(e, ev.defendingPlayer().getId()); JsonArray pairs = new JsonArray();
            for (var defended : ev.blockers().values()) for (var pair : defended.entries()) {
                // PhaseHandler represents an UNBLOCKED attacker as attacker -> itself.
                if (pair.getKey().getId() == pair.getValue().getId()) continue;
                JsonElement attacker = cardRef(pair.getKey()), blocker = cardRef(pair.getValue());
                if (attacker.isJsonNull() || blocker.isJsonNull()) continue;
                JsonObject block = new JsonObject(); block.add("attackerId", attacker); block.add("blockerId", blocker); pairs.add(block);
            }
            e.add("blocks", pairs); return null;
        }
        @Override public Void visit(GameEventShuffle ev) { player(emit("shuffle"), ev.player().getId()); return null; }
        @Override public Void visit(GameEventFlipCoin ev) {
            JsonObject e = emit("coinFlip"); player(e, ev.flipper().getId());
            e.addProperty("result", ev.heads()); e.addProperty("startingToss", ev.startingToss()); return null;
        }
        @Override public Void visit(GameEventRollDie ev) {
            JsonObject e = emit("dieRoll"); e.addProperty("sides", ev.sides());
            if (ev.planar() == null) e.addProperty("result", ev.result()); else e.addProperty("result", ev.planar().name());
            return null;
        }
        @Override public Void visit(GameEventMulligan ev) { player(emit("mulligan"), ev.player().getId()); return null; }
        @Override public Void visit(GameEventTurnBegan ev) {
            JsonObject e = emit("turnBegan"); player(e, ev.turnOwner().getId()); e.addProperty("turn", ev.turnNumber()); return null;
        }
        @Override public Void visit(GameEventGameOutcome ev) {
            JsonObject e = emit("gameOver"); e.addProperty("winner", ev.winningPlayerName());
            e.addProperty("turn", ev.lastTurnNumber()); return null;
        }
        @Override public Void visit(GameEventAddLog ev) {
            output.add(filter.log(new GameLogEntry(ev.type(), ev.message(), ev.sourceCard()), logVisibility)); return null;
        }
    }

    private JsonElement cardRef(CardView card) {
        if (card == null) return JsonNull.INSTANCE;
        Object ref = ids.currentRef(card.getId()).orElse(null);
        // Never substitute a raw id for a retired/unrepresented card. Recheck view predicates
        // before emitting an integer, so a visibility change cannot expose a stale identity.
        if (ref instanceof Integer id) return CardVisibility.forSeat(card, seat).identityVisible()
                ? new JsonPrimitive(id) : JsonNull.INSTANCE;
        return ref instanceof String opaque ? new JsonPrimitive(opaque) : JsonNull.INSTANCE;
    }
    private void cards(JsonObject event, CardView... cards) {
        JsonArray refs = new JsonArray();
        for (CardView card : cards) { JsonElement ref = cardRef(card); if (!ref.isJsonNull() && !refs.contains(ref)) refs.add(ref); }
        event.add("cardIds", refs);
    }
    private JsonElement entity(GameEntityView entity) {
        if (entity == null) return JsonNull.INSTANCE;
        JsonElement id = entity instanceof CardView card ? cardRef(card) : new JsonPrimitive(entity.getId());
        if (id.isJsonNull()) return id;
        JsonObject result = new JsonObject(); result.addProperty("kind", entity instanceof CardView ? "card" : "player");
        result.add("id", id); return result;
    }
    private static void player(JsonObject event, int id) { JsonArray players = new JsonArray(); players.add(id); event.add("playerIds", players); }
    private static JsonObject event(String kind, long seq) {
        JsonObject event = new JsonObject(); event.addProperty("type", "event"); event.addProperty("seq", 0);
        event.addProperty("kind", kind); event.addProperty("stateSeq", seq); return event;
    }
    private static Map<Integer, JsonObject> players(JsonObject state) {
        Map<Integer, JsonObject> result = new LinkedHashMap<>();
        if (state != null) for (JsonElement player : state.getAsJsonArray("players")) {
            JsonObject p = player.getAsJsonObject(); result.put(p.get("id").getAsInt(), p);
        }
        return result;
    }
}
