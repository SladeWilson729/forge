package forge.arena.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.networknt.schema.*;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import static org.testng.Assert.assertTrue;

/** Shared socket/codec schema checks; validation errors retain the complete payload. */
public final class PromptAssertions {
    private static final ConcurrentHashMap<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();
    private PromptAssertions() { }
    public static void validate(JsonObject json, String definition) {
        final JsonSchema schema = SCHEMAS.computeIfAbsent(definition, name ->
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                        SchemaLocation.of(Path.of("../protocol/schema.json").toAbsolutePath().normalize().toUri()
                                + "#/$defs/" + name)));
        final var errors = schema.validate(WsServer.GSON.toJson(json), InputFormat.JSON);
        assertTrue(errors.isEmpty(), definition + ": " + errors + " payload=" + json);
    }
    public static JsonObject reply(JsonObject prompt, JsonElement result) {
        final JsonObject reply = new JsonObject();
        reply.addProperty("type", "promptReply");
        reply.add("requestId", prompt.get("requestId"));
        reply.add("result", result);
        validate(reply, "promptReply");
        return reply;
    }
}
