package forge.arena.server;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.player.PlayerControllerHuman;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Public override points only; no reflection or changes to Forge's scan/input behavior. */
final class HighlightProbe {
    private final Map<String, List<Long>> totals = new ConcurrentHashMap<>();
    private volatile Map<String, List<Long>> pass = new ConcurrentHashMap<>();
    private int passNumber;

    void timed(String name, Runnable operation) {
        long start = System.nanoTime();
        try { operation.run(); } finally { record(name, System.nanoTime() - start); }
    }

    private void record(String name, long nanos) {
        totals.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(nanos);
        pass.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(nanos);
    }

    private static void report(String label, Map<String, List<Long>> measurements) {
        measurements.forEach((name, values) -> {
            long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length == 0) return;
            double median = (sorted[(sorted.length - 1) / 2] / 2.0 + sorted[sorted.length / 2] / 2.0) / 1_000_000;
            System.out.printf(Locale.ROOT, "[highlight] %s %s count=%d min=%.3f median=%.3f max=%.3f ms%n",
                    label, name, sorted.length, sorted[0] / 1_000_000.0, median, sorted[sorted.length - 1] / 1_000_000.0);
        });
    }

    void reportTotals() { report("TOTAL passes=" + passNumber, totals); }

    PlayerControllerHuman controller(Game game, Player player, LobbyPlayer lobby) {
        return new PlayerControllerHuman(game, player, lobby) {
            private Thread scanThread;
            private long scanStart;

            @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                pass = new ConcurrentHashMap<>();
                int number = ++passNumber;
                scanThread = Thread.currentThread();
                scanStart = System.nanoTime();
                try { return super.chooseSpellAbilityToPlay(); }
                finally { scanStart = 0; report("pass=" + number, pass); }
            }

            @Override public boolean mayAutoPass() {
                if (Thread.currentThread() == scanThread && scanStart != 0) {
                    // First call in chooseSpellAbilityToPlay is immediately AFTER the action scan.
                    // Includes its small setup overhead, but excludes Input/human wait and yields.
                    record("priority-scan-prefix", System.nanoTime() - scanStart);
                    scanStart = 0;
                }
                return super.mayAutoPass();
            }

            @Override public void pushActionableCards(boolean payment, Iterable<CardView> emphasized) {
                timed("pushActionableCards", () -> super.pushActionableCards(payment, emphasized));
            }

            @Override public void pushAttackerCandidates(Player attacker, Combat combat) {
                timed("pushAttackerCandidates", () -> super.pushAttackerCandidates(attacker, combat));
            }

            @Override public void pushBlockerCandidates(Player defender, Combat combat) {
                timed("pushBlockerCandidates", () -> super.pushBlockerCandidates(defender, combat));
            }
        };
    }
}
