package forge.arena.server;

import com.google.gson.*;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.YieldUpdate;
import forge.localinstance.properties.ForgePreferences.FPref;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.testng.Assert.*;

public class YieldBridgeTest {
    @BeforeClass public void initialize() { PromptGameHarness.initialize(); }

    @Test public void allowListMatchesThePrivateSourceConstantWithoutReflection() throws Exception {
        String source = Files.readString(Path.of("../forge-gui/src/main/java/forge/gamemodes/match/YieldController.java"));
        String list = source.substring(source.indexOf("private static final EnumSet<FPref> SYNCED_PREFS")); list = list.substring(0, list.indexOf(");"));
        Set<FPref> prefs = EnumSet.noneOf(FPref.class); var matcher = Pattern.compile("FPref\\.([A-Z_]+)").matcher(list);
        while (matcher.find()) prefs.add(FPref.valueOf(matcher.group(1)));
        assertEquals(YieldBridge.SYNCED, prefs); assertEquals(prefs.size(), 17);
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of("../protocol/schema.json"))).getAsJsonObject();
        Set<String> names = new HashSet<>(); schema.getAsJsonObject("$defs").getAsJsonObject("YieldPref").getAsJsonArray("enum").forEach(p -> names.add(p.getAsString()));
        assertEquals(names, prefs.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test(timeOut = 120_000)
    public void allTwelveRecordsUsePublicIdsAndServerComputedFields() throws Exception {
        try (PromptGameHarness h = new PromptGameHarness(true)) {
            h.start("Selesnya Guildmage;Forest;Forest;Forest;Forest", "Forest");
            PromptGameHarness.edt(() -> {
                var bridge = h.remote.yields(); var card = h.card("Selesnya Guildmage", ZoneType.Battlefield);
                int ability = card.getSpellAbilities().get(0).getId();
                List<JsonObject> messages = new ArrayList<>();
                messages.add(json("{\"kind\":\"setMarker\",\"phaseOwnerId\":" + h.human.getPlayer().getId() + ",\"phase\":\"UPKEEP\"}"));
                messages.add(json("{\"kind\":\"clearMarker\"}"));
                messages.add(json("{\"kind\":\"stackYield\",\"active\":true,\"respectsInterrupts\":true}"));
                messages.add(json("{\"kind\":\"untilEndOfTurn\",\"active\":true}"));
                messages.add(json("{\"kind\":\"cardAutoYield\",\"cardId\":" + card.getId() + ",\"abilityId\":" + ability + ",\"active\":true}"));
                messages.add(json("{\"kind\":\"triggerDecision\",\"cardId\":" + card.getId() + ",\"abilityId\":" + ability + ",\"decision\":\"ACCEPT\"}"));
                messages.add(json("{\"kind\":\"disableYields\",\"disabled\":true}"));
                messages.add(json("{\"kind\":\"disableTriggers\",\"disabled\":true}"));
                messages.add(json("{\"kind\":\"skipPhase\",\"turnPlayerId\":" + h.human.getPlayer().getId() + ",\"phase\":\"DRAW\",\"skip\":true}"));
                messages.add(json("{\"kind\":\"pref\",\"name\":\"UI_SHOW_AUTOTAP_PREVIEW\",\"value\":\"true\"}"));
                messages.add(YieldBridge.defaultSeed(h.game, h.human.getPlayer().getView()));
                messages.add(json("{\"kind\":\"clearAbilityOrders\"}"));
                Set<Class<?>> records = new HashSet<>();
                for (JsonObject message : messages) {
                    message.addProperty("type", "setYield"); message.addProperty("clientSeq", 1); message.addProperty("baseSeq", h.remote.getLastStateSequence());
                    PromptAssertions.validate(message, "setYield");
                    YieldUpdate decoded = bridge.decode(message, h.game, h.human); records.add(decoded.getClass());
                    JsonObject encoded = bridge.encode(decoded); encoded.addProperty("seq", 0); PromptAssertions.validate(encoded, "yieldState");
                    assertEquals(encoded.get("kind"), message.get("kind"));
                    assertFalse(encoded.has("cardKey") || encoded.has("storageKey"));
                    if (decoded instanceof YieldUpdate.SetMarker marker) assertTrue(marker.atOrPastAtClick());
                }
                assertEquals(records.size(), 12);
                JsonObject forged = messages.get(0).deepCopy(); forged.addProperty("atOrPastAtClick", false);
                expectThrows(IllegalArgumentException.class, () -> new ClientMessageSchema().validate(forged));
                JsonObject raw = messages.get(4).deepCopy(); raw.addProperty("cardKey", "untrusted key");
                expectThrows(IllegalArgumentException.class, () -> new ClientMessageSchema().validate(raw));
                assertTrue(expectThrows(IllegalArgumentException.class, () -> YieldBridge.preference("UI_LANGUAGE", "en-US", false)).getMessage().contains("not a synced"));
                assertTrue(expectThrows(IllegalArgumentException.class, () -> YieldBridge.preference("YIELD_AUTO_PASS_NO_ACTIONS", "false", false)).getMessage().contains("server-wide"));
                return null;
            });
        }
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}
