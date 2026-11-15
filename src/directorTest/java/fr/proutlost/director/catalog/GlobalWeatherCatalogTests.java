package fr.proutlost.director.catalog;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;

/** J02 content validation only: never claims native weather/J04/client qualification. */
public final class GlobalWeatherCatalogTests {
 private static final PrimitiveId WEATHER=new PrimitiveId("proutlost:global_weather_lease");
 private static final ResourceId RAW=new ResourceId("proutlost_director:weather_state"),RECIPE=CatalogFixtures.id("test_global_weather_lease"),
   CODEC=CatalogFixtures.id("codec_global_weather_lease"),FACT=CatalogFixtures.id("lab_storm_observed"),WIND=new ResourceId("minecraft:entity.breeze.idle_air");
 private static final CapabilityId CAP=new CapabilityId("weather.global_lease_v1");
 private static int assertions;
 private static void check(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
 private static JsonValue.Text t(Object v){return new JsonValue.Text(v.toString());}
 private static JsonValue.Number n(long v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 private static JsonValue change(JsonValue root,String pointer,JsonValue value){
  if(pointer.isEmpty())return value;var split=pointer.substring(1).split("/",2);var tail=split.length==1?"":"/"+split[1];
  if(root instanceof JsonValue.ObjectValue o){var copy=new TreeMap<>(o.values());copy.put(split[0],change(copy.get(split[0]),tail,value));return obj(copy);}
  var copy=new ArrayList<>(array(root));int at=Integer.parseInt(split[0]);copy.set(at,change(copy.get(at),tail,value));return new JsonValue.Array(copy);
 }
 private static DefinitionLoader.Source alter(DefinitionLoader.Source s,String pointer,JsonValue v){
  return new DefinitionLoader.Source(s.path(),CanonicalJsonV1.encode(change(CanonicalJsonV1.decode(s.bytes()),pointer,v)));
 }
 private static PrimitiveDescriptor descriptor(boolean weatherDomain,boolean damage){
  return new PrimitiveDescriptor(WEATHER,1,CODEC,Set.of(CAP),CatalogFixtures.cost(0),CatalogFixtures.id("dimension"),Set.of(RAW),
   Set.of(weatherDomain?OwnershipDomain.WEATHER:OwnershipDomain.PERCEPTION),damage?Set.of(NoninvertibleEffect.DAMAGE,NoninvertibleEffect.DEATH):Set.of(),
   new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,CatalogFixtures.id("cleanup"),Optional.of(WEATHER),Optional.empty(),ProviderMode.TEST_ONLY);
 }
 private static PrimitiveRegistry registry(boolean weatherDomain,boolean damage){
  var all=new ArrayList<>(CatalogFixtures.primitives().descriptors().values());all.add(descriptor(weatherDomain,damage));return new PrimitiveRegistry(all,ProviderMode.TEST_ONLY);
 }
 private static ContentReferences refs(){return refs(true);}
 private static ContentReferences refs(boolean damage){
  var b=CatalogFixtures.references();var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);known.putAll(b.known());
  for(var e:Map.of(ContentReferences.Kind.FACT,Set.of(FACT),ContentReferences.Kind.CODEC,Set.of(RAW,CODEC),
    ContentReferences.Kind.RESOLVER,Set.of(CatalogFixtures.id("dimension"),CatalogFixtures.id("global_dimension")),ContentReferences.Kind.SOUND,Set.of(WIND)).entrySet()){
   var all=new HashSet<>(known.getOrDefault(e.getKey(),Set.of()));all.addAll(e.getValue());known.put(e.getKey(),all);
  }
  var mutations=new HashMap<>(b.mutations());mutations.put(RECIPE,new MutationProfile(RECIPE,1,Set.of(WEATHER),Set.of(RAW),CatalogFixtures.id("dimension"),
   new ReadSet(Map.of(),Map.of(),Map.of()),CatalogFixtures.id("global_dimension"),CatalogFixtures.cost(1),0,List.of(),PlayerEditPolicy.YIELD,
   Optional.empty(),WEATHER,damage?Set.of(NoninvertibleEffect.DAMAGE,NoninvertibleEffect.DEATH):Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:NATIVE_WEATHER_CYCLE_J04")));
  var caps=new HashSet<>(b.capabilities());caps.add(CAP);var claims=new HashSet<>(b.claims());claims.add("WEATHER:DIMENSION");
  return new ContentReferences(ProviderMode.TEST_ONLY,known,mutations,b.warnings(),b.damages(),caps,claims);
 }
 private static DefinitionLoader loader(ConfigView c,PrimitiveRegistry r){return new DefinitionLoader(c,r,refs(),ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512));}
 private static DefinitionLoader.Source source(DefinitionLoader.Source template){
  var s=alter(new DefinitionLoader.Source("data/proutlost_director/director/events/lab/global_weather_catalog.json",template.bytes()),"/id",t("proutlost_director:lab/global_weather_catalog"));
  s=alter(s,"/eligibility",obj(Map.of("type",t("players_present"),"minimum",n(1))));
  s=alter(s,"/safety/mutation_profile",t(RECIPE.value()));
  s=alter(s,"/safety/required_capabilities",new JsonValue.Array(List.of(t("warning.readable_v1"),t(CAP.value()))));
  s=alter(s,"/conflicts/claims",new JsonValue.Array(List.of(t("WEATHER:DIMENSION"))));
  s=alter(s,"/budget",obj(Map.of("server_ms_per_tick",n(1),"global_units",n(1),"region_units",n(1),"entity_slots",n(0),"mutation_units",n(1),"client_units",n(1))));
  s=alter(s,"/timing/max_duration_ticks",n(6000));s=alter(s,"/recovery/deadline_ticks",n(6000));
  s=alter(s,"/steps/1/primitive",t(WEATHER.value()));s=alter(s,"/steps/1/delay_ticks",n(400));
  s=alter(s,"/steps/1/args",obj(Map.of("dimension",t("minecraft:overworld"),"weather",t("minecraft:thunder"),"duration",n(400),"external_revision",n(8589934609L))));
  s=alter(s,"/completion/fact_type",t(FACT.value()));s=alter(s,"/completion/durable_trace",JsonValue.Nil.INSTANCE);return alter(s,"/completion/persistence_probability",n(0));
 }
 private static void rejected(ConfigView config,DefinitionLoader.Source good,DefinitionLoader.Source bad,String why){rejected(config,good,bad,why,true);}
 private static void rejected(ConfigView config,DefinitionLoader.Source good,DefinitionLoader.Source bad,String why,boolean damage){
  var l=new DefinitionLoader(config,registry(true,damage),refs(damage),ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512));check(l.reload(List.of(good)).installed(),why+" baseline");var before=l.current();
  var actual=l.reload(List.of(bad));check(!actual.installed()&&!actual.diagnostics().isEmpty(),why+" rejected");
  check(l.current()==before&&actual.snapshot()==before,why+" populated snapshot retained");
  check(actual.diagnostics().stream().allMatch(d->d.code()==ReasonCode.CATALOG_INVALID),why+" typed diagnostics");
 }
 private static DefinitionLoader.Source withWind(DefinitionLoader.Source rain){
  var root=CanonicalJsonV1.decode(rain.bytes());var steps=array(object(root).get("steps"));
  var wind=obj(Map.of("id",t("wind"),"after",new JsonValue.Array(List.of(t("warn"))),"delay_ticks",n(400),
   "primitive",t("proutlost:target_sound"),"on_failure",t("ABORT_SAFE"),"args",obj(Map.of("role",t("witness"),"sound",t(WIND.value()),
    "volume",new JsonValue.Number(new BigDecimal("0.7")),"radius_blocks",n(16),"repeats",n(1)))));
  var weather=change(change(steps.get(1),"/after",new JsonValue.Array(List.of(t("wind")))),"/delay_ticks",n(0));
  root=change(root,"/steps",new JsonValue.Array(List.of(steps.getFirst(),wind,weather)));
  root=change(root,"/target/scope",t("GROUP"));root=change(root,"/target/roles/witness/min",n(1));root=change(root,"/target/roles/witness/max",n(4));
  root=change(root,"/safety/required_capabilities",new JsonValue.Array(List.of(t("warning.readable_v1"),t(CAP.value()),t("perception.sound_v1"))));
  return new DefinitionLoader.Source(rain.path(),CanonicalJsonV1.encode(root));
 }
 public static void run(ConfigView config,List<DefinitionLoader.Source> baseline){
  assertions=0;var template=baseline.stream().filter(s->s.path().endsWith("/controlled_landslide.json")).findFirst().orElseThrow();var good=source(template);
  var preserved=loader(config,registry(true,true)).reload(baseline);
  check(preserved.installed()&&preserved.snapshot().events().size()==9&&preserved.snapshot().chains().size()==2,"weather addition preserves original nine/two");
  var loaded=loader(config,registry(true,true)).reload(List.of(good));check(loaded.installed(),"global weather load "+loaded.diagnostics());
  var e=loaded.snapshot().events().values().iterator().next();var a=(GlobalWeatherLease)e.steps().get(1).args();
  check(a.dimension().value().equals("minecraft:overworld")&&a.weather().value().equals("minecraft:thunder")&&a.duration().value()==400&&a.externalRevision().value()==8589934609L,"actual long external revision and typed lease");
  var rain=alter(alter(good,"/steps/1/args/weather",t("minecraft:rain")),"/safety/risk",t("SAFE"));
  var rainLoaded=new DefinitionLoader(config,registry(true,false),refs(false),ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512)).reload(List.of(rain));
  check(rainLoaded.installed(),"non-thundering rainfall SAFE with explicit WARN "+rainLoaded.diagnostics());
  var windy=withWind(rain);var windyLoaded=new DefinitionLoader(config,registry(true,false),refs(false),ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512)).reload(List.of(windy));
  check(windyLoaded.installed(),"actual breeze-wind then native rain J02 "+windyLoaded.diagnostics());
  var windyEvent=windyLoaded.snapshot().events().values().iterator().next();var wind=(TargetSound)windyEvent.steps().get(1).args();
  check(wind.sound().equals(WIND)&&wind.volume()==.7&&wind.radiusBlocks()==16&&wind.repeats()==1&&wind.role().equals("witness"),"one real wind exposure has exact G0 args");
  check(windyEvent.target().scope()==TargetScope.GROUP&&windyEvent.target().roles().get("witness").max()==4,"one-to-four actual clients can bind witness");
  check(windyEvent.steps().get(1).after().equals(List.of("warn"))&&windyEvent.steps().get(1).delayTicks().value()==400
   &&windyEvent.steps().get(1).onFailure()==OnFailure.ABORT_SAFE&&windyEvent.steps().get(2).after().equals(List.of("wind"))&&windyEvent.steps().get(2).delayTicks().value()==0,"unavoidable WARN400 then wind then weather graph");
  rejected(config,windy,alter(windy,"/steps/1/after",new JsonValue.Array(List.of())),"rain wind cannot bypass WARN",false);
  rejected(config,windy,alter(windy,"/steps/1/delay_ticks",n(399)),"rain wind preserves full warning lead",false);
  rejected(config,windy,alter(windy,"/steps/0/on_failure",t("SKIP_SAFE")),"rain WARN cannot be skipped",false);
  rejected(config,windy,alter(windy,"/steps/1/args/sound",t("missing:sound")),"wind needs an actual registered native sound",false);

