package forge.arena.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEntity;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputConfirmMulligan;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.FThreads;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.testng.Assert.*;

/** Uses real Inputs and their latches; never forces a delta or resync to satisfy a step. */
public class CombatAndManaStateTest {
    private record Built(JsonObject state, long seq, String thread, boolean fullSnapshot) { }

    private static final ITriggerEvent RIGHT_CLICK = new ITriggerEvent() {
        public int getButton() { return 3; }
        public int getX() { return 0; }
        public int getY() { return 0; }
    };

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Built> built = new CopyOnWriteArrayList<>();
    private final List<Throwable> offThreadSnapshots = new CopyOnWriteArrayList<>();
    private final Set<CountDownLatch> parked = ConcurrentHashMap.newKeySet();
    private final SoftAssert steps = new SoftAssert();
    private volatile String stepName = "initialization";
    private volatile String colorPath;
    private JsonSeatRemote remote;
    private SeatProtocolGui gui;
    private Game game;
    private PlayerControllerHuman human;
    private IGameController controller;
    private JsonSchema schema;

    @BeforeClass
    public void initialize() {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                SchemaLocation.of(Path.of("../protocol/schema.json").toAbsolutePath().normalize().toUri()
                        + "#/$defs/state"));
    }

    private IRemote observe() {
        return new IRemote() {
            @Override
            public void send(final NetEvent event) {
                final long before = remote.getLastStateSequence();
                final long fullBefore = remote.getLastFullSnapshotSequence();
                remote.send(event);
                try {
                    assertFalse(remote.isDirty(), "Snapshot exception during " + stepName);
                    if (remote.getLastStateSequence() > before) {
                        final JsonObject state = remote.getLatestState();
                        final Built item = new Built(state, state.get("seq").getAsLong(), Thread.currentThread().getName(),
                                remote.getLastFullSnapshotSequence() > fullBefore);
                        built.add(item);
                        System.out.printf("[step-state] %s seq=%d thread=%s%n", stepName, item.seq(), item.thread());
                        if (item.fullSnapshot() && !item.thread().startsWith("Game-") && !item.thread().startsWith("AWT-EventQueue")) {
                            final Throwable trace = new AssertionError("Snapshot seq=" + item.seq()
                                    + " built on disallowed thread " + item.thread());
                            offThreadSnapshots.add(trace);
                            trace.printStackTrace(System.out);
                        }
                        final var errors = schema.validate(WsServer.GSON.toJson(state), InputFormat.JSON);
                        assertTrue(errors.isEmpty(), "State schema: " + errors);
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            }

            @Override
            public Object sendAndWait(final IdentifiableNetEvent event) {
                return remote.sendAndWait(event);
            }
        };
    }

    private Object answer(final GuiGameEvent event) {
        try {
            final Object[] args = event.getObjects();
            return switch (event.getMethod()) {
                case getAbilityToPlay -> ((List<?>) args[1]).stream()
                        .map(SpellAbilityView.class::cast)
                        .filter(ability -> !ability.toString().toLowerCase(java.util.Locale.ROOT).contains("cycling"))
                        .findFirst().orElseThrow();
                case getChoices -> {
                    final List<?> choices = (List<?>) args[3];
                    if (choices.contains(MagicColor.Color.BLUE)) {
                        colorPath = "getChoices via IGuiGame.one on " + Thread.currentThread().getName();
                        System.out.println("[step-color] " + colorPath);
                        yield List.of(MagicColor.Color.BLUE);
                    }
                    yield new ArrayList<>(choices.subList(0, Math.min(choices.size(), Math.max(1, (Integer) args[1]))));
                }
                case confirm -> args[2];
                case showConfirmDialog, showOptionDialog -> args[4];
                case showInputDialog -> args[3];
                default -> throw new AssertionError("Unscripted blocking call: " + event.getMethod());
            };
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
            throw ex;
        }
    }

    private static Deck deck(final String name) {
        final Deck deck = new Deck(name);
        deck.getMain().add("Plains", 98);
        deck.getMain().add("Drifting Meadow", 1);
        deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
        return deck;
    }

    private static GameState scenario(final boolean inputChecks) {
        final List<String> lines = new ArrayList<>(List.of("activeplayer=p0", "activephase=MAIN1",
                "turn=1", "removesummoningsickness=true"));
        for (int seat = 0; seat < 4; seat++) {
            final String p = "p" + seat;
            lines.add(p + "life=" + (seat == 0 ? 2 : 40));
            lines.add(p + "library=" + String.join(";", Collections.nCopies(10, "Forest")));
            lines.add(p + "hand=" + (seat == 0 ? "Balduvian Bears" + (inputChecks ? ";Shock" : "") : ""));
            lines.add(p + "battlefield=" + switch (seat) {
                case 0 -> "Grizzly Bears;Runeclaw Bear;Forest;Forest;Forest;Birds of Paradise"
                        + (inputChecks ? ";Mountain" : "");
                // Trample plus p0's low life gives the real AI a lethal attack to consider.
                case 1 -> "Colossal Dreadmaw;Colossal Dreadmaw;Wall of Wood";
                default -> "";
            });
            lines.add(p + "command=Isamaru, Hound of Konda|IsCommander");
            lines.add(p + "graveyard=");
            lines.add(p + "exile=");
        }
        final GameState state = new GameState();
        state.parse(lines);
        return state;
    }

    @Test(timeOut = 180_000)
    public void humanCombatAndManaProduceStatePerStep() throws Exception {
        runScenario(false);
    }

    // Shared real-Input harness: ActiveInputTest adds Shock and highlight assertions.
    void runScenario(final boolean inputChecks) throws Exception {
        MyRandom.setRandom(new java.util.Random(7));
        final HostedMatch match = new HostedMatch();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, inputChecks);
        FModel.getPreferences().setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, inputChecks);
        final WsServer server = new WsServer("127.0.0.1", 0);
        server.start();
        final SeatWriter writer = server.writer(0);
        final WsTestClient client = new WsTestClient();
        remote = new JsonSeatRemote(match, 0, writer);
        remote.setPromptResponder(this::answer);
        gui = new SeatProtocolGui(observe()) {
            @Override
            public void awaitInput(final CountDownLatch done) throws InterruptedException {
                parked.add(done);
                try {
                    done.await();
                } finally {
                    parked.remove(done);
                }
            }
        };
        final CountDownLatch configured = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        match.setStartGameHook(() -> {
            try {
                final Game running = match.getGame();
                running.getRules().setGamesPerMatch(1);
                scenario(inputChecks).applyToGame(running); // Same game-thread hook as CSubmenuPuzzleSolve.
                running.getPlayers().get(0).getController().getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            } finally {
                configured.countDown();
            }
        });
        match.setEndGameHook(finished::countDown);
        final List<RegisteredPlayer> players = new ArrayList<>();
        final RegisteredPlayer owner = RegisteredPlayer.forCommander(deck("Step human"))
                .setPlayer(new LobbyPlayerHuman("Step human"));
        players.add(owner);
        for (int i = 1; i < 4; i++) {
            players.add(RegisteredPlayer.forCommander(deck("Step AI " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("Step AI " + i)));
        }
        try {
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0");
            client.hello(0);
            // HostedMatch synchronously sets/opens the GUI before scheduling the game loop.
            // Its initial snapshots must use the same EDT as subsequent UI actions.
            edt(() -> {
                match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Map.of(owner, gui));
                game = match.getGame();
                human = (PlayerControllerHuman) game.getPlayers().get(0).getController();
                controller = gui.getGameController(human.getPlayer().getView());
                return null;
            });
            advanceUntil(input -> configured.getCount() == 0 && input instanceof InputPassPriority
                    && game.getPhaseHandler().getPlayerTurn() == human.getPlayer()
                    && game.getPhaseHandler().getPhase() == PhaseType.MAIN1, "scenario main phase", false);
            final List<Card> creatures = edt(() -> List.of(card("Grizzly Bears"), card("Runeclaw Bear")));
            if (inputChecks) {
                assertKind("priority");
                final Card shock = edt(() -> {
                    for (Card card : human.getPlayer().getCardsIn(ZoneType.Hand)) {
                        if (card.getName().equals("Shock")) return card;
                    }
                    throw new AssertionError("Missing Shock");
                });
                step("cast Shock", () -> controller.selectCard(shock.getView(), null, null),
                        InputSelectTargets.class, state -> assertEquals(kind(state), "target"));
                step("target p3", () -> controller.selectPlayer(game.getPlayers().get(3).getView(), null),
                        InputPayMana.class, state -> assertEquals(kind(state), "payMana"));
                step("tap Mountain", () -> controller.selectCard(card("Mountain").getView(), null, null),
                        InputPayMana.class, this::checkMana);
                step("pay red for Shock", () -> controller.useMana(MagicColor.RED), InputPassPriority.class, this::checkMana);
                advanceUntil(input -> input instanceof InputPassPriority && game.getStack().isEmpty(), "Shock resolves", false);
            }
            final Card spell = edt(() -> {
                for (Card card : human.getPlayer().getCardsIn(ZoneType.Hand)) {
                    if (card.getName().equals("Balduvian Bears")) return card;
                }
                throw new AssertionError("Missing Balduvian Bears");
            });
            final List<Card> forests = edt(() -> {
                final List<Card> result = new ArrayList<>();
                for (Card card : human.getPlayer().getCardsIn(ZoneType.Battlefield)) {
                    if (card.getName().equals("Forest")) result.add(card);
                }
                return result;
            });
            final Card birds = edt(() -> card("Birds of Paradise"));
            assertEquals(forests.size(), 3);
            step("cast Balduvian Bears", () -> controller.selectCard(spell.getView(), null, null),
                    InputPayMana.class, this::checkMana);
            for (int i = 0; i < forests.size(); i++) {
                final Card forest = forests.get(i);
                step("tap Forest " + (i + 1), () -> controller.selectCard(forest.getView(), null, null),
                        InputPayMana.class, state -> { assertTrue(forest.isTapped()); checkMana(state); });
            }
            // Leave only generic mana due so Birds offers all colors rather than autochoosing green.
            step("pay green from pool", () -> controller.useMana(MagicColor.GREEN), InputPayMana.class, this::checkMana);
            step("tap Birds and choose blue", () -> controller.selectCard(birds.getView(), null, null),
                    InputPayMana.class, state -> {
                        assertTrue(birds.isTapped());
                        assertNotNull(colorPath, "Birds must actually ask for a color");
                        assertEquals(human.getPlayer().getManaPool().getAmountOfColor(MagicColor.BLUE), 1);
                        checkMana(state);
                    });
            step("pay blue for generic", () -> controller.useMana(MagicColor.BLUE), InputPassPriority.class, this::checkMana);
            advanceUntil(input -> input instanceof InputAttack, "p0 declare attackers", false);
            // Forge defaults to p1; first move elsewhere so the tested click changes chosen.
            edt(() -> { controller.selectPlayer(game.getPlayers().get(3).getView(), null); return null; });
            selectionStep("select defender p1", () -> controller.selectPlayer(game.getPlayers().get(1).getView(), null),
                    InputAttack.class, "player", edt(() -> game.getPlayers().get(1).getId()));
            awaitWire(client);
            step("add attacker A", () -> controller.selectCard(creatures.get(0).getView(), null, null),
                    InputAttack.class, this::checkCombat);
            step("add attacker B", () -> controller.selectCard(creatures.get(1).getView(), null, null),
                    InputAttack.class, this::checkCombat);
            selectionStep("switch defender to p2", () -> controller.selectPlayer(game.getPlayers().get(2).getView(), null),
                    InputAttack.class, "player", edt(() -> game.getPlayers().get(2).getId()));
            awaitWire(client);
            step("re-add B against p2", () -> controller.selectCard(creatures.get(1).getView(), null, null),
                    InputAttack.class, this::checkCombat);
            step("remove attacker A", () -> controller.selectCard(creatures.get(0).getView(), null, RIGHT_CLICK),
                    InputAttack.class, this::checkCombat);
            edt(() -> { controller.selectButtonOk(); return null; });
            advanceUntil(input -> input instanceof InputBlock, "p1 attacks p0", true);
            final Card attacker = edt(() -> {
                final Combat combat = game.getCombat();
                assertEquals(combat.getAttackingPlayer(), game.getPlayers().get(1));
                for (Card card : combat.getAttackers()) {
                    if (card.getName().equals("Colossal Dreadmaw") && combat.getDefenderByAttacker(card) == human.getPlayer()) {
                        return card;
                    }
                }
                throw new AssertionError("AI did not attack p0 with Colossal Dreadmaw during p1's turn");
            });
            final List<Card> blockers = edt(() -> List.of(card("Grizzly Bears"), card("Balduvian Bears")));
            // InputBlock preselects an attacker. Choose the other legal attacker first.
            edt(() -> {
                final Card other = game.getCombat().getAttackers().stream().filter(card -> card != attacker)
                        .findFirst().orElseThrow(() -> new AssertionError("AI must attack with both Dreadmaws"));
                controller.selectCard(other.getView(), null, null);
                return null;
            });
            selectionStep("select attacking Dreadmaw", () -> controller.selectCard(attacker.getView(), null, null),
                    InputBlock.class, "card", attacker.getId());
            awaitWire(client);
            for (Card blocker : blockers) {
                step("block with " + blocker.getName(), () -> controller.selectCard(blocker.getView(), null, null),
                        InputBlock.class, state -> { assertTrue(game.getCombat().isBlocking(blocker, attacker)); checkCombat(state); });
            }
            healthy();
            steps.assertAll();
            if (inputChecks) checkInputCoverage();
            awaitWire(client);
            reportWireRate(client);
        } finally {
            stepName = "cleanup";
            if (game != null && !game.isGameOver()) {
                // Cleanup is game-thread work too. Release the real input latch after ending it.
                edt(() -> {
                    game.getAction().invoke(() -> {
                        // A winner makes HostedMatch.isMatchOver() a public completion signal
                        // AFTER its final forwarder flush (the end hook fires before that flush).
                        game.getRules().setGamesPerMatch(1);
                        for (var player : new ArrayList<>(game.getPlayers())) {
                            if (player != human.getPlayer()) player.concede();
                        }
                        game.setGameOver(GameEndReason.AllOpponentsLost);
                        human.getInputQueue().onGameOver(true);
                    });
                    return null;
                });
                assertTrue(finished.await(5, TimeUnit.SECONDS), "Game cleanup fired end-game hook");
            }
            final long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!edt(match::isMatchOver) && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
            assertTrue(edt(match::isMatchOver), "HostedMatch finished its post-game forwarder flush");
            // afterGameEnd synchronously calls updateGameView; teardown is UI work too.
            edt(() -> { match.endCurrentGame(); return null; });
            client.close();
            server.close();
            FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
            FModel.getPreferences().setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, false);
        }
        healthy();
        assertTrue(offThreadSnapshots.isEmpty(), "Snapshots outside Game-/AWT-EventQueue threads: " + offThreadSnapshots);
    }

    private void selectionStep(final String name, final Runnable action,
                               final Class<? extends Input> expected, final String chosenKind, final int chosenId) throws Exception {
        stepName = name;
        final JsonObject before = edt(() -> {
            assertTrue(expected.isInstance(human.getInputQueue().getInput()));
            assertTrue(ready(human.getInputQueue().getInput()));
            final JsonObject state = remote.getLatestState();
            checkCombat(state);
            return state;
        });
        final long displays = remote.getDisplayCallCount();
        final long fullBefore = remote.getLastFullSnapshotSequence();
        edt(() -> { action.run(); return null; });
        // A second EDT task is the barrier after the action and its queued display work.
        edt(() -> {
            healthy();
            assertTrue(expected.isInstance(human.getInputQueue().getInput()));
            assertTrue(ready(human.getInputQueue().getInput()));
            assertTrue(remote.getDisplayCallCount() > displays, name + " must emit a display call");
            // Compare the PRE-action snapshot to live Combat after the selection: this proves
            // attacking, attackTarget, blocking and blockingIds did not change, even without a delta.
            checkCombat(before);
            final JsonObject after = remote.getLatestState();
            checkCombat(after);
            assertTrue(after.get("seq").getAsLong() > before.get("seq").getAsLong(), name + " needs a new state");
            assertEquals(remote.getLastFullSnapshotSequence(), fullBefore, "Selection copies JSON without a graph walk");
            final JsonObject input = after.getAsJsonObject("activeInput");
            final JsonObject chosen = new JsonObject();
            chosen.addProperty("kind", chosenKind);
            final Object id = chosenKind.equals("card") ? remote.getIds().currentRef(chosenId).orElseThrow() : chosenId;
            if (id instanceof Integer number) chosen.addProperty("id", number);
            else chosen.addProperty("id", (String) id);
            assertTrue(input.getAsJsonArray("chosen").contains(chosen), name + ": " + input);
            if (expected == InputAttack.class) {
                assertEquals(kind(after), "declareAttackers");
                final com.google.gson.JsonArray defenders = new com.google.gson.JsonArray();
                for (GameEntity defender : game.getCombat().getDefenders()) {
                    final JsonObject ref = new JsonObject();
                    ref.addProperty("kind", defender instanceof Card ? "card" : "player");
                    final Object mapped = defender instanceof Card ? remote.getIds().currentRef(defender.getId()).orElseThrow() : defender.getId();
                    if (mapped instanceof Integer number) ref.addProperty("id", number);
                    else ref.addProperty("id", (String) mapped);
                    defenders.add(ref);
                }
                assertEquals(input.getAsJsonArray("attackTargets"), defenders);
            } else assertEquals(kind(after), "declareBlockers");
            assertEquals(ownPlayer(after).getAsJsonObject("zones").getAsJsonArray("battlefield"),
                    ownPlayer(before).getAsJsonObject("zones").getAsJsonArray("battlefield"));
            System.out.printf("[step-selection] %s displayCalls=%d->%d seq=%d->%d combat unchanged%n",
                    name, displays, remote.getDisplayCallCount(), before.get("seq").getAsLong(), after.get("seq").getAsLong());
            return null;
        });
    }

    private Card card(final String name) {
        for (Card card : human.getPlayer().getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(card.getName())) return card;
        }
        throw new AssertionError("Missing battlefield card " + name);
    }

    private static String kind(final JsonObject state) {
        return state.getAsJsonObject("activeInput").get("kind").getAsString();
    }

    private void assertKind(final String expected) throws Exception {
        edt(() -> { assertEquals(kind(remote.getLatestState()), expected); return null; });
    }

    private void checkInputCoverage() throws Exception {
        for (String expected : List.of("mulligan", "priority", "payMana", "target", "declareAttackers", "declareBlockers")) {
            assertTrue(built.stream().anyMatch(item -> {
                final JsonObject input = item.state().getAsJsonObject("activeInput");
                return kind(item.state()).equals(expected) && input.has("prompt") && !input.get("prompt").getAsString().isBlank()
                        && input.has("okLabel") && !input.get("okLabel").getAsString().isBlank()
                        && input.has("buttons");
            }), "Missing live kind/prompt/buttons: " + expected);
        }
        final int ownerId = edt(() -> human.getPlayer().getId());
        assertTrue(built.stream().anyMatch(item -> kind(item.state()).equals("waiting")
                && !item.state().get("activePlayerId").isJsonNull()
                && item.state().get("activePlayerId").getAsInt() != ownerId), "Waiting while AI acts");
        assertTrue(built.stream().anyMatch(item -> {
            final JsonObject input = item.state().getAsJsonObject("activeInput");
            if (!kind(item.state()).equals("payMana") || !input.has("highlighted")) return false;
            for (JsonElement highlight : input.getAsJsonArray("highlighted")) {
                if (highlight.getAsJsonObject().get("strength").getAsInt() == 2) return true;
            }
            return false;
        }), "Auto-tap preview must push duplicate mana sources (strength 2)");

        // Exercise the bare-send timer path while the real Input remains parked. This must
        // copy JSON and reuse the last kind, never query InputQueue or walk Forge's graph.
        final long fullBefore = remote.getLastFullSnapshotSequence();
        final String beforeKind = kind(remote.getLatestState());
        final FutureTask<Void> timer = new FutureTask<>(() -> {
            gui.showPromptMessage(null, "Timer display test", (forge.game.card.CardView) null);
            final long copied = remote.getLastStateSequence();
            gui.showPromptMessage(null, "Timer display test", (forge.game.card.CardView) null);
            assertEquals(remote.getLastStateSequence(), copied, "Identical display JSON sends no redundant state");
            return null;
        });
        new Thread(timer, "Arena-test-auto-pass-timer").start();
        timer.get(5, TimeUnit.SECONDS);
        assertEquals(remote.getLastFullSnapshotSequence(), fullBefore);
        assertEquals(kind(remote.getLatestState()), beforeKind);
        assertEquals(remote.getLatestState().getAsJsonObject("activeInput").get("prompt").getAsString(), "Timer display test");
    }

    private void awaitWire(final WsTestClient client) throws Exception {
        final long seq = remote.getLastStateSequence();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (client.history().stream().map(WsTestClient.Received::object).anyMatch(message ->
                    "state".equals(message.get("type").getAsString()) && message.get("seq").getAsLong() >= seq)) return;
            healthy();
            Thread.sleep(10);
        }
        fail("Writer did not send state >= " + seq);
    }

    private void reportWireRate(final WsTestClient client) {
        final Set<Long> copies = new java.util.HashSet<>();
        built.stream().filter(item -> !item.fullSnapshot()).forEach(item -> copies.add(item.seq()));
        final List<Long> arrivals = new ArrayList<>();
        for (WsTestClient.Received received : client.history()) {
            final JsonObject message = received.object();
            if (!"state".equals(message.get("type").getAsString())) continue;
            assertTrue(schema.validate(received.json(), InputFormat.JSON).isEmpty(), "Wire state validates");
            if (copies.contains(message.get("seq").getAsLong())) arrivals.add(received.at());
        }
        assertFalse(arrivals.isEmpty(), "At least one activeInput-only state reached the socket");
        int peak = 0;
        for (long at : arrivals) {
            final int count = (int) arrivals.stream().filter(time -> time >= at && time - at < TimeUnit.SECONDS.toNanos(1)).count();
            peak = Math.max(peak, count);
        }
        assertTrue(peak <= 10, "Coalesced activeInput-only peak/s: " + peak);
        System.out.printf("[input-wire] activeInput-only=%d peak/s=%d%n", arrivals.size(), peak);
    }

    private void healthy() {
        if (failure.get() != null) throw new AssertionError("Bridge failed during " + stepName, failure.get());
        assertFalse(remote.isDirty(), "Dirty seat during " + stepName);
    }

    private boolean ready(final Input input) {
        return input != null && parked.stream().anyMatch(latch -> latch.getCount() != 0)
                && (!(input instanceof InputPayMana pay) || !pay.isActivatingManaAbility());
    }

    private void advanceUntil(final Predicate<Input> target, final String label, final boolean p1Only) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        final AtomicReference<Input> lastPassed = new AtomicReference<>();
        while (System.nanoTime() < deadline) {
            healthy();
            if (edt(() -> {
                final Input input = human.getInputQueue().getInput();
                if (!ready(input)) return false;
                if (target.test(input)) return true;
                // The coin toss can ask this human to choose the starting player. This is
                // a real selectable Player input, not a blocking getChoices dialog.
                if (stepName.equals("initialization") && input instanceof InputSelectEntitiesFromList<?> entities
                        && entities.getValidChoices().contains(human.getPlayer()) && input != lastPassed.get()) {
                    lastPassed.set(input);
                    controller.selectPlayer(human.getPlayer().getView(), null);
                    return false;
                }
                if (p1Only && game.getPhaseHandler().getPlayerTurn() != human.getPlayer()
                        && game.getPhaseHandler().getPlayerTurn() != game.getPlayers().get(1)) {
                    fail("AI did not attack p0 within p1's turn; scenario was not loosened");
                }
                if (input != lastPassed.get() && (input instanceof InputPassPriority || input instanceof InputConfirmMulligan)) {
                    lastPassed.set(input);
                    controller.selectButtonOk();
                }
                return false;
            })) return;
            Thread.sleep(10);
        }
        fail("Timed out reaching " + label + "; input=" + edt(() -> human.getInputQueue().getInput()));
    }

    private void step(final String name, final Runnable action, final Class<? extends Input> expected,
                      final Consumer<JsonObject> verify) throws Exception {
        stepName = name;
        final long before = remote.getLastStateSequence();
        edt(() -> { action.run(); return null; });
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            healthy();
            // An EDT sentinel runs after the action/display work. Mana worker completion is
            // checked separately through InputPayMana's public locked-state accessor.
            if (edt(() -> {
                final Input input = human.getInputQueue().getInput();
                if (!expected.isInstance(input) || !ready(input) || remote.getLastStateSequence() <= before) return false;
                final JsonObject state = remote.getLatestState();
                verify.accept(state); // Game thread is parked, and mana worker has finished.
                final List<Built> updates = built.stream().filter(item -> item.seq() > before).toList();
                boolean firstMatches = true;
                try { verify.accept(updates.get(0).state()); } catch (AssertionError lag) { firstMatches = false; }
                System.out.printf("[step-check] %s seq=%d->%d updates=%d firstMatches=%s threads=%s%n",
                        name, before, remote.getLastStateSequence(), updates.size(), firstMatches,
                        updates.stream().map(Built::thread).distinct().toList());
                return true;
            })) return;
            Thread.sleep(10);
        }
        // Retain this failure, but exercise later combat edits while the same Input is parked.
        // This does not relax the assertion: assertAll at the end still fails the entire test.
        final String detail = name + ": no new state within 5 s (seq " + before + " -> " + remote.getLastStateSequence() + ")";
        System.out.println("[step-missing] " + detail);
        steps.fail(detail);
        edt(() -> { assertTrue(expected.isInstance(human.getInputQueue().getInput())); return null; });
    }

    private JsonObject ownPlayer(final JsonObject state) {
        for (JsonElement player : state.getAsJsonArray("players")) {
            if (player.getAsJsonObject().get("id").getAsInt() == human.getPlayer().getId()) return player.getAsJsonObject();
        }
        throw new AssertionError("Missing seat 0");
    }

    private JsonObject wireCard(final JsonObject state, final Card card) {
        for (JsonElement item : ownPlayer(state).getAsJsonObject("zones").getAsJsonArray("battlefield")) {
            final JsonElement ref = item.getAsJsonObject().get("id");
            final Object wire = ref.getAsJsonPrimitive().isString() ? ref.getAsString() : Integer.valueOf(ref.getAsInt());
            if (remote.getIds().resolve(wire).orElse(-1) == card.getId()) return item.getAsJsonObject();
        }
        throw new AssertionError("Missing wire card " + card.getName());
    }

    private void checkMana(final JsonObject state) {
        final JsonObject mana = ownPlayer(state).getAsJsonObject("manaPool");
        final String[] colors = {"W", "U", "B", "R", "G", "C"};
        for (int i = 0; i < colors.length; i++) {
            assertEquals(mana.get(colors[i]).getAsInt(), human.getPlayer().getManaPool().getAmountOfColor(MagicColor.WUBRGC[i]), colors[i]);
        }
        for (Card card : human.getPlayer().getCardsIn(ZoneType.Battlefield)) {
            assertEquals(wireCard(state, card).get("tapped").getAsBoolean(), card.isTapped(), card.getName());
        }
    }

    private void checkCombat(final JsonObject state) {
        final Combat combat = game.getCombat();
        for (Card card : human.getPlayer().getCreaturesInPlay()) {
            final JsonObject wire = wireCard(state, card);
            assertEquals(wire.get("attacking").getAsBoolean(), combat.isAttacking(card), card.getName());
            final GameEntity target = combat.getDefenderByAttacker(card);
            if (target == null) {
                assertTrue(wire.get("attackTarget").isJsonNull(), card.getName());
            } else {
                final JsonObject ref = wire.getAsJsonObject("attackTarget");
                assertEquals(ref.get("kind").getAsString(), "player");
                assertEquals(ref.get("id").getAsInt(), target.getId());
            }
            assertEquals(wire.get("blocking").getAsBoolean(), combat.isBlocking(card), card.getName());
            final List<Integer> expected = new ArrayList<>();
            for (Card attacker : combat.getAttackersBlockedBy(card)) expected.add(attacker.getId());
            final List<Integer> actual = new ArrayList<>();
            for (JsonElement ref : wire.getAsJsonArray("blockingIds")) {
                final Object value = ref.getAsJsonPrimitive().isString() ? ref.getAsString() : Integer.valueOf(ref.getAsInt());
                actual.add(remote.getIds().resolve(value).orElseThrow());
            }
            assertEquals(actual, expected, card.getName());
        }
    }

    private static <T> T edt(final Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        FThreads.invokeInEdtLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
