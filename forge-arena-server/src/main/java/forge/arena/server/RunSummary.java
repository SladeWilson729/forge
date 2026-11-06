package forge.arena.server;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Run-owned detached results. The bookkeeping monitor never encloses a Forge call or file I/O. */
final class RunSummary {
    record Exit(String reason, int code, String message) { }
    private final ZonedDateTime started = ZonedDateTime.now();
    private final long startNanos = System.nanoTime();
    private final String commit = System.getProperty("arena.commit", "unknown");
    private final List<GameRun> games = new ArrayList<>();
    private final ConcurrentMap<Integer, GameRun> byGame = new ConcurrentHashMap<>();
    private final JsonArray transcripts = new JsonArray();
    private volatile List<RegisteredPlayer> seats = List.of();
    private volatile GameRun current;
    private long seed = new SecureRandom().nextLong();

    static final class GameRun {
        final int index;
        final long seed;
        final ZonedDateTime started = ZonedDateTime.now();
        final long nanos = System.nanoTime();
        JsonObject result = new JsonObject();
        final Map<Integer, JsonObject> takeovers = new HashMap<>();
        GameRun(int index, long seed) { this.index = index; this.seed = seed; }
    }

    void configureSeed() {
        String configured = System.getProperty("arena.seed");
        if (configured != null) {
            try { seed = Long.parseLong(configured); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("arena.seed must be a signed 64-bit integer", ex); }
        }
    }
    void seats(List<RegisteredPlayer> players) { seats = List.copyOf(players); }

    /** Before HostedMatch.startGame creates players, zones, GUIs or performs the opening shuffle. */
    void beforeGame() {
        final GameRun next;
        synchronized (this) {
            next = new GameRun(games.size(), seed + games.size()); games.add(next); current = next;
        }
        MyRandom.setRandom(new Random(next.seed));
        System.out.println("[arena] Seed: " + next.seed + " (game " + (next.index + 1) + ", gameIndex " + next.index + ")");
    }

    /** First view binding occurs on the EDT before gameplay, or on its owning Game worker. */
    void observe(Game game) {
        GameRun run = current;
        if (run == null) return;
        if (byGame.putIfAbsent(game.getId(), run) == null) game.subscribeToEvents(new Events(game, run));
        capture(game, run);
    }

    public final class Events {
        private final Game game;
        private final GameRun run;
        Events(Game game, GameRun run) { this.game = game; this.run = run; }
        @Subscribe public void turn(GameEventTurnBegan event) { capture(game, run); }
        @Subscribe public void outcome(GameEventGameOutcome event) { capture(game, run); }
    }

    void capture(Game game) {
        GameRun run = byGame.get(game.getId());
        if (run != null) capture(game, run);
    }
    private void capture(Game game, GameRun run) {
        JsonObject result = new JsonObject();
        result.addProperty("gameIndex", run.index); result.addProperty("seed", run.seed);
        result.addProperty("turns", game.getPhaseHandler().getTurn());
        result.addProperty("randomCalls", MyRandom.callCount());
        var outcome = game.getOutcome();
        result.addProperty("draw", outcome != null && outcome.isDraw());
        result.add("winnerSeat", JsonNull.INSTANCE);
        JsonArray players = new JsonArray(), placements = new JsonArray();
        List<Player> ordered = new ArrayList<>(game.getRegisteredPlayers());
        ordered.removeAll(game.getLostPlayers());
        List<Player> lost = new ArrayList<>(game.getLostPlayers()); Collections.reverse(lost); ordered.addAll(lost);
        for (Player player : ordered) {
            int seat = seats.indexOf(player.getRegisteredPlayer());
            JsonObject place = new JsonObject(); place.addProperty("seat", seat);
            place.addProperty("playerId", player.getId()); place.addProperty("name", player.getName());
            place.addProperty("reason", player.getOutcome() == null || player.getOutcome().lossState == null
                    ? null : player.getOutcome().lossState.name());
            placements.add(place);
            if (outcome != null && !outcome.isDraw() && outcome.getWinningPlayer() == player.getRegisteredPlayer()) result.addProperty("winnerSeat", seat);
        }
        for (int seat = 0; seat < seats.size(); seat++) {
            Player player = ArenaServerMain.playerFor(game, seats.get(seat));
            JsonObject row = new JsonObject(); row.addProperty("seat", seat);
            row.addProperty("kind", player.getController() instanceof PlayerControllerHuman ? "human" : "ai");
            players.add(row);
        }
        result.add("placements", placements); result.add("seats", players);
        synchronized (this) {
            // Keep the game's starting kind even after its controller has been handed off.
            if (run.result.has("seats")) result.add("seats", run.result.getAsJsonArray("seats"));
            run.result = result;
        }
    }

