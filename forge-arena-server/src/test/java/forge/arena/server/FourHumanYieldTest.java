package forge.arena.server;

import com.google.gson.*;
import com.google.common.eventbus.Subscribe;
import forge.deck.*;
import forge.game.*;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.*;
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.*;
import org.testng.annotations.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.testng.Assert.*;

/** Real four-human matches, native Inputs and socket actions; no yield predicates are mocked. */
public class FourHumanYieldTest {
    @BeforeClass public void initialize() {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
    }
    private record Wait(int seat, int active, int turn, PhaseType phase, Class<?> input, int stack) { }
    public static final class TurnStartGate implements AutoCloseable {
        private record Turn(int player, int number, CountDownLatch release) { }
        final Set<Integer> opponents;
        final Queue<Turn> pending = new ConcurrentLinkedQueue<>();
        final List<Turn> all = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        TurnStartGate(Set<Integer> opponents) { this.opponents = opponents; }
        @Subscribe public void turn(GameEventTurnBegan event) throws InterruptedException {
            if (closed || !opponents.contains(event.turnOwner().getId())) return;
            Turn turn = new Turn(event.turnOwner().getId(), event.turnNumber(), new CountDownLatch(1));
            all.add(turn); pending.add(turn); if (closed) turn.release().countDown();
            // Test-only safe point: park the game while the client's turn-start update runs on EDT.
            assertTrue(turn.release().await(10, TimeUnit.SECONDS), "Client re-armed the turn-start yield");
        }
        @Override public void close() { closed = true; all.forEach(t -> t.release().countDown()); }
    }

    private static final class FourSeats implements AutoCloseable {
        final WsServer server = new WsServer("127.0.0.1", 0);
        final HostedMatch match = new HostedMatch();
        final List<RegisteredPlayer> players = new ArrayList<>();
        final JsonSeatRemote[] remotes = new JsonSeatRemote[4];
        final SeatProtocolGui[] guis = new SeatProtocolGui[4];
        final PlayerControllerHuman[] humans = new PlayerControllerHuman[4];
        final WsTestClient[] clients = new WsTestClient[4];
        final JsonObject[] states = new JsonObject[4];
        final List<Wait> waits = new CopyOnWriteArrayList<>();
        final List<Integer> prompts = new CopyOnWriteArrayList<>();
        final Map<Input, Boolean> parked = new ConcurrentHashMap<>();
        final Map<Input, Long> answered = new IdentityHashMap<>(); // EDT only
        final long[] clientSeq = new long[4], inFlight = {-1, -1, -1, -1};
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final forge.arena.server.view.ActiveInputTracker classifier = new forge.arena.server.view.ActiveInputTracker();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean configured;
        Game game;

