package forge.arena.server;

import com.google.gson.*;
import forge.arena.server.view.CardVisibility;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.gamemodes.match.*;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.AutoYieldStore;
import forge.player.PlayerControllerHuman;

import java.util.*;

/** EDT-only adapter for Forge's normal per-player sendYieldUpdate/applyYieldUpdate path. */
final class YieldBridge {
    // YieldController.SYNCED_PREFS is private. Keep this exact source-verified copy; the test audits it.
    static final Set<FPref> SYNCED = Collections.unmodifiableSet(EnumSet.of(
            FPref.YIELD_INTERRUPT_ON_ATTACKERS, FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL,
            FPref.YIELD_INTERRUPT_ON_TARGETING, FPref.YIELD_INTERRUPT_ON_TRIGGERS,
            FPref.YIELD_INTERRUPT_ON_REVEAL, FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL,
            FPref.YIELD_AUTO_PASS_NO_ACTIONS, FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS,
            FPref.YIELD_SKIP_PHASE_DELAY, FPref.YIELD_SKIP_RESOLVE_DELAY,
            FPref.YIELD_SUPPRESS_ON_OWN_TURN, FPref.YIELD_SUPPRESS_AFTER_END,
            FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS, FPref.YIELD_DECLINE_SCOPE_STACK_YIELD,
            FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, FPref.UI_SHOW_AUTOTAP_PREVIEW));
    static final Set<FPref> GLOBAL = Set.of(FPref.YIELD_AUTO_PASS_NO_ACTIONS);
    private final JsonSeatRemote remote;
    private int seededGame = -1;
    private final Map<String, JsonObject> keyRefs = new HashMap<>();

    YieldBridge(JsonSeatRemote remote) { this.remote = remote; }

