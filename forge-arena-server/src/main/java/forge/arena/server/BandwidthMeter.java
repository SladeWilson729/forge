package forge.arena.server;

import java.io.IOException;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional payload accounting. Each Row is touched exclusively by its seat writer. */
final class BandwidthMeter {
    record Snapshot(int seat, long total, long states, long stateBytes, double average, long peak) {
        String tableRow() {
            return String.format(Locale.ROOT, "| %d | %.2f | %d | %d | %d | %.2f |%n", seat, average, peak,
                    total, states, states == 0 ? 0.0 : (double) stateBytes / states);
        }
    }
    static final String HEADER = "| Seat | Average B/s | Peak 1-second B/s | Total bytes | States | Average state bytes |\n"
            + "|---|---:|---:|---:|---:|---:|\n";
    private final ConcurrentMap<Integer, Report> reports = new ConcurrentHashMap<>();
    Report game(int id, SeatWriter.GameStart start) { return reports.computeIfAbsent(id, ignored -> new Report(start.time(), start.nanos())); }
    Collection<Report> reports() { return List.copyOf(reports.values()); }

    static final class Row {
        final Report report;
        private final int seat;
        private long total, states, stateBytes, peak, nextPrint;
        private final Map<Long, Long> buckets = new HashMap<>();
        private boolean finished;
        Row(Report report, int seat) { this.report = report; this.seat = seat; nextPrint = report.start + TimeUnit.SECONDS.toNanos(30); }
        void wrote(int bytes, boolean state, long at) {
            long second = Math.max(0, at - report.start) / TimeUnit.SECONDS.toNanos(1);
            // Replacement sockets may complete on different I/O threads out of order.
            // Attribute each completion to its actual write bucket, not queue arrival order.
            long bucketBytes = buckets.merge(second, (long) bytes, Long::sum);
            total += bytes; peak = Math.max(peak, bucketBytes);
            if (state) { states++; stateBytes += bytes; }
        }
        Snapshot snapshot(long at) {
            double seconds = Math.max(1e-9, (at - report.start) / 1e9);
            return new Snapshot(seat, total, states, stateBytes, total / seconds, peak);
        }
        void poll(long at) {
            if (!finished && at >= nextPrint) {
                System.out.print("[meter] game " + report.started + "\n" + HEADER + snapshot(at).tableRow());
                nextPrint = at + TimeUnit.SECONDS.toNanos(30);
            }
        }
        void finish(long at) {
            if (finished) return;
            finished = true;
            report.rows.set(seat, snapshot(at));
            if (report.remaining.decrementAndGet() == 0) report.write();
        }
    }
    static final class Report {
        final ZonedDateTime started;
        final long start;
        final Row[] seats = new Row[4];
        final AtomicReferenceArray<Snapshot> rows = new AtomicReferenceArray<>(4);
        final AtomicInteger remaining = new AtomicInteger(4);
        Report(ZonedDateTime time, long nanos) {
            started = time; start = nanos;
            for (int seat = 0; seat < 4; seat++) seats[seat] = new Row(this, seat);
        }
        private void write() {
            StringBuilder text = new StringBuilder("# Task 1.15b WebSocket payload bandwidth\n\nGame started: " + started + "\n\n" + HEADER);
            for (int seat = 0; seat < 4; seat++) text.append(rows.get(seat).tableRow());
            text.append("\nBudgets per remote seat: average <250,000 B/s (250 KB/s); peak <1,000,000 B/s (1 MB/s).\n")
                    .append("Successful outbound WebSocket frame payloads, including handshake/replays and control frames. HTTP upgrade, frame headers, TCP/IP and Tailscale overhead are excluded.\n")
                    .append("Buckets are fixed one-second intervals from game start; average includes disconnected/idle time. State counts include cached replays, never coalesced-away states.\n");
            System.out.print("[meter] GAME END\n" + text);
            try {
                Path directory = Path.of("target/perf"); Files.createDirectories(directory);
                Files.writeString(directory.resolve("1.15b-" + started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md"), text);
            } catch (IOException ex) { System.getLogger(BandwidthMeter.class.getName()).log(System.Logger.Level.WARNING, "Cannot write bandwidth report", ex); }
        }
    }
}
