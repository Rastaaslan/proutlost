package fr.proutlost.director.catalog;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;
/** Closed regional catalogue contract; actual native effects are qualified separately. */
public final class RegionalWeatherCatalogTests {
 private static final PrimitiveId WEATHER=new PrimitiveId("proutlost:regional_weather_lease");
 private static final ResourceId RAW=new ResourceId("proutlost_director:weathercore_regional_witness_v2"),RECIPE=CatalogFixtures.id("regional_test_recipe"),CODEC=CatalogFixtures.id("codec_regional_weather_lease"),FACT=CatalogFixtures.id("regional_test_completed");
 private static final CapabilityId CAP=new CapabilityId("weather.regional_scope_v1");private static int assertions;
 private static void check(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
 private static JsonValue.Text t(String v){return new JsonValue.Text(v);}private static JsonValue.Number n(long v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 private static JsonValue change(JsonValue root,String pointer,JsonValue value){
  if(pointer.isEmpty())return value;var path=pointer.substring(1).split("/",2);var tail=path.length==1?"":"/"+path[1];
  if(root instanceof JsonValue.ObjectValue o){var v=new TreeMap<>(o.values());v.put(path[0],change(v.get(path[0]),tail,value));return obj(v);}
  var v=new ArrayList<>(array(root));int at=Integer.parseInt(path[0]);v.set(at,change(v.get(at),tail,value));return new JsonValue.Array(v);
 }
 private static DefinitionLoader.Source alter(DefinitionLoader.Source s,String pointer,JsonValue value){return new DefinitionLoader.Source(s.path(),CanonicalJsonV1.encode(change(CanonicalJsonV1.decode(s.bytes()),pointer,value)));}
 private static PrimitiveRegistry registry(boolean correctDomain){
  var all=new ArrayList<>(CatalogFixtures.primitives().descriptors().values());
  all.add(new PrimitiveDescriptor(WEATHER,1,CODEC,Set.of(CAP),new BudgetVector(0,0,0,0,0,1,0,0,0,0),CatalogFixtures.id("footprint"),Set.of(RAW),
   Set.of(correctDomain?OwnershipDomain.WEATHER:OwnershipDomain.PERCEPTION),Set.of(),new DurationTicks(600),PausePolicy.CLEANUP,RecoveryMode.RECOVER,CatalogFixtures.id("cleanup"),Optional.of(WEATHER),Optional.empty(),ProviderMode.TEST_ONLY));
  return new PrimitiveRegistry(all,ProviderMode.TEST_ONLY);
 }
 private static ContentReferences refs(){
  var b=CatalogFixtures.references();var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);known.putAll(b.known());
  for(var e:Map.of(ContentReferences.Kind.FACT,Set.of(FACT),ContentReferences.Kind.CODEC,Set.of(RAW,CODEC)).entrySet()){var v=new HashSet<>(known.getOrDefault(e.getKey(),Set.of()));v.addAll(e.getValue());known.put(e.getKey(),v);}
  var mutations=new HashMap<>(b.mutations());mutations.put(RECIPE,new MutationProfile(RECIPE,1,Set.of(WEATHER),Set.of(RAW),CatalogFixtures.id("footprint"),new ReadSet(Map.of(),Map.of(),Map.of()),CatalogFixtures.id("mask"),CatalogFixtures.cost(1),0,List.of(),PlayerEditPolicy.YIELD,Optional.empty(),WEATHER,Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:NATIVE_REGIONAL_J04")));
  var caps=new HashSet<>(b.capabilities());caps.add(CAP);var claims=new HashSet<>(b.claims());claims.add("WEATHER:REGION");
  return new ContentReferences(ProviderMode.TEST_ONLY,known,mutations,b.warnings(),b.damages(),caps,claims);
 }
 private static DefinitionLoader loader(ConfigView c,boolean correct){return new DefinitionLoader(c,registry(correct),refs(),ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(256,65536,4194304,512,512));}
 private static void reject(ConfigView c,DefinitionLoader.Source good,DefinitionLoader.Source bad,String why){
  var l=loader(c,true);check(l.reload(List.of(good)).installed(),why+" baseline");var prior=l.current();var r=l.reload(List.of(bad));
  check(!r.installed()&&!r.diagnostics().isEmpty(),why+" rejects");check(l.current()==prior,why+" preserves previous catalogue");
 }
 public static void run(ConfigView config,List<DefinitionLoader.Source> baseline){
  assertions=0;var template=baseline.stream().filter(s->s.path().endsWith("/local_mist.json")).findFirst().orElseThrow();
  var good=new DefinitionLoader.Source("data/proutlost/director/events/regional_test.json",template.bytes());
  good=alter(good,"/id",t("proutlost:regional_test"));good=alter(good,"/target/scope",t("REGION"));
  good=alter(good,"/eligibility",obj(Map.of("type",t("players_present"),"minimum",n(1))));
  good=alter(good,"/safety/mutation_profile",t(RECIPE.value()));good=alter(good,"/safety/required_capabilities",new JsonValue.Array(List.of(t(CAP.value()))));
  good=alter(good,"/conflicts/claims",new JsonValue.Array(List.of(t("WEATHER:REGION"))));
  good=alter(good,"/budget/mutation_units",n(1));good=alter(good,"/timing/max_duration_ticks",n(1200));
  good=alter(good,"/completion/fact_type",t(FACT.value()));good=alter(good,"/completion/persistence_probability",n(0));good=alter(good,"/completion/durable_trace",JsonValue.Nil.INSTANCE);
  good=alter(good,"/steps/0/primitive",t(WEATHER.value()));good=alter(good,"/steps/0/args",obj(Map.of("dimension",t("minecraft:overworld"),"profile",t("proutlost_director:regional_mist"),"duration",n(600),"external_revision",n(1))));
  var loaded=loader(config,true).reload(List.of(good));check(loaded.installed(),"regional SAFE WEATHER ownership catalogue loads: "+loaded.diagnostics());
  var event=loaded.snapshot().events().values().iterator().next();var args=(RegionalWeatherLease)event.steps().getFirst().args();
  check(args.duration().value()==600&&args.externalRevision().value()==1,"real typed duration/revision");check(event.budget().mutationsInFlight()==1,"single native weather mutation budget");
  var codec=new ModelCodec<>(EventDefinition.class);check(codec.decode(codec.encode(event)).equals(event),"regional persistence roundtrip");
  check(!loader(config,false).reload(List.of(good)).installed(),"PERCEPTION descriptor cannot authorize WEATHER");
  reject(config,good,alter(good,"/steps/0/args/duration",n(0)),"zero TTL");
  reject(config,good,alter(good,"/steps/0/args/duration",n(601)),"primitive maximum TTL");
  reject(config,good,alter(good,"/timing/max_duration_ticks",n(600)),"event must outlive native deadline");
  reject(config,good,alter(good,"/steps/0/args/profile",t("proutlost_director:regional_weather_stop")),"authored content cannot invoke cleanup");
  reject(config,good,alter(good,"/steps/0/args/external_revision",n(-1)),"negative revision");
  reject(config,good,alter(good,"/budget/mutation_units",n(0)),"unbudgeted mutation");
  reject(config,good,alter(good,"/safety/mutation_profile",JsonValue.Nil.INSTANCE),"missing owned mutation profile");
  System.out.println("DIRECTOR_REGIONAL_WEATHER_CATALOG_PASS assertions="+assertions+" scope=CATALOG_ONLY_NOT_NATIVE");
 }
}