    void takeover(int gameId, int seat, boolean bot, int turn) {
        GameRun run = byGame.get(gameId);
        if (run == null) return;
        JsonObject value = new JsonObject(); value.addProperty("takeover", bot ? "bot" : "conceded"); value.addProperty("takeoverTurn", turn);
        synchronized (this) { run.takeovers.put(seat, value); }
    }
    SeatWriter.GameStart gameStart(int gameId) {
        GameRun run = byGame.get(gameId);
        if (run == null) throw new IllegalStateException("Run game was not bound before its transcript");
        return new SeatWriter.GameStart(run.started, run.nanos, run.index, run.seed, commit);
    }
    void transcript(int gameId, int seat, Path file) {
        GameRun run = byGame.get(gameId);
        if (run == null) return;
        JsonObject row = new JsonObject(); row.addProperty("gameIndex", run.index); row.addProperty("seat", seat); row.addProperty("file", file.getFileName().toString());
        synchronized (this) { transcripts.add(row); }
    }

    JsonObject snapshot(Exit exit) {
        ZonedDateTime ended = ZonedDateTime.now();
        long calls = MyRandom.callCount();
        JsonObject result = new JsonObject(); result.addProperty("protocolVersion", 1); result.addProperty("serverCommit", commit);
        result.addProperty("startedAt", started.toOffsetDateTime().toString()); result.addProperty("endedAt", ended.toOffsetDateTime().toString());
        result.addProperty("durationMs", Math.max(0, (System.nanoTime() - startNanos) / 1_000_000));
        result.addProperty("exitReason", exit.reason()); result.addProperty("exitCode", exit.code()); result.addProperty("message", exit.message());
        JsonArray results = new JsonArray();
        synchronized (this) {
            for (GameRun game : games) {
                JsonObject row = game.result.deepCopy();
                // A game aborted before any view still records the actual seed installed.
                row.addProperty("gameIndex", game.index); row.addProperty("seed", game.seed);
                if (!row.has("turns")) row.addProperty("turns", 0);
                if (!row.has("draw")) row.addProperty("draw", false);
                if (!row.has("winnerSeat")) row.add("winnerSeat", JsonNull.INSTANCE);
                if (!row.has("placements")) row.add("placements", new JsonArray());
                if (!row.has("seats")) row.add("seats", new JsonArray());
                if (game == current && !row.has("completed")) row.addProperty("randomCalls", calls);
                row.remove("completed");
                for (var value : row.getAsJsonArray("seats")) {
                    JsonObject seat = value.getAsJsonObject(); JsonObject change = game.takeovers.get(seat.get("seat").getAsInt());
                    seat.addProperty("takeover", change == null ? "none" : change.get("takeover").getAsString());
                    seat.add("takeoverTurn", change == null ? JsonNull.INSTANCE : change.get("takeoverTurn"));
                }
                results.add(row);
            }
            result.add("transcripts", transcripts.deepCopy());
        }
        result.add("games", results); return result;
    }

    void finished(Game game) {
        capture(game);
        GameRun run = byGame.get(game.getId());
        synchronized (this) { if (run != null) run.result.addProperty("completed", true); }
    }

    Path path() {
        String filename = started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "_ForgeArena_RunSummary.json";
        return Path.of(System.getProperty("arena.summary", Path.of(System.getProperty("arena.transcriptDir", "transcripts"), filename).toString()));
    }
    void write(Exit exit) throws IOException {
        JsonObject summary = snapshot(exit);
        Path target = path().toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".arena-summary-", ".tmp");
        try {
            Files.writeString(temporary, WsServer.GSON.toJson(summary) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("[arena] Run summary: " + target);
    }
}
