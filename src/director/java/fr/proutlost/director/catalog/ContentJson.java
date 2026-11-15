package fr.proutlost.director.catalog;

import fr.proutlost.director.api.model.JsonValue;
import java.math.BigDecimal;
import java.util.*;

final class ContentJson {
    private ContentJson() {}
    static Map<String, JsonValue> object(JsonValue v) { return ((JsonValue.ObjectValue) v).values(); }
    static List<JsonValue> array(JsonValue v) { return ((JsonValue.Array) v).values(); }
    static String text(JsonValue v) { return ((JsonValue.Text) v).value(); }
    static BigDecimal number(JsonValue v) { return ((JsonValue.Number) v).value(); }
    static String pointer(String base, String key) { return base + "/" + key.replace("~", "~0").replace("/", "~1"); }
    static JsonValue.ObjectValue obj(Map<String, JsonValue> v) { return new JsonValue.ObjectValue(v); }
}
