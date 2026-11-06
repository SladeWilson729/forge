package forge.arena.server;

import com.google.gson.JsonObject;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Host-only console. It releases prompt futures before posting any work to the EDT. */
final class SeatAbandonment implements AutoCloseable {
    private record Command(String text, CompletableFuture<String> result) { }
    private static final class Notice {
        final long disconnected;
        long deadline;
        boolean shown;
        Notice(long disconnected, long deadline) { this.disconnected = disconnected; this.deadline = deadline; }
    }
    private final WsServer server;
    private final PrintStream out;
    private final long graceNanos;
    private final LongSupplier clock;
    private final Map<Integer, Notice> notices = new HashMap<>(); // Console thread only.
    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<String> outputMessages = new ConcurrentLinkedQueue<>();
    private final Thread console;
    private volatile boolean closed;
    private volatile boolean inputEnded;
    private volatile CompletableFuture<Void> exitOnEnter;
    java.util.function.Consumer<String> matchCommand;

    SeatAbandonment(WsServer server) {
        this(server, new InputStreamReader(System.in, StandardCharsets.UTF_8), System.out,
                Long.getLong("arena.abandonAfterSeconds", 180L), System::nanoTime);
    }

    SeatAbandonment(WsServer server, Reader input, PrintStream out, long seconds, LongSupplier clock) {
        if (seconds < 0) throw new IllegalArgumentException("arena.abandonAfterSeconds must be zero or positive");
        this.server = server; this.out = out; this.clock = clock; this.graceNanos = TimeUnit.SECONDS.toNanos(seconds);
        server.clock().onTimeout((seat, name) -> outputMessages.add("[arena] Seat " + seat + " (" + name
                + ") is out of time: keep " + seat + " or concede " + seat));
        console = new Thread(this::run, "Arena-console"); console.setDaemon(true); console.start();
        Thread reader = new Thread(() -> {
            BufferedReader lines = new BufferedReader(input);
            try {
                String line;
                while (!closed && (line = lines.readLine()) != null) submit(line);
            } catch (IOException ex) { if (!closed) out.println("[arena] Console input failed: " + ex.getMessage()); }
            finally { inputEnded = true; CompletableFuture<Void> entered = exitOnEnter; if (entered != null) entered.complete(null); }
        }, "Arena-stdin");
        reader.setDaemon(true); reader.start(); // Never close System.in or wait for a blocked read on shutdown.
    }

    CompletableFuture<String> submit(String command) {
        CompletableFuture<String> result = new CompletableFuture<>();
        commands.add(new Command(command, result)); return result;
    }

    void awaitEnter() throws InterruptedException {
        CompletableFuture<Void> entered = new CompletableFuture<>(); exitOnEnter = entered;
        out.println("[arena] Holding server open. Press Enter to exit.");
        if (inputEnded) entered.complete(null); // Preserve readLine's previous EOF behavior.
        try { entered.get(); }
        catch (ExecutionException ex) { throw new IllegalStateException(ex.getCause()); }
        finally { exitOnEnter = null; }
    }

    private void run() {
        while (!closed) {
            try {
                Command command = commands.poll(100, TimeUnit.MILLISECONDS);
                if (command != null) execute(command);
                String output; while ((output = outputMessages.poll()) != null) out.println(output);
                checkDisconnected();
            } catch (InterruptedException ex) { if (closed) return; }
        }
    }

    private Notice notice(int seat, SeatWriter writer) {
        long since = writer.disconnectedAtNanos();
        Notice notice = notices.get(seat);
        if (notice == null || notice.disconnected != since) {
            notice = new Notice(since, since + graceNanos); notices.put(seat, notice);
        }
        return notice;
    }

    private void checkDisconnected() {
        for (int seat = 0; seat < 4; seat++) {
            SeatWriter writer = server.writer(seat); JsonSeatRemote remote = writer.remote();
            if (writer.connected()) { notices.remove(seat); continue; }
            // Bot views require no human input. Only a human's disconnected seat can stall play.
            if (remote == null || !remote.isHumanSeat() || !remote.hasGameState() || remote.gameFinished()
                    || remote.abandonedReason() != null || writer.disconnectedAtNanos() == 0) continue;
            Notice notice = notice(seat, writer);
            if (!notice.shown && clock.getAsLong() >= notice.deadline) {
                notice.shown = true;
                out.println("[arena] Seat " + seat + " (" + remote.seatName() + ") disconnected: wait " + seat
                        + ", concede " + seat + ", or bot " + seat);
                broadcast("warning", remote.seatName() + " disconnected; waiting for the host", seat);
            }
        }
    }

