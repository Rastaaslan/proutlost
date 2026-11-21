package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.JsonValue;
import java.util.*;
/** Canonical model payload in a durable fact. Legacy base64 text stays readable.
 * Structural JSON keeps the existing per-string, depth, item and total-byte bounds;
 * it does not enlarge any ingress envelope or invent a second store. */
final class NativeDossierJson {
 private NativeDossierJson(){}
 static JsonValue encode(byte[] canonical){
  var value=CanonicalJsonV1.decode(canonical);
  if(!(value instanceof JsonValue.ObjectValue))throw new IllegalArgumentException("dossier model must be an object");
  if(!Arrays.equals(canonical,CanonicalJsonV1.encode(value)))throw new IllegalArgumentException("dossier model is not canonical");
  return value;
 }
 static byte[] decode(JsonValue value){
  Objects.requireNonNull(value);
  if(value instanceof JsonValue.Text legacy)return Base64.getDecoder().decode(legacy.value());
  if(value instanceof JsonValue.ObjectValue)return CanonicalJsonV1.encode(value);
  throw new IllegalArgumentException("unsupported dossier model encoding");
 }
}
