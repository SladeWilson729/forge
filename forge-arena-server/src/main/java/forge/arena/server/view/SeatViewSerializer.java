package forge.arena.server.view;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds detached JSON only on the game thread, or on the UI thread while the game thread
 * is parked (plan 2.2 rule 1). The caller supplies that guarantee; thread names cannot prove it.
 * JsonSeatRemote invokes this inline on its Forge-safe caller, never a socket/writer thread.
 */
public final class SeatViewSerializer {
    private static final System.Logger LOG = System.getLogger(SeatViewSerializer.class.getName());
    private boolean missingLiveCardLogged;

    public JsonObject buildState(final Game game, final PlayerView seat, final IdMapper ids, final long seq) {
        Objects.requireNonNull(game);
        Objects.requireNonNull(ids);
        if (seat == null || game.getPlayer(seat) == null || seq < 0) {
            throw new IllegalArgumentException("A game seat and nonnegative sequence are required");
        }
        ids.beginSnapshot();
        try {
            final JsonObject result = new Snapshot(game, seat, ids).build(seq);
            ids.endSnapshot();
            return result;
        } catch (final RuntimeException | Error failure) {
            ids.abortSnapshot();
            throw failure;
        }
    }

    private record CardInfo(CardView card, boolean shown, boolean identityVisible,
                            boolean faceDown, JsonElement ref) { }

    /** All Forge references and per-card visibility decisions live only for this one walk. */
    private final class Snapshot {
        private final Game game;
        private final PlayerView seat;
        private final IdMapper ids;
        private final Map<Integer, CardInfo> cards = new HashMap<>();
        private final List<CardView> commanders = new ArrayList<>();

        private Snapshot(final Game game, final PlayerView seat, final IdMapper ids) {
            this.game = game;
            this.seat = seat;
            this.ids = ids;
        }

        private JsonObject build(final long seq) {
            final GameView view = game.getView();
            final JsonObject state = new JsonObject();
            state.addProperty("type", "state");
            state.addProperty("seq", seq);
            state.addProperty("turn", view.getTurn());
            state.add("activePlayerId", playerRef(view.getPlayerTurn()));
            state.add("priorityPlayerId", playerRef(PlayerView.get(game.getPhaseHandler().getPriorityPlayer())));
            state.addProperty("phase", view.getPhase() == null ? null : view.getPhase().name());
            state.add("monarchId", playerRef(PlayerView.get(game.getMonarch())));
            state.add("initiativeId", playerRef(PlayerView.get(game.getHasInitiative())));
            for (final PlayerView player : view.getPlayers()) {
                if (player.getCommanders() != null) {
                    commanders.addAll(player.getCommanders());
                }
            }
            final JsonArray players = new JsonArray();
            for (final PlayerView player : view.getPlayers()) {
                players.add(player(player));
            }
            state.add("players", players);
            final JsonArray stack = new JsonArray();
            for (final StackItemView item : view.getStack()) {
                stack.add(stackItem(item));
            }
            state.add("stack", stack);
            final JsonObject input = new JsonObject();
            // View-only seats wait. JsonSeatRemote overlays its display tracker after this walk,
            // once endSnapshot has published all card refs; the serializer never reads Inputs.
            input.addProperty("kind", "waiting");
            state.add("activeInput", input);
            return state;
        }

        private JsonObject player(final PlayerView player) {
            final JsonObject result = new JsonObject();
            result.addProperty("id", player.getId());
            result.addProperty("name", player.getName());
            result.addProperty("life", player.getLife());
            result.addProperty("poison", player.getCounters(CounterEnumType.POISON));
            result.addProperty("eliminated", player.getHasLost());
            final JsonArray damage = new JsonArray();
            for (final CardView commander : commanders) {
                final JsonObject entry = new JsonObject();
                entry.add("commanderId", cardInfo(commander).ref());
                entry.addProperty("damage", player.getCommanderDamage(commander));
                damage.add(entry);
            }
            result.add("commanderDamage", damage);
            final JsonArray owned = new JsonArray();
            if (player.getCommanders() != null) {
                for (final CardView commander : player.getCommanders()) {
                    final JsonObject entry = new JsonObject();
                    entry.add("cardId", cardInfo(commander).ref());
                    final int casts = player.getCommanderCast(commander);
                    entry.addProperty("casts", casts);
                    // CostAdjustment: base command-zone surcharge, before other cost adjustments.
                    entry.addProperty("tax", 2 * casts);
                    owned.add(entry);
                }
            }
            result.add("commanders", owned);
            final JsonObject mana = new JsonObject();
            final String[] colors = {"W", "U", "B", "R", "G", "C"};
            for (int i = 0; i < colors.length; i++) {
                mana.addProperty(colors[i], player.getMana(MagicColor.WUBRGC[i]));
            }
            result.add("manaPool", mana);
            final JsonObject zones = new JsonObject();
            zones.add("hand", cardArray(player.getHand()));
            final JsonObject library = new JsonObject();
            library.addProperty("count", player.getZoneSize(ZoneType.Library));
            zones.add("library", library); // Never walk library identities for the zone payload.
            zones.add("graveyard", cardArray(player.getGraveyard()));
            zones.add("exile", cardArray(player.getExile()));
            zones.add("command", cardArray(player.getCommand()));
            zones.add("battlefield", cardArray(player.getBattlefield()));
            result.add("zones", zones);
            return result;
        }

