package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.math.BigDecimal;
import java.util.*;

/** Real J02 JSON decode/registry validation for explicit OP4 TEST_ONLY rainfall and real wind. This is partial STORM groundwork.
 * The weather footprint is the entire dimension. No fabricated spatial safe exit,
 * bounded-lightning promise, natural risk admission or physical qualification.
 */
public final class DirectorLabStormCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final CatalogSnapshot snapshot;
 private final DefinitionId definitionId=new DefinitionId("proutlost_director:lab/global_rain_lease");
 private static ResourceId id(String p){return new ResourceId("proutlost:"+p);}
 private static ResourceId nativeCodec(String p){return new ResourceId("proutlost_director:"+p);}
 private static BudgetVector cost(long mutations){return new BudgetVector(.01,.01,.01,0,0,mutations,64,.01,32,0);}
 private static Map<String,JsonValue> object(JsonValue value){return new TreeMap<>(((JsonValue.ObjectValue)value).values());}
 private static JsonValue.ObjectValue json(Map<String,JsonValue> values){return new JsonValue.ObjectValue(values);}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static JsonValue.Number number(long n){return new JsonValue.Number(BigDecimal.valueOf(n));}
 public DirectorLabStormCatalog(ConfigView config,GlobalWeatherLease lease){
  Objects.requireNonNull(lease);
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING||!lease.dimension().value().equals("minecraft:overworld")
    ||!lease.weather().value().equals("minecraft:rain")||lease.duration().value()!=400)
   throw new IllegalArgumentException("explicit overworld rain400 LAB definition required; native thunder is blocked");
  var template=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals(
   "data/proutlost_director/director/events/candidate/controlled_landslide.json")).findFirst().orElseThrow();
  var root=object(CanonicalJsonV1.decode(template.bytes()));root.put("id",text(definitionId.value()));
  root.put("eligibility",json(Map.of("type",text("players_present"),"minimum",number(1))));
  root.put("families",new JsonValue.Array(List.of(text("AMBIENCE"),text("DISASTER"))));
  var target=object(root.get("target"));target.put("scope",text("GROUP"));
  target.put("roles",json(Map.of("witness",json(Map.of("min",number(1),"max",number(4),"overlap",new JsonValue.Bool(false))))));root.put("target",json(target));
  root.put("budget",json(Map.of("server_ms_per_tick",number(1),"global_units",number(1),"region_units",number(1),
    "entity_slots",number(0),"mutation_units",number(1),"client_units",number(1))));
  var timing=object(root.get("timing"));timing.put("max_duration_ticks",number(6000));root.put("timing",json(timing));
  var recovery=object(root.get("recovery"));recovery.put("deadline_ticks",number(6000));root.put("recovery",json(recovery));
  var recipe=id("test_global_storm_lease");var warningId=id("test_landslide_warning");
  var readable=new CapabilityId("warning.readable_v1");var global=new CapabilityId("weather.global_lease_v1");
  var audible=new CapabilityId("perception.sound_v1");var windSound=new ResourceId("minecraft:entity.breeze.idle_air");var caps=Set.of(readable,global,audible);
  var safety=object(root.get("safety"));safety.put("risk",text("SAFE"));safety.put("mutation_profile",text(recipe.value()));
  safety.put("warning_profile",text(warningId.value()));safety.put("required_capabilities",new JsonValue.Array(caps.stream().sorted(Comparator.comparing(CapabilityId::value)).map(c->(JsonValue)text(c.value())).toList()));
  root.put("safety",json(safety));
  var conflicts=object(root.get("conflicts"));conflicts.put("claims",new JsonValue.Array(List.of(text("WEATHER:DIMENSION"),text("PERCEPTION:WITNESS"))));root.put("conflicts",json(conflicts));
  var oldSteps=((JsonValue.Array)root.get("steps")).values();if(oldSteps.size()!=2)throw new IllegalStateException("WARN/impact template changed");
  var warnStep=object(oldSteps.getFirst());var warnArgs=object(warnStep.get("args"));warnArgs.put("minimum_lead_ticks",number(400));warnStep.put("args",json(warnArgs));
  var physical=object(oldSteps.get(1));physical.put("primitive",text("proutlost:global_weather_lease"));physical.put("delay_ticks",number(0));physical.put("after",new JsonValue.Array(List.of(text("wind"))));
  physical.put("args",json(Map.of("dimension",text(lease.dimension().value()),"weather",text(lease.weather().value()),
    "duration",number(lease.duration().value()),"external_revision",number(lease.externalRevision().value()))));
  var wind=json(Map.of("id",text("wind"),"after",new JsonValue.Array(List.of(text("warn"))),"delay_ticks",number(400),
    "primitive",text("proutlost:target_sound"),"on_failure",text("ABORT_SAFE"),"args",json(Map.of("role",text("witness"),"sound",text(windSound.value()),
      "volume",new JsonValue.Number(new BigDecimal("0.7")),"radius_blocks",number(16),"repeats",number(1)))));
  root.put("steps",new JsonValue.Array(List.of(json(warnStep),wind,json(physical))));
  var fact=new ResourceId("proutlost_director:lab_global_rain_observed");var completion=object(root.get("completion"));completion.put("fact_type",text(fact.value()));
  completion.put("durable_trace",JsonValue.Nil.INSTANCE);completion.put("persistence_probability",number(0));root.put("completion",json(completion));
  var warn=new PrimitiveId("proutlost:warning");var weather=new PrimitiveId("proutlost:global_weather_lease");
  var resolver=id("dimension");var cleanup=id("weather_restore");var raw=nativeCodec("weather_state");
  var codecs=Set.of(raw,nativeCodec("weather_clock_v1"),nativeCodec("weather_timed_state_v1"),nativeCodec("weather_restore_source_v1"),nativeCodec("weather_deadlines_v1"),nativeCodec("owner_v1"));
  var warning=new PrimitiveDescriptor(warn,1,id("codec_warning"),Set.of(readable),cost(0),id("footprint"),Set.of(),Set.of(OwnershipDomain.PERCEPTION),
    Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,id("cleanup"),Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var sound=new PrimitiveDescriptor(new PrimitiveId("proutlost:target_sound"),1,id("codec_target_sound"),Set.of(audible),cost(0),id("footprint"),Set.of(),
    Set.of(OwnershipDomain.PERCEPTION),Set.of(NoninvertibleEffect.SOUND_EXPOSURE),new DurationTicks(80),PausePolicy.CLEANUP,RecoveryMode.RECOVER,id("cleanup"),Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var nativeWeather=new PrimitiveDescriptor(weather,1,id("codec_global_weather_lease"),Set.of(global),cost(0),resolver,codecs,Set.of(OwnershipDomain.WEATHER),
    Set.of(),new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,cleanup,Optional.of(weather),Optional.empty(),ProviderMode.TEST_ONLY);
  var mutation=new MutationProfile(recipe,1,Set.of(weather),codecs,resolver,new ReadSet(Map.of(),Map.of(),Map.of()),id("global_dimension"),cost(1),0,
    List.of(),PlayerEditPolicy.YIELD,Optional.empty(),weather,Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:NATIVE_WEATHER_CYCLE_J04"));
  var warningProfile=new WarningProfile(warningId,Set.of(id("warning_sign")),id("all_dimension_players"),Set.of(id("visibility")),new DurationTicks(400),
    Set.of(id("warning_sign")),id("new_arrival"),id("warning_cancel"),id("proof"));
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.SOUND,Set.of(windSound));known.put(ContentReferences.Kind.FACT,Set.of(fact));known.put(ContentReferences.Kind.MODEL,Set.of(id("warning_sign"),id("warning_cancel")));
  var allCodecs=new HashSet<>(codecs);allCodecs.addAll(Set.of(id("codec_warning"),id("codec_global_weather_lease"),id("codec_target_sound"),id("proof")));known.put(ContentReferences.Kind.CODEC,allCodecs);
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,id("footprint"),id("global_dimension"),id("all_dimension_players"),id("visibility"),id("new_arrival")));
  known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup,id("cleanup")));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(recipe,mutation),Map.of(warningId,warningProfile),Map.of(),caps,Set.of("WEATHER:DIMENSION","PERCEPTION:WITNESS"));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(List.of(warning,sound,nativeWeather),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY,
    new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
  var source=new DefinitionLoader.Source("data/proutlost_director/director/events/lab/global_rain_lease.json",CanonicalJsonV1.encode(json(root)));
  var loaded=loader.reload(List.of(source));
  if(!loaded.installed()||!loaded.diagnostics().isEmpty()||loaded.snapshot().events().size()!=1||!loaded.snapshot().events().containsKey(definitionId))
   throw new IllegalStateException("J02 LAB rainfall invalid "+loaded.diagnostics());
  snapshot=loaded.snapshot();
 }
 public CatalogSnapshot snapshot(){return snapshot;}
 public DefinitionId definitionId(){return definitionId;}
}
