package forge.arena.server.view;

import com.google.gson.*;
import forge.arena.server.*;
import forge.game.*;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventCardChangeZone;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.testng.Assert.*;

/** Static four-player puzzle: no game loop or concurrent Forge mutation during measurements. */
@Test(singleThreaded = true)
public class LoadTest {
    private static final int WARMUP = 50, SAMPLES = 200;
    private static final String[][] HANDS = {
            {"Aetherling", "Cryptic Serpent", "Fact or Fiction", "Pongify", "Rapid Hybridization", "Dig Through Time", "Mystic Confluence"},
            {"Avenger of Zendikar", "Beast Within", "Harmonize", "Krosan Grip", "Nature's Lore", "Return of the Wildspeaker", "Heroic Intervention"},
            {"Gray Merchant of Asphodel", "Read the Bones", "Feed the Swarm", "Damnation", "Victimize", "Sign in Blood", "Reanimate"},
            {"Sun Titan", "Generous Gift", "Austere Command", "Wrath of God", "Teferi's Protection", "Esper Sentinel", "Smothering Tithe"}
    };
    private record Ref(int id, String zone, boolean faceDown, boolean visible) { }
    private record Measurements(long[] bytes, long[] build, long[] pipeline, long[] highlights, long[] log, long[] mapper,
                                Map<String, Long> fields, int references) { }

    @BeforeClass public void initialize() { ScenarioGame.initialize(); }

