package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface JsonValue permits JsonValue.Text, JsonValue.Number, JsonValue.Bool, JsonValue.Nil, JsonValue.Array, JsonValue.ObjectValue {
 record Text(String value) implements JsonValue { public Text { text(value); } }
 record Number(java.math.BigDecimal value) implements JsonValue { public Number { Objects.requireNonNull(value); value=value.signum()==0?java.math.BigDecimal.ZERO:value.stripTrailingZeros(); require(value.precision()<=384 && Math.abs((long)value.scale())<=340,"normalized number bound"); } }
 record Bool(boolean value) implements JsonValue {}
 enum Nil implements JsonValue { INSTANCE }
 record Array(List<JsonValue> values) implements JsonValue { public Array { values=list(values); } }
 record ObjectValue(Map<String,JsonValue> values) implements JsonValue { public ObjectValue { values=map(values); values.keySet().forEach(Checks::text); } }
}
