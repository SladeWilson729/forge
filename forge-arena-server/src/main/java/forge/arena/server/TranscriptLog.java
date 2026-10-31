package forge.arena.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** Per-seat JSONL sink. All methods are called only by the owning SeatWriter. */
final class TranscriptLog implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TranscriptLog.class.getName());
    private final long startNanos;
    private BufferedWriter output;
    private long lastFlush;
    private boolean warned;

    TranscriptLog(int seat, int playerId, ZonedDateTime startedAt, long startNanos) {
        this.startNanos = startNanos;
        try {
            Path directory = Path.of(System.getProperty("arena.transcriptDir", "transcripts"));
            Files.createDirectories(directory);
            String base = startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_'ForgeArena_Transcript_'HHmmss"))
                    + "_seat" + seat;
            for (int suffix = 1; ; suffix++) {
                String name = base + (suffix == 1 ? "" : "_" + suffix) + ".jsonl";
                try {
                    output = Files.newBufferedWriter(directory.resolve(name), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    break;
                } catch (FileAlreadyExistsException collision) {
                    if (suffix == Integer.MAX_VALUE) throw collision;
                }
            }
            JsonObject header = new JsonObject();
            header.addProperty("type", "transcriptHeader");
            header.addProperty("protocolVersion", 1);
            header.addProperty("serverVersion", "1.4-dev");
            header.addProperty("seat", seat);
            header.addProperty("playerId", playerId);
            header.addProperty("startedAt", startedAt.toOffsetDateTime().toString());
            output.write(WsServer.GSON.toJson(header));
            output.newLine();
            flush();
        } catch (IOException ex) { disable(ex); }
    }

    void record(String direction, String json, String result, long when) {
        if (output == null) return;
        try {
            JsonObject line = new JsonObject();
            line.addProperty("t", Math.max(0, TimeUnit.NANOSECONDS.toMillis(when - startNanos)));
            line.addProperty("dir", direction);
            line.add("msg", JsonParser.parseString(json));
            if (result != null) line.addProperty("result", result);
            output.write(WsServer.GSON.toJson(line));
            output.newLine();
        } catch (IOException ex) { disable(ex); }
    }

    void flushIfDue() {
        if (System.nanoTime() - lastFlush >= TimeUnit.SECONDS.toNanos(1)) flush();
    }

    void flush() {
        if (output == null) return;
        try { output.flush(); lastFlush = System.nanoTime(); }
        catch (IOException ex) { disable(ex); }
    }

    private void disable(IOException error) {
        if (!warned) { warned = true; LOG.log(System.Logger.Level.WARNING, "Transcript disabled", error); }
        if (output != null) try { output.close(); } catch (IOException ignored) { }
        output = null;
    }

    @Override public void close() {
        if (output == null) return;
        try { output.close(); } catch (IOException ex) { disable(ex); }
        output = null;
    }
}
