package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Private storage envelopes; domain values always use the frozen codecs. */
final class StoreJson {
    private StoreJson() {}
    static JsonValue.Text text(Object value) { return new JsonValue.Text(value.toString()); }
    static JsonValue.ObjectValue object(Map<String, JsonValue> fields) { return new JsonValue.ObjectValue(fields); }
    static Map<String, JsonValue> fields(byte[] bytes) { return ((JsonValue.ObjectValue) CanonicalJsonV1.decode(bytes)).values(); }
    static String string(Map<String, JsonValue> fields, String key) { return ((JsonValue.Text) fields.get(key)).value(); }
    static long number(Map<String, JsonValue> fields, String key) { return Long.parseLong(string(fields, key)); }
    static <T> JsonValue model(Class<T> type, T value) { return CanonicalJsonV1.decode(new ModelCodec<>(type).encode(value)); }
    static <T> T model(Class<T> type, JsonValue value) { return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value)); }
    static byte[] encode(Map<String, JsonValue> fields) { return CanonicalJsonV1.encode(object(fields)); }
    static void keys(Map<String, JsonValue> fields, String... keys) {
        if (!fields.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("unknown storage fields");
    }
}
