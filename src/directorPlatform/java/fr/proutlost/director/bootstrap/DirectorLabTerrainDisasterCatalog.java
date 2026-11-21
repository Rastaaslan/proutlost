package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import fr.proutlost.director.runtime.DisasterRunner;
import java.math.BigDecimal;
import java.util.*;
/** Exact landslide candidate or distinct four-cell manual COLLAPSE LAB definition. */
public final class DirectorLabTerrainDisasterCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final CatalogSnapshot snapshot;private final DefinitionId definitionId;
 private static ResourceId id(String p){return new ResourceId("proutlost:"+p);}
 private static BudgetVector cost(long blocks){return new BudgetVector(.01,.01,.01,0,0,blocks,64,.01,32,0);}
 private static Map<String,JsonValue> object(JsonValue value){return new TreeMap<>(((JsonValue.ObjectValue)value).values());}
 private static JsonValue.ObjectValue json(Map<String,JsonValue> value){return new JsonValue.ObjectValue(value);}
 private static JsonValue.Number number(long n){return new JsonValue.Number(BigDecimal.valueOf(n));}
 public DirectorLabTerrainDisasterCatalog(ConfigView config,DisasterRunner.Family family,List<RouteSegment> safeExits){
  Objects.requireNonNull(family);safeExits=List.copyOf(safeExits);
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING||safeExits.isEmpty())throw new IllegalArgumentException("LAB calibration and surveyed exit required");
  if(family==DisasterRunner.Family.LANDSLIDE){
   snapshot=new DirectorLabLandslideCatalog(config,safeExits).snapshot();
   definitionId=new DefinitionId("proutlost_director:candidate/controlled_landslide");return;
  }
  if(family!=DisasterRunner.Family.COLLAPSE)throw new IllegalArgumentException("terrain disaster family");
  definitionId=new DefinitionId("proutlost_director:lab/controlled_collapse");
  var original=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals(
   "data/proutlost_director/director/events/candidate/controlled_landslide.json")).findFirst().orElseThrow();
  var root=object(CanonicalJsonV1.decode(original.bytes()));
  root.put("id",new JsonValue.Text(definitionId.value()));
  root.put("eligibility",json(Map.of("type",new JsonValue.Text("players_present"),"minimum",number(1))));
  var target=object(root.get("target"));target.put("scope",new JsonValue.Text("SOLO"));
  target.put("roles",json(Map.of("witness",json(Map.of("min",number(1),"max",number(1),"overlap",new JsonValue.Bool(false))))));
  root.put("target",json(target));
  var budget=object(root.get("budget"));budget.put("mutation_units",number(4));root.put("budget",json(budget));
  var safety=object(root.get("safety"));safety.put("mutation_profile",new JsonValue.Text(id("test_bounded_collapse").value()));root.put("safety",json(safety));
  var steps=new ArrayList<JsonValue>();
  for(var value:((JsonValue.Array)root.get("steps")).values()){
   var step=object(value);
   if(((JsonValue.Text)step.get("primitive")).value().equals("proutlost:landslide")){
    var args=object(step.get("args"));args.put("recipe",new JsonValue.Text(id("test_bounded_collapse").value()));args.put("max_blocks",number(4));step.put("args",json(args));
   }
   steps.add(json(step));
  }
  root.put("steps",new JsonValue.Array(steps));
  var fact=new ResourceId("proutlost_director:lab_collapse_observed");
  var completion=object(root.get("completion"));completion.put("fact_type",new JsonValue.Text(fact.value()));
  completion.put("durable_trace",JsonValue.Nil.INSTANCE);completion.put("persistence_probability",number(0));root.put("completion",json(completion));
  var source=new DefinitionLoader.Source("data/proutlost_director/director/events/lab/controlled_collapse.json",CanonicalJsonV1.encode(json(root)));
  var warn=new PrimitiveId("proutlost:warning");var slide=new PrimitiveId("proutlost:landslide");
  var readable=new CapabilityId("warning.readable_v1");var terrain=new CapabilityId("mutation.terrain_v1");var debris=new CapabilityId("danger.debris_v1");var caps=Set.of(readable,terrain,debris);
  var codec=id("block_codec");var resolver=id("footprint");var cleanup=id("cleanup");
  var warning=new PrimitiveDescriptor(warn,1,id("codec_warning"),Set.of(readable),cost(0),resolver,Set.of(),Set.of(OwnershipDomain.PERCEPTION),Set.of(),
   new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var landslide=new PrimitiveDescriptor(slide,1,id("codec_landslide"),Set.of(terrain,debris),cost(0),resolver,Set.of(codec),Set.of(OwnershipDomain.BLOCK),
   Set.of(NoninvertibleEffect.DAMAGE),new DurationTicks(6000),PausePolicy.STABILIZE,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var mutation=new MutationProfile(id("test_bounded_collapse"),1,Set.of(slide),Set.of(codec),resolver,new ReadSet(Map.of(),Map.of(),Map.of()),id("mask"),
   cost(4),1,safeExits,PlayerEditPolicy.YIELD,Optional.empty(),slide,Set.of(NoninvertibleEffect.DAMAGE),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT"));
  var warningProfile=new WarningProfile(id("test_landslide_warning"),Set.of(id("warning_sign")),id("audience"),Set.of(id("visibility")),new DurationTicks(400),
   Set.of(id("warning_sign")),id("new_arrival"),id("warning_cancel"),id("proof"));
  var damage=new DamageProfile(id("test_debris_damage"),Set.of(new ResourceId("minecraft:falling_block")),2,6,new DurationTicks(20),List.of(),
   Set.of(ReasonCode.CAPABILITY_MISSING),id("hit_ledger"));
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.FACT,Set.of(fact));
  known.put(ContentReferences.Kind.MODEL,Set.of(id("warning_sign"),id("warning_cancel")));
  known.put(ContentReferences.Kind.DAMAGE_TYPE,Set.of(new ResourceId("minecraft:falling_block")));
  known.put(ContentReferences.Kind.CODEC,Set.of(codec,id("codec_warning"),id("codec_landslide"),id("proof"),id("hit_ledger")));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,id("mask"),id("audience"),id("visibility"),id("new_arrival")));
  known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(mutation.recipeId(),mutation),Map.of(warningProfile.id(),warningProfile),
   Map.of(damage.id(),damage),caps,Set.of("BLOCKS:FOOTPRINT","DANGER:FOOTPRINT"));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(List.of(warning,landslide),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY,
   new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
  var loaded=loader.reload(List.of(source));
  if(!loaded.installed()||!loaded.diagnostics().isEmpty()||loaded.snapshot().events().size()!=1||!loaded.snapshot().events().containsKey(definitionId))
   throw new IllegalStateException("J02 LAB controlled_collapse invalid "+loaded.diagnostics());
  snapshot=loaded.snapshot();
 }
 public CatalogSnapshot snapshot(){return snapshot;}
 public DefinitionId definitionId(){return definitionId;}
}
