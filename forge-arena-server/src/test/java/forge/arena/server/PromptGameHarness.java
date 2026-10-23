package forge.arena.server;

import com.google.gson.*;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.*;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.event.*;
import forge.gui.FThreads;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.*;
import forge.util.MyRandom;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import static org.testng.Assert.*;

/** The 1.4a real human/Input-latch harness, with socket replies instead of a PromptResponder. */
final class PromptGameHarness implements AutoCloseable {
    final HostedMatch match = new HostedMatch();
    final WsServer server = new WsServer("127.0.0.1", 0);
    final JsonSeatRemote remote;
    final SeatProtocolGui gui;
    final WsTestClient client = new WsTestClient();
    private final List<WsTestClient> clients = new CopyOnWriteArrayList<>();
    private WsTestClient replyClient;
    final List<JsonObject> prompts = new CopyOnWriteArrayList<>();
    final List<String> replyThreads = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Set<CountDownLatch> parked = ConcurrentHashMap.newKeySet();
    final CountDownLatch configured = new CountDownLatch(1), finished = new CountDownLatch(1);
    volatile Function<JsonObject, JsonElement> answer = p -> p.get("default");
    volatile boolean blockingConfirm;
    Game game;
    PlayerControllerHuman human;
    IGameController controller;

    static void initialize() {
        ArenaServerMain.initializeForge();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        FModel.getPreferences().setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, false);
    }

    PromptGameHarness() throws Exception {
        MyRandom.setRandom(new Random(7));
        server.start();
        remote = new JsonSeatRemote(match, 0, server.writer(0));
        gui = new SeatProtocolGui(new IRemote() {
            public void send(NetEvent event) {
                long before = remote.getLastFullSnapshotSequence();
                remote.send(event);
                try {
                    assertFalse(remote.isDirty(), "Snapshot failed");
                    if (remote.getLastFullSnapshotSequence() > before) {
                        String thread = Thread.currentThread().getName();
                        if (!thread.startsWith("Game-") && !thread.startsWith("AWT-EventQueue")) {
                            AssertionError trace = new AssertionError("Off-thread snapshot: " + thread);
                            trace.printStackTrace(System.out);
                            throw trace;
                        }
                    }
                    JsonObject state = remote.getLatestState();
                    if (state != null) PromptAssertions.validate(state, "state");
                } catch (Throwable ex) { failure.compareAndSet(null, ex); }
            }
            public Object sendAndWait(IdentifiableNetEvent event) {
                final String caller = Thread.currentThread().getName();
                final GuiGameEvent guiEvent = (GuiGameEvent) event;
                System.out.printf("[prompt-test] entering %s requestId=%d caller=%s%n",
                        guiEvent.getMethod(), guiEvent.getId(), caller);
                final Object result = remote.sendAndWait(event);
                final String completion = remote.getLastReplyThread();
                replyThreads.add(completion);
                System.out.println("[prompt-test] " + guiEvent.getMethod() + " requestId=" + guiEvent.getId()
                        + " caller=" + caller + " completed=" + completion);
                if (human != null) traceState("returned " + guiEvent.getMethod() + " requestId=" + guiEvent.getId());
                return result;
            }
        }) {
            // Enable only while resolving the may trigger. Leaving this on during mana payment
            // selects InputPayMana's mobile express-mana path, which can silently choose white.
            @Override public boolean isLibgdxPort() { return blockingConfirm; }
            @Override public void awaitInput(CountDownLatch done) throws InterruptedException {
                parked.add(done);
                try { done.await(); } finally { parked.remove(done); }
            }
        };
        useClient(client);
        client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0");
        client.hello(0);
    }

    void useClient(WsTestClient transport) {
        replyClient = transport;
        clients.add(transport);
        transport.onMessage = message -> {
            String type = message.get("type").getAsString();
            if (type.equals("state")) PromptAssertions.validate(message, "state");
            if (!type.equals("prompt")) return;
            PromptAssertions.validate(message, "prompt");
            System.out.println("[prompt-test] client received " + message);
            prompts.add(message);
            JsonElement result = answer.apply(message);
            if (result != null) transport.send(PromptAssertions.reply(message, result));
        };
    }

    void start(String battlefield, String hand) throws Exception {
        match.setStartGameHook(() -> {
            try {
                Game running = match.getGame();
                running.getRules().setGamesPerMatch(1);
                List<String> lines = new ArrayList<>(List.of("activeplayer=p0", "activephase=MAIN1",
                        "turn=1", "removesummoningsickness=true"));
                for (int i = 0; i < 4; i++) {
                    String p = "p" + i;
                    lines.add(p + "life=40");
                    lines.add(p + "hand=" + (i == 0 ? hand : ""));
                    lines.add(p + "battlefield=" + (i == 0 ? battlefield : ""));
                    lines.add(p + "library=" + (i == 0 ? "Plains;Island;Swamp;Mountain;Forest;Plains;Plains;Plains;Plains;Plains"
                            : String.join(";", Collections.nCopies(10, "Plains"))));
                    lines.add(p + "command=Isamaru, Hound of Konda|IsCommander");
                    lines.add(p + "graveyard="); lines.add(p + "exile=");
                }
                GameState state = new GameState(); state.parse(lines); state.applyToGame(running);
            } catch (Throwable ex) { failure.compareAndSet(null, ex); }
            finally { configured.countDown(); }
        });
        match.setEndGameHook(finished::countDown);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Deck deck = new Deck("Prompt " + i); deck.getMain().add("Plains", 99);
            deck.getOrCreate(DeckSection.Commander).add("Isamaru, Hound of Konda", 1);
            players.add(RegisteredPlayer.forCommander(deck).setPlayer(i == 0
                    ? new LobbyPlayerHuman("Prompt human") : GamePlayerUtil.createAiPlayer("Prompt AI " + i)));
        }
        edt(() -> {
            match.startMatch(GameType.Commander, EnumSet.of(GameType.Commander), players, Map.of(players.get(0), gui));
            game = match.getGame(); human = (PlayerControllerHuman) game.getPlayers().get(0).getController();
            controller = gui.getGameController(human.getPlayer().getView()); return null;
        });
        advance(input -> configured.getCount() == 0 && input instanceof InputPassPriority
                && game.getPhaseHandler().getPlayerTurn() == human.getPlayer()
                && game.getPhaseHandler().getPhase() == PhaseType.MAIN1);
    }

    Card card(String name, ZoneType zone) {
        for (Card c : human.getPlayer().getCardsIn(zone)) if (c.getName().equals(name)) return c;
        throw new AssertionError("Missing " + name + " in " + zone);
    }

    boolean ready(Input input) {
        return input != null && parked.stream().anyMatch(l -> l.getCount() > 0)
                && (!(input instanceof InputPayMana pay) || !pay.isActivatingManaAbility());
    }

    void advance(Predicate<Input> target) throws Exception {
        AtomicReference<Input> last = new AtomicReference<>();
        until(() -> {
            Input input = human.getInputQueue().getInput();
            if (!ready(input)) return false;
            if (target.test(input)) return true;
            if (last.get() == input) return false;
            if (configured.getCount() > 0 && input instanceof InputSelectEntitiesFromList<?> entities
                    && entities.getValidChoices().contains(human.getPlayer())) {
                last.set(input); controller.selectPlayer(human.getPlayer().getView(), null);
            } else if (input instanceof InputPassPriority || input instanceof InputConfirmMulligan) {
                last.set(input); controller.selectButtonOk();
            }
            return false;
        }, "advance to next Input");
    }

    void waitInput(Class<? extends Input> expected) throws Exception {
        until(() -> expected.isInstance(human.getInputQueue().getInput()) && ready(human.getInputQueue().getInput()),
                expected.getSimpleName());
    }

    void until(Callable<Boolean> predicate, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            healthy(); if (edt(predicate)) return; Thread.sleep(10);
        }
        fail("Timed out: " + label + "; input=" + edt(() -> human.getInputQueue().getInput()));
    }
    static <T> FutureTask<T> later(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action); FThreads.invokeInEdtLater(task); return task;
    }
    static <T> T edt(Callable<T> action) throws Exception { return later(action).get(10, TimeUnit.SECONDS); }
    void action(Runnable action) throws Exception { edt(() -> { action.run(); return null; }); }
    void healthy() {
        if (failure.get() != null) throw new AssertionError("Snapshot guard", failure.get());
        for (WsTestClient transport : clients) {
            if (transport.error != null) throw new AssertionError("Socket client", transport.error);
        }
        assertFalse(remote.isDirty());
    }

    /** Call only on a Forge-safe thread, including the EDT while an Input or prompt parks play. */
    void traceState(String label) {
        final List<String> stack = new ArrayList<>();
        for (var item : game.getStack()) stack.add(item.getSpellAbility().getApi() + ": " + item.getSpellAbility());
        System.out.printf("[prompt-state] %s thread=%s inputs=%s stack=%s%n", label,
                Thread.currentThread().getName(), human.getInputQueue().printInputStack(), stack);
    }

    static void dumpThreads(String label) {
        System.out.println("[prompt-thread-dump] " + label);
        Thread.getAllStackTraces().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getName())).forEach(entry -> {
                    System.out.printf("\"%s\" id=%d state=%s%n", entry.getKey().getName(),
                            entry.getKey().getId(), entry.getKey().getState());
                    for (StackTraceElement frame : entry.getValue()) System.out.println("    at " + frame);
                });
        System.out.println("[prompt-thread-dump] end");
    }

    @Override public void close() throws Exception {
        // Release any unanswered socket prompt before trying to post cleanup to a blocked EDT.
        answer = p -> p.get("default");
        for (JsonObject prompt : prompts) replyClient.send(PromptAssertions.reply(prompt, prompt.get("default")));
        try {
            if (game != null && !game.isGameOver()) {
                action(() -> game.getAction().invoke(() -> {
                    for (var player : new ArrayList<>(game.getPlayers())) if (player != human.getPlayer()) player.concede();
                    game.setGameOver(GameEndReason.AllOpponentsLost); human.getInputQueue().onGameOver(true);
                }));
                assertTrue(finished.await(5, TimeUnit.SECONDS), "End-game hook");
                until(match::isMatchOver, "post-game flush");
                action(match::endCurrentGame);
            }
            healthy();
            assertTrue(replyThreads.stream().allMatch(t -> t != null && t.startsWith("nioEventLoopGroup-")),
                    "All prompt replies complete on Netty: " + replyThreads);
        } finally { clients.forEach(WsTestClient::close); server.close(); }
    }
}
