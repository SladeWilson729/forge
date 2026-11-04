package forge.arena.server;

import com.google.gson.JsonObject;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gui.FThreads;
import java.util.*;
import java.util.concurrent.*;

/** Host lifecycle; Forge decisions run on the EDT, shutdown waits on the owner thread. */
final class MatchLifecycle implements AutoCloseable {
    private final WsServer server;
    private final HostedMatch match;
    private final Set<Integer> humans;
    private final Set<Integer> rematches = new HashSet<>(); // EDT only
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Arena-match-lifecycle"); t.setDaemon(true); return t;
    });
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean ended, quitting;
    private volatile long deadline;

    MatchLifecycle(WsServer server, HostedMatch match, Set<Integer> humans) {
        this.server = server; this.match = match; this.humans = Set.copyOf(humans);
        for (int seat = 0; seat < 4; seat++) {
            final int index = seat;
            server.writer(seat).nextGame = decision -> decide(index, decision);
        }
        timer.scheduleAtFixedRate(() -> FThreads.invokeInEdtLater(this::check), 100, 100, TimeUnit.MILLISECONDS);
    }
    void started() {
        FThreads.invokeInEdtLater(() -> {
            ended = false; rematches.clear();
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(Long.getLong("arena.timeoutMinutes", 30L));
        });
    }
    void finished() { FThreads.invokeInEdtLater(() -> { ended = true; check(); }); }
    void command(String command) {
        FThreads.invokeInEdtLater(() -> {
            if (command.equals("quit")) quit("Host");
            else if (ended) { rematches.addAll(humans); rematches.add(-1); check(); }
        });
    }
    private void decide(int seat, NextGameDecision decision) {
        if (!ended) throw new IllegalStateException("The game has not ended");
        if (decision == NextGameDecision.QUIT) quit(server.writer(seat).remote().seatName());
        else { rematches.add(seat); check(); }
    }
    private void check() {
        if (quitting) return;
        if (!ended) {
            if (deadline != 0 && System.nanoTime() > deadline) stopped.completeExceptionally(new IllegalStateException("Game did not finish within arena.timeoutMinutes"));
            return;
        }
        for (int seat : humans) {
            JsonSeatRemote remote = server.writer(seat).remote();
            if (remote != null && remote.abandonedReason() != null) { quit(remote.seatName()); return; }
        }
        // No connected humans: keep the results available until the host chooses.
        if (rematches.isEmpty()) return;
        for (int seat : humans) if (server.writer(seat).connected() && !rematches.contains(seat)) return;
        ended = false; rematches.clear();
        if (humans.isEmpty()) { match.restartMatch(); return; }
        // Native NEW tally includes disconnected humans; they do not veto connected voters.
        for (var controller : List.copyOf(match.getHumanControllers())) controller.nextGameDecision(NextGameDecision.NEW);
    }
    private void quit(String name) {
        if (quitting) return;
        quitting = true;
        for (int seat = 0; seat < 4; seat++) {
            JsonObject message = new JsonObject(); message.addProperty("type", "message");
            message.addProperty("level", "info"); message.addProperty("text", name + " left; the match is over");
            server.writer(seat).sendFromIo(message);
        }
        timer.schedule(() -> FThreads.invokeInEdtLater(() -> {
            if (ended && !match.getHumanControllers().isEmpty())
                match.getHumanControllers().get(0).nextGameDecision(NextGameDecision.QUIT);
            stopped.complete(null);
        }), 750, TimeUnit.MILLISECONDS);
    }
    void awaitShutdown() { stopped.join(); }
    CompletableFuture<Void> shutdown() { return stopped; }
    @Override public void close() { timer.shutdownNow(); }
}
