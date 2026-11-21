package fr.proutlost.contract.director;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded, closed JSON codec. Only statically registered records can be instantiated. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class StrictCodec {
    private StrictCodec() {}
    private record NumericToken(String literal, BigDecimal decimal) {}
    private record PayloadText(String value) {
        PayloadText { Checks.rowPayload(value); }
    }
    private static int byteLimit(Class<?> type) {
        return type == StoredRow.class || type == RowChange.class || type == StoreTransaction.class || type == LeaseReservation.class
            ? Limits.MAX_ENVELOPE_JSON_BYTES : Limits.MAX_JSON_BYTES;
    }
    public static <T> T decode(String json, Class<T> type) {
        Objects.requireNonNull(json);
        Checks.require(json.length() <= byteLimit(type) && json.getBytes(StandardCharsets.UTF_8).length <= byteLimit(type), "JSON byte cap");
        try { return type.cast(convert(new Parser(json, type == CatalogSnapshot.class).parse(), type)); }
        catch (ReflectiveOperationException | ArithmeticException e) { throw new IllegalArgumentException("Invalid contract", e); }
    }
    public static String encode(Object value) {
        var result = json(tree(value));
        Checks.require(result.getBytes(StandardCharsets.UTF_8).length <= byteLimit(value == null ? Void.class : value.getClass()), "JSON byte cap");
        return result;
    }
    /** Content digest excludes only this object's own digest field. Nested digests remain pinned. */
    public static Digest contentDigest(Object record) {
        Object tree = tree(record);
        Checks.require(tree instanceof Map<?,?>, "Digest requires object");
        var fields = new TreeMap<>((Map<String,Object>)tree);
        Checks.require(fields.remove("digest") != null, "No digest field");
        return Digests.sha256(json(fields));
    }
    private static boolean registered(Class<?> type) {
        return ContractTypes.RECORDS.contains(type) || type == TypedValue.class || type.getEnclosingClass() == TypedValue.class;
    }
    private static Object convert(Object value, Type type) throws ReflectiveOperationException {
        if (type instanceof ParameterizedType p) {
            var raw = p.getRawType(); var args = p.getActualTypeArguments();
            if (raw == Map.class) {
                Checks.require(value instanceof Map<?,?>, "Expected map");
                var result = new LinkedHashMap<Object,Object>();
                for (var e : ((Map<?,?>)value).entrySet()) {
                    Object key = convert(e.getKey(), args[0]);
                    Checks.require(!result.containsKey(key), "Duplicate canonical key");
                    result.put(key, convert(e.getValue(), args[1]));
                }
                return result;
            }
            Checks.require(value instanceof List<?>, "Expected array");
            var result = new ArrayList<Object>();
            for (var e : (List<?>)value) result.add(convert(e, args[0]));
            if (raw == Set.class) { var set = new HashSet<>(result); Checks.require(set.size() == result.size(), "Duplicate set member"); return set; }
            Checks.require(raw == List.class, "Unregistered collection"); return result;
        }
        Class<?> c = (Class<?>)type;
        Checks.require(value != null, "Unexpected null");
        if (c == String.class) { Checks.require(value instanceof String, "Expected string"); return value; }
        if (c == Id.class) return new Id((String)convert(value, String.class));
        if (c == Revision.class) return new Revision((String)convert(value, String.class));
        if (c == Digest.class) return new Digest((String)convert(value, String.class));
        if (c == SchemaVersion.class) return new SchemaVersion((String)convert(value, String.class));
        if (c == UUID.class) {
            String s = (String)convert(value, String.class); UUID uuid = UUID.fromString(s);
            Checks.require(uuid.toString().equals(s), "Noncanonical UUID"); return uuid;
        }
        if (c == Instant.class) { String s = (String)convert(value, String.class); Instant instant = Instant.parse(s); Checks.require(instant.toString().equals(s), "Noncanonical UTC instant"); return instant; }
        if (c == boolean.class || c == Boolean.class) { Checks.require(value instanceof Boolean, "Expected boolean"); return value; }
        if (c == long.class || c == Long.class || c == int.class || c == Integer.class || c == double.class) {
            Checks.require(value instanceof NumericToken, "Expected number"); var token = (NumericToken)value; var number = token.decimal();
            if (c == double.class) { double d = Double.parseDouble(token.literal()); Checks.require(Double.isFinite(d), "Nonfinite number"); Checks.require(d != 0 || number.signum() == 0, "Double underflow"); return d; }
            if (c == int.class || c == Integer.class) return number.intValueExact();
            return number.longValueExact();
        }
        if (c.isEnum()) {
            Checks.require(c.getEnclosingClass() == Vocabulary.class, "Unregistered enum");
            return Enum.valueOf((Class)c, (String)convert(value, String.class));
        }
        if (c == TypedValue.Scalar.class) {
            Checks.require(value instanceof Map<?,?>, "Expected scalar object"); var m = (Map<?,?>)value;
            Checks.require(m.keySet().equals(Set.of("kind", "value")), "Unknown scalar fields");
            String kind = (String)convert(m.get("kind"), String.class);
            return switch (kind) {
                case "boolean" -> new TypedValue.Bool((boolean)convert(m.get("value"), boolean.class));
                case "integer" -> new TypedValue.Number((long)convert(m.get("value"), long.class));
                case "text" -> new TypedValue.Text((String)convert(m.get("value"), String.class));
                case "id" -> new TypedValue.Identifier((Id)convert(m.get("value"), Id.class));
                case "audience" -> new TypedValue.Scope((AudienceScope)convert(m.get("value"), AudienceScope.class));
                case "names" -> {
                    Checks.require(m.get("value") instanceof List<?>, "Expected names"); var names = new ArrayList<String>();
                    for (var item : (List<?>)m.get("value")) names.add((String)convert(item, String.class));
                    yield new TypedValue.Names(names);
                }
                case "ids" -> {
                    Checks.require(m.get("value") instanceof List<?>, "Expected IDs"); var ids = new ArrayList<Id>();
                    for (var item : (List<?>)m.get("value")) ids.add((Id)convert(item, Id.class));
                    yield new TypedValue.Identifiers(ids);
                }
                default -> throw new IllegalArgumentException("Unknown scalar kind");
            };
        }
        if (c == Vec3.class || c == BlockPos.class) {
            Checks.require(value instanceof List<?> && ((List<?>)value).size() == 3, "Expected coordinate triple"); var list = (List<?>)value;
            if (c == Vec3.class) return new Vec3((double)convert(list.get(0), double.class), (double)convert(list.get(1), double.class), (double)convert(list.get(2), double.class));
            return new BlockPos((int)convert(list.get(0), int.class), (int)convert(list.get(1), int.class), (int)convert(list.get(2), int.class));
        }
        if (c == SemanticRegionId.class) return new SemanticRegionId((Id)convert(value, Id.class));
        Checks.require(registered(c) && c.isRecord() && value instanceof Map<?,?>, "Unregistered record/type");
        var map = (Map<?,?>)value; var components = c.getRecordComponents();
        Checks.require(map.size() == components.length, "Unknown or missing required fields");
        Object[] values = new Object[components.length]; Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var f = components[i]; types[i] = f.getType();
            Checks.require(map.containsKey(f.getName()), "Missing field " + f.getName());
            Object field = map.get(f.getName());
            if (field == null) Checks.require(f.isAnnotationPresent(Nullable.class), "Null required field " + f.getName());
            if (c == StoredRow.class && f.getName().equals("canonical_payload")) {
                Checks.require(field instanceof PayloadText, "Expected row payload string");
                values[i] = ((PayloadText)field).value();
            } else values[i] = field == null ? null : convert(field, f.getGenericType());
        }
        try { return c.getDeclaredConstructor(types).newInstance(values); }
        catch (InvocationTargetException e) { throw new IllegalArgumentException("Invalid " + c.getSimpleName(), e.getCause()); }
    }
    private static Object tree(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof Id i) return i.value(); if (value instanceof Revision r) return r.value();
        if (value instanceof Digest d) return d.value(); if (value instanceof SchemaVersion s) return s.value();
        if (value instanceof SemanticRegionId s) return s.value().value();
        if (value instanceof UUID || value instanceof Instant || value instanceof Enum<?>) return value.toString();
        if (value instanceof Vec3 v) return List.of(v.x(), v.y(), v.z());
        if (value instanceof BlockPos v) return List.of(v.x(), v.y(), v.z());
        if (value instanceof TypedValue.Scalar scalar) {
            String kind; Object v;
            switch (scalar) {
                case TypedValue.Bool b -> { kind = "boolean"; v = b.value(); }
                case TypedValue.Number n -> { kind = "integer"; v = n.value(); }
                case TypedValue.Text t -> { kind = "text"; v = t.value(); }
                case TypedValue.Identifier i -> { kind = "id"; v = i.value(); }
                case TypedValue.Identifiers i -> { kind = "ids"; v = i.value(); }
                case TypedValue.Names n -> { kind = "names"; v = n.value(); }
                case TypedValue.Scope s -> { kind = "audience"; v = s.value(); }
            }
            return Map.of("kind", kind, "value", tree(v));
        }
        if (value instanceof Map<?,?> map) {
            Checks.size(map.size()); var result = new TreeMap<String,Object>();
            map.forEach((k,v) -> { Object key = tree(k); Checks.require(key instanceof String, "Map keys must be scalar strings"); result.put((String)key, tree(v)); });
            return result;
        }
        if (value instanceof Collection<?> collection) {
            Checks.size(collection.size()); var list = collection.stream().map(StrictCodec::tree).toList();
            if (value instanceof Set<?>) return list.stream().sorted(Comparator.comparing(StrictCodec::json)).toList();
            return list;
        }
        Checks.require(registered(value.getClass()) && value.getClass().isRecord(), "Unregistered record");
        var fields = new TreeMap<String,Object>();
        try {
            for (var f : value.getClass().getRecordComponents()) {
                Object field = f.getAccessor().invoke(value);
                fields.put(f.getName(), value instanceof StoredRow && f.getName().equals("canonical_payload")
                    ? new PayloadText((String)field) : tree(field));
            }
        }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
        return fields;
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof PayloadText) {
            String s = value instanceof PayloadText payload ? payload.value() : (String)value;
            if (value instanceof PayloadText) Checks.rowPayload(s); else Checks.text(s); var b = new StringBuilder("\"");
            for (char c : s.toCharArray()) switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
            return b.append('"').toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number n) {
            if ((n instanceof Double || n instanceof Float) && n.doubleValue() == 0 && Double.doubleToRawLongBits(n.doubleValue()) < 0) return "-0.0";
            var d = new BigDecimal(n.toString()).stripTrailingZeros();
            Checks.require(Math.abs((long)d.scale()) <= Limits.MAX_NUMBER_SCALE, "Numeric exponent cap");
            long plainLength = (d.signum() < 0 ? 1L : 0L) + (d.scale() <= 0 ? (long)d.precision() - d.scale()
                : Math.max((long)d.precision() + 1, (long)d.scale() + 2));
            Checks.require(plainLength <= Limits.MAX_NUMBER_CHARS, "Number length cap");
            return d.toPlainString();
        }
        if (value instanceof List<?> l) return "[" + String.join(",", l.stream().map(StrictCodec::json).toList()) + "]";
        if (value instanceof Map<?,?> m) return "{" + String.join(",", m.entrySet().stream().sorted(Comparator.comparing(e -> (String)e.getKey())).map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static final class Parser {
        private final String input; private final boolean catalogue; private int at; private int nodes;
        Parser(String input, boolean catalogue) { this.input = input; this.catalogue = catalogue; }
        /** Complete catalogue transfer uses independently bounded top-level records.
         * Whole UTF-8 byte, collection, string and depth ceilings stay unchanged.
         * Every record retains the ordinary complexity ceiling. Only the complete
         * closed CatalogSnapshot is converted/published; no partial ACK is created. */
        Object catalogueArray(int depth) {
            Checks.require(depth <= Limits.MAX_DEPTH, "JSON complexity cap"); expect('[');
            var list = new ArrayList<Object>(); if (take(']')) return list;
            do {
                Checks.require(list.size() < Limits.MAX_ITEMS, "Array cap");
                nodes = 0; list.add(read(depth + 1));
            } while (take(',')); expect(']'); return list;
        }
        Object parse() { Object value = read(0); whitespace(); Checks.require(at == input.length(), "Trailing JSON"); return value; }
        void whitespace() { while (at < input.length() && " \n\r\t".indexOf(input.charAt(at)) >= 0) at++; }
        boolean take(char c) { whitespace(); if (at < input.length() && input.charAt(at) == c) { at++; return true; } return false; }
        void expect(char c) { Checks.require(take(c), "Expected " + c); }
        Object read(int depth) {
            Checks.require(depth <= Limits.MAX_DEPTH && ++nodes <= Limits.MAX_ITEMS * 16, "JSON complexity cap");
            whitespace(); Checks.require(at < input.length(), "Truncated JSON"); char c = input.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                var map = new LinkedHashMap<String,Object>(); if (take('}')) return map;
                do {
                    Checks.require(map.size() < Limits.MAX_ITEMS, "Object cap"); String key = string(); expect(':');
                    Checks.require(!map.containsKey(key), "Duplicate JSON key");
                    Object value = catalogue && depth == 0 && Set.of("entries","regions","validation_evidence").contains(key)
                        ? catalogueArray(depth + 1) : key.equals("canonical_payload")
                        ? new PayloadText(string(Limits.MAX_ROW_PAYLOAD_BYTES)) : read(depth + 1);
                    map.put(key,value);
                } while (take(',')); expect('}'); return map;
            }
            if (take('[')) {
                var list = new ArrayList<Object>(); if (take(']')) return list;
                do { Checks.require(list.size() < Limits.MAX_ITEMS, "Array cap"); list.add(read(depth + 1)); } while (take(',')); expect(']'); return list;
            }
            for (String literal : List.of("true", "false", "null")) if (input.startsWith(literal, at)) {
                at += literal.length(); return literal.equals("null") ? null : literal.equals("true");
            }
            int start = at;
            while (at < input.length() && "0123456789-+.eE".indexOf(input.charAt(at)) >= 0) { at++; Checks.require(at-start <= Limits.MAX_NUMBER_CHARS, "Number length cap"); }
            String number = input.substring(start, at);
            Checks.require(number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), "Invalid JSON number");
            BigDecimal result = new BigDecimal(number); Checks.require(Math.abs((long)result.scale()) <= Limits.MAX_NUMBER_SCALE, "Numeric exponent cap"); return new NumericToken(number, result);
        }
        String string() { return string(Limits.MAX_STRING); }
        String string(int maxChars) {
            expect('"'); var b = new StringBuilder(); boolean ended = false;
            while (at < input.length()) {
                char c = input.charAt(at++); if (c == '"') { ended = true; break; }
                Checks.require(c >= 32, "Unescaped control character");
                if (c == '\\') {
                    Checks.require(at < input.length(), "Truncated escape"); c = input.charAt(at++);
                    c = switch (c) {
                        case '"', '\\', '/' -> c; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> { Checks.require(at + 4 <= input.length(), "Truncated Unicode"); String hex = input.substring(at, at+4); Checks.require(hex.matches("[0-9a-fA-F]{4}"), "Invalid Unicode escape"); at += 4; yield (char)Integer.parseInt(hex,16); }
                        default -> throw new IllegalArgumentException("Unknown escape");
                    };
                }
                Checks.require(b.length() < maxChars, "String cap"); b.append(c);
            }
            Checks.require(ended, "Unterminated string"); String s = b.toString(); Checks.text(s, maxChars); return s;
        }
    }
}
