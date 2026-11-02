package forge.arena.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.StaticData;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputAttack;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

/** Startup/legality probes only. Scenario actions belong to Will's Unity checklist. */
public class SmokePuzzleTest {
    private static final Path ROOT = Path.of("tools/puzzles/smoke");
    private static final String CHECKLIST = "2026-11-02_ForgeArena_SmokeChecklist.md";
    private static final Map<String, ZoneType> ZONES = Map.of("hand", ZoneType.Hand, "battlefield", ZoneType.Battlefield,
            "command", ZoneType.Command, "library", ZoneType.Library, "graveyard", ZoneType.Graveyard, "exile", ZoneType.Exile);
    private record Spec(String name, Map<String, String> attributes) {
        static Spec parse(String text) {
            String[] parts = text.split("\\|"); Map<String, String> attributes = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] pair = parts[i].split(":", 2); attributes.put(pair[0], pair.length == 1 ? "" : pair[1]);
            }
            return new Spec(parts[0], attributes);
        }
    }

    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @DataProvider public Object[][] puzzles() throws Exception {
        try (var paths = Files.list(ROOT)) {
            List<Path> puzzles = paths.filter(p -> p.toString().endsWith(".pzl")).sorted().toList();
            assertEquals(puzzles.size(), 9, "Every smoke puzzle has a startup case");
            return puzzles.stream().map(p -> new Object[]{p}).toArray(Object[][]::new);
        }
    }

    @Test(dataProvider = "puzzles", timeOut = 90_000)
    public void startedPuzzleMatchesItsFileAndHighlightsItsFirstActions(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path);
        Map<String, String> fields = fields(lines);
        verifyScripts(lines, fields);
        verifyRunConfiguration(path);
        String savedHighlights = FModel.getPreferences().getPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
        try (PromptGameHarness h = new PromptGameHarness(true, 0, 1, false)) {
            // A preloaded spell gives its caster (seat 1) priority, just as a real cast
            // does. Pass only that human's priority windows until seat 0 can act; do not
            // resolve the spell or auto-answer seat 0's Counterspell opportunity.
            h.secondaryAutoPriority = true;
            h.extraSetup = game -> {
                try { ArenaPuzzle.read(path).apply(game, h.players); }
                catch (Exception ex) { throw new IllegalStateException("Loading " + path, ex); }
            };
            h.start("", "");
            h.secondaryAutoPriority = false;
            PromptGameHarness.edt(() -> {
                assertTrue(h.game.getRules().hasCommander());
                assertTrue(ArenaServerMain.playerFor(h.game, h.players.get(0)).getController() instanceof PlayerControllerHuman);
                assertTrue(ArenaServerMain.playerFor(h.game, h.players.get(1)).getController() instanceof PlayerControllerHuman);
                assertTrue(ArenaServerMain.playerFor(h.game, h.players.get(2)).isAI());
                assertTrue(ArenaServerMain.playerFor(h.game, h.players.get(3)).isAI());
                verifyBoard(h, fields, lines);
                if (path.getFileName().toString().startsWith("09-")) {
                    var token = StaticData.instance().getAllTokens().getToken("undercity");
                    assertNotNull(token, "Undercity token script loads");
                    Card dungeon = CardFactory.getCard(token, h.human.getPlayer(), h.game);
                    assertTrue(dungeon.getType().isDungeon()); assertFalse(dungeon.getTriggers().isEmpty());
                    // Construct only: do not enter a room, put it in a zone, or resolve an effect.
                }
                return null;
            });
            for (String line : lines) if (line.startsWith("#smoke-highlight=")) {
                String[] choice = line.substring("#smoke-highlight=".length()).split("\\|", 3);
                int seat = Integer.parseInt(choice[0]); ZoneType zone = ZoneType.valueOf(choice[1]);
                h.until(() -> {
                    Player player = ArenaServerMain.playerFor(h.game, h.players.get(seat));
                    Card card = player.getCardsIn(zone).stream().filter(c -> c.getName().equals(choice[2])).findFirst().orElseThrow();
                    JsonObject state = seat == 0 ? h.clientState() : h.secondaryState;
                    if (!highlighted(state, card.getId())) return false;
                    assertTrue(card.getAllPossibleAbilities(player, true).stream().anyMatch(sa -> sa.canPlay()),
                            choice[2] + " has a playable ability on the parked human's priority");
                    return true;
                }, path.getFileName() + " highlight " + choice[2]);
                System.out.printf("[smoke-highlight] %s seat=%d %s%n", path.getFileName(), seat, choice[2]);
            }
            for (String line : lines) if (line.startsWith("#smoke-attackers=")) {
                // Only pass priority to the declare-attackers Input. No creature is selected,
                // no damage is assigned, and no rule scenario is played out by this test.
                h.secondaryAutoPriority = true;
                try { h.advance(input -> input instanceof InputAttack); }
                finally { h.secondaryAutoPriority = false; }
                for (String name : line.substring("#smoke-attackers=".length()).split(";")) h.until(() -> {
                    Card card = h.card(name, ZoneType.Battlefield);
                    assertFalse(card.hasSickness()); assertFalse(card.isTapped());
                    return highlighted(h.clientState(), card.getId());
                }, "legal attacker highlight " + name);
                PromptGameHarness.edt(() -> {
                    Card commander = h.card("Isamaru, Hound of Konda", ZoneType.Battlefield);
                    assertTrue(commander.isCommander()); assertEquals(commander.getNetPower(), 21);
                    assertEquals(ArenaServerMain.playerFor(h.game, h.players.get(1)).getLife(), 22);
                    assertTrue(h.game.getCombat().getDefenders().stream().anyMatch(d ->
                            d instanceof Card c && c.getName().equals("Jace Beleren")), "Planeswalker is a legal defender");
                    assertTrue(h.game.getCombat().getAttackers().isEmpty(), "Startup test declared no attacks");
                    return null;
                });
            }
            h.healthy();
            PromptGameHarness.edt(() -> {
                assertTrue(h.game.getStack().isEmpty() || path.getFileName().toString().startsWith("05-"), "No scenario was cast");
                return null;
            });
            for (var received : h.client.history()) if (received.object().get("type").getAsString().equals("state"))
                PromptAssertions.validate(received.object(), "state");
            System.out.println("[smoke-puzzle] " + path.getFileName() + " board+scripts+highlights verified; no scenario played");
        } finally { FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, savedHighlights); }
    }

    private static Map<String, String> fields(List<String> lines) {
        Map<String, String> result = new HashMap<>();
        for (String line : lines) if (!line.isBlank() && !line.startsWith("#")) {
            String[] pair = line.split("=", 2); assertEquals(pair.length, 2, line); result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static List<Spec> cards(String text) {
        return text == null || text.isEmpty() ? List.of() : Arrays.stream(text.split(";")).map(Spec::parse).toList();
    }

    private static void verifyScripts(List<String> lines, Map<String, String> fields) throws Exception {
        Set<String> names = new TreeSet<>();
        for (int seat = 0; seat < 4; seat++) for (String zone : ZONES.keySet())
            cards(fields.get("p" + seat + zone)).forEach(card -> names.add(card.name));
        for (String line : lines) if (line.startsWith("#arena-stack=")) names.add(line.split("\\|", 2)[1]);
        String checklist = Files.readString(ROOT.resolve(CHECKLIST));
        for (String name : names) {
            String file = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replace(' ', '_');
            String source = "forge-gui/res/cardsfolder/" + file.charAt(0) + "/" + file + ".txt";
            assertTrue(Files.exists(Path.of("..").resolve(source)), source);
            assertTrue(Files.readAllLines(Path.of("..").resolve(source)).contains("Name:" + name), source + " names " + name);
            assertTrue(checklist.contains(source), "Checklist records verified script " + source);
            var paper = FModel.getMagicDb().getCommonCards().getCard(name);
            assertNotNull(paper, name + " loads from the real card DB"); assertFalse(paper.getRules().isUnsupported(), name);
        }
    }

    private static void verifyRunConfiguration(Path puzzle) throws Exception {
        int number = Integer.parseInt(puzzle.getFileName().toString().substring(0, 2));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("../.run/Smoke-" + number + ".run.xml").toFile());
        var options = document.getElementsByTagName("option"); Map<String, String> values = new HashMap<>();
        for (int i = 0; i < options.getLength(); i++) {
            var attrs = options.item(i).getAttributes();
            if (attrs.getNamedItem("value") != null) values.put(attrs.getNamedItem("name").getNodeValue(), attrs.getNamedItem("value").getNodeValue());
        }
        assertEquals(values.get("MAIN_CLASS_NAME"), "forge.arena.server.ArenaServerMain");
        String vm = values.get("VM_PARAMETERS");
        for (String required : List.of("-Darena.humanSeats=0,1", "-Darena.bind=127.0.0.1", "-Darena.meter=false",
                "-Darena.puzzle=" + puzzle.toString().replace('\\', '/'))) assertTrue(vm.contains(required), required);
        assertEquals(values.get("WORKING_DIRECTORY"), "$MODULE_WORKING_DIR$");
    }

    private static void verifyBoard(PromptGameHarness h, Map<String, String> fields, List<String> lines) {
        assertEquals(h.game.getPhaseHandler().getPhase(), PhaseType.valueOf(fields.get("activephase")));
        assertEquals(h.game.getPhaseHandler().getTurn(), Integer.parseInt(fields.get("turn")));
        assertEquals(h.game.getView().getTurn(), Integer.parseInt(fields.get("turn")), "Tracked turn matches puzzle");
        int active = Integer.parseInt(fields.get("activeplayer").substring(1));
        assertSame(h.game.getPhaseHandler().getPlayerTurn(), ArenaServerMain.playerFor(h.game, h.players.get(active)));
        Map<Integer, Card> ids = new HashMap<>(); Map<Card, Integer> attachments = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            Player player = ArenaServerMain.playerFor(h.game, h.players.get(seat));
            assertEquals(player.getLife(), Integer.parseInt(fields.get("p" + seat + "life")));
            assertEquals(player.getCounters(CounterType.getType("POISON")), 0);
            for (var zone : ZONES.entrySet()) {
                List<Spec> expected = cards(fields.get("p" + seat + zone.getKey()));
                // Native Commander Effect is an immutable rules object, not a puzzle card.
                List<Card> actual = player.getCardsIn(zone.getValue()).stream().filter(c -> !c.isImmutable()).toList();
                assertEquals(actual.stream().map(Card::getName).sorted().toList(), expected.stream().map(Spec::name).sorted().toList(),
                        "seat " + seat + " " + zone.getKey());
                if (zone.getValue() == ZoneType.Library) assertEquals(actual.stream().map(Card::getName).toList(),
                        expected.stream().map(Spec::name).toList(), "Library top order is part of the manual scenario");
                Set<Card> used = new HashSet<>();
                for (Spec spec : expected) {
                    Card card = actual.stream().filter(c -> !used.contains(c) && c.getName().equals(spec.name)
                            && c.isTapped() == spec.attributes.containsKey("Tapped")).findFirst().orElseThrow(); used.add(card);
                    assertEquals(card.isCommander(), spec.attributes.containsKey("IsCommander"), spec.name);
                    if (card.isCreature() && zone.getValue() == ZoneType.Battlefield) assertFalse(card.hasSickness(), spec.name);
                    if (spec.attributes.containsKey("Id")) ids.put(Integer.parseInt(spec.attributes.get("Id")), card);
                    if (spec.attributes.containsKey("AttachedTo")) attachments.put(card, Integer.parseInt(spec.attributes.get("AttachedTo")));
                    if (spec.attributes.containsKey("Counters")) for (String counter : spec.attributes.get("Counters").split(",")) {
                        String[] pair = counter.split("="); assertEquals(card.getCounters(CounterType.getType(pair[0])), Integer.parseInt(pair[1]), spec.name);
                    }
                }
            }
            assertEquals(player.getCommanders().size(), 1);
            for (Card commander : player.getCommanders()) assertEquals(player.getCommanderCast(commander), 0, "No cast history invented");
        }
        attachments.forEach((card, id) -> assertSame(card.getEntityAttachedTo(), ids.get(id), card.getName()));
        List<String> stack = lines.stream().filter(l -> l.startsWith("#arena-stack=")).map(l -> l.split("\\|", 2)[1]).toList();
        List<String> actualStack = new ArrayList<>();
        for (var item : h.game.getStack()) actualStack.add(item.getSpellAbility().getHostCard().getName());
        assertEquals(actualStack.stream().sorted().toList(), stack.stream().sorted().toList());
    }

    private static boolean highlighted(JsonObject state, int id) {
        if (state == null) return false;
        JsonObject input = state.getAsJsonObject("activeInput");
        if (!input.has("highlighted")) return false;
        for (JsonElement item : input.getAsJsonArray("highlighted")) if (item.getAsJsonObject().get("id").equals(new JsonPrimitive(id))) return true;
        return false;
    }
}