    private static final class Client implements WebSocket.Listener, AutoCloseable {
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch ready = new CountDownLatch(1), seeded = new CountDownLatch(1);
        private volatile Throwable error;
        private WebSocket socket;
        void connect(HttpClient http, int port, int seat) throws Exception {
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?seat=" + seat), this).get(5, TimeUnit.SECONDS);
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Client received joined and cached state");
            assertTrue(seeded.await(5, TimeUnit.SECONDS), "Server applied the seat's initial yield preferences");
            assertNull(error);
        }
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    JsonObject message = JsonParser.parseString(text.toString()).getAsJsonObject();
                    String type = message.get("type").getAsString();
                    if (type.equals("hello")) webSocket.sendText("{\"type\":\"join\",\"name\":\"Load client\",\"protocolVersion\":1}", true);
                    if (type.equals("state")) ready.countDown();
                    if (type.equals("yieldState") && message.get("kind").getAsString().equals("seed")) seeded.countDown();
                } catch (Throwable ex) { error = ex; ready.countDown(); }
                text.setLength(0);
            }
            webSocket.request(1); return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable failure) { error = failure; ready.countDown(); }
        @Override public void close() { if (socket != null) socket.abort(); }
    }

    private static ScenarioGame board() throws Exception {
        ScenarioGame scenario = new ScenarioGame();
        FutureTask<ScenarioGame> setup = new FutureTask<>(() -> loadPuzzle(scenario));
        scenario.game.getAction().invoke(setup);
        return setup.get(30, TimeUnit.SECONDS);
    }

    private static ScenarioGame loadPuzzle(ScenarioGame scenario) {
        List<String> lines = new ArrayList<>(List.of("activeplayer=p0", "activephase=MAIN1", "turn=21", "removesummoningsickness=true"));
        String[] commanders = {"Kenrith, the Returned King", "Atraxa, Praetors' Voice", "Muldrotha, the Gravetide", "Breya, Etherium Shaper"};
        for (int seat = 0; seat < 4; seat++) {
            int anchor = 100 + seat;
            List<String> battlefield = new ArrayList<>();
            for (int land = 0; land < 15; land++) battlefield.add(new String[] {"Plains", "Island", "Swamp", "Mountain", "Forest"}[land % 5] + (land % 2 == 0 ? "|Tapped" : ""));
            battlefield.addAll(List.of(commanders[seat] + "|IsCommander|NoETBTrigs", "Colossal Dreadmaw|Id:" + anchor + "|Counters:P1P1=3,SHIELD=1",
                    "Llanowar Elves", "Birds of Paradise", "Selesnya Guildmage", "Steel Overseer", "Walking Ballista|Counters:P1P1=5",
                    "Jace Beleren|Counters:LOYALTY=5", "Garruk Wildspeaker|Counters:LOYALTY=4", "Sol Ring|Tapped", "Arcane Signet",
                    "Rancor|AttachedTo:" + anchor, "Sword of the Animist|AttachedTo:" + anchor));
            for (int token = 0; token < 4; token++) battlefield.add("T:g_1_1_saproling");
            String p = "p" + seat;
            lines.add(p + "life=" + (35 - seat * 3)); lines.add(p + "counters=POISON=" + seat);
            lines.add(p + "battlefield=" + String.join(";", battlefield));
            lines.add(p + "hand="); lines.add(p + "command=");
            lines.add(p + "library=" + String.join(";", Collections.nCopies(40, "Plains")));
            lines.add(p + "graveyard=Cultivate;Rampant Growth;Swords to Plowshares;Counterspell;Evolving Wilds;Eternal Witness;Sakura-Tribe Elder;Farseek");
            lines.add(p + "exile=Path to Exile;Lightning Greaves;Chromatic Lantern");
        }
        GameState puzzle = new GameState(); puzzle.parse(lines); puzzle.applyToGame(scenario.game);
        for (int seat = 0; seat < 4; seat++) {
            for (String name : HANDS[seat]) scenario.addCardToZone(name, seat, ZoneType.Hand);
            // Unique synthetic printing metadata lets the existing whole-JSON scanner
            // detect a leak without matching a public card's shared edition/collector number.
            Card faceDown = scenario.addCardToZone(new String[] {"Vesuvan Shapeshifter", "Willbender", "Den Protector", "Fathom Seer"}[seat], seat, ZoneType.Battlefield);
            assertTrue(faceDown.turnFaceDown());
        }
        // GameState.handleCombat is explicitly 1v1-only. Public Combat APIs supply
        // the same four-player puzzle's attackers/blockers without running a game loop.
        scenario.game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, scenario.player(0), 21);
        Combat combat = new Combat(scenario.player(0)); scenario.game.getPhaseHandler().setCombat(combat);
        Card attacker = find(scenario.player(0), "Colossal Dreadmaw");
        combat.addAttacker(attacker, scenario.player(1));
        combat.addAttacker(find(scenario.player(0), "Kenrith, the Returned King"), scenario.player(2));
        combat.addBlocker(attacker, find(scenario.player(1), "Colossal Dreadmaw"));
        combat.addBlocker(attacker, find(scenario.player(1), "Steel Overseer"));
        scenario.game.updateCombatForView();
        for (int i = 0; i < 3; i++) {
            Card card = scenario.addCardToZone(new String[] {"Opt", "Brainstorm", "Impulse"}[i], i, ZoneType.Hand);
            SpellAbility ability = card.getFirstSpellAbility(); ability.setActivatingPlayer(scenario.player(i));
            card = scenario.game.getAction().moveToStack(card, ability); ability.setHostCard(card);
            scenario.game.getStack().add(ability);
        }
        assertEquals(scenario.game.getStack().size(), 3);
        for (int seat = 0; seat < 4; seat++) {
            Player player = scenario.player(seat);
            assertTrue(player.getCardsIn(ZoneType.Battlefield).size() >= 25);
            assertEquals(player.getCardsIn(ZoneType.Hand).size(), 7);
            assertEquals(player.getCommanders().size(), 1);
            assertTrue(player.getCardsIn(ZoneType.Battlefield).stream().anyMatch(Card::isToken));
            assertEquals(find(player, "Rancor").getEntityAttachedTo(), find(player, "Colossal Dreadmaw"));
            assertEquals(find(player, "Sword of the Animist").getEntityAttachedTo(), find(player, "Colossal Dreadmaw"));
        }
        return scenario;
    }

    private static Card find(Player player, String name) {
        return player.getCardsIn(ZoneType.Battlefield).stream().filter(card -> card.getName().equals(name)).findFirst().orElseThrow();
    }

    // Even a missed budget must finish collecting/reporting all samples: the production
    // dynamic scan can take 1.5 seconds, up to 1,500 seconds across 1,000 passes.
    @Test(timeOut = 1_800_000)
    public void lateGameSnapshotsAndHighlights() throws Exception {
        ScenarioGame scenario = board();
        String highlights = FModel.getPreferences().getPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        String budget = FModel.getPreferences().getPref(FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS);
        String transcriptDir = System.getProperty("arena.transcriptDir");
        Path directory = Path.of("target/perf"); Files.createDirectories(directory);
        System.setProperty("arena.transcriptDir", directory.resolve("transcripts").toString());
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
        FModel.getPreferences().setPref(FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS, "0"); // Production dynamic budget, never shortened for a passing test.
        List<Client> clients = new ArrayList<>(); List<Measurements> results = new ArrayList<>();
        List<PlayerControllerHuman> controllers = new ArrayList<>(); List<JsonSeatRemote> remotes = new ArrayList<>();
        try (WsServer server = new WsServer("127.0.0.1", 0)) {
            server.start(); HttpClient http = HttpClient.newHttpClient();
            for (int seat = 0; seat < 4; seat++) {
                int index = seat;
                onGame(scenario, () -> {
                    Player player = scenario.player(index);
                    LobbyPlayerHuman lobby = new LobbyPlayerHuman("Load seat " + index);
                    PlayerControllerHuman controller = new PlayerControllerHuman(scenario.game, player, lobby);
                    player.dangerouslySetController(controller); player.getRegisteredPlayer().setPlayer(lobby);
                    JsonSeatRemote remote = new JsonSeatRemote(scenario.game, player.getView(), server.writer(index));
                    SeatProtocolGui gui = new SeatProtocolGui(remote); controller.setGui(gui); gui.setGameController(player.getView(), controller);
                    gui.setGameView(scenario.game.getView()); gui.openView(null);
                    controllers.add(controller); remotes.add(remote); return null;
                });
            }
            for (int seat = 0; seat < 4; seat++) {
                Client client = new Client(); clients.add(client); client.connect(http, server.getPort(), seat);
            }
            for (int seat = 0; seat < 4; seat++) {
                int index = seat;
                results.add(onGame(scenario, () -> measure(scenario, index, controllers.get(index), remotes.get(index), server.writer(index))));
            }
            clients.forEach(client -> assertNull(client.error));
            writeReport(directory, results);
            for (int seat = 0; seat < 4; seat++) {
                Measurements m = results.get(seat);
                assertTrue(Arrays.stream(m.bytes()).max().orElseThrow() < 150_000, "Seat " + seat + " snapshot exceeds 150 KB");
                if (Boolean.getBoolean("arena.perf")) {
                    assertTrue(median(m.build()) < 20_000_000, "Seat " + seat + " build median >= 20 ms; see target/perf/1.15-report.md");
                    assertTrue(median(m.highlights()) < 300_000_000, "Seat " + seat + " highlight median >= 300 ms; see target/perf/1.15-report.md");
                }
            }
        } finally {
            clients.forEach(Client::close);
            FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, highlights);
            FModel.getPreferences().setPref(FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS, budget);
            if (transcriptDir == null) System.clearProperty("arena.transcriptDir"); else System.setProperty("arena.transcriptDir", transcriptDir);
        }
    }

    private static <T> T onGame(ScenarioGame scenario, Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action); scenario.game.getAction().invoke(work);
        return work.get(1_800, TimeUnit.SECONDS);
    }

    private static Measurements measure(ScenarioGame scenario, int seat, PlayerControllerHuman controller, JsonSeatRemote remote, SeatWriter writer) {
        IdMapper ids = EventTestSupport.ids(remote), standaloneIds = new IdMapper();
        SeatViewSerializer serializer = new SeatViewSerializer(); EventPump pump = new EventPump(scenario.player(seat).getView(), ids);
        PayloadFilter filter = new PayloadFilter(); HighlightProbe probe = new HighlightProbe();
        JsonObject initial = serializer.buildState(scenario.game, scenario.player(seat).getView(), ids, 0);
        List<Ref> refs = new ArrayList<>();
        for (Card card : scenario.game.getCardsInGame()) if (ids.currentRef(card.getId()).isPresent()) {
            CardVisibility visibility = CardVisibility.forSeat(card.getView(), scenario.player(seat).getView());
            refs.add(new Ref(card.getId(), card.getView().getZone() == null ? null : card.getView().getZone().name(), visibility.faceDown(), visibility.identityVisible()));
        }
        assertEquals(refs.size(), serializer.cardStubs().size(), "Standalone mapper replays every snapshot card exactly once");
        Card hidden = scenario.player((seat + 1) % 4).getCardsIn(ZoneType.Hand).get(0);
        GameLogEntry entry = new GameLogEntry(GameLogEntryType.ZONE_CHANGE, hidden.getName() + " moved to a hand", hidden.getView());
        List<Object> events = List.of(new GameEventAddLog(entry.type(), entry.message(), entry.sourceCard()),
                new GameEventCardChangeZone(hidden.getView(), hidden.getOwner().getZone(ZoneType.Library).getView(), hidden.getZone().getView()));
        PayloadFilter.LogVisibility visibility = filter.captureLogVisibility(scenario.game, scenario.player(seat).getView());
        long[] sizes = new long[SAMPLES], builds = new long[SAMPLES], pipelines = new long[SAMPLES], scans = new long[SAMPLES], logs = new long[SAMPLES], mappings = new long[SAMPLES];
        JsonObject last = initial;
        for (int sample = -WARMUP; sample < SAMPLES; sample++) {
            long start = System.nanoTime();
            JsonObject state = serializer.buildState(scenario.game, scenario.player(seat).getView(), ids, 0);
            long built = System.nanoTime();
            long sequence = writer.send(state);
            pump.snapshot(scenario.game, state, serializer.cardStubs()).forEach(writer::send);
            List<JsonObject> payloads = pump.batch(events, sequence); payloads.forEach(writer::send);
            long enqueued = System.nanoTime();
            long beginLog = System.nanoTime(); JsonObject log = filter.log(entry, visibility); long logTime = System.nanoTime() - beginLog;
            long beginMapper = System.nanoTime(); standaloneIds.beginSnapshot();
            for (Ref ref : refs) standaloneIds.wireRef(ref.id(), ref.zone(), ref.faceDown(), ref.visible());
            standaloneIds.endSnapshot(); long mapTime = System.nanoTime() - beginMapper;
            scenario.game.getPhaseHandler().setPriority(scenario.player(seat));
            assertTrue(controller.getYieldController().getBoolPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS));
            long highlightTime = probe.priorityScanAndPush(controller);
            if (sample >= 0) {
                sizes[sample] = bytes(state); builds[sample] = built - start; pipelines[sample] = enqueued - start;
                scans[sample] = highlightTime; logs[sample] = logTime; mappings[sample] = mapTime;
            }
            last = state;
            if (sample == SAMPLES - 1) {
                ScenarioGame.validate(log, "log"); payloads.forEach(payload -> ScenarioGame.validate(payload, payload.get("type").getAsString()));
                for (int owner = 0; owner < 4; owner++) if (owner != seat) {
                    for (Card card : scenario.player(owner).getCardsIn(ZoneType.Hand)) HiddenInfoAssertions.noIdentity(state, card);
                    for (Card card : scenario.player(owner).getCardsIn(ZoneType.Battlefield)) if (card.isFaceDown()) HiddenInfoAssertions.noIdentity(state, card);
                }
                HiddenInfoAssertions.noIdentity(log, hidden);
                payloads.forEach(payload -> HiddenInfoAssertions.noIdentity(payload, hidden));
            }
        }
        ScenarioGame.validate(last, "state");
        return new Measurements(sizes, builds, pipelines, scans, logs, mappings, fieldSizes(last), refs.size());
    }

    private static long bytes(JsonElement json) { return ScenarioGame.JSON.toJson(json).getBytes(StandardCharsets.UTF_8).length; }
    private static double median(long[] values) {
        long[] sorted = values.clone(); Arrays.sort(sorted); return sorted[(sorted.length - 1) / 2] / 2.0 + sorted[sorted.length / 2] / 2.0;
    }
    private static String stats(long[] values, double divisor) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%.3f / %.3f / %.3f", median(sorted) / divisor, sorted[(int) Math.ceil(.95 * sorted.length) - 1] / divisor, sorted[sorted.length - 1] / divisor);
    }
    private static Map<String, Long> fieldSizes(JsonObject state) {
        Map<String, Long> fields = new TreeMap<>();
        for (JsonElement p : state.getAsJsonArray("players")) {
            JsonObject player = p.getAsJsonObject();
            player.getAsJsonObject("zones").entrySet().forEach(field -> fields.merge("players[*].zones." + field.getKey(), bytes(field.getValue()), Long::sum));
            for (var field : player.entrySet()) if (!field.getKey().equals("zones")) fields.merge("players[*]." + field.getKey(), bytes(field.getValue()), Long::sum);
        }
        for (var field : state.entrySet()) if (!field.getKey().equals("players")) fields.put(field.getKey(), bytes(field.getValue()));
        return fields;
    }
    private static String machine() throws Exception {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json -Compress").redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("CPU inventory timed out"); }
        assertEquals(process.exitValue(), 0, "CPU inventory");
        JsonObject cpu = JsonParser.parseString(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        return cpu.get("Name").getAsString().trim() + "; " + cpu.get("NumberOfCores").getAsInt() + " physical cores / "
                + cpu.get("NumberOfLogicalProcessors").getAsInt() + " logical processors (JVM sees " + Runtime.getRuntime().availableProcessors() + ")";
    }
    private static void writeReport(Path directory, List<Measurements> results) throws Exception {
        StringBuilder report = new StringBuilder("# Task 1.15 late-game load report\n\n");
        report.append("Machine: ").append(machine()).append(".\n\nJVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.runtime.version")).append(" (").append(System.getProperty("java.vendor")).append("); max heap ")
                .append(Runtime.getRuntime().maxMemory() / 1048576).append(" MiB; ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append(".\n\n");
        report.append("Per seat: 50 warm-up builds/passes, then 200 samples on the same static turn-21 Commander puzzle. Each player has 33 permanents, 7 private hand cards, 40 library cards, 8 graveyard and 3 exile cards; four commanders in play, tokens, counters, attached aura/equipment, face-down cards, eight planeswalkers, a three-spell stack and two attacks/two blocks. No game loop runs; Forge reads are serial.\n\n")
                .append("Pipeline = serializer (including IdMapper), state serialization/enqueue, EventPump snapshot/diff and PayloadFilter visibility capture, filtered log + zone-change encoding/enqueue. Four loopback clients are connected; socket delivery/coalescing/transcript I/O run on the writers and are outside enqueue latency. IdMapper is measured separately by replaying the same seat's copied primitive descriptors; log time uses the production cached visibility index and a hidden-source entry. Highlights use the full uncached priority predicate scan plus the normal protocol push with the production dynamic scan budget. Schema validation and leak checks are outside timing.\n\n")
                .append("Timing cells are median / p95 / max; nearest-rank p95. JSON sizes are UTF-8 bytes. arena.perf=").append(Boolean.getBoolean("arena.perf")).append(".\n\n")
                .append("| Seat | JSON bytes median / max | Build ms | Pipeline ms | Highlight scan + push ms | Log us | IdMapper ms | Mapped cards |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
        boolean missed = false;
        for (int seat = 0; seat < results.size(); seat++) {
            Measurements m = results.get(seat);
            report.append(String.format(Locale.ROOT, "| %d | %.0f / %d | %s | %s | %s | %s | %s | %d |%n", seat,
                    median(m.bytes()), Arrays.stream(m.bytes()).max().orElseThrow(), stats(m.build(), 1e6), stats(m.pipeline(), 1e6), stats(m.highlights(), 1e6), stats(m.log(), 1e3), stats(m.mapper(), 1e6), m.references()));
            missed |= median(m.build()) >= 20e6 || median(m.highlights()) >= 300e6;
        }
        report.append("\nThree largest value fields per seat (zone arrays aggregated across players; excludes field-name/container punctuation):\n\n| Seat | Field | Bytes |\n|---|---|---:|\n");
        for (int seat = 0; seat < results.size(); seat++) {
            int index = seat;
            results.get(seat).fields().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                    .forEach(field -> report.append("| ").append(index).append(" | ").append(field.getKey()).append(" | ").append(field.getValue()).append(" |\n"));
        }
        report.append("\nBudgets: <150,000 bytes per seat, build median <20 ms, highlight median <300 ms. Timing assertions run only with arena.perf=true.\n");
        if (missed) report.append("\nTiming budget missed; no optimization beyond send-path reparse cleanup was applied. Proposed fixes, ranked by gain/cost: (1) lower YIELD_AVAILABLE_ACTIONS_BUDGET_MS if scan-bound (low cost; expiry prompts safely); (2) avoid full snapshot rebuilds when only activeInput changed, preserving existing path (medium cost if regressions caused excess walks); (3) profile repeated live-card lookup/JSON allocations and cache detached fields per object delta (higher cost, visibility invalidation must stay correct).\n");
        else report.append("All measured timing medians meet their budgets. No further optimization applied.\n");
        Files.writeString(directory.resolve("1.15-report.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
