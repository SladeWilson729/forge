package forge.arena.server;

import com.google.gson.*;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

/** Captures socket payloads from the writer's transcript, including its accepted-reply verdict. */
public class WireFixtureCatalogTest {
    private static final Path FIXTURES = Path.of("../protocol/fixtures");
    // These are NOT synthesized examples. README explains the absent live Commander callers.
    private static final Set<String> ABSENT_PROMPTS = Set.of("option", "text");
    private static final Set<String> ABSENT_INPUTS = Set.of("select");
    private static final Set<String> PROMPT_VARIANTS = Set.of("choose-revealOnly", "chooseEntities-multiple",
            "order-destination", "assignCombatDamage-trample");
    private record Captured(String json, String reply, String scenario) { }
    private final Map<String, Captured> prompts = new TreeMap<>(), inputs = new TreeMap<>();

    @Test(timeOut = 240_000)
    public void catalogFromRealCommanderGames() throws Exception {
        String previousDirectory = System.getProperty("arena.transcriptDir");
        Path capture = Files.createTempDirectory(Path.of("target"), "wire-fixtures-");
        try {
            Path combatDirectory = capture.resolve("combat");
            System.setProperty("arena.transcriptDir", combatDirectory.toString());
            CombatAndManaStateTest combat = new CombatAndManaStateTest();
            combat.initialize();
            combat.runScenario(true);
            collect(combatDirectory, "CombatAndManaStateTest.runScenario(true)");

            Path decisionsDirectory = capture.resolve("decisions");
            System.setProperty("arena.transcriptDir", decisionsDirectory.toString());
            captureControllerDecisions();
            collect(decisionsDirectory, "PromptGameHarness live controller decisions");

            JsonObject defs = JsonParser.parseString(Files.readString(Path.of("../protocol/schema.json")))
                    .getAsJsonObject().getAsJsonObject("$defs");
            Set<String> promptKinds = strings(defs.getAsJsonObject("PromptKind").getAsJsonArray("enum"));
            Set<String> inputKinds = strings(defs.getAsJsonObject("ActiveInput").getAsJsonObject("properties")
                    .getAsJsonObject("kind").getAsJsonArray("enum"));
            assertTrue(promptKinds.containsAll(ABSENT_PROMPTS), "Remove obsolete prompt exceptions");
            assertTrue(inputKinds.containsAll(ABSENT_INPUTS), "Remove obsolete input exceptions");
            promptKinds.removeAll(ABSENT_PROMPTS); inputKinds.removeAll(ABSENT_INPUTS);
            Set<String> expectedPrompts = new TreeSet<>(promptKinds); expectedPrompts.addAll(PROMPT_VARIANTS);
            assertEquals(prompts.keySet(), expectedPrompts, "Live prompt coverage: " + prompts.keySet() + " expected " + expectedPrompts);
            assertEquals(inputs.keySet(), inputKinds, "Live Input coverage: " + inputs.keySet() + " expected " + inputKinds);

            // Normal test runs never change the checked-in catalog. Regeneration is explicit;
            // ids, seq and requestId remain the ORIGINAL values, with no normalization.
            Path generated = capture.resolve("catalog");
            write(generated);
            if (Boolean.getBoolean("arena.fixtures.write")) write(FIXTURES);
            validateCatalog(FIXTURES, expectedPrompts, inputKinds);
            validateCatalog(generated, expectedPrompts, inputKinds);
            String readme = Files.readString(FIXTURES.resolve("README.md"));
            for (String name : expectedPrompts) {
                assertTrue(readme.contains("prompts/" + name + ".json"), "README prompt " + name);
                assertTrue(readme.contains("prompts/" + name + ".reply.json"), "README reply " + name);
            }
            for (String name : inputKinds) assertTrue(readme.contains("inputs/" + name + ".json"), "README Input " + name);
            for (String name : ABSENT_PROMPTS) assertTrue(readme.contains("`prompt." + name + "`"));
            for (String name : ABSENT_INPUTS) assertTrue(readme.contains("`activeInput." + name + "`"));
            prompts.forEach((name, value) -> System.out.println("[wire-fixture] prompt=" + name + " accepted; " + value.scenario));
            inputs.forEach((name, value) -> System.out.println("[wire-fixture] input=" + name + "; " + value.scenario));
        } finally {
            if (previousDirectory == null) System.clearProperty("arena.transcriptDir");
            else System.setProperty("arena.transcriptDir", previousDirectory);
        }
    }

