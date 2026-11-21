package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.JsonValue;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;
import static fr.proutlost.director.catalog.InvalidContent.require;

/** Evaluates the closed subset used by the pinned content schemas; unknown keywords fail closed. */
public final class ContentSchema {
    private static final Set<String> KEYWORDS = Set.of("$schema", "$id", "$defs", "$ref", "type", "properties",
        "required", "additionalProperties", "minProperties", "propertyNames", "items", "minItems", "uniqueItems",
        "const", "enum", "pattern", "minimum", "maximum", "exclusiveMinimum", "anyOf", "oneOf");
    private final JsonValue root;
    public ContentSchema(byte[] bytes) { root = CanonicalJsonV1.decode(bytes); inspect(root); }
    public static ContentSchema bundled(String name) {
        if (!Set.of("event-definition", "chain-definition", "scoring-metadata").contains(name)) throw new IllegalArgumentException("schema name");
        try (InputStream in = ContentSchema.class.getResourceAsStream("/schemas/" + name + ".schema.json")) {
            if (in == null) throw new IllegalStateException("missing content schema " + name);
            return new ContentSchema(in.readNBytes(CanonicalJsonV1.MAX_BYTES + 1));
        } catch (IOException e) { throw new IllegalStateException("schema read", e); }
    }
    private void inspect(JsonValue schema) {
        var s = object(schema);
        require(KEYWORDS.containsAll(s.keySet()), "", "unsupported schema keyword");
        for (String key : List.of("properties", "$defs")) if (s.containsKey(key)) object(s.get(key)).values().forEach(this::inspect);
        for (String key : List.of("items", "propertyNames")) if (s.containsKey(key)) inspect(s.get(key));
        if (s.get("additionalProperties") instanceof JsonValue.ObjectValue v) inspect(v);
        for (String key : List.of("oneOf", "anyOf")) if (s.containsKey(key)) array(s.get(key)).forEach(this::inspect);
        if (s.containsKey("$ref")) resolve(text(s.get("$ref")));
    }
    private JsonValue resolve(String ref) {
        require(ref.startsWith("#/$defs/") && ref.substring(8).indexOf('/') < 0, "", "external/invalid schema reference");
        JsonValue result = object(object(root).get("$defs")).get(ref.substring(8));
        require(result != null, "", "missing schema reference"); return result;
    }
    public void validate(JsonValue value) { validate(root, value, "", 0); }
    private void validate(JsonValue schema, JsonValue v, String p, int depth) {
        require(depth <= 128, p, "schema depth");
        var s = object(schema);
        if (s.containsKey("$ref")) validate(resolve(text(s.get("$ref"))), v, p, depth + 1);
        for (String key : List.of("anyOf", "oneOf")) if (s.containsKey(key)) {
            List<JsonValue> branches = array(s.get(key));
            // The pinned unions have unique constant discriminators. Select them without
            // reporting an unrelated branch's missing argument as the user's error.
            if (key.equals("oneOf") && v instanceof JsonValue.ObjectValue input) {
                for (String discriminator : List.of("primitive", "type")) {
                    Map<JsonValue, JsonValue> variants = new HashMap<>(); boolean tagged = true;
                    for (JsonValue branch : branches) {
                        JsonValue props = object(branch).get("properties");
                        JsonValue tag = props instanceof JsonValue.ObjectValue properties ? properties.values().get(discriminator) : null;
                        JsonValue constant = tag instanceof JsonValue.ObjectValue property ? property.values().get("const") : null;
                        if (constant == null || variants.put(constant, branch) != null) { tagged = false; break; }
                    }
                    if (tagged) {
                        JsonValue selected = variants.get(input.values().get(discriminator));
                        require(selected != null, pointer(p, discriminator), "unknown/missing discriminator");
                        branches = List.of(selected); break;
                    }
                }
            }
            int matches = 0; InvalidContent deepest = null;
            for (var branch : branches) {
                try { validate(branch, v, p, depth + 1); matches++; }
                catch (InvalidContent e) { if (deepest == null || e.pointer.length() > deepest.pointer.length()) deepest = e; }
            }
            if (matches == 0 && deepest != null) throw deepest;
            require(key.equals("anyOf") ? matches > 0 : matches == 1, p, key + " mismatch");
        }
        if (s.containsKey("const")) require(v.equals(s.get("const")), p, "constant mismatch");
        if (s.containsKey("enum")) require(array(s.get("enum")).contains(v), p, "enum mismatch");
        if (s.containsKey("type")) {
            boolean valid = switch (text(s.get("type"))) {
                case "object" -> v instanceof JsonValue.ObjectValue;
                case "array" -> v instanceof JsonValue.Array;
                case "string" -> v instanceof JsonValue.Text;
                case "boolean" -> v instanceof JsonValue.Bool;
                case "null" -> v == JsonValue.Nil.INSTANCE;
                case "number" -> v instanceof JsonValue.Number;
                case "integer" -> v instanceof JsonValue.Number n && n.value().stripTrailingZeros().scale() <= 0;
                default -> false;
            };
            require(valid, p, "expected " + text(s.get("type")));
        }
        if (v instanceof JsonValue.Number n) {
            for (String key : List.of("minimum", "maximum", "exclusiveMinimum")) if (s.containsKey(key)) {
                int c = n.value().compareTo(number(s.get(key)));
                require(key.equals("maximum") ? c <= 0 : key.equals("minimum") ? c >= 0 : c > 0, p, key);
            }
        }
        if (v instanceof JsonValue.Text t && s.containsKey("pattern")) require(t.value().matches(text(s.get("pattern"))), p, "pattern mismatch");
        if (v instanceof JsonValue.Array a) {
            if (s.containsKey("minItems")) require(a.values().size() >= number(s.get("minItems")).intValueExact(), p, "minItems");
            if (s.get("uniqueItems") instanceof JsonValue.Bool b && b.value()) require(new HashSet<>(a.values()).size() == a.values().size(), p, "duplicate item");
            if (s.containsKey("items")) for (int i = 0; i < a.values().size(); i++) validate(s.get("items"), a.values().get(i), p + "/" + i, depth + 1);
        }
        if (v instanceof JsonValue.ObjectValue o) {
            if (s.containsKey("minProperties")) require(o.values().size() >= number(s.get("minProperties")).intValueExact(), p, "minProperties");
            if (s.containsKey("required")) for (var key : array(s.get("required"))) require(o.values().containsKey(text(key)), pointer(p, text(key)), "missing property");
            var properties = s.containsKey("properties") ? object(s.get("properties")) : Map.<String, JsonValue>of();
            for (var entry : new TreeMap<>(o.values()).entrySet()) {
                String child = pointer(p, entry.getKey());
                if (s.containsKey("propertyNames")) validate(s.get("propertyNames"), new JsonValue.Text(entry.getKey()), child, depth + 1);
                if (properties.containsKey(entry.getKey())) validate(properties.get(entry.getKey()), entry.getValue(), child, depth + 1);
                else if (s.get("additionalProperties") instanceof JsonValue.Bool b) require(b.value(), child, "unknown property");
                else if (s.get("additionalProperties") instanceof JsonValue.ObjectValue extra) validate(extra, entry.getValue(), child, depth + 1);
            }
        }
    }
}
