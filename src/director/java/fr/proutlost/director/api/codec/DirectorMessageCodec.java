package fr.proutlost.director.api.codec;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Exact director-message.schema.json wire shape; compiled-model tagged unions stay internal. */
public final class DirectorMessageCodec {
 private DirectorMessageCodec() {}
 private static final ModelCodec<DirectorEnvelope> MODEL=new ModelCodec<>(DirectorEnvelope.class);
 private static Map<String,JsonValue> object(JsonValue v){if(v instanceof JsonValue.ObjectValue o)return o.values();throw new IllegalArgumentException("object required");}
 private static String text(JsonValue v){if(v instanceof JsonValue.Text t)return t.value();throw new IllegalArgumentException("text required");}
 private static String tag(PayloadType type){return switch(type){case DirectorFact -> "fact_payload";case DirectorEvent -> "event_payload";case DirectorObservation -> "observation_payload";case DirectorRequest -> "request_message";case DirectorWorldState -> "world_state_payload";};}
 public static byte[] encode(DirectorEnvelope envelope){Map<String,JsonValue> root=new HashMap<>(object(CanonicalJsonV1.decode(MODEL.encode(envelope))));var tagged=object(root.get("payload"));JsonValue payload=tagged.get("value");if(envelope.payloadType()==PayloadType.DirectorRequest)payload=request(payload,true);root.put("payload",payload);return CanonicalJsonV1.encode(new JsonValue.ObjectValue(root));}
 public static DirectorEnvelope decode(byte[] bytes){Map<String,JsonValue> root=new HashMap<>(object(CanonicalJsonV1.decode(bytes)));PayloadType type=PayloadType.valueOf(text(root.get("payload_type")));JsonValue payload=root.get("payload");if(type==PayloadType.DirectorRequest)payload=request(payload,false);root.put("payload",new JsonValue.ObjectValue(Map.of("type",new JsonValue.Text(tag(type)),"value",payload)));return MODEL.decode(CanonicalJsonV1.encode(new JsonValue.ObjectValue(root)));}
 private static JsonValue request(JsonValue value,boolean encode){
  Map<String,JsonValue> fields;String kind;
  if(encode){var union=object(value);String type=text(union.get("type"));kind=switch(type){case "director_preference" -> "PREFER";case "director_requirement" -> "REQUIRE";case "cancel_payload" -> "CANCEL";default -> throw new IllegalArgumentException("request variant");};fields=new HashMap<>(object(union.get("value")));fields.put("kind",new JsonValue.Text(kind));}
  else {fields=new HashMap<>(object(value));kind=text(fields.remove("kind"));}
  if(fields.containsKey("selector"))fields.put("selector",selector(fields.get("selector"),encode));
  if(fields.containsKey("required_fact_predicate"))fields.put("required_fact_predicate",predicate(fields.get("required_fact_predicate"),encode));
  if(encode)return new JsonValue.ObjectValue(fields);
  String type=switch(kind){case "PREFER" -> "director_preference";case "REQUIRE" -> "director_requirement";case "CANCEL" -> "cancel_payload";default -> throw new IllegalArgumentException("request kind");};
  return new JsonValue.ObjectValue(Map.of("type",new JsonValue.Text(type),"value",new JsonValue.ObjectValue(fields)));
 }
 private static JsonValue selector(JsonValue value,boolean encode){var o=object(value);Map<String,JsonValue> fields;String type=text(o.get("type"));if(encode){Checks.require(o.keySet().equals(Set.of("type","value")),"selector fields");fields=new HashMap<>(object(o.get("value")));fields.put("type",new JsonValue.Text(switch(type){case "any_of_events" -> "ANY_OF";case "all_tags" -> "ALL_TAGS";default -> throw new IllegalArgumentException("selector");}));return new JsonValue.ObjectValue(fields);}fields=new HashMap<>(o);fields.remove("type");return new JsonValue.ObjectValue(Map.of("type",new JsonValue.Text(switch(type){case "ANY_OF" -> "any_of_events";case "ALL_TAGS" -> "all_tags";default -> throw new IllegalArgumentException("selector");}),"value",new JsonValue.ObjectValue(fields)));}
 private static JsonValue predicate(JsonValue value,boolean encode){var o=object(value);String type=text(o.get("type"));Map<String,JsonValue> f=encode?new HashMap<>(object(o.get("value"))):new HashMap<>(o);f.remove("type");
  String mapped=switch(type){case "all_facts" -> "all";case "any_facts" -> "any";case "not_fact" -> "not";case "all" -> "all_facts";case "any" -> "any_facts";case "not" -> "not_fact";case "fact_match" -> "fact_match";default -> throw new IllegalArgumentException("predicate");};
  if(f.containsKey("predicates")){var a=(JsonValue.Array)f.get("predicates");f.put("predicates",new JsonValue.Array(a.values().stream().map(v->predicate(v,encode)).toList()));}
  if(f.containsKey("predicate"))f.put("predicate",predicate(f.get("predicate"),encode));
  if(encode){f.put("type",new JsonValue.Text(mapped));return new JsonValue.ObjectValue(f);}return new JsonValue.ObjectValue(Map.of("type",new JsonValue.Text(mapped),"value",new JsonValue.ObjectValue(f)));
 }
}