    private void execute(Command command) {
        try {
            String text = command.text().trim();
            if (matchCommand != null && java.util.Set.of("rematch", "quit", "abort").contains(text)) {
                matchCommand.accept(text); reply(command, "[arena] " + text); return;
            }
            if (text.isEmpty() && exitOnEnter != null) { exitOnEnter.complete(null); command.result().complete(""); return; }
            if (text.equals("seats")) {
                StringBuilder seats = new StringBuilder();
                for (int seat = 0; seat < 4; seat++) {
                    SeatWriter writer = server.writer(seat); JsonSeatRemote remote = writer.remote();
                    long elapsed = writer.disconnectedAtNanos() == 0 ? 0
                            : Math.max(0, TimeUnit.NANOSECONDS.toSeconds(clock.getAsLong() - writer.disconnectedAtNanos()));
                    boolean ai = remote != null && !remote.isHumanSeat();
                    seats.append("Seat ").append(seat).append(": ").append(remote == null ? "unbound" : ai ? "AI (" + remote.seatName() + ")" : remote.seatName())
                            .append(ai ? writer.connected() ? ", viewer connected" : "" : writer.connected() ? ", connected" : ", disconnected " + elapsed + " s").append('\n');
                }
                reply(command, seats.toString().stripTrailing()); return;
            }
            String[] parts = text.split("\\s+");
            if (parts.length != 2 || !java.util.Set.of("wait", "concede", "bot", "keep").contains(parts[0]))
                throw new IllegalArgumentException("Commands: seats, wait N, concede N, bot N, keep N, rematch, quit, abort");
            int seat;
            try { seat = Integer.parseInt(parts[1]); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Seat must be 0..3"); }
            SeatWriter writer = server.writer(seat); JsonSeatRemote remote = writer.remote();
            boolean expired = server.clock().expired(seat);
            if (parts[0].equals("keep")) {
                if (!expired) throw new IllegalArgumentException("Seat " + seat + " is not out of time");
                server.clock().keep(seat).thenAccept(accepted -> {
                    String message = accepted ? "[arena] Seat " + seat + " continues untimed" : "[arena] Seat " + seat + " is no longer out of time";
                    outputMessages.add(message); command.result().complete(message);
                });
                return;
            }
            if (writer.connected() && !(expired && parts[0].equals("concede"))) throw new IllegalArgumentException("Seat " + seat + " is connected");
            if (remote == null || !remote.isHumanSeat() || !remote.hasGameState()) throw new IllegalArgumentException("Seat " + seat + " has no human game to abandon");
            if (remote.gameFinished()) throw new IllegalArgumentException("The game has ended");
            if (remote.abandonedReason() != null) throw new IllegalArgumentException(remote.abandonedReason());
            if (parts[0].equals("wait")) {
                Notice notice = notice(seat, writer); notice.shown = false;
                notice.deadline = clock.getAsLong() + TimeUnit.MINUTES.toNanos(3);
                reply(command, "[arena] Waiting for seat " + seat + " for three more minutes"); return;
            }
            boolean bot = parts[0].equals("bot");
            // Binding order, plan 2.2: flag BOTH bridge components, then complete futures
            // here on Arena-console; only then enqueue ONE EDT task. No future wait here.
            remote.abandon(bot ? "This seat is now played by a bot" : "You conceded this game");
            String name = remote.seatName();
            remote.whenAbandonmentReady(() -> FThreads.invokeInEdtLater(() -> {
                try {
                    var game = remote.actionGame(); var player = game.getPlayer(remote.actionSeat());
                    int takeoverTurn = game.getPhaseHandler().getTurn();
                    if (!(player.getController() instanceof PlayerControllerHuman human)) throw new IllegalStateException("Seat controller is no longer human");
                    // A Game worker may already be inside the old controller and install
                    // its Input AFTER clearInputs below (notably after an ability choice).
                    // Keep releasing that abandoned controller's late Inputs on the EDT;
                    // never wait or call Forge from the queue's notification thread.
                    human.getInputQueue().addObserver((queue, ignored) -> {
                        if (human.getInputQueue().getInput() != null)
                            FThreads.invokeInEdtLater(() -> human.getInputQueue().clearInputs());
                    });
                    if (bot) {
                        LobbyPlayerAi lobby = new LobbyPlayerAi(player.getName(), null);
                        PlayerControllerAi ai = new PlayerControllerAi(game, player, lobby);
                        player.dangerouslySetController(ai);
                        player.getRegisteredPlayer().setPlayer(lobby);
                        human.getInputQueue().clearInputs();
                        remote.hostedMatch().tallyNextGameDecisions();
                    } else {
                        remote.discardAbandonedPriorityChoices();
                        human.concede();
                        // Forge only releases queues in concede() if the WHOLE game ended.
                        // A multiplayer loser must also release its own parked Input.
                        human.getInputQueue().clearInputs();
                    }
                    server.recordTakeover(game.getId(), seat, bot, takeoverTurn);
                    broadcast("info", name + (bot ? "'s seat is now played by a bot" : "'s seat was conceded"), -1);
                    outputMessages.add("[arena] " + name + (bot ? "'s seat is now played by a bot" : "'s seat was conceded"));
                    command.result().complete(bot ? "Bot handoff complete" : "Seat conceded");
                } catch (Throwable failure) { outputMessages.add("[arena] Seat abandonment failed: " + failure); command.result().completeExceptionally(failure); }
                finally { remote.abandonmentApplied(); }
            }));
        } catch (IllegalArgumentException | IllegalStateException ex) { reply(command, "[arena] " + ex.getMessage()); }
    }

    private void reply(Command command, String message) { out.println(message); command.result().complete(message); }
    private void broadcast(String level, String text, int excluded) {
        for (int seat = 0; seat < 4; seat++) if (seat != excluded) {
            JsonObject message = new JsonObject(); message.addProperty("type", "message");
            message.addProperty("level", level); message.addProperty("text", text); server.writer(seat).sendFromIo(message);
        }
    }
    @Override public void close() { closed = true; console.interrupt(); }
}