    static FPref preference(String name, String value, boolean seed) {
        final FPref pref;
        try { pref = FPref.valueOf(name); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unknown yield preference: " + name); }
        require(SYNCED.contains(pref), name + " is not a synced yield preference");
        if (GLOBAL.contains(pref)) require(seed && Objects.equals(value, FModel.getPreferences().getPref(pref)),
                name + " is server-wide and cannot be changed for one seat");
        if (pref == FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS) {
            try { require(Integer.parseInt(value) >= 0, "Yield scan budget must be zero or a positive integer"); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Yield scan budget must be zero or a positive integer"); }
        } else if (pref == FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS || pref == FPref.YIELD_DECLINE_SCOPE_STACK_YIELD) {
            require(Set.of("NEVER", "ALWAYS", "STACK", "TURN").contains(value)
                    && (pref != FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS || !value.equals("STACK")), "Invalid yield decline scope");
        } else require(value.equals("true") || value.equals("false"), name + " must be true or false");
        return pref;
    }

    void connected() {
        Game game = remote.actionGame();
        if (game == null || remote.actionSeat() == null || seededGame == game.getId()) return;
        if (!(game.getPlayer(remote.actionSeat()).getController() instanceof PlayerControllerHuman)) return;
        apply(defaultSeed(game, remote.actionSeat()));
    }

    static JsonObject defaultSeed(Game game, PlayerView seat) {
        JsonObject message = new JsonObject(); message.addProperty("kind", "seed");
        JsonObject snapshot = new JsonObject(); message.add("snapshot", snapshot);
        for (String field : List.of("cardYields", "abilityYields", "cardTriggerDecisions", "abilityTriggerDecisions")) snapshot.add(field, new JsonArray());
        snapshot.addProperty("autoYieldsDisabled", false); snapshot.addProperty("autoTriggersDisabled", false);
        JsonObject prefs = new JsonObject(); snapshot.add("prefOverrides", prefs);
        for (FPref pref : SYNCED) prefs.addProperty(pref.name(), FModel.getPreferences().getPref(pref));
        for (FPref pref : List.of(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, FPref.UI_SHOW_AUTOTAP_PREVIEW,
                FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS, FPref.YIELD_INTERRUPT_ON_ATTACKERS,
                FPref.YIELD_INTERRUPT_ON_TARGETING, FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL)) prefs.addProperty(pref.name(), "true");
        prefs.addProperty(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL.name(), "false");
        JsonArray phases = new JsonArray(); snapshot.add("skipPhases", phases);
        for (var player : game.getPlayers()) {
            EnumSet<PhaseType> stops = player.getView().equals(seat)
                    ? EnumSet.of(PhaseType.UPKEEP, PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2)
                    : EnumSet.of(PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN);
            JsonObject entry = new JsonObject(); entry.addProperty("turnPlayerId", player.getId());
            JsonArray skipped = new JsonArray(); for (PhaseType phase : PhaseType.values()) if (!stops.contains(phase)) skipped.add(phase.name());
            entry.add("phases", skipped); phases.add(entry);
        }
        return message;
    }

    void apply(JsonObject message) {
        Game game = Objects.requireNonNull(remote.actionGame(), "The game is not ready");
        var player = game.getPlayer(remote.actionSeat());
        require(player != null && player.getController() instanceof PlayerControllerHuman, "This seat is not yours to play");
        PlayerControllerHuman controller = (PlayerControllerHuman) player.getController();
        YieldUpdate update = decode(message, game, controller);
        if (update instanceof YieldUpdate.SeedFromClient) seededGame = game.getId();
        // NetGameController mirrors ordinary updates locally. Our browser has no Forge cache,
        // so echo through the same GUI push path BEFORE application can immediately end a yield.
        controller.getGui().applyYieldUpdate(update);
        controller.sendYieldUpdate(update);
    }

    YieldUpdate decode(JsonObject m, Game game, PlayerControllerHuman controller) {
        PlayerView seat = remote.actionSeat();
        return switch (m.get("kind").getAsString()) {
            case "setMarker" -> {
                PlayerView owner = player(game, m.get("phaseOwnerId").getAsInt()); PhaseType phase = PhaseType.valueOf(m.get("phase").getAsString());
                var handler = game.getPhaseHandler();
                yield new YieldUpdate.SetMarker(owner, phase, handler.getPlayerTurn().getView().equals(owner)
                        && (handler.getPhase() == phase || handler.getPhase().isAfter(phase)));
            }
            case "clearMarker" -> new YieldUpdate.ClearMarker(seat);
            case "stackYield" -> new YieldUpdate.StackYield(seat, m.get("active").getAsBoolean(), m.get("respectsInterrupts").getAsBoolean());
            case "untilEndOfTurn" -> new YieldUpdate.SetAutoPassUntilEndOfTurn(seat, m.get("active").getAsBoolean());
            case "cardAutoYield" -> new YieldUpdate.CardAutoYield(key(m, game, controller.getYieldController().isAbilityScope()), m.get("active").getAsBoolean());
            case "triggerDecision" -> new YieldUpdate.TriggerDecision(key(m, game, controller.getYieldController().isAbilityScope()), AutoYieldStore.TriggerDecision.valueOf(m.get("decision").getAsString()));
            case "disableYields" -> new YieldUpdate.SetDisableYields(m.get("disabled").getAsBoolean());
            case "disableTriggers" -> new YieldUpdate.SetDisableTriggers(m.get("disabled").getAsBoolean());
            case "skipPhase" -> new YieldUpdate.SkipPhase(player(game, m.get("turnPlayerId").getAsInt()), PhaseType.valueOf(m.get("phase").getAsString()), m.get("skip").getAsBoolean());
            case "pref" -> new YieldUpdate.SetYieldPref(preference(m.get("name").getAsString(), m.get("value").getAsString(), false), m.get("value").getAsString());
            case "seed" -> new YieldUpdate.SeedFromClient(seed(m.getAsJsonObject("snapshot"), game));
            case "clearAbilityOrders" -> new YieldUpdate.ClearAbilityOrders();
            default -> throw new IllegalArgumentException("Unknown yield kind");
        };
    }

    private String key(JsonObject m, Game game, boolean abilityScope) {
        JsonElement ref = m.get("cardId");
        require(ref != null && ref.isJsonPrimitive() && ref.getAsJsonPrimitive().isNumber(), "Hidden card references cannot be used for yields");
        int id = ref.getAsInt();
        require(remote.getIds().currentRef(id).filter(current -> current instanceof Integer n && n == id).isPresent(), "Unknown or hidden yield card");
        Card card = game.findById(id);
        require(card != null && CardVisibility.forSeat(card.getView(), remote.actionSeat()).identityVisible(), "Unknown or hidden yield card");
        int abilityId = m.get("abilityId").getAsInt(); SpellAbility ability = null;
        for (var item : game.getStack()) if (item.getSpellAbility().getId() == abilityId && item.getSpellAbility().getHostCard().getId() == id) ability = item.getSpellAbility();
        if (ability == null) for (var candidate : card.getSpellAbilities()) if (candidate.getId() == abilityId) ability = candidate;
        require(ability != null, "That ability does not belong to this card");
        String raw = ability.yieldKey(); String key = abilityScope ? AutoYieldStore.abilitySuffix(raw) : raw;
        JsonObject ids = new JsonObject(); ids.addProperty("cardId", id); ids.addProperty("abilityId", abilityId); keyRefs.put(key, ids);
        return key;
    }

    private YieldStateSnapshot seed(JsonObject s, Game game) {
        Set<String> cards = keys(s.getAsJsonArray("cardYields"), game, false), abilities = keys(s.getAsJsonArray("abilityYields"), game, true);
        Map<String, AutoYieldStore.TriggerDecision> cardDecisions = decisions(s.getAsJsonArray("cardTriggerDecisions"), game, false);
        Map<String, AutoYieldStore.TriggerDecision> abilityDecisions = decisions(s.getAsJsonArray("abilityTriggerDecisions"), game, true);
        Map<PlayerView, EnumSet<PhaseType>> phases = new HashMap<>();
        for (JsonElement item : s.getAsJsonArray("skipPhases")) {
            JsonObject entry = item.getAsJsonObject(); EnumSet<PhaseType> skipped = EnumSet.noneOf(PhaseType.class);
            entry.getAsJsonArray("phases").forEach(phase -> skipped.add(PhaseType.valueOf(phase.getAsString())));
            phases.put(player(game, entry.get("turnPlayerId").getAsInt()), skipped);
        }
        Map<FPref, String> prefs = new EnumMap<>(FPref.class);
        s.getAsJsonObject("prefOverrides").entrySet().forEach(entry -> prefs.put(preference(entry.getKey(), entry.getValue().getAsString(), true), entry.getValue().getAsString()));
        return new YieldStateSnapshot(cards, abilities, cardDecisions, abilityDecisions, s.get("autoYieldsDisabled").getAsBoolean(), s.get("autoTriggersDisabled").getAsBoolean(), phases, prefs);
    }
    private Set<String> keys(JsonArray entries, Game game, boolean scope) {
        Set<String> result = new HashSet<>(); for (JsonElement entry : entries) result.add(key(entry.getAsJsonObject(), game, scope)); return result;
    }
    private Map<String, AutoYieldStore.TriggerDecision> decisions(JsonArray entries, Game game, boolean scope) {
        Map<String, AutoYieldStore.TriggerDecision> result = new HashMap<>();
        for (JsonElement entry : entries) result.put(key(entry.getAsJsonObject(), game, scope), AutoYieldStore.TriggerDecision.valueOf(entry.getAsJsonObject().get("decision").getAsString()));
        return result;
    }

    JsonObject encode(YieldUpdate update) {
        JsonObject m = new JsonObject(); m.addProperty("type", "yieldState");
        if (update instanceof YieldUpdate.SetMarker u) { m.addProperty("kind", "setMarker"); m.addProperty("phaseOwnerId", u.phaseOwner().getId()); m.addProperty("phase", u.phase().name()); m.addProperty("atOrPastAtClick", u.atOrPastAtClick()); }
        else if (update instanceof YieldUpdate.ClearMarker) m.addProperty("kind", "clearMarker");
        else if (update instanceof YieldUpdate.StackYield u) { m.addProperty("kind", "stackYield"); m.addProperty("active", u.active()); m.addProperty("respectsInterrupts", u.respectsInterrupts()); }
        else if (update instanceof YieldUpdate.SetAutoPassUntilEndOfTurn u) { m.addProperty("kind", "untilEndOfTurn"); m.addProperty("active", u.active()); }
        else if (update instanceof YieldUpdate.CardAutoYield u) { m.addProperty("kind", "cardAutoYield"); refs(m, u.cardKey()); m.addProperty("active", u.active()); }
        else if (update instanceof YieldUpdate.TriggerDecision u) { m.addProperty("kind", "triggerDecision"); refs(m, u.storageKey()); m.addProperty("decision", u.decision().name()); }
        else if (update instanceof YieldUpdate.SetDisableYields u) { m.addProperty("kind", "disableYields"); m.addProperty("disabled", u.disabled()); }
        else if (update instanceof YieldUpdate.SetDisableTriggers u) { m.addProperty("kind", "disableTriggers"); m.addProperty("disabled", u.disabled()); }
        else if (update instanceof YieldUpdate.SkipPhase u) { m.addProperty("kind", "skipPhase"); m.addProperty("turnPlayerId", u.turnPlayer().getId()); m.addProperty("phase", u.phase().name()); m.addProperty("skip", u.skip()); }
        else if (update instanceof YieldUpdate.SetYieldPref u) { m.addProperty("kind", "pref"); m.addProperty("name", u.pref().name()); m.addProperty("value", u.value()); }
        else if (update instanceof YieldUpdate.SeedFromClient u) { m.addProperty("kind", "seed"); m.add("snapshot", snapshot(u.snapshot())); }
        else if (update instanceof YieldUpdate.ClearAbilityOrders) m.addProperty("kind", "clearAbilityOrders");
        return m;
    }
    private void refs(JsonObject out, String key) {
        JsonObject ref = keyRefs.get(key); require(ref != null, "No public reference for yield key");
        Object current = remote.getIds().currentRef(ref.get("cardId").getAsInt()).orElseThrow(() -> new IllegalArgumentException("Yield card reference expired"));
        out.add("cardId", WsServer.GSON.toJsonTree(current)); out.add("abilityId", ref.get("abilityId"));
    }
    private JsonObject snapshot(YieldStateSnapshot s) {
        JsonObject out = new JsonObject();
        out.add("cardYields", encodedKeys(s.cardYields())); out.add("abilityYields", encodedKeys(s.abilityYields()));
        out.add("cardTriggerDecisions", encodedDecisions(s.cardTriggerDecisions())); out.add("abilityTriggerDecisions", encodedDecisions(s.abilityTriggerDecisions()));
        out.addProperty("autoYieldsDisabled", s.autoYieldsDisabled()); out.addProperty("autoTriggersDisabled", s.autoTriggersDisabled());
        JsonArray phases = new JsonArray(); s.skipPhases().forEach((player, skipped) -> { JsonObject entry = new JsonObject(); entry.addProperty("turnPlayerId", player.getId()); JsonArray values = new JsonArray(); skipped.forEach(p -> values.add(p.name())); entry.add("phases", values); phases.add(entry); }); out.add("skipPhases", phases);
        JsonObject prefs = new JsonObject(); s.prefOverrides().forEach((pref, value) -> prefs.addProperty(pref.name(), value)); out.add("prefOverrides", prefs); return out;
    }
    private JsonArray encodedKeys(Set<String> keys) { JsonArray result = new JsonArray(); for (String key : keys) { JsonObject entry = new JsonObject(); refs(entry, key); result.add(entry); } return result; }
    private JsonArray encodedDecisions(Map<String, AutoYieldStore.TriggerDecision> entries) { JsonArray result = new JsonArray(); entries.forEach((key, decision) -> { JsonObject entry = new JsonObject(); refs(entry, key); entry.addProperty("decision", decision.name()); result.add(entry); }); return result; }
    private static PlayerView player(Game game, int id) { var player = game.getPlayer(id); require(player != null, "Unknown yield phase player"); return player.getView(); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
