package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.util.*;
/** Actual unchanged bundled candidate, with TEST_ONLY profiles and a surveyed native exit. */
public final class DirectorLabLandslideCatalog implements DirectorBootstrap.TestOnlyBackend {
 private final CatalogSnapshot snapshot;
 private static ResourceId id(String p){return new ResourceId("proutlost:"+p);}
 private static BudgetVector cost(long blocks){return new BudgetVector(.01,.01,.01,0,0,blocks,64,.01,32,0);}
 public DirectorLabLandslideCatalog(ConfigView config,List<RouteSegment> safeExits){
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING||safeExits.isEmpty())throw new IllegalArgumentException("LAB calibration and native exit required");
  var source=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals(
   "data/proutlost_director/director/events/candidate/controlled_landslide.json")).findFirst().orElseThrow();
  var warn=new PrimitiveId("proutlost:warning");var slide=new PrimitiveId("proutlost:landslide");
  var readable=new CapabilityId("warning.readable_v1");var terrain=new CapabilityId("mutation.terrain_v1");
  var debris=new CapabilityId("danger.debris_v1");var caps=Set.of(readable,terrain,debris);
  var codec=id("block_codec");var resolver=id("footprint");var cleanup=id("cleanup");
  var warning=new PrimitiveDescriptor(warn,1,id("codec_warning"),Set.of(readable),cost(0),resolver,
   Set.of(),Set.of(OwnershipDomain.PERCEPTION),Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,
   RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var landslide=new PrimitiveDescriptor(slide,1,id("codec_landslide"),Set.of(terrain,debris),cost(0),resolver,
   Set.of(codec),Set.of(OwnershipDomain.BLOCK),Set.of(NoninvertibleEffect.DAMAGE),new DurationTicks(6000),
   PausePolicy.STABILIZE,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var mutation=new MutationProfile(id("test_bounded_landslide"),1,Set.of(slide),Set.of(codec),resolver,
   new ReadSet(Map.of(),Map.of(),Map.of()),id("mask"),cost(1024),1,List.copyOf(safeExits),PlayerEditPolicy.YIELD,
   Optional.empty(),slide,Set.of(NoninvertibleEffect.DAMAGE),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT"));
  var warningProfile=new WarningProfile(id("test_landslide_warning"),Set.of(id("warning_sign")),id("audience"),
   Set.of(id("visibility")),new DurationTicks(400),Set.of(id("warning_sign")),id("new_arrival"),id("warning_cancel"),id("proof"));
  var damage=new DamageProfile(id("test_debris_damage"),Set.of(new ResourceId("minecraft:falling_block")),
   2,6,new DurationTicks(20),List.of(),Set.of(ReasonCode.CAPABILITY_MISSING),id("hit_ledger"));
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.FACT,Set.of(id("controlled_landslide_observed")));
  known.put(ContentReferences.Kind.TAG,Set.of(id("landslide_scar")));known.put(ContentReferences.Kind.RISK,Set.of(id("landslide")));
  known.put(ContentReferences.Kind.MODEL,Set.of(id("warning_sign"),id("warning_cancel")));
  known.put(ContentReferences.Kind.DAMAGE_TYPE,Set.of(new ResourceId("minecraft:falling_block")));
  known.put(ContentReferences.Kind.CODEC,Set.of(codec,id("codec_warning"),id("codec_landslide"),id("proof"),id("hit_ledger")));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,id("mask"),id("audience"),id("visibility"),id("new_arrival")));
  known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(mutation.recipeId(),mutation),
   Map.of(warningProfile.id(),warningProfile),Map.of(damage.id(),damage),caps,Set.of("BLOCKS:FOOTPRINT","DANGER:FOOTPRINT"));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(List.of(warning,landslide),ProviderMode.TEST_ONLY),
   refs,ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
  var result=loader.reload(List.of(source));
  if(!result.installed()||!result.diagnostics().isEmpty()||result.snapshot().events().size()!=1)
   throw new IllegalStateException("J02 controlled_landslide cannot compile "+result.diagnostics());
  snapshot=result.snapshot();
 }
 public CatalogSnapshot snapshot(){return snapshot;}
}
