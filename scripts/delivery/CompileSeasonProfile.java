import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.catalog.*;
public class CompileSeasonProfile {
 static JsonValue obj(Map<String,JsonValue> m){return new JsonValue.ObjectValue(m);}
 static JsonValue txt(String s){return new JsonValue.Text(s);}
 static JsonValue num(long n){return new JsonValue.Number(java.math.BigDecimal.valueOf(n));}
 static Map<String,JsonValue> map(JsonValue v){return ((JsonValue.ObjectValue)v).values();}
 static JsonValue read(Path p)throws Exception{return CanonicalJsonV1.decode(Files.readAllBytes(p));}
 static JsonValue arr(Collection<? extends JsonValue> v){return new JsonValue.Array(new ArrayList<>(v));}
 static <T>JsonValue model(Class<T> t,T v){return CanonicalJsonV1.decode(new ModelCodec<T>(t).encode(v));}
 static JsonValue pin(Path root,String name)throws Exception{return obj(Map.of("path",txt(name),"sha256",txt(BlobContent.hash(Files.readAllBytes(root.resolve(name))).value())));}
 static void write(Path p,JsonValue v)throws Exception{Files.createDirectories(p.getParent());Files.write(p,CanonicalJsonV1.encode(v));}
 static JsonValue resolve(JsonValue v,ConfigView c){
  if(v instanceof JsonValue.Text t&&t.value().startsWith("@test:"))return Objects.requireNonNull(c.values().get(t.value().substring(6)));
  if(v instanceof JsonValue.ObjectValue o){var m=new TreeMap<String,JsonValue>();o.values().forEach((k,x)->m.put(k,k.equals("calibration")?txt("APPROVED"):resolve(x,c)));return obj(m);}
  if(v instanceof JsonValue.Array a)return arr(a.values().stream().map(x->resolve(x,c)).toList());
  return v;
 }
 public static void main(String[] a)throws Exception{
  Path source=Path.of(a[0]),input=Path.of(a[1]),decision=Path.of(a[2]),out=Path.of(a[3]);
  var author=map(read(decision));String approval=((JsonValue.Text)author.get("id")).value(),by=((JsonValue.Text)author.get("approved_by")).value(),at=((JsonValue.Text)author.get("recorded_utc")).value();
  if(!approval.equals("PLS2-S2-AUTHOR-20261122-CONTRA-INITIAL")||!by.equals("Damien Lagorce"))throw new IllegalArgumentException("Explicit season author decision required");
  Files.createDirectories(out);
  var config=DirectorLabObservationProfile.load();var values=new TreeMap<String,JsonValue>();values.putAll(config.values());
  if(a.length>4){
   var overrides=map(read(Path.of(a[4])));
   for(var item:overrides.entrySet()){
    if(!values.containsKey(item.getKey())||!(item.getValue() instanceof JsonValue.Number))throw new IllegalArgumentException("Known numeric calibration key required: "+item.getKey());
    values.put(item.getKey(),item.getValue());
   }
   var numeric=new TreeMap<String,JsonValue.Number>();values.forEach((k,v)->numeric.put(k,(JsonValue.Number)v));
   config=new ConfigView(config.revision(),BlobContent.hash(CanonicalJsonV1.encode(obj(values))),Calibration.DEFAULT_FOR_TESTING,numeric);
  }
  final var resolvedConfig=config;
  String id="proutlost:saison2_initial";
  write(out.resolve("profiles/initial.json"),obj(Map.of("schema_version",num(1),"id",txt(id),"revision",num(1),"status",txt("APPROVED"),"production_approved",new JsonValue.Bool(true),"approval_id",txt(approval),"approved_by",txt(by),"approved_at",txt(at),"values",obj(values))));
  var geometry=map(read(input.resolve("director-location-imports/qualification-evidence/f05-functional-contract-v1.json")));
  var path=(JsonValue.Array)map(geometry.get("geometry")).get("escapeRoute");
  var exits=new ArrayList<RouteSegment>();var dimension=new DimensionId("minecraft:overworld");
  var points=new ArrayList<BlockPosition>();
  for(var row:path.values()){var p=(JsonValue.Array)row;points.add(new BlockPosition(dimension,((JsonValue.Number)p.values().get(0)).value().intValueExact(),((JsonValue.Number)p.values().get(1)).value().intValueExact(),((JsonValue.Number)p.values().get(2)).value().intValueExact()));}
  for(int i=1;i<points.size();i++)exits.add(new RouteSegment(points.get(i-1),points.get(i),.3));
  // Read only the pinned static data inventory. No TEST_ONLY provider or token is composed into production.
  var inventory=new DirectorNativeCatalogueCatalog(config,exits);var descriptors=new ArrayList<JsonValue>();
  for(var d:inventory.primitiveRegistry().descriptors().values()){
   var m=new TreeMap<>(map(model(PrimitiveDescriptor.class,d)));m.put("mode",txt("PRODUCTION"));descriptors.add(obj(m));
  }
  write(out.resolve("primitives.json"),arr(descriptors));var refs=inventory.references();var known=new TreeMap<String,JsonValue>();
  refs.known().forEach((k,v)->known.put(k.name(),arr(v.stream().map(x->txt(x.value())).toList())));
  var mutations=new ArrayList<JsonValue>();
  for(var m:refs.mutations().values()){var fields=new TreeMap<>(map(model(MutationProfile.class,m)));fields.put("required_game_tests",arr(List.of(txt("P008_R28_THREE_SELECTED_EVENTS_NATIVE_PHYSICAL_CLOSED"))));mutations.add(obj(fields));}
  write(out.resolve("references.json"),obj(Map.of("mode",txt("PRODUCTION"),"known",obj(known),"mutations",arr(mutations),"warnings",arr(refs.warnings().values().stream().map(v->model(WarningProfile.class,v)).toList()),"damages",arr(refs.damages().values().stream().map(v->model(DamageProfile.class,v)).toList()),"capabilities",arr(refs.capabilities().stream().map(v->txt(v.value())).toList()),"claims",arr(refs.claims().stream().map(CompileSeasonProfile::txt).toList()))));
  var docs=new ArrayList<JsonValue>();Path resources=source.resolve("src/director/resources/data/proutlost_director/director");
  for(String kind:List.of("events","chains")){
   try(var files=Files.list(resources.resolve(kind+"/candidate"))){for(var f:files.sorted().toList()){
    String n="data/proutlost_director/director/"+kind+"/candidate/"+f.getFileName();write(out.resolve(n),resolve(read(f),resolvedConfig));docs.add(pin(out,n));
   }}
  }
  write(out.resolve("manifest.json"),obj(Map.ofEntries(Map.entry("schema_version",num(1)),Map.entry("status",txt("APPROVED")),Map.entry("production_approved",new JsonValue.Bool(true)),Map.entry("approval_id",txt(approval)),Map.entry("approved_by",txt(by)),Map.entry("approved_at",txt(at)),Map.entry("default_profile",txt(id)),Map.entry("profiles",arr(List.of(pin(out,"profiles/initial.json")))),Map.entry("primitives",pin(out,"primitives.json")),Map.entry("references",pin(out,"references.json")),Map.entry("documents",arr(docs)))));
  var pin=BlobContent.hash(Files.readAllBytes(out.resolve("manifest.json")));var loaded=DirectorOperatorBundle.load(out,pin);
  var definitions=new TreeMap<String,JsonValue>();loaded.catalog().events().forEach((key,v)->definitions.put(key.value(),model(DefinitionRef.class,new DefinitionRef(v.id(),v.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(v))))));
  write(out.resolve("definition_refs.json"),obj(definitions));Files.copy(decision,out.resolve("author_decision.json"),StandardCopyOption.REPLACE_EXISTING);
  System.out.println("REAL_PRODUCTION_OPERATOR_BUNDLE_LOADED events="+loaded.catalog().events().size()+" chains="+loaded.catalog().chains().size()+" primitives="+loaded.catalog().primitives().size()+" profile="+loaded.configuration().calibration()+" manifest="+pin.value());
 }
}