    /**
     * A real started four-player Commander game, parked at priority. Probe the public
     * controller decision seams used by effects/combat; each runs on a Game pool worker,
     * uses that game's cards, and blocks on the real GUI/Input latch. No constructed
     * GuiGameEvent, JSON prompt, artificial Input subclass or direct remote call.
     */
    private void captureControllerDecisions() throws Exception {
        PromptGameHarness.initialize();
        String previousDisplays = FModel.getPreferences().getPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS);
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.answer = p -> {
                String kind = p.get("kind").getAsString();
                if (kind.equals("choose") && p.get("title").getAsString().equals("Fixture integer")) {
                    JsonArray other = new JsonArray(); other.add(p.getAsJsonArray("options").size() - 1); return other;
                }
                if (kind.equals("number")) return new JsonPrimitive("12");
                if (kind.equals("order") && p.get("remainingMax").getAsInt() > 0) {
                    JsonObject selection = new JsonObject(); JsonArray chosen = new JsonArray(); chosen.add(0);
                    selection.add("ordered", chosen); selection.addProperty("remember", false); return selection;
                }
                return p.get("default");
            };
            h.scenarioLines = lines -> {
                lines.removeIf(line -> line.startsWith("p1battlefield="));
                lines.add("p1battlefield=Grizzly Bears;Runeclaw Bear");
            };
            h.start("Colossal Dreadmaw;Grizzly Bears;Runeclaw Bear;Selesnya Guildmage;Forest;Island;Plains;Plains",
                    "Shock;Sprout Swarm;Counterspell;Opt");
            onGame(h, () -> {
                SpellAbility cause = cause(h);
                assertEquals(h.human.chooseNumber(cause, "Fixture choose", 1, 3), 1);
                CardCollection library = library(h);
                h.human.reveal(library, ZoneType.Library, h.human.getPlayer(), "Fixture reveal", false);
                FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
                assertNotNull(h.human.chooseSingleEntityForEffect(library, null, cause,
                        "Fixture library search", false, null, null));
                assertEquals(h.human.chooseEntitiesForEffect(library, 1, 2, null, cause,
                        "Fixture multiple library choices", null, null).size(), 1);
                Card attacker = h.card("Colossal Dreadmaw", ZoneType.Battlefield);
                CardCollection blockers = new CardCollection(h.game.getPlayers().get(1).getCreaturesInPlay());
                assertEquals(h.human.orderBlockers(attacker, blockers).size(), 2);
                // Scry's dual-list UI starts with an empty destination. Its reply contains
                // destination ids, not the whole source list (unlike damage-order sorting).
                var scry = h.human.arrangeForScry(library(h));
                assertEquals(scry.getRight().size(), 1);
                h.blockingConfirm = true;
                h.human.confirmAction(cause, PlayerActionConfirmMode.OptionalChoose,
                        "Fixture optional action?", List.of(), null, null);
                h.blockingConfirm = false;
                assertEquals(h.human.chooseNumber(cause, "Fixture integer", 0, Integer.MAX_VALUE), 12);
                Map<GameEntity, Integer> recipients = new LinkedHashMap<>();
                recipients.put(blockers.get(0), 3); recipients.put(h.human.getPlayer(), 3);
                assertEquals(h.human.divideShield(attacker, recipients, 3).values().stream().mapToInt(Integer::intValue).sum(), 3);
                assertNotNull(h.human.assignCombatDamage(bears(h).get(0), blockers, null, 2, null, false));
                Map<Card, Integer> trample = h.human.assignCombatDamage(attacker, blockers, null, 6, h.game.getPlayers().get(1), false);
                assertEquals(trample.get(null), Integer.valueOf(2), "Real trample defender allocation");
                FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, true);
                var displayScry = h.human.arrangeForScry(library(h));
                assertEquals(displayScry.getLeft().size() + displayScry.getRight().size(), 2);
                return null;
            }).get(30, TimeUnit.SECONDS);

            // Nested real Inputs while the main game thread remains parked at priority.
            CompletableFuture<?> selection = onGame(h, () -> h.human.choosePermanentsToSacrifice(cause(h), 1, 1, bears(h), "one creature"));
            h.waitClientKind("selectCards");
            h.sendAction("selectCard", "cardId", h.clientState().getAsJsonObject("activeInput").getAsJsonArray("selectable").get(0));
            assertNotNull(selection.get(5, TimeUnit.SECONDS)); h.waitClientKind("priority");

            selection = onGame(h, () -> h.human.chooseSingleEntityForEffect(h.game.getPlayers(), null, cause(h),
                    "Fixture player choice", false, null, null));
            h.waitClientKind("selectEntities");
            h.sendAction("selectPlayer", "playerId", new JsonPrimitive(h.human.getPlayer().getId()));
            assertNotNull(selection.get(5, TimeUnit.SECONDS)); h.waitClientKind("priority");

            selection = onGame(h, () -> h.human.chooseCardsForConvokeOrImprovise(
                    h.card("Sprout Swarm", ZoneType.Hand).getFirstSpellAbility(),
                    h.card("Sprout Swarm", ZoneType.Hand).getManaCost(), bears(h), false, true, null));
            h.waitClientKind("convoke"); h.sendAction("ok");
            assertNotNull(selection.get(5, TimeUnit.SECONDS)); h.waitClientKind("priority");

            selection = onGame(h, () -> h.human.confirmAction(cause(h), PlayerActionConfirmMode.OptionalChoose,
                    "Fixture desktop confirmation?", List.of(), null, null));
            h.waitClientKind("confirm"); h.sendAction("ok");
            assertEquals(selection.get(5, TimeUnit.SECONDS), true); h.waitClientKind("priority");

            // The original combat harness answers Shock targeting immediately on the EDT;
            // SeatWriter may coalesce that state away. A fixture needs the actual wire state,
            // so this probe explicitly waits for delivery before sending its player target.
            selection = onGame(h, () -> {
                SpellAbility shock = cause(h); shock.setActivatingPlayer(h.human.getPlayer());
                return h.human.chooseTargetsFor(shock);
            });
            h.waitClientKind("target");
            h.sendAction("selectPlayer", "playerId", new JsonPrimitive(h.game.getPlayers().get(1).getId()));
            assertEquals(selection.get(5, TimeUnit.SECONDS), true); h.waitClientKind("priority");

            // As in EventPumpTest, stage a real stack entry without paying for a setup spell.
            // TargetSelection then creates InputSelectStackItems itself, using Counterspell's script.
            selection = onGame(h, () -> {
                Card opt = h.game.getAction().moveToStack(h.card("Opt", ZoneType.Hand), null);
                SpellAbility spell = opt.getFirstSpellAbility(); spell.setActivatingPlayer(h.human.getPlayer());
                h.game.getStack().addAndUnfreeze(spell);
                SpellAbility counter = h.card("Counterspell", ZoneType.Hand).getFirstSpellAbility();
                counter.setActivatingPlayer(h.human.getPlayer());
                return h.human.chooseTargetsFor(counter);
            });
            h.waitClientKind("selectStack");
            h.sendAction("selectStackItem", "stackId", h.clientState().getAsJsonObject("activeInput").getAsJsonArray("targets")
                    .get(0).getAsJsonObject().get("id"));
            assertEquals(selection.get(5, TimeUnit.SECONDS), true); h.waitClientKind("priority");

            // A normal action raises the EDT-inside-selectCard ability-choice prompt.
            // Cancel the subsequent cost Input after capturing it; no state or prompt is forged.
            h.action(() -> h.controller.selectCard(h.card("Selesnya Guildmage", ZoneType.Battlefield).getView(), null, null));
            h.waitClientKind("payMana"); h.sendAction("cancel"); h.waitClientKind("priority");
            h.until(() -> h.remote.pendingPromptCount() == 0, "all captured replies accepted");
            assertFalse(h.client.history().stream().map(WsTestClient.Received::object).anyMatch(p ->
                    "message".equals(p.get("type").getAsString()) && "rejected".equals(p.get("level").getAsString())),
                    "Every scripted client response must be accepted");
            h.healthy();
        } finally {
            FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, previousDisplays);
        }
    }

    private static SpellAbility cause(PromptGameHarness h) { return h.card("Shock", ZoneType.Hand).getFirstSpellAbility(); }
    private static CardCollection bears(PromptGameHarness h) {
        return new CardCollection(List.of(h.card("Grizzly Bears", ZoneType.Battlefield), h.card("Runeclaw Bear", ZoneType.Battlefield)));
    }
    private static CardCollection library(PromptGameHarness h) {
        return new CardCollection(h.human.getPlayer().getCardsIn(ZoneType.Library)).subList(0, 2);
    }
    private static <T> CompletableFuture<T> onGame(PromptGameHarness h, Callable<T> action) {
        CompletableFuture<T> done = new CompletableFuture<>();
        h.game.getAction().invoke(() -> {
            try { done.complete(action.call()); }
            catch (Throwable ex) { done.completeExceptionally(ex); }
        });
        return done;
    }

    private void collect(Path directory, String scenario) throws Exception {
        try (var files = Files.list(directory)) {
            for (Path path : files.sorted().toList()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                if (JsonParser.parseString(lines.get(0)).getAsJsonObject().get("seat").getAsInt() != 0) continue;
                Map<Integer, String> accepted = new HashMap<>();
                for (String line : lines.subList(1, lines.size())) {
                    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                    JsonObject message = record.getAsJsonObject("msg");
                    if ("in".equals(record.get("dir").getAsString()) && "promptReply".equals(message.get("type").getAsString())
                            && record.has("result") && "accepted".equals(record.get("result").getAsString())) {
                        String raw = line.substring(line.indexOf("\"msg\":") + 6, line.lastIndexOf(",\"result\":"));
                        accepted.put(message.get("requestId").getAsInt(), raw);
                    }
                }
                for (String line : lines.subList(1, lines.size())) {
                    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                    if (!"out".equals(record.get("dir").getAsString())) continue;
                    JsonObject message = record.getAsJsonObject("msg");
                    String raw = line.substring(line.indexOf("\"msg\":") + 6, line.length() - 1);
                    String type = message.get("type").getAsString();
                    if (type.equals("state")) {
                        String kind = message.getAsJsonObject("activeInput").get("kind").getAsString();
                        Captured previous = inputs.get(kind);
                        // Prefer the complete display rather than an intermediate setSelection delta.
                        if (previous == null || inputScore(message) > inputScore(JsonParser.parseString(previous.json).getAsJsonObject()))
                            inputs.put(kind, new Captured(raw, null, scenario));
                    } else if (type.equals("prompt")) {
                        String reply = accepted.get(message.get("requestId").getAsInt());
                        assertNotNull(reply, "No accepted socket reply for " + message);
                        String name = promptName(message);
                        prompts.putIfAbsent(name, new Captured(raw, reply, scenario));
                    }
                }
            }
        }
    }

    private static int inputScore(JsonObject message) {
        JsonObject input = message.getAsJsonObject("activeInput");
        return (input.has("prompt") && !input.get("prompt").getAsString().isBlank() ? 100_000 : 0)
                + (input.has("buttons") ? 10_000 : 0) + input.toString().length();
    }
    private static String promptName(JsonObject message) {
        String kind = message.get("kind").getAsString();
        if (kind.equals("choose") && message.get("revealOnly").getAsBoolean()) return "choose-revealOnly";
        if (kind.equals("chooseEntities") && !message.get("single").getAsBoolean()) return "chooseEntities-multiple";
        if (kind.equals("order") && message.get("remainingMax").getAsInt() > 0) return "order-destination";
        if (kind.equals("assignCombatDamage") && message.has("defenderOptionId")) return "assignCombatDamage-trample";
        return kind;
    }
    private static Set<String> strings(JsonArray values) {
        Set<String> names = new TreeSet<>(); values.forEach(v -> names.add(v.getAsString())); return names;
    }
    private void write(Path root) throws Exception {
        Files.createDirectories(root.resolve("prompts")); Files.createDirectories(root.resolve("inputs"));
        for (var entry : prompts.entrySet()) {
            Files.writeString(root.resolve("prompts/" + entry.getKey() + ".json"), entry.getValue().json + "\n", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("prompts/" + entry.getKey() + ".reply.json"), entry.getValue().reply + "\n", StandardCharsets.UTF_8);
        }
        for (var entry : inputs.entrySet())
            Files.writeString(root.resolve("inputs/" + entry.getKey() + ".json"), entry.getValue().json + "\n", StandardCharsets.UTF_8);
    }
    private static void validateCatalog(Path root, Set<String> expectedPrompts, Set<String> expectedInputs) throws Exception {
        Set<String> expectedFiles = new TreeSet<>();
        for (String name : expectedPrompts) {
            Path prompt = root.resolve("prompts/" + name + ".json"), reply = root.resolve("prompts/" + name + ".reply.json");
            JsonObject p = JsonParser.parseString(Files.readString(prompt)).getAsJsonObject();
            JsonObject r = JsonParser.parseString(Files.readString(reply)).getAsJsonObject();
            PromptAssertions.validate(p, "prompt"); PromptAssertions.validate(r, "promptReply");
            assertEquals(promptName(p), name); assertEquals(r.get("requestId"), p.get("requestId"));
            expectedFiles.add("prompts/" + name + ".json"); expectedFiles.add("prompts/" + name + ".reply.json");
        }
        for (String name : expectedInputs) {
            JsonObject state = JsonParser.parseString(Files.readString(root.resolve("inputs/" + name + ".json"))).getAsJsonObject();
            PromptAssertions.validate(state, "state"); assertEquals(state.getAsJsonObject("activeInput").get("kind").getAsString(), name);
            // Seat identity belongs to the connection; collect() filters transcript headers to seat 0.
            expectedFiles.add("inputs/" + name + ".json");
        }
        try (var paths = Files.walk(root)) {
            Set<String> actual = new TreeSet<>();
            paths.filter(p -> p.toString().endsWith(".json")).forEach(p -> actual.add(root.relativize(p).toString().replace('\\', '/')));
            assertEquals(actual, expectedFiles, "Missing or unexpected fixture files");
        }
    }
}
