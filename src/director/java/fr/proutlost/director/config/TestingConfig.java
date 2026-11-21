package fr.proutlost.director.config;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import java.security.*;
import java.util.*;
/** Explicit calibration profile; never production approval. */
public final class TestingConfig {
 public static final String SOURCE_SHA256="a0ba94dfec141788b6f3768a585fec5208d9270e10fc0202139b4dae228cd81d";
 private final Map<String,JsonValue.Number> values;
 private TestingConfig(Map<String,JsonValue.Number> values){this.values=Map.copyOf(values);}
 public static TestingConfig load(byte[] bytes) {
  try {Checks.require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(SOURCE_SHA256),"calibration provenance mismatch");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
  var root=((JsonValue.ObjectValue)CanonicalJsonV1.decode(bytes)).values();
  Checks.require(root.keySet().equals(Set.of("schema_version","status","production_approved","notes","values")),"profile fields");
  Checks.require(((JsonValue.Number)root.get("schema_version")).value().intValueExact()==1,"version");
  Checks.require(((JsonValue.Text)root.get("status")).value().equals("DEFAULT_FOR_TESTING")&&!((JsonValue.Bool)root.get("production_approved")).value(),"test profile");
  var raw=((JsonValue.ObjectValue)root.get("values")).values();Checks.require(raw.keySet().equals(ConfigRegistry.KEYS.keySet()),"calibration key set");
  Map<String,JsonValue.Number> values=new HashMap<>();
  raw.forEach((name,v)->{var n=(JsonValue.Number)v;ConfigKey<?> k=ConfigRegistry.KEYS.get(name);k.convert(n.value());Checks.require(k.testingDefault().compareTo(n.value())==0,"testing calibration mismatch");values.put(name,n);});
  return new TestingConfig(values);
 }
 public <T extends Number> T get(ConfigKey<T> key){Checks.require(ConfigRegistry.KEYS.get(key.name())==key,"unregistered key");return key.convert(values.get(key.name()).value());}
 public ConfigView view(){return new ConfigView(new Revision(1),new Fingerprint(SOURCE_SHA256),Calibration.DEFAULT_FOR_TESTING,values);}
}
