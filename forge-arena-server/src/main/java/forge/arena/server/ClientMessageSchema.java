package forge.arena.server;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/**
 * Compiles the vocabulary used by ClientMessage once, outside I/O. No runtime dependency:
 * networknt remains test-only and independently checks this interpreter in ActionRouterTest.
 * Unknown validation keywords fail startup rather than silently weakening validation.
 */
final class ClientMessageSchema {
    private static final Set<String> KEYWORDS = Set.of("$comment", "$ref", "type", "const", "enum",
            "allOf", "anyOf", "oneOf", "properties", "required", "additionalProperties",
            "propertyNames", "items", "uniqueItems", "minimum", "maximum");
    private final JsonObject defs;
    private final Predicate<JsonElement> validator;

    ClientMessageSchema() {
        Path path = Path.of(System.getProperty("arena.schema", "../protocol/schema.json"));
        if (!Files.exists(path) && System.getProperty("arena.schema") == null) path = Path.of("protocol/schema.json");
        try { defs = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("$defs"); }
        catch (IOException ex) { throw new IllegalStateException("Cannot read protocol schema: " + path, ex); }
        validator = compile(defs.get("ClientMessage"));
    }

    void validate(JsonElement message) {
        if (!validator.test(message)) throw new IllegalArgumentException("Message does not match ClientMessage schema");
    }

    private Predicate<JsonElement> compile(JsonElement schema) {
        if (schema.isJsonPrimitive()) return value -> schema.getAsBoolean();
        JsonObject s = schema.getAsJsonObject();
        for (String key : s.keySet()) if (!KEYWORDS.contains(key))
            throw new IllegalStateException("Unsupported ClientMessage schema keyword: " + key);
        List<Predicate<JsonElement>> checks = new ArrayList<>();
        if (s.has("$ref")) {
            String ref = s.get("$ref").getAsString();
            if (!ref.startsWith("#/$defs/")) throw new IllegalStateException("Non-local schema reference: " + ref);
            checks.add(compile(Objects.requireNonNull(defs.get(ref.substring(8)))));
        }
        if (s.has("type")) {
            JsonElement types = s.get("type");
            List<String> names = new ArrayList<>();
            if (types.isJsonArray()) types.getAsJsonArray().forEach(t -> names.add(t.getAsString()));
            else names.add(types.getAsString());
            checks.add(v -> names.stream().anyMatch(t -> matchesType(v, t)));
        }
        if (s.has("const")) checks.add(v -> s.get("const").equals(v));
        if (s.has("enum")) checks.add(v -> s.getAsJsonArray("enum").contains(v));
        for (String key : List.of("allOf", "anyOf", "oneOf")) if (s.has(key)) {
            List<Predicate<JsonElement>> branches = new ArrayList<>();
            s.getAsJsonArray(key).forEach(b -> branches.add(compile(b)));
            checks.add(v -> {
                long count = branches.stream().filter(b -> b.test(v)).count();
                return key.equals("allOf") ? count == branches.size() : key.equals("oneOf") ? count == 1 : count > 0;
            });
        }
        Map<String, Predicate<JsonElement>> properties = new HashMap<>();
        if (s.has("properties")) s.getAsJsonObject("properties").entrySet()
                .forEach(e -> properties.put(e.getKey(), compile(e.getValue())));
        List<String> required = new ArrayList<>();
        if (s.has("required")) s.getAsJsonArray("required").forEach(v -> required.add(v.getAsString()));
        Predicate<JsonElement> extra = s.has("additionalProperties") ? compile(s.get("additionalProperties")) : v -> true;
        Predicate<JsonElement> names = s.has("propertyNames") ? compile(s.get("propertyNames")) : v -> true;
        checks.add(v -> {
            if (!v.isJsonObject()) return true;
            JsonObject obj = v.getAsJsonObject();
            if (!obj.keySet().containsAll(required)) return false;
            return obj.entrySet().stream().allMatch(e -> names.test(new JsonPrimitive(e.getKey()))
                    && properties.getOrDefault(e.getKey(), extra).test(e.getValue()));
        });
        Predicate<JsonElement> item = s.has("items") ? compile(s.get("items")) : v -> true;
        checks.add(v -> {
            if (!v.isJsonArray()) return true;
            Set<JsonElement> seen = new HashSet<>();
            for (JsonElement e : v.getAsJsonArray()) {
                if (!item.test(e)) return false;
                if (s.has("uniqueItems") && s.get("uniqueItems").getAsBoolean() && !seen.add(e)) return false;
            }
            return true;
        });
        for (String bound : List.of("minimum", "maximum")) if (s.has(bound)) {
            BigDecimal limit = s.get(bound).getAsBigDecimal();
            checks.add(v -> !matchesType(v, "number") || (bound.equals("minimum")
                    ? v.getAsBigDecimal().compareTo(limit) >= 0 : v.getAsBigDecimal().compareTo(limit) <= 0));
        }
        return v -> v != null && checks.stream().allMatch(c -> c.test(v));
    }

    private static boolean matchesType(JsonElement v, String type) {
        return switch (type) {
            case "null" -> v.isJsonNull();
            case "object" -> v.isJsonObject();
            case "array" -> v.isJsonArray();
            case "string" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
            case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
            case "number" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
            case "integer" -> matchesType(v, "number") && v.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalStateException("Unsupported schema type: " + type);
        };
    }
}