        FourSeats(String[] battlefield, String[] hand, int active) throws Exception {
            try {
            FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, true);
            Map<RegisteredPlayer, IGuiGame> map = new HashMap<>();
            server.start();
            for (int i = 0; i < 4; i++) {
                final int seat = i;
                Deck deck = new Deck("Four human " + i); deck.getMain().add("Plains", 99);
                deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
                RegisteredPlayer player = RegisteredPlayer.forCommander(deck).setPlayer(new LobbyPlayerHuman("Yield " + i));
                players.add(player);
                remotes[i] = new JsonSeatRemote(match, player, server.writer(i));
                remotes[i].setSnapshotBuiltObserver(state -> {
                    try {
                        String thread = Thread.currentThread().getName();
                        assertTrue(thread.startsWith("Game-") || thread.startsWith("AWT-EventQueue"), thread);
                        PromptAssertions.validate(state, "state");
                    } catch (Throwable ex) { failure.compareAndSet(null, ex); }
                });
                guis[i] = new SeatProtocolGui(remotes[i]) {
                    @Override public void awaitInput(CountDownLatch done) throws InterruptedException {
                        if (humans[seat] == null) humans[seat] = (PlayerControllerHuman) ArenaServerMain.playerFor(match.getGame(), players.get(seat)).getController();
                        Input input = humans[seat].getInputQueue().getInput(); parked.put(input, true);
                        if (configured) waits.add(new Wait(seat, index(game.getPhaseHandler().getPlayerTurn().getRegisteredPlayer()),
                                game.getPhaseHandler().getTurn(), game.getPhaseHandler().getPhase(), input.getClass(), game.getStack().size()));
                        try { super.awaitInput(done); } finally { parked.remove(input); }
                    }
                };
                map.put(player, guis[i]);
                clients[i] = new WsTestClient();
                clients[i].onMessage = message -> {
                    try {
                        String type = message.get("type").getAsString();
                        if (type.equals("state")) { PromptAssertions.validate(message, "state"); synchronized (states) { states[seat] = message; } }
                        if (type.equals("prompt")) {
                            if (configured) prompts.add(seat);
                            clients[seat].send(PromptAssertions.reply(message, message.get("default")));
                        }
                        if (type.equals("message") && message.get("level").getAsString().equals("rejected"))
                            failure.compareAndSet(null, new AssertionError(message.toString()));
                    } catch (Throwable ex) { failure.compareAndSet(null, ex); }
                };
                clients[i].connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=" + i);
            }
            match.setStartGameHook(() -> {
                game.getRules().setGamesPerMatch(1);
                // GameState creates ordinary puzzle cards unsick; preserve explicit |SummonSick.
                List<String> lines = new ArrayList<>(List.of("activeplayer=p" + active, "activephase=MAIN1", "turn=1", "removesummoningsickness=false"));
                for (int i = 0; i < 4; i++) {
                    lines.add("p" + i + "life=40"); lines.add("p" + i + "battlefield=" + battlefield[i]);
                    lines.add("p" + i + "hand=" + hand[i]); lines.add("p" + i + "library=" + String.join(";", Collections.nCopies(12, "Plains")));
                    lines.add("p" + i + "command=Isamaru, Hound of Konda|IsCommander");
                    lines.add("p" + i + "graveyard="); lines.add("p" + i + "exile=");
                }
                GameState puzzle = new GameState(); puzzle.parse(lines); puzzle.applyToGame(game); configured = true;
            });
            match.setEndGameHook(finished::countDown);
            edt(() -> {
                match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, map);
                game = match.getGame();
                for (int i = 0; i < 4; i++) humans[i] = (PlayerControllerHuman) ArenaServerMain.playerFor(game, players.get(i)).getController();
            });
            for (int i = 0; i < 4; i++) clients[i].hello(i);
            driveUntil(() -> configured && ready(active, InputPassPriority.class), true);
            edt(() -> {
                for (var human : humans) {
                    assertTrue(human.getYieldController().getBoolPref(FPref.YIELD_INTERRUPT_ON_ATTACKERS));
                    assertTrue(human.getYieldController().getBoolPref(FPref.YIELD_INTERRUPT_ON_TARGETING));
                    assertFalse(human.getYieldController().getBoolPref(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL));
                }
            });
            assertFalse(state(active).get("gameOver").getAsBoolean(), "Live game state is not over");
            } catch (Exception | Error ex) {
                try { close(); } catch (Exception | Error cleanup) { ex.addSuppressed(cleanup); }
                throw ex;
            }
        }
        int index(RegisteredPlayer player) { return players.indexOf(player); }
        JsonObject state(int seat) { synchronized (states) { return states[seat]; } }
        void send(int seat, String type, Object... fields) {
            if (!type.equals("setYield")) {
                Runnable mark = () -> {
                    Input input = humans[seat].getInputQueue().getInput();
                    if (input != null) answered.put(input, remotes[seat].getDisplayCallCount());
                    inFlight[seat] = remotes[seat].completedActionCount();
                };
                if (FThreads.isGuiThread()) mark.run();
                else try { edt(mark); } catch (Exception ex) { throw new AssertionError(ex); }
            }
            JsonObject message = new JsonObject(); message.addProperty("type", type); message.addProperty("clientSeq", ++clientSeq[seat]);
            JsonObject state = state(seat); message.addProperty("baseSeq", state == null ? 0 : state.get("seq").getAsLong());
            for (int i = 0; i < fields.length; i += 2) message.add((String) fields[i], WsServer.GSON.toJsonTree(fields[i + 1]));
            PromptAssertions.validate(message, "ClientMessage"); clients[seat].send(message);
        }
        void yield(int seat, String kind, Object... fields) {
            Object[] args = new Object[fields.length + 2]; args[0] = "kind"; args[1] = kind; System.arraycopy(fields, 0, args, 2, fields.length);
            long before = remotes[seat].completedActionCount(); send(seat, "setYield", args);
            await(() -> remotes[seat].completedActionCount() > before);
        }
        boolean ready(int seat, Class<?> type) {
            Input input = humans[seat].getInputQueue().getInput(); JsonObject state = state(seat);
            if (!type.isInstance(input) || !parked.containsKey(input) || state == null) return false;
            String kind = classifier.classify(input);
            if (!state.getAsJsonObject("activeInput").get("kind").getAsString().equals(kind)) return false;
            return !(input instanceof InputConfirmMulligan) || PromptGameHarness.mulliganDisplayed(state);
        }
        void driveUntil(BooleanSupplier goal, boolean drive) throws Exception {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < limit) {
                if (failure.get() != null) throw new AssertionError("Four-seat guard", failure.get());
                if (PromptGameHarness.edt(() -> {
                    if (goal.getAsBoolean()) return true;
                    if (drive) for (int seat = 0; seat < 4; seat++) {
                        Input input = humans[seat].getInputQueue().getInput();
                        if (!ready(seat, Input.class) || remotes[seat].completedActionCount() <= inFlight[seat]) continue;
                        long calls = remotes[seat].getDisplayCallCount();
                        if (Objects.equals(answered.get(input), calls)) continue;
                        if (input instanceof InputSelectEntitiesFromList<?> choices && choices.getValidChoices().contains(humans[0].getPlayer())) {
                            answered.put(input, calls); inFlight[seat] = remotes[seat].completedActionCount();
                            send(seat, "selectPlayer", "playerId", humans[0].getPlayer().getId());
                        } else if (input instanceof InputPassPriority || input instanceof InputAttack || input instanceof InputBlock || input instanceof InputConfirmMulligan) {
                            answered.put(input, calls); inFlight[seat] = remotes[seat].completedActionCount(); send(seat, "ok");
                        }
                    }
                    return false;
                })) return;
                Thread.sleep(25);
            }
            PromptGameHarness.dumpThreads("Four human yield timeout"); fail("Four human goal timed out");
        }
        void await(BooleanSupplier goal) { try { driveUntil(goal, false); } catch (Exception ex) { throw new AssertionError(ex); } }
        void edt(Runnable action) throws Exception { PromptGameHarness.edt(() -> { action.run(); return null; }); }
        int card(int seat, String name, ZoneType zone) throws Exception {
            return PromptGameHarness.edt(() -> humans[seat].getPlayer().getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow().getId());
        }
        @Override public void close() throws Exception {
            try {
                if (game != null) {
                    edt(() -> game.getAction().invoke(() -> {
                        for (var player : new ArrayList<>(game.getPlayers())) if (player != humans[0].getPlayer()) player.concede();
                        game.getAction().checkGameOverCondition(); for (var human : humans) human.getInputQueue().onGameOver(true);
                    }));
                    assertTrue(finished.await(10, TimeUnit.SECONDS));
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!PromptGameHarness.edt(match::isMatchOver) && System.nanoTime() < limit) Thread.sleep(10);
                    assertTrue(PromptGameHarness.edt(match::isMatchOver), "Native final flush completed");
                    long sentLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!allOver() && System.nanoTime() < sentLimit) Thread.sleep(10);
                    assertTrue(allOver(), "Every socket receives gameOver=true in the final state");
                    edt(() -> { for (var gui : guis) gui.pause(); match.endCurrentGame(); });
                }
            } finally { for (var client : clients) if (client != null) client.close(); server.close(); }
        }
        boolean allOver() {
            for (int seat = 0; seat < 4; seat++) { JsonObject state = state(seat); if (state == null || !state.get("gameOver").getAsBoolean()) return false; }
            return true;
        }
    }

    @Test(timeOut = 120_000)
    public void fullRoundNeedsZeroNonActiveClicks() throws Exception {
        String[] board = Collections.nCopies(4, "Forest;Grizzly Bears|SummonSick").toArray(String[]::new);
        try (FourSeats h = new FourSeats(board, new String[]{"Forest", "Forest", "Forest", "Forest"}, 0)) {
            h.edt(() -> { for (var human : h.humans) assertTrue(human.getPlayer().getCreaturesInPlay().stream().allMatch(forge.game.card.Card::hasSickness)); });
            h.driveUntil(() -> h.game.getPhaseHandler().getTurn() >= 5 && h.game.getPhaseHandler().getPlayerTurn() == h.humans[0].getPlayer(), true);
            assertTrue(h.prompts.isEmpty(), "No post-setup prompt needed a reply");
            for (int seat = 0; seat < 4; seat++) {
                final int s = seat;
                var inactive = h.waits.stream().filter(w -> w.seat() == s && w.active() != s).toList();
                assertTrue(inactive.isEmpty(), "No non-active Input waits for seat " + s + ": " + inactive);
                assertTrue(h.waits.stream().anyMatch(w -> w.active() == s), "Turn visited for seat " + s);
                System.out.println("[four-human] zero-click seat=" + s + " non-active-priority-waits=0 prompts=0");
            }
        }
    }

    @Test(timeOut = 120_000)
    public void targetingInterruptsEotWithTheSpellStillOnStack() throws Exception {
        try (FourSeats h = spellGame("Shock")) {
            h.yield(1, "untilEndOfTurn", "active", true);
            int before = h.waits.size();
            cast(h, "Shock", h.card(1, "Grizzly Bears", ZoneType.Battlefield));
            h.driveUntil(() -> h.ready(1, InputPassPriority.class) && !h.game.getStack().isEmpty(), true);
            assertFalse(PromptGameHarness.edt(() -> h.humans[1].getYieldController().isYieldActive()));
            assertTrue(h.waits.stream().skip(before).anyMatch(w -> w.seat() == 1 && w.input() == InputPassPriority.class && w.stack() > 0));
            System.out.println("[four-human] targeting: seat 1 priority with Shock on stack; EOT yield cleared");
        }
    }

    @Test(timeOut = 120_000)
    public void opponentNonTargetingSpellDoesNotStopOtherSeats() throws Exception {
        try (FourSeats h = spellGame("Raging Goblin")) {
            int before = h.waits.size();
            cast(h, "Raging Goblin", null);
            h.driveUntil(() -> h.humans[2].getPlayer().getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals("Raging Goblin")), true);
            var stops = h.waits.stream().skip(before).filter(w -> w.seat() != 2).toList();
            assertTrue(stops.isEmpty(), "Non-casters (wire seats 0,1,3) do not stop: " + stops);
            assertTrue(h.prompts.isEmpty());
            System.out.println("[four-human] non-targeting Raging Goblin: seats 0,1,3 had zero waits/prompts");
        }
    }

    @Test(timeOut = 120_000)
    public void rearmAtEveryOpponentTurnUntilTheOwnersNextTurn() throws Exception {
        try (FourSeats h = new FourSeats(Collections.nCopies(4, "Forest;Grizzly Bears|SummonSick").toArray(String[]::new),
                new String[]{"Forest", "Forest", "Forest", "Forest"}, 0)) {
            Set<Integer> opponents = new HashSet<>(); for (int i = 1; i < 4; i++) opponents.add(h.humans[i].getPlayer().getId());
            try (TurnStartGate turns = new TurnStartGate(opponents)) {
            h.edt(() -> h.game.subscribeToEvents(turns));
            for (int opponent = 1; opponent < 4; opponent++) {
                final int next = opponent;
                h.driveUntil(() -> !turns.pending.isEmpty(), true);
                var turn = turns.pending.remove(); assertEquals(turn.player(), h.humans[next].getPlayer().getId());
                h.yield(0, "untilEndOfTurn", "active", true);
                h.await(() -> h.humans[0].getYieldController().isYieldActive());
                System.out.println("[four-human] rearmed seat 0 at opponent " + next + " turn-start " + turn.number());
                turn.release().countDown();
            }
            h.driveUntil(() -> h.game.getPhaseHandler().getTurn() >= 5 && h.game.getPhaseHandler().getPlayerTurn() == h.humans[0].getPlayer(), true);
            assertTrue(h.waits.stream().noneMatch(w -> w.seat() == 0 && w.active() != 0), "No seat-0 waits on opponent turns");
            assertTrue(h.prompts.isEmpty());
            assertFalse(PromptGameHarness.edt(() -> h.humans[0].getYieldController().isYieldActive()), "Cleanup clears final opponent-turn yield");
            }
        }
    }

    @Test(timeOut = 90_000)
    public void consoleLabelsAiSeats() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.start("Forest", "Forest");
            try (SeatAbandonment console = new SeatAbandonment(h.server, new java.io.StringReader(""), System.out, 180, System::nanoTime)) {
                String seats = console.submit("seats").get(5, TimeUnit.SECONDS);
                for (int seat = 1; seat < 4; seat++) {
                    final int index = seat;
                    String line = seats.lines().filter(s -> s.startsWith("Seat " + index + ":")).findFirst().orElseThrow();
                    assertTrue(line.contains(": AI"), line); assertFalse(line.contains("unbound") || line.contains("disconnected"), line);
                }
            }
        }
    }

    private static FourSeats spellGame(String spell) throws Exception {
        return new FourSeats(new String[]{"Forest", "Grizzly Bears", "Mountain", "Forest"}, new String[]{"Forest", "", spell + ";Forest", "Forest"}, 2);
    }
    private static void cast(FourSeats h, String name, Integer target) throws Exception {
        int source = h.card(2, name, ZoneType.Hand);
        h.send(2, "selectCard", "cardId", source);
        if (target != null) {
            h.await(() -> h.ready(2, InputSelectTargets.class)); h.send(2, "selectCard", "cardId", target);
        }
        h.await(() -> h.ready(2, InputPayMana.class));
        h.send(2, "selectCard", "cardId", h.card(2, "Mountain", ZoneType.Battlefield));
        h.await(() -> !h.game.getStack().isEmpty());
    }

    @Test(timeOut = 120_000)
    public void attackInterruptsEotEvenWithBlockersPhaseSkipped() throws Exception {
        try (FourSeats h = new FourSeats(new String[]{"Forest", "Grizzly Bears", "Forest;Grizzly Bears", "Forest"},
                new String[]{"Forest", "", "Forest", "Forest"}, 2)) {
            h.yield(1, "skipPhase", "turnPlayerId", h.humans[2].getPlayer().getId(), "phase", "COMBAT_DECLARE_BLOCKERS", "skip", true);
            h.yield(1, "untilEndOfTurn", "active", true);
            h.await(() -> h.humans[1].getYieldController().isYieldActive());
            h.driveUntil(() -> h.ready(2, InputAttack.class), true);
            JsonObject defender = new JsonObject(); defender.addProperty("kind", "player"); defender.addProperty("id", h.humans[1].getPlayer().getId());
            int attacker = h.card(2, "Grizzly Bears", ZoneType.Battlefield);
            h.send(2, "declareAttacker", "attackerId", attacker, "defenderId", defender);
            h.await(() -> h.game.getCombat().isAttacking(h.game.findById(attacker)));
            h.send(2, "ok");
            h.driveUntil(() -> h.ready(1, InputBlock.class), true);
            assertTrue(PromptGameHarness.edt(() -> h.humans[1].getYieldController().isSkippingPhase(h.humans[2].getPlayer().getView(), PhaseType.COMBAT_DECLARE_BLOCKERS)));
            assertFalse(PromptGameHarness.edt(() -> h.humans[1].getYieldController().isYieldActive()));
            System.out.println("[four-human] attack: seat 1 declares blockers despite EOT yield and skipPhase");
        }
    }
}
