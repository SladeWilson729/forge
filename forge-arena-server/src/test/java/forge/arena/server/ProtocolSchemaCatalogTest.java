package forge.arena.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.gamemodes.net.ProtocolMethod;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class ProtocolSchemaCatalogTest {
    private static final Pattern METHOD_ROW = Pattern.compile("(?m)^\\| `([A-Za-z][A-Za-z0-9]*)` \\|");

    @Test
    public void catalogCoversPublicProtocol() throws Exception {
        final Path protocol = Path.of("../protocol");
        final String catalog = Files.readString(protocol.resolve("schema.md"), StandardCharsets.UTF_8);
        final String sectionA = section(catalog, "## A.", "## B.");
        final List<String> enumRows = methodRows(sectionA);
        final List<String> enumNames = Arrays.stream(ProtocolMethod.values()).map(Enum::name).toList();
        // Comparing the entire ordered list catches missing, duplicate, extra and reordered entries.
        assertEquals(enumRows, enumNames, "Section A must catalog each enum entry exactly once in enum order");

        final Set<String> catalogedMethods = new HashSet<>(methodRows(section(catalog, "## A.", "## D.")));
        // Only public interface metadata is inspected; no Forge internals or accessibility overrides.
        for (final Class<?> contract : List.of(IGuiGame.class, IGameController.class)) {
            for (final Method method : contract.getMethods()) {
                assertTrue(catalogedMethods.contains(method.getName()),
                        contract.getSimpleName() + "." + method.getName() + " is missing from sections A-C");
            }
        }

        final JsonObject schema = JsonParser.parseString(
                Files.readString(protocol.resolve("schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(schema.get("$schema").getAsString(), "https://json-schema.org/draft/2020-12/schema");
        final JsonObject definitions = schema.getAsJsonObject("$defs");
        assertNotNull(definitions, "$defs must exist");
        final Set<String> allMessageTypes = new HashSet<>();
        for (final String unionName : List.of("ServerMessage", "ClientMessage")) {
            final JsonObject union = definitions.getAsJsonObject(unionName);
            assertNotNull(union, unionName + " must exist");
            assertNotNull(union.getAsJsonArray("oneOf"), unionName + " must be a oneOf union");
            assertTrue(union.getAsJsonArray("oneOf").size() > 0, unionName + " must not be empty");
            for (final JsonElement member : union.getAsJsonArray("oneOf")) {
                final String reference = member.getAsJsonObject().get("$ref").getAsString();
                assertTrue(reference.startsWith("#/$defs/"), "Message reference must target $defs: " + reference);
                final String name = reference.substring("#/$defs/".length());
                final JsonObject message = definitions.getAsJsonObject(name);
                assertNotNull(message, "Unresolved message reference: " + reference);
                assertEquals(message.get("type").getAsString(), "object", name + " must be an object");
                final JsonObject type = message.getAsJsonObject("properties").getAsJsonObject("type");
                assertNotNull(type, name + " must declare a type discriminator");
                assertTrue(type.has("const"), name + " must have a type const");
                assertEquals(type.get("const").getAsString(), name, "Message definition and discriminator must match");
                assertTrue(allMessageTypes.add(name), "Duplicate message discriminator: " + name);
                assertTrue(message.getAsJsonArray("required").asList().stream()
                        .anyMatch(value -> value.getAsString().equals("type")), name + " must require type");
            }
        }
        assertEquals(definitions.getAsJsonObject("hello").getAsJsonObject("properties")
                .getAsJsonObject("protocolVersion").get("const").getAsInt(), 1);
    }

    private static String section(final String catalog, final String heading, final String nextHeading) {
        final int start = catalog.indexOf(heading);
        final int end = catalog.indexOf(nextHeading, start + heading.length());
        assertTrue(start >= 0 && end > start, "Missing or misordered sections: " + heading + " / " + nextHeading);
        return catalog.substring(start, end);
    }

    private static List<String> methodRows(final String section) {
        final List<String> names = new ArrayList<>();
        final Matcher rows = METHOD_ROW.matcher(section);
        while (rows.find()) {
            names.add(rows.group(1));
        }
        return names;
    }
}
