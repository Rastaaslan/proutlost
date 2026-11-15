package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.network.DirectorGameplayAssets;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.CatalogPort;
import fr.proutlost.director.catalog.*;
import java.util.*;

/** Loads the unchanged SHA-verified packaged nine events and two chains.
 * TEST_ONLY descriptors describe native adapters; they grant no physical permit,
 * client receipt, semantic tag, region, risk, role or eligibility. All such input
 * must still be captured by the real observation/planning and J04/J05/J06 lanes.
 * The supplied exits are immutable actor-surveyed geometry, never a fixture route. */
public final class DirectorNativeCatalogueCatalog
 implements CatalogPort,DirectorBootstrap.TestOnlyBackend {
 private static final ProviderMode MODE=ProviderMode.TEST_ONLY;
 private static final ResourceId FOOTPRINT=id("footprint"),CLEANUP=id("cleanup");
 private static final ResourceId DAMAGE_LEDGER=new ResourceId("proutlost_director:landslide_damage_v1");
 private static final ResourceId PERCEPTION_INTENT=new ResourceId("proutlost_director:perception_intent_v1");
 private static final ResourceId BLOCK_OWNER=new ResourceId("proutlost_director:owner_v1");
 private static final ResourceId BLOCK_INTENT=new ResourceId("proutlost_director:block_intent");
 private static final Map<String,Set<CapabilityId>> CAPS=Map.ofEntries(
  Map.entry("target_sound",caps("perception.sound_v1")),
  Map.entry("apparition",caps("perception.silhouette_v1")),
  Map.entry("local_weather",caps("perception.local_weather_v1")),
  Map.entry("camp_growth",caps("mutation.plants_v1")),
  Map.entry("fauna_migrate",caps("entity.migration_v1","habitat.corridor_v1")),
  Map.entry("shared_choice",caps("interaction.shared_anchor_v1","perception.symbol_v1")),
  Map.entry("goat_visit",caps("entity.goat_v1")),
  Map.entry("warning",caps("warning.readable_v1")),
  Map.entry("landslide",caps("mutation.terrain_v1","danger.debris_v1")),
  Map.entry("trace",caps("anchor.state_v1")));
 private final CatalogSnapshot snapshot;
 private final PrimitiveRegistry primitives;
 private final ContentReferences references;
 private final List<RouteSegment> safeExits;
 private final Set<CapabilityId> capabilities;
 public DirectorNativeCatalogueCatalog(ConfigView config,List<RouteSegment> actualSafeExits){
  Objects.requireNonNull(config);
  if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)
   throw new IllegalArgumentException("TEST_ONLY candidate calibration required");
  safeExits=List.copyOf(actualSafeExits);
  if(safeExits.isEmpty()||safeExits.size()>64||safeExits.stream().anyMatch(r->
    r.from().equals(r.to())||r.radius()<=0))
   throw new IllegalArgumentException("actual nonempty surveyed safe exits required");
  var bundled=new DirectorNativeCandidateCatalog(config);
  var descriptors=new ArrayList<PrimitiveDescriptor>();
  for(var name:new TreeSet<>(CAPS.keySet())){
   var domain=switch(name){
    case "landslide","camp_growth"->OwnershipDomain.BLOCK;
    case "fauna_migrate","goat_visit"->OwnershipDomain.ENTITY;
    case "trace","shared_choice"->OwnershipDomain.ANCHOR;
    default->OwnershipDomain.PERCEPTION;
   };
   var codecs=switch(name){
    case "camp_growth"->Set.of(BlockTransformBinding.STATE_CODEC,BLOCK_OWNER,BLOCK_INTENT);
    case "landslide"->Set.of(BlockTransformBinding.STATE_CODEC,BLOCK_OWNER,BLOCK_INTENT,DAMAGE_LEDGER);
    case "trace"->Set.of(TraceBinding.CODEC,new ResourceId("proutlost_director:trace_intent_v1"));
    case "shared_choice"->Set.of(TraceBinding.CODEC,SharedChoiceOfferBinding.PLAN_CODEC,SharedChoiceResolutionBinding.PLAN_CODEC);
    case "goat_visit"->Set.of(new ResourceId("proutlost_director:goat_slot_v1"));
    case "fauna_migrate"->Set.of(new ResourceId("proutlost_director:animal_route"),new ResourceId("proutlost_director:entity_slot"));
    default->Set.of(PERCEPTION_INTENT);
   };
   var noninvertible=name.equals("landslide")?Set.of(NoninvertibleEffect.DAMAGE):
    name.equals("target_sound")?Set.of(NoninvertibleEffect.SOUND_EXPOSURE):Set.<NoninvertibleEffect>of();
   descriptors.add(new PrimitiveDescriptor(new PrimitiveId("proutlost:"+name),1,id("codec_"+name),
    CAPS.get(name),BudgetMath.zero(),name.equals("goat_visit")?id("goat_slot"):FOOTPRINT,
    codecs,Set.of(domain),noninvertible,new DurationTicks(6000),
    name.equals("landslide")?PausePolicy.STABILIZE:PausePolicy.CLEANUP,
    RecoveryMode.RECOVER,CLEANUP,Optional.empty(),Optional.empty(),MODE));
  }
  primitives=new PrimitiveRegistry(descriptors,MODE);
  var allCaps=new HashSet<CapabilityId>();CAPS.values().forEach(allCaps::addAll);
  capabilities=Set.copyOf(allCaps);
  if(primitives.descriptors().size()!=10||capabilities.size()!=13||
    !bundled.dependencies().requiredPrimitives().equals(primitives.descriptors().keySet())||
    !bundled.dependencies().requiredCapabilities().equals(capabilities))
   throw new IllegalStateException("packaged native 10-primitives/13-capabilities graph drift");
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  // These are semantic identifiers understood by the packaged content. Their
  // registration does not add them to any observed anchor, region or memory.
  put(known,ContentReferences.Kind.TAG,"proutlost:fauna_corridor","proutlost:fauna_settled",
   "proutlost:camp_abandoned","proutlost:abandoned_camp","proutlost:regrown_camp",
   "proutlost:landslide_scar","proutlost:camp","proutlost:clearing","proutlost:signal_anchor",
   "proutlost:signal_revealed","proutlost:clearing_choice","proutlost:remembered_camp",
   "proutlost:scar_still_relevant","proutlost:recovering_landscape","proutlost:unfinished_recovery");
  put(known,ContentReferences.Kind.FACT,"proutlost:animal_migration_observed","proutlost:camp_regrowth_observed",
   "proutlost:controlled_landslide_observed","proutlost:goat_guest_observed","proutlost:local_mist_observed",
   "proutlost:parallel_steps_observed","proutlost:required_signal_observed","proutlost:safe_micro_observed",
   "proutlost:shared_choice_observed","proutlost:context_lost");
  known.put(ContentReferences.Kind.SOUND,Set.of(
   asset("minecraft:block.gravel.step",PerceptionKind.SOUND),asset("minecraft:block.campfire.crackle",PerceptionKind.SOUND)));
  known.put(ContentReferences.Kind.MODEL,Set.of(asset("proutlost:test_silhouette",PerceptionKind.APPARITION),
   asset(DirectorGameplayAssets.LANDSLIDE_WARNING.value(),PerceptionKind.SYMBOL),
   asset(DirectorGameplayAssets.WARNING.value(),PerceptionKind.SYMBOL)));
  known.put(ContentReferences.Kind.LOCAL_WEATHER,Set.of(asset("proutlost:test_mist",PerceptionKind.LOCAL_WEATHER)));
  put(known,ContentReferences.Kind.ENTITY,"minecraft:sheep");
  known.put(ContentReferences.Kind.CHOICE,Set.of(SharedChoiceOfferBinding.LISTEN,SharedChoiceOfferBinding.LEAVE_TRACE));
  put(known,ContentReferences.Kind.RISK,"proutlost:landslide");
  put(known,ContentReferences.Kind.DAMAGE_TYPE,"minecraft:falling_block");
  var codecs=new HashSet<ResourceId>(Set.of(id("proof"),DAMAGE_LEDGER));
  for(var d:descriptors){codecs.add(d.argumentCodec());codecs.addAll(d.capturableCodecs());}
  known.put(ContentReferences.Kind.CODEC,Set.copyOf(codecs));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(FOOTPRINT,id("mask"),id("goat_slot"),
   id("audience"),id("visibility"),id("new_arrival")));
  known.put(ContentReferences.Kind.CLEANUP,Set.of(CLEANUP));
  var camp=mutation("test_camp_plants","camp_growth",configuredLong(config,"event.growth.max_blocks"),List.of(),false);
  var slide=mutation("test_bounded_landslide","landslide",configuredLong(config,"event.disaster.max_blocks"),safeExits,true);
  var warning=new WarningProfile(id("test_landslide_warning"),Set.of(DirectorGameplayAssets.LANDSLIDE_WARNING),
   id("audience"),Set.of(id("visibility")),new DurationTicks(400),Set.of(DirectorGameplayAssets.LANDSLIDE_WARNING),
   id("new_arrival"),DirectorGameplayAssets.WARNING,id("proof"));
  var damage=new DamageProfile(id("test_debris_damage"),Set.of(new ResourceId("minecraft:falling_block")),
   2,6,new DurationTicks(20),List.of(),Set.of(ReasonCode.CAPABILITY_MISSING),DAMAGE_LEDGER);
  references=new ContentReferences(MODE,known,Map.of(camp.recipeId(),camp,slide.recipeId(),slide),
   Map.of(warning.id(),warning),Map.of(damage.id(),damage),capabilities,
   Set.of("ENTITY:COHORT","NAVIGATION:CORRIDOR","BLOCKS:FOOTPRINT","DANGER:FOOTPRINT",
    "ENTITY:GOAT_SLOT","PERCEPTION:WITNESS","ANCHOR:STATE"));
  var loader=new DefinitionLoader(config,primitives,references,MODE,
   new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
  var loaded=loader.reload(bundled.candidateDocuments());
  if(!loaded.installed()||!loaded.diagnostics().isEmpty()||loaded.snapshot().events().size()!=9||
    loaded.snapshot().chains().size()!=2)
   throw new IllegalStateException("unchanged packaged native candidate catalogue invalid "+loaded.diagnostics());
  snapshot=loaded.snapshot();
 }
 private static ResourceId asset(String value,PerceptionKind kind){
  var id=new ResourceId(value);var spec=DirectorGameplayAssets.specs().get(id);
  if(spec==null||spec.kind()!=kind)throw new IllegalStateException("actual native asset unavailable "+id);
  return id;
 }
 private static MutationProfile mutation(String recipe,String primitive,long blocks,List<RouteSegment> exits,boolean danger){
  var p=new PrimitiveId("proutlost:"+primitive);
  return new MutationProfile(id(recipe),1,Set.of(p),Set.of(BlockTransformBinding.STATE_CODEC),FOOTPRINT,
   new ReadSet(Map.of(),Map.of(),Map.of()),id("mask"),new BudgetVector(0,0,0,0,0,blocks,0,0,0,0),
   1,List.copyOf(exits),PlayerEditPolicy.YIELD,Optional.empty(),p,
   danger?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT"));
 }
 private static long configuredLong(ConfigView config,String key){
  var value=Objects.requireNonNull(config.values().get(key),"missing configured bound "+key).value().longValueExact();
  if(value<=0)throw new IllegalArgumentException("positive configured bound required "+key);return value;
 }
 private static ResourceId id(String path){return new ResourceId("proutlost:"+path);}
 private static Set<CapabilityId> caps(String... names){
  var result=new HashSet<CapabilityId>();for(var name:names)result.add(new CapabilityId(name));return Set.copyOf(result);
 }
 private static void put(Map<ContentReferences.Kind,Set<ResourceId>> known,ContentReferences.Kind kind,String... names){
  var result=new HashSet<ResourceId>();for(var name:names)result.add(new ResourceId(name));known.put(kind,Set.copyOf(result));
 }
 @Override public CatalogSnapshot current(){return snapshot;}
 public CatalogSnapshot snapshot(){return snapshot;}
 public PrimitiveRegistry primitiveRegistry(){return primitives;}
 public ContentReferences references(){return references;}
 public List<RouteSegment> safeExits(){return safeExits;}
 public Set<CapabilityId> capabilities(){return capabilities;}
}
