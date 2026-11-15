package fr.proutlost.director.config;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** An operator-supplied approval record; loading never promotes a test profile.
 * The trusted bootstrap pins the SHA-256 of the complete operator manifest.
 * This parser validates the attestation, not the truth of a human's approval. */
public final class ApprovedConfig {
 private ApprovedConfig() {}
 public record Profile(ResourceId id, String approvalId, String approvedBy,
                       String approvedAt, ConfigView config) {}
 public static Profile load(byte[] bytes) {
  if(bytes.length>CanonicalJsonV1.MAX_BYTES)throw new IllegalArgumentException("profile byte limit");
  var root=((JsonValue.ObjectValue)CanonicalJsonV1.decode(bytes)).values();
  Checks.require(root.keySet().equals(Set.of("schema_version","id","revision","status",
   "production_approved","approval_id","approved_by","approved_at","values")),"approved profile fields");
  Checks.require(((JsonValue.Number)root.get("schema_version")).value().intValueExact()==1,"profile version");
  Checks.require(((JsonValue.Text)root.get("status")).value().equals("APPROVED")
   &&((JsonValue.Bool)root.get("production_approved")).value(),"operator approval required");
  long revision=((JsonValue.Number)root.get("revision")).value().longValueExact();
  Checks.require(revision>0,"profile revision");
  String approval=text(root,"approval_id"),by=text(root,"approved_by"),at=text(root,"approved_at");
  java.time.Instant.parse(at);
  var raw=((JsonValue.ObjectValue)root.get("values")).values();
  Checks.require(raw.keySet().equals(ConfigRegistry.KEYS.keySet()),"complete approved key set");
  Map<String,JsonValue.Number> values=new HashMap<>();
  raw.forEach((name,value)->{var number=(JsonValue.Number)value;
   ConfigRegistry.KEYS.get(name).convert(number.value());values.put(name,number);});
  return new Profile(new ResourceId(text(root,"id")),approval,by,at,
   new ConfigView(new Revision(revision),BlobContent.hash(bytes),Calibration.APPROVED,values));
 }
 private static String text(Map<String,JsonValue> root,String key){
  String value=((JsonValue.Text)root.get(key)).value();
  Checks.require(!value.isBlank()&&value.length()<=256,"profile "+key);return value;
 }
}
