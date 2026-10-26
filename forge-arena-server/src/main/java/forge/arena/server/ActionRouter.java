package forge.arena.server;

import com.google.gson.*;
import forge.arena.server.view.ActiveInputTracker;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;

import java.util.*;

/** One per seat. I/O handles copied data only; one EDT task performs each complete action. */
final class ActionRouter {
    private static final System.Logger LOG = System.getLogger(ActionRouter.class.getName());
    private static final ClientMessageSchema SCHEMA = new ClientMessageSchema();
    private static final Set<String> LATER = Set.of("join", "submitDeck", "ready", "startGame", "lobbySettings",
            "setYield", "macroRecord", "macroNext", "macroRepeat");
    private static final Set<String> CARD_FIELDS = Set.of("cardId", "attackerId", "blockerId");
    private final JsonSeatRemote remote;
    private final boolean humanSeat;
    private final SeatWriter writer;
    private final ActiveInputTracker classifier = new ActiveInputTracker();
    private final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
    long completedCount() { return completed.get(); }
    private record Ref(Object wire, int id, String zone) { }

    ActionRouter(JsonSeatRemote remote, boolean humanSeat, SeatWriter writer) {
        this.remote = remote; this.humanSeat = humanSeat; this.writer = writer;
    }

    void accept(JsonObject message) {
        try {
            SCHEMA.validate(message);
            String type = message.get("type").getAsString();
            LOG.log(System.Logger.Level.INFO, "[action] {0} clientSeq={1} baseSeq={2}", type,
                    message.get("clientSeq"), message.get("baseSeq"));
            if (type.equals("ping")) { JsonObject pong = new JsonObject(); pong.addProperty("type", "pong"); writer.sendFromIo(pong); return; }
            if (LATER.contains(type)) { LOG.log(System.Logger.Level.INFO, "[action] Deferred: {0}", type); return; }
            if (type.equals("promptReply")) { remote.promptReply(message); return; }
            if (type.equals("resync")) {
                writer.resync();
                FThreads.invokeInEdtLater(() -> {
                    Game game = remote.actionGame();
                    if (game != null && remote.actionSeat() != null && game.getPlayer(remote.actionSeat()).getController() instanceof PlayerControllerHuman h
                            && h.getGui() instanceof ProtocolGuiGame gui) gui.setResyncPending();
                });
                return;
            }
            require(humanSeat, "This seat is not yours to play");
            require(!remote.hasPendingPrompt(), "Answer the pending prompt first");
            JsonObject state = remote.getLatestState();
            require(state != null, "The game is not ready");
            JsonObject input = state.getAsJsonObject("activeInput");
            Map<String, Ref> refs = new HashMap<>();
            for (String field : CARD_FIELDS) if (message.has(field)) refs.put(field, resolve(message.get(field), state, input));
            List<Ref> others = new ArrayList<>();
            if (message.has("otherCardIds")) for (JsonElement id : message.getAsJsonArray("otherCardIds")) others.add(resolve(id, state, input));
            if (message.has("defenderId")) {
                JsonObject defender = message.getAsJsonObject("defenderId");
                if (defender.get("kind").getAsString().equals("card")) refs.put("defenderId", resolve(defender.get("id"), state, input));
                else {
                    require(defender.get("kind").getAsString().equals("player"), "A stack item cannot defend");
                    require(hasId(state.getAsJsonArray("players"), "id", defender.get("id")), "Unknown player reference");
                }
            }
            if (message.has("playerId")) require(hasId(state.getAsJsonArray("players"), "id", message.get("playerId")), "Unknown player reference");
            if (message.has("stackId")) require(hasId(state.getAsJsonArray("stack"), "stackId", message.get("stackId")), "Unknown or stale stack reference");
            if (message.has("abilityId")) require(remote.offeredAbility(message.get("abilityId").getAsInt()) != null, "Unknown or stale ability reference");
            JsonObject action = message.deepCopy();
            FThreads.invokeInEdtLater(() -> {
                try { apply(action, input.get("kind").getAsString(), refs, others); }
                catch (IllegalArgumentException | IllegalStateException ex) { reject(action, ex.getMessage()); }
                finally { completed.incrementAndGet(); }
            });
        } catch (IllegalArgumentException | IllegalStateException ex) { reject(message, ex.getMessage()); }
    }

    Ref resolve(JsonElement value, JsonObject state, JsonObject input) {
        Object wire = value.getAsJsonPrimitive().isString() ? value.getAsString() : value.getAsInt();
        int id = remote.getIds().resolve(wire).orElseThrow(() -> new IllegalArgumentException("Unknown or stale card reference"));
        // A hidden ref alone is not permission to act on an opponent's hand. Selection is the
        // explicit exception; public face-down targets are offered there by Forge as well.
        if (wire instanceof String) require(input.has("selectable") && input.getAsJsonArray("selectable").contains(value),
                "That hidden card is not offered by this input");
        String zone = findZone(state, value);
        return new Ref(wire, id, zone);
    }

