package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.effects.ControlledFireWaveBinding;
import fr.proutlost.director.catalog.*;
import java.math.BigDecimal;
import java.util.*;

/** Separate manual FIRE LAB definition. It does not modify the nine packaged events. */
public final class DirectorLabFireDisasterCatalog implements DirectorBootstrap.TestOnlyBackend {
 public static final DefinitionId DEFINITION=new DefinitionId("proutlost_director:lab/controlled_fire");
 public static final ResourceId RECIPE=new ResourceId("proutlost:test_bounded_fire"),WARNING=new ResourceId("proutlost:test_fire_warning");
 public static final ResourceId DAMAGE=new ResourceId("proutlost:test_controlled_fire_damage"),COMPLETED=new ResourceId("proutlost_director:lab_fire_disaster_observed");
 private final CatalogSnapshot snapshot;private final DamageProfile damage;
 private static ResourceId id(String p){return new ResourceId("proutlost:"+p);}
 private static BudgetVector cost(long cells){return new BudgetVector(.01,.01,.01,0,0,cells,64,.01,32,0);}
 private static Map<String,JsonValue> object(JsonValue v){return new TreeMap<>(((JsonValue.ObjectValue)v).values());}
 private static JsonValue.ObjectValue json(Map<String,JsonValue> v){return new JsonValue.ObjectValue(v);}
 private static JsonValue.Text str(Object v){return new JsonValue.Text(v.toString());}
 private static JsonValue.Number number(long v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 public DirectorLabFireDisasterCatalog(ConfigView config,List<RouteSegment> safeExits,BlobRef mask){
  safeExits=List.copyOf(safeExits);
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING||safeExits.isEmpty()||!mask.codecId().equals(ControlledFireWaveBinding.MASK_CODEC)||mask.codecVersion()!=1||mask.bytes()<1)
   throw new IllegalArgumentException("real fire mask/surveyed exit and LAB calibration required");
  var original=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals(
   "data/proutlost_director/director/events/candidate/controlled_landslide.json")).findFirst().orElseThrow();
  var root=object(CanonicalJsonV1.decode(original.bytes()));root.put("id",str(DEFINITION.value()));
  root.put("eligibility",json(Map.of("type",str("players_present"),"minimum",number(1))));
  var target=object(root.get("target"));target.put("scope",str("SOLO"));target.put("roles",json(Map.of("witness",json(Map.of("min",number(1),"max",number(1),"overlap",new JsonValue.Bool(false))))));root.put("target",json(target));
  var budget=object(root.get("budget"));budget.put("mutation_units",number(2));root.put("budget",json(budget));
  var safety=object(root.get("safety"));safety.put("mutation_profile",str(RECIPE.value()));safety.put("warning_profile",str(WARNING.value()));
  safety.put("required_capabilities",new JsonValue.Array(List.of("mutation.controlled_fire_v1","danger.controlled_fire_v1","warning.readable_v1").stream().map(v->(JsonValue)str(v)).toList()));root.put("safety",json(safety));
  var steps=new ArrayList<JsonValue>();
  for(var value:((JsonValue.Array)root.get("steps")).values()){
   var step=object(value);var primitive=((JsonValue.Text)step.get("primitive")).value();
   if(primitive.equals("proutlost:warning")){var args=object(step.get("args"));args.put("profile",str(WARNING.value()));step.put("args",json(args));}
   else if(primitive.equals("proutlost:landslide")){
    step.put("primitive",str(ControlledFireWaveBinding.FIRE.value()));
    var encodedMask=json(Map.of("hash",str(mask.hash().value()),"bytes",number(mask.bytes()),"codec_version",number(mask.codecVersion()),"codec_id",str(mask.codecId().value())));
    step.put("args",json(Map.of("recipe",str(RECIPE.value()),"propagation_mask",encodedMask,"energy",number(400),"damage_profile",str(DAMAGE.value()))));
   }
   steps.add(json(step));
  }
  root.put("steps",new JsonValue.Array(steps));var completion=object(root.get("completion"));completion.put("fact_type",str(COMPLETED.value()));
  completion.put("durable_trace",JsonValue.Nil.INSTANCE);completion.put("persistence_probability",number(0));root.put("completion",json(completion));
  var source=new DefinitionLoader.Source("data/proutlost_director/director/events/lab/controlled_fire.json",CanonicalJsonV1.encode(json(root)));
  var warn=new PrimitiveId("proutlost:warning");var fire=ControlledFireWaveBinding.FIRE;
  var readable=new CapabilityId("warning.readable_v1");var caps=Set.of(readable,new CapabilityId("mutation.controlled_fire_v1"),new CapabilityId("danger.controlled_fire_v1"));
  var state=new ResourceId("proutlost_director:block_state_v1");var engine=ControlledFireWaveBinding.STATE_CODEC;var resolver=id("footprint");var cleanup=id("cleanup");
  var warning=new PrimitiveDescriptor(warn,1,id("codec_warning"),Set.of(readable),cost(0),resolver,Set.of(),Set.of(OwnershipDomain.PERCEPTION),Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var physical=new PrimitiveDescriptor(fire,1,id("codec_controlled_fire"),Set.of(new CapabilityId("mutation.controlled_fire_v1"),new CapabilityId("danger.controlled_fire_v1")),cost(0),resolver,
   Set.of(state,engine,mask.codecId(),ControlledFireWaveBinding.HIT_CODEC),Set.of(OwnershipDomain.BLOCK),Set.of(NoninvertibleEffect.DAMAGE),new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var mutation=new MutationProfile(RECIPE,1,Set.of(fire),Set.of(state,engine),resolver,new ReadSet(Map.of(),Map.of(),Map.of()),id("mask"),cost(2),1,safeExits,PlayerEditPolicy.YIELD,Optional.empty(),fire,Set.of(NoninvertibleEffect.DAMAGE),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT"));
  var warningProfile=new WarningProfile(WARNING,Set.of(id("warning_sign")),id("audience"),Set.of(id("visibility")),new DurationTicks(400),Set.of(id("warning_sign")),id("new_arrival"),id("warning_cancel"),id("proof"));
  damage=new DamageProfile(DAMAGE,Set.of(new ResourceId("minecraft:in_fire")),1,6,new DurationTicks(20),List.of(),Set.of(ReasonCode.CAPABILITY_MISSING),ControlledFireWaveBinding.HIT_CODEC);
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);known.put(ContentReferences.Kind.FACT,Set.of(COMPLETED));
  known.put(ContentReferences.Kind.MODEL,Set.of(id("warning_sign"),id("warning_cancel")));known.put(ContentReferences.Kind.DAMAGE_TYPE,Set.of(new ResourceId("minecraft:in_fire")));
  known.put(ContentReferences.Kind.CODEC,Set.of(state,engine,mask.codecId(),ControlledFireWaveBinding.HIT_CODEC,id("codec_warning"),id("codec_controlled_fire"),id("proof")));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,id("mask"),id("audience"),id("visibility"),id("new_arrival")));known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(RECIPE,mutation),Map.of(WARNING,warningProfile),Map.of(DAMAGE,damage),caps,Set.of("BLOCKS:FOOTPRINT","DANGER:FOOTPRINT"));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(List.of(warning,physical),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(32,65536,4194304,128,128));
  var loaded=loader.reload(List.of(source));
  if(!loaded.installed()||!loaded.diagnostics().isEmpty()||loaded.snapshot().events().size()!=1||!loaded.snapshot().events().containsKey(DEFINITION))throw new IllegalStateException("J02 FIRE LAB invalid "+loaded.diagnostics());
  snapshot=loaded.snapshot();
 }
 public CatalogSnapshot snapshot(){return snapshot;}public DefinitionId definitionId(){return DEFINITION;}
 public DamageProfile damageProfile(){return damage;}
 /** Explicit deterministic LAB recipe probability, forced again into each native wave blob. */
 public double ignitionProbability(){return 1.0;}
}
