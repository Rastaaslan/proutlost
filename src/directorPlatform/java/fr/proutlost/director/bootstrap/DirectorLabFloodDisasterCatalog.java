package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.math.BigDecimal;
import java.util.*;

/** Genuine additive J02 TEST_ONLY definition for an observed finite conservative water pulse. */
public final class DirectorLabFloodDisasterCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final CatalogSnapshot snapshot;
 private final DefinitionId definitionId=new DefinitionId("proutlost_director:lab/controlled_flood");
 private final DefinitionId pulseDefinitionId=new DefinitionId("proutlost_director:lab/controlled_flood_pulse");
 private static ResourceId id(String value){return new ResourceId("proutlost:"+value);}
 private static JsonValue.Text text(String v){return new JsonValue.Text(v);}
 private static JsonValue.Number number(long v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 private static JsonValue.ObjectValue json(Map<String,JsonValue> v){return new JsonValue.ObjectValue(v);}
 private static Map<String,JsonValue> object(JsonValue v){return new TreeMap<>(((JsonValue.ObjectValue)v).values());}
 private static BudgetVector cost(long cells){return new BudgetVector(.01,.01,.01,0,0,cells,64,.01,32,0);}
 private static JsonValue.ObjectValue args(ResourceId recipe,BlobRef mask,long units){
  return json(Map.of("recipe",text(recipe.value()),"propagation_mask",json(Map.of(
   "hash",text(mask.hash().value()),"bytes",number(mask.bytes()),"codec_version",number(mask.codecVersion()),"codec_id",text(mask.codecId().value()))),
   "transfer_units",number(units),"max_level",number(8)));
 }
 public DirectorLabFloodDisasterCatalog(ConfigView config,List<RouteSegment> exits,BlobRef mask,long units){
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING||exits.isEmpty()||units<1||units>32
    ||!mask.codecId().equals(ControlledWaterBinding.MASK_CODEC)||mask.codecVersion()!=1||mask.bytes()==0)
   throw new IllegalArgumentException("observed LAB basin, volume and real exit required");
  exits=List.copyOf(exits);
  var original=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals(
   "data/proutlost_director/director/events/candidate/controlled_landslide.json")).findFirst().orElseThrow();
  var root=object(CanonicalJsonV1.decode(original.bytes()));root.put("id",text(definitionId.value()));
  root.put("eligibility",json(Map.of("type",text("players_present"),"minimum",number(1))));
  var target=object(root.get("target"));target.put("scope",text("SOLO"));
  target.put("roles",json(Map.of("witness",json(Map.of("min",number(1),"max",number(1),"overlap",new JsonValue.Bool(false))))));root.put("target",json(target));
  var budget=object(root.get("budget"));budget.put("mutation_units",number(4));root.put("budget",json(budget));
  var safety=object(root.get("safety"));safety.put("mutation_profile",text(ControlledWaterBinding.IMPORT.value()));
  safety.put("required_capabilities",new JsonValue.Array(List.of(text(ControlledWaterBinding.CAP.value()),text("warning.readable_v1"))));root.put("safety",json(safety));
  var steps=new ArrayList<JsonValue>();String importedStep=null;Map<String,JsonValue> pulse=null;
  for(var value:((JsonValue.Array)root.get("steps")).values()){
   var step=object(value);
   if(((JsonValue.Text)step.get("primitive")).value().equals("proutlost:landslide")){
    step.put("primitive",text(ControlledWaterBinding.ID.value()));step.put("args",args(ControlledWaterBinding.IMPORT,mask,units));
    importedStep=((JsonValue.Text)step.get("id")).value();pulse=new TreeMap<>(step);
   }steps.add(json(step));
  }
  if(importedStep==null)throw new IllegalStateException("native source definition has no physical step");
  root.put("steps",new JsonValue.Array(steps));
  var fact=new ResourceId("proutlost_director:lab_flood_pulse_observed");var completion=object(root.get("completion"));
  completion.put("fact_type",text(fact.value()));completion.put("durable_trace",JsonValue.Nil.INSTANCE);completion.put("persistence_probability",number(0));root.put("completion",json(completion));
  var document=new DefinitionLoader.Source("data/proutlost_director/director/events/lab/controlled_flood.json",CanonicalJsonV1.encode(json(root)));
  // G0 has one mutation profile per definition. The pulse has a second genuine J02
  // definition/admission, under the same actual disaster event and already deployed WARN.
  var pulseRoot=new TreeMap<>(root);pulseRoot.put("id",text(pulseDefinitionId.value()));
  var pulseSafety=object(root.get("safety"));pulseSafety.put("mutation_profile",text(ControlledWaterBinding.PULSE.value()));pulseRoot.put("safety",json(pulseSafety));
  var pulseBudget=object(root.get("budget"));pulseBudget.put("mutation_units",number(2));pulseRoot.put("budget",json(pulseBudget));
  var pulseSteps=new ArrayList<JsonValue>();
  for(var v:steps){var step=object(v);if(((JsonValue.Text)step.get("primitive")).value().equals(ControlledWaterBinding.ID.value()))step.put("args",args(ControlledWaterBinding.PULSE,mask,1));pulseSteps.add(json(step));}
  pulseRoot.put("steps",new JsonValue.Array(pulseSteps));
  var pulseDocument=new DefinitionLoader.Source("data/proutlost_director/director/events/lab/controlled_flood_pulse.json",CanonicalJsonV1.encode(json(pulseRoot)));
  var warning=new PrimitiveId("proutlost:warning");var readable=new CapabilityId("warning.readable_v1");var fluid=ControlledWaterBinding.CAP;var caps=Set.of(readable,fluid);
  ResourceId resolver=id("controlled_water_mask"),cleanup=id("controlled_water_cleanup"),codec=id("codec_controlled_water");
  var warn=new PrimitiveDescriptor(warning,1,id("codec_warning"),Set.of(readable),cost(0),resolver,Set.of(),Set.of(OwnershipDomain.PERCEPTION),Set.of(),
   new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var water=new PrimitiveDescriptor(ControlledWaterBinding.ID,1,codec,Set.of(fluid),cost(0),resolver,
   Set.of(BlockTransformBinding.STATE_CODEC,ControlledWaterBinding.MASK_CODEC,ControlledWaterBinding.RESTORE_CODEC),Set.of(OwnershipDomain.BLOCK),Set.of(),
   new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var mutations=new HashMap<ResourceId,MutationProfile>();
  for(var recipe:List.of(ControlledWaterBinding.IMPORT,ControlledWaterBinding.PULSE,ControlledWaterBinding.RESTORE,ControlledWaterBinding.DRAIN)){
   mutations.put(recipe,new MutationProfile(recipe,1,Set.of(ControlledWaterBinding.ID),Set.of(BlockTransformBinding.STATE_CODEC),resolver,
    new ReadSet(Map.of(),Map.of(),Map.of()),resolver,cost(recipe.equals(ControlledWaterBinding.PULSE)?2:4),1,exits,PlayerEditPolicy.YIELD,
    Optional.empty(),ControlledWaterBinding.ID,Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT")));
  }
  var profile=new WarningProfile(id("test_landslide_warning"),Set.of(id("warning_sign")),id("audience"),Set.of(id("visibility")),new DurationTicks(400),
   Set.of(id("warning_sign")),id("new_arrival"),id("warning_cancel"),id("proof"));
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.FACT,Set.of(fact));known.put(ContentReferences.Kind.MODEL,Set.of(id("warning_sign"),id("warning_cancel")));
  known.put(ContentReferences.Kind.CODEC,Set.of(codec,id("codec_warning"),id("proof"),BlockTransformBinding.STATE_CODEC,ControlledWaterBinding.MASK_CODEC,ControlledWaterBinding.RESTORE_CODEC));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,id("audience"),id("visibility"),id("new_arrival")));known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,mutations,Map.of(profile.id(),profile),Map.of(),caps,Set.of("BLOCKS:FOOTPRINT","DANGER:FOOTPRINT"));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(List.of(warn,water),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(32,65536,4194304,128,128));
  var loaded=loader.reload(List.of(document,pulseDocument));
  if(!loaded.installed()||!loaded.diagnostics().isEmpty()||!loaded.snapshot().events().containsKey(definitionId)||!loaded.snapshot().events().containsKey(pulseDefinitionId))throw new IllegalStateException("J02 finite FLOOD invalid "+loaded.diagnostics());
  snapshot=loaded.snapshot();
 }
 public CatalogSnapshot snapshot(){return snapshot;}
 public DefinitionId definitionId(){return definitionId;}
 public DefinitionId pulseDefinitionId(){return pulseDefinitionId;}
}