    private static String findZone(JsonObject state, JsonElement ref) {
        for (JsonElement p : state.getAsJsonArray("players")) for (var zone : p.getAsJsonObject().getAsJsonObject("zones").entrySet()) {
            if (zone.getValue().isJsonArray() && hasId(zone.getValue().getAsJsonArray(), "id", ref)) return zone.getKey();
        }
        return null; // Input-scoped library or spell-in-payment offer, checked against the live Selection on EDT.
    }

    private void apply(JsonObject m, String receivedKind, Map<String, Ref> refs, List<Ref> others) {
        Game game = remote.actionGame();
        require(game != null && remote.actionSeat() != null, "The game is not ready");
        Player player = game.getPlayer(remote.actionSeat());
        require(player != null && player.getController() instanceof PlayerControllerHuman, "This seat is not yours to play");
        PlayerControllerHuman controller = (PlayerControllerHuman) player.getController();
        String type = m.get("type").getAsString();
        if (type.equals("nextGame")) {
            require(game.isGameOver(), "The game has not ended"); controller.nextGameDecision(nextGame(m.get("decision").getAsString())); return;
        }
        require(!game.isGameOver(), "The game has ended");
        if (type.equals("concede")) { controller.concede(); return; }
        require(!remote.hasPendingPrompt(), "Answer the pending prompt first");
        String kind = classifier.classify(controller.getInputQueue().getInput());
        require(!kind.equals("waiting"), "The game is busy; try again");
        require(kind.equals(receivedKind), "That input is no longer active");
        Map<String, Card> cards = new HashMap<>();
        refs.forEach((field, ref) -> cards.put(field, card(game, ref)));
        List<CardView> otherViews = new ArrayList<>();
        others.forEach(ref -> otherViews.add(card(game, ref).getView()));
        Card card = cards.get("cardId");
        switch (type) {
            case "selectCard" -> require(controller.selectCard(card.getView(), otherViews,
                    new TriggerClick(m.has("button") ? m.get("button").getAsInt() : 1)), "That card cannot be selected now");
            case "selectPlayer" -> controller.selectPlayer(player(game, m.get("playerId").getAsInt()).getView(), new TriggerClick(1));
            case "selectAbility" -> {
                require(Set.of("priority", "payMana", "target", "selectStack").contains(kind), "An ability cannot be selected now");
                var ability = remote.offeredAbility(m.get("abilityId").getAsInt());
                require(ability != null, "Unknown or stale ability reference"); controller.selectAbility(ability);
            }
            case "selectStackItem" -> {
                require(Set.of("target", "selectStack").contains(kind), "No stack target is requested");
                var item = game.getView().getStack().stream().filter(s -> s.getId() == m.get("stackId").getAsInt()).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("That stack item is no longer present"));
                controller.selectStackItem(item);
            }
            case "ok", "cancel" -> {
                JsonObject input = remote.getLatestState().getAsJsonObject("activeInput");
                require(input.has("buttons") && input.getAsJsonObject("buttons").getAsJsonObject(type).get("enabled").getAsBoolean(), "That button is disabled");
                if (type.equals("ok")) controller.selectButtonOk(); else controller.selectButtonCancel();
            }
            case "useMana" -> { require(kind.equals("payMana"), "No mana payment is requested"); controller.useMana(m.get("color").getAsByte()); }
            case "undo" -> { require(kind.equals("priority") || kind.equals("payMana"), "Cannot undo during this input"); controller.undoLastAction(); }
            case "reorderHand" -> {
                int index = m.get("index").getAsInt();
                require(card.getController() == player && card.isInZone(ZoneType.Hand), "That card is no longer in your hand");
                require(index >= 0 && index < player.getCardsIn(ZoneType.Hand).size(), "Invalid hand position");
                controller.reorderHand(card.getView(), index);
            }
            case "describeAbility" -> {
                JsonObject response = new JsonObject(); response.addProperty("type", "abilityDescription");
                response.add("cardId", m.get("cardId")); response.addProperty("text", Objects.toString(controller.getActivateDescription(card.getView()), "")); writer.send(response);
            }
            case "declareAttacker", "undeclareAttacker", "attackAll" -> {
                require(kind.equals("declareAttackers"), "Attackers are not being declared");
                Combat combat = Objects.requireNonNull(game.getCombat(), "Combat has ended");
                Card attacker = cards.get("attackerId");
                if (attacker != null) ownCreature(attacker, player);
                if (type.equals("undeclareAttacker")) {
                    if (combat.isAttacking(attacker)) click(controller, attacker, 3);
                    return;
                }
                JsonObject target = m.getAsJsonObject("defenderId");
                GameEntity defender = target.get("kind").getAsString().equals("card") ? cards.get("defenderId") : player(game, target.get("id").getAsInt());
                require(combat.getDefenders().contains(defender), "That defender is no longer legal");
                if (attacker != null) {
                    if (combat.isAttacking(attacker) && combat.getDefenderByAttacker(attacker).equals(defender)) return;
                    require(CombatUtil.canAttack(attacker, defender), "That creature cannot attack that defender");
                    if (combat.isAttacking(attacker)) click(controller, attacker, 3);
                }
                if (defender instanceof Player p) controller.selectPlayer(p.getView(), new TriggerClick(1));
                else click(controller, (Card) defender, 1);
                if (attacker == null) controller.alphaStrike(); else click(controller, attacker, 1);
            }
            case "declareBlocker", "undeclareBlocker" -> {
                require(kind.equals("declareBlockers"), "Blockers are not being declared");
                Combat combat = Objects.requireNonNull(game.getCombat(), "Combat has ended");
                Card blocker = cards.get("blockerId"); ownCreature(blocker, player);
                if (type.equals("undeclareBlocker")) { if (combat.isBlocking(blocker)) click(controller, blocker, 3); return; }
                Card attacker = cards.get("attackerId");
                require(attacker.isInZone(ZoneType.Battlefield) && combat.isAttacking(attacker), "That creature is no longer attacking");
                if (combat.isBlocking(blocker, attacker)) return;
                require(CombatUtil.canBlock(attacker, blocker) && CombatUtil.canBeBlocked(attacker, combat, player), "That creature cannot block this attacker");
                if (!CombatUtil.canBlockMoreCreatures(blocker, combat.getAttackersBlockedBy(blocker))) click(controller, blocker, 3);
                JsonObject input = remote.getLatestState().getAsJsonObject("activeInput");
                Object attackerRef = remote.getIds().inputRef(attacker.getId()).orElseThrow();
                boolean chosen = input.has("chosen") && hasCardEntity(input.getAsJsonArray("chosen"), attackerRef);
                if (!chosen) click(controller, attacker, 1);
                click(controller, blocker, 1);
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + type);
        }
    }

    private Card card(Game game, Ref ref) {
        require(remote.getIds().resolve(ref.wire()).orElse(-1) == ref.id(), "Unknown or stale card reference");
        Card card = game.findById(ref.id());
        require(card != null, "That card is no longer present");
        if (ref.zone() != null && (card.getZone() == null
                || !card.getZone().getZoneType().name().equalsIgnoreCase(ref.zone()))) {
            String location = switch (ref.zone().toLowerCase(Locale.ROOT)) {
                case "battlefield" -> "on the battlefield";
                case "hand" -> "in your hand";
                case "graveyard" -> "in the graveyard";
                case "exile" -> "in exile";
                case "command" -> "in the command zone";
                case "stack" -> "on the stack";
                default -> "in the expected zone";
            };
            throw new IllegalArgumentException("That card is no longer " + location);
        }
        return card;
    }
    private static void ownCreature(Card card, Player player) {
        require(card.isInZone(ZoneType.Battlefield), "That creature is no longer on the battlefield");
        require(card.isCreature() && card.getController() == player, "That creature is not yours to declare");
    }
    private static void click(PlayerControllerHuman controller, Card card, int button) {
        require(controller.selectCard(card.getView(), null, new TriggerClick(button)), "Forge rejected that card selection");
    }
    private static Player player(Game game, int id) {
        return game.getPlayers().stream().filter(p -> p.getId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That player is no longer present"));
    }
    static NextGameDecision nextGame(String decision) {
        return switch (decision) { case "rematch" -> NextGameDecision.NEW; case "quit" -> NextGameDecision.QUIT;
            default -> throw new IllegalArgumentException("Unknown next-game decision"); };
    }
    private static boolean hasId(JsonArray array, String key, JsonElement id) {
        for (JsonElement e : array) if (e.isJsonObject() && id.equals(e.getAsJsonObject().get(key))) return true;
        return false;
    }
    private static boolean hasCardEntity(JsonArray array, Object id) {
        JsonElement value = id instanceof Integer n ? new JsonPrimitive(n) : new JsonPrimitive((String) id);
        for (JsonElement e : array) if (e.getAsJsonObject().get("kind").getAsString().equals("card") && value.equals(e.getAsJsonObject().get("id"))) return true;
        return false;
    }
    void reject(String reason) { reject(null, reason); }
    private void reject(JsonObject action, String reason) {
        LOG.log(System.Logger.Level.INFO, "[action] Rejected clientSeq={0} baseSeq={1}: {2}",
                action == null ? null : action.get("clientSeq"), action == null ? null : action.get("baseSeq"), reason);
        JsonObject rejected = new JsonObject(); rejected.addProperty("type", "message");
        rejected.addProperty("level", "rejected"); rejected.addProperty("text", reason == null ? "Action no longer applies" : reason);
        writer.sendFromIo(rejected);
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