        private CardInfo cardInfo(final CardView card) {
            return cards.computeIfAbsent(card.getId(), ignored -> {
                final CardVisibility visibility = CardVisibility.forSeat(card, seat);
                final ZoneType zone = card.getZone();
                // Evaluate all Forge getters before entering the mapper's leaf lock.
                final Object ref = ids.wireRef(card.getId(), zone == null ? null : zone.name(),
                        visibility.faceDown(), visibility.identityVisible());
                return new CardInfo(card, visibility.shown(), visibility.identityVisible(), visibility.faceDown(),
                        ref instanceof Integer id ? new JsonPrimitive(id) : new JsonPrimitive((String) ref));
            });
        }

        private JsonArray cardArray(final Iterable<CardView> views) {
            final JsonArray result = new JsonArray();
            if (views != null) {
                for (final CardView card : views) {
                    result.add(card(cardInfo(card)));
                }
            }
            return result;
        }

        private JsonObject card(final CardInfo info) {
            final JsonObject result = new JsonObject();
            result.add("id", info.ref());
            if (!info.shown()) {
                result.addProperty("hidden", true);
                return result;
            }
            final CardView view = info.card();
            Card live = game.findByView(view);
            if (live == null) {
                live = game.findById(view.getId());
            }
            if (live == null && !missingLiveCardLogged) {
                missingLiveCardLogged = true;
                LOG.log(System.Logger.Level.DEBUG,
                        "Card {0} has no live counterpart; using view fields (logged once per serializer)", view.getId());
            }
            final CardView.CardStateView current = view.getCurrentState();
            if (info.identityVisible()) {
                // CurrentState is the anonymous face-down face. Only an authorized seat gets
                // its alternate identity; public characteristics below still use CurrentState.
                final CardView.CardStateView identity = info.faceDown()
                        ? Objects.requireNonNull(view.getAlternateState(), "Face-down card has no identity state")
                        : current;
                result.addProperty("name", identity.getName());
                result.addProperty("set", identity.getSetCode());
                final IPaperCard paper = live == null ? null : live.getPaperCard();
                if (paper != null) {
                    result.addProperty("num", paper.getCollectorNumber());
                }
            }
            result.addProperty("tapped", view.isTapped());
            result.addProperty("sick", view.isSick());
            result.addProperty("power", current.getPower());
            result.addProperty("toughness", current.getToughness());
            result.addProperty("damage", view.getDamage());
            final JsonObject counters = new JsonObject();
            final Multiset<CounterType> counts = view.getCounters();
            if (counts != null) {
                for (final Multiset.Entry<CounterType> count : counts.entrySet()) {
                    counters.addProperty(count.getElement().toString(), count.getCount());
                }
            }
            result.add("counters", counters);
            result.add("attachedTo", entityRef(view.getEntityAttachedTo()));
            result.addProperty("attacking", view.isAttacking());
            result.addProperty("blocking", view.isBlocking());
            // A conceding player's views can outlive their live cards. Visibility still
            // comes exclusively from CardVisibility; only live-only optional fields vanish.
            if (live != null) {
                final Combat combat = game.getCombat();
                final GameEntity defender = combat == null ? null : combat.getDefenderByAttacker(live);
                result.add("attackTarget", entityRef(defender == null ? null : defender.getView()));
                final JsonArray blockers = new JsonArray();
                if (combat != null) {
                    for (final Card attacker : combat.getAttackersBlockedBy(live)) {
                        blockers.add(cardInfo(attacker.getView()).ref());
                    }
                }
                result.add("blockingIds", blockers);
            }
            result.addProperty("faceDown", info.faceDown());
            result.add("controllerId", playerRef(view.getController()));
            result.add("ownerId", playerRef(view.getOwner()));
            result.addProperty("token", view.isToken());
            result.addProperty("isCommander", view.isCommander());
            return result;
        }

        private JsonObject stackItem(final StackItemView item) {
            final JsonObject result = new JsonObject();
            result.addProperty("stackId", item.getId());
            final CardInfo source = cardInfo(item.getSourceCard());
            result.add("sourceCardId", source.ref());
            result.add("controllerId", playerRef(item.getActivatingPlayer()));
            String text = item.getText();
            if (!source.identityVisible()) {
                text = source.faceDown() ? "Face-down spell" : "Hidden ability";
            } else if (source.faceDown()) {
                // Forge's morph stack text is generic even for its controller. This is the
                // only identity-bearing field in StackItem; expose the name only after gating.
                text = source.card().getAlternateState().getName() + " - " + text;
            }
            result.addProperty("text", text);
            final JsonArray targets = new JsonArray();
            if (item.getTargetCards() != null) {
                for (final CardView target : item.getTargetCards()) {
                    targets.add(entityRef(target));
                }
            }
            if (item.getTargetPlayers() != null) {
                for (final PlayerView target : item.getTargetPlayers()) {
                    targets.add(entityRef(target));
                }
            }
            if (item.getTargetStackItems() != null) {
                for (final StackItemView target : item.getTargetStackItems()) {
                    targets.add(tagged("stack", new JsonPrimitive(target.getId())));
                }
            }
            result.add("targets", targets);
            return result;
        }

        private JsonElement entityRef(final GameEntityView entity) {
            if (entity == null) {
                return JsonNull.INSTANCE;
            }
            if (entity instanceof CardView card) {
                return tagged("card", cardInfo(card).ref());
            }
            if (entity instanceof PlayerView player) {
                return tagged("player", playerRef(player));
            }
            throw new IllegalArgumentException("Unsupported entity type: " + entity.getClass().getName());
        }

        private static JsonObject tagged(final String kind, final JsonElement id) {
            final JsonObject result = new JsonObject();
            result.addProperty("kind", kind);
            result.add("id", id);
            return result;
        }

        private static JsonElement playerRef(final PlayerView player) {
            return player == null ? JsonNull.INSTANCE : new JsonPrimitive(player.getId());
        }
    }
}