  var codec=new ModelCodec<>(EventDefinition.class);check(codec.decode(codec.encode(e)).equals(e),"G0 persistence roundtrip");
  check(e.budget().mutationsInFlight()==1,"one global lease is one mutation; no invented cell envelope");
  rejected(config,good,alter(good,"/steps/1/args/weather",t("missing:weather")),"unsupported native profile");
  rejected(config,good,alter(good,"/steps/1/args/weather",t("proutlost_director:restore")),"authored content cannot bypass inverse hydration");
  rejected(config,good,alter(good,"/steps/1/args/duration",n(0)),"zero duration");
  rejected(config,good,alter(good,"/steps/1/args/duration",n(6001)),"descriptor duration bound");
  rejected(config,good,alter(good,"/steps/1/args/external_revision",n(-1)),"negative external revision");
  rejected(config,good,alter(good,"/steps/1/args/dimension",t("invalid dimension")),"invalid dimension identity");
  rejected(config,good,alter(good,"/safety/risk",t("SAFE")),"thunder cannot bypass danger");
  rejected(config,good,alter(good,"/steps/1/after",new JsonValue.Array(List.of())),"WARN unavoidable");
  rejected(config,good,alter(good,"/steps/1/delay_ticks",n(399)),"full WARN lead");
  rejected(config,good,alter(good,"/steps/0/on_failure",t("SKIP_SAFE")),"WARN cannot be skipped");
  rejected(config,good,alter(good,"/budget/mutation_units",n(0)),"one lease underbudget");
  rejected(config,good,alter(good,"/timing/max_duration_ticks",n(800)),"event deadline includes lease duration");
  check(!loader(config,registry(false,true)).reload(List.of(good)).installed(),"WEATHER ownership domain required");
  check(!loader(config,registry(true,false)).reload(List.of(good)).installed(),"native thunder damage must be noninvertible");
  check(!loader(config,CatalogFixtures.primitives()).reload(List.of(good)).installed(),"argument codec alone is not a provider");
  System.out.println("PASS T-CAT global-weather assertions="+assertions+" J02_ONLY native gameplay unqualified");
 }
 private GlobalWeatherCatalogTests(){}
}
