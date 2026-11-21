package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.EntityCohorts;
import java.util.*;
import java.util.function.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;

/** Internal exact-owner removal. A missing mobile UUID never proves removal. */
public final class FaunaRemoveBinding implements ServerEffectPort.Binding {
 public static final PrimitiveId REMOVE=new PrimitiveId("proutlost:fauna_remove_owned");
 public static final CapabilityId CLEANUP=new CapabilityId("entity.cleanup_owned_v1");
 private static final ResourceId CODEC_ID=new ResourceId("proutlost_director:entity_slot");
 private static final ResourceId SHEEP=new ResourceId("minecraft:sheep");
 private static final ModelCodec<EntityOwnershipRecord> CODEC=new ModelCodec<>(EntityOwnershipRecord.class);
 private static final BudgetVector COST=new BudgetVector(0,0,0,0,0,1,0,0,0,0);
 private final Function<DimensionId,ServerLevel> levels;
 private final Function<EffectIntent,EntityOwnershipRecord> slots;
 private final Consumer<EntityOwnershipRecord> changed;
 private final Set<ResourceId> compatibleSpecies;
 public FaunaRemoveBinding(Function<DimensionId,ServerLevel> levels,
   Function<EffectIntent,EntityOwnershipRecord> slots,Consumer<EntityOwnershipRecord> changed){
  this(levels,slots,changed,Set.of(SHEEP));
 }
 /** Explicit registry profile only; the legacy constructor remains sheep-only. */
 public FaunaRemoveBinding(Function<DimensionId,ServerLevel> levels,
   Function<EffectIntent,EntityOwnershipRecord> slots,Consumer<EntityOwnershipRecord> changed,
   Set<ResourceId> compatibleSpecies){
  this.levels=Objects.requireNonNull(levels);this.slots=Objects.requireNonNull(slots);this.changed=Objects.requireNonNull(changed);
  this.compatibleSpecies=Set.copyOf(compatibleSpecies);
  if(this.compatibleSpecies.isEmpty())throw new IllegalArgumentException("explicit removal species profile required");
 }
 private static boolean playerAdopted(Animal animal){
  return animal.hasCustomName()||animal instanceof TamableAnimal tame&&tame.isTame();
 }
 private static boolean species(Animal animal,ResourceId expected){
  return BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString().equals(expected.value());
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(REMOVE);}
 public Set<CapabilityId> capabilities(){return Set.of(CLEANUP);}
 private FaunaMigrate args(EffectIntent intent){
  if(!intent.primitive().equals(REMOVE)||intent.version()!=1||
     !(intent.typedArgs() instanceof FaunaMigrate a)||a.cohortSize()!=1||!compatibleSpecies.contains(a.species()))
   throw new IllegalArgumentException("one owned profiled animal removal required");
  return a;
 }
 private ServerLevel level(DimensionId dimension){
  var level=Objects.requireNonNull(levels.apply(dimension),"dimension unavailable");
  if(!level.getServer().isSameThread()||!level.dimension().location().toString().equals(dimension.value()))
   throw new IllegalStateException("fauna removal actor/dimension");
  return level;
 }
 private static boolean eligible(EntityOwnershipRecord row){
  return row.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&row.adoptedBy().isEmpty()&&
   Set.of(EntityPhase.PRESENT,EntityPhase.MOVING,EntityPhase.CLEANING).contains(row.phase());
 }
 private static ResourceKey key(EntityOwnershipRecord row){
  return new ResourceKey(OwnershipDomain.ENTITY,row.lastKnownChunk().dimension(),row.entityUuid().toString());
 }
 private static BlobContent blob(EntityOwnershipRecord row){return BlobContent.capture(CODEC_ID,1,CODEC.encode(row));}
 private static EntityOwnershipRecord decode(BlobBundle blobs,BlobRef ref){
  if(!ref.codecId().equals(CODEC_ID)||ref.codecVersion()!=1)throw new IllegalArgumentException("entity slot codec");
  return CODEC.decode(blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow().bytes());
 }
 private static boolean chunksAvailable(EffectIntent intent,Animal sheep,ServerLevel level){
  var dimension=intent.expectedFootprint().dimension();
  var current=new ChunkRef(dimension,sheep.chunkPosition().x,sheep.chunkPosition().z);
  if(!intent.expectedFootprint().loadedChunks().contains(current)||
     level.getChunkSource().getChunkNow(current.x(),current.z())==null)return false;
  for(var chunk:intent.expectedFootprint().loadedChunks())
   if(!chunk.dimension().equals(dimension)||level.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)return false;
  return true;
 }
 private Animal live(EffectIntent intent,EntityOwnershipRecord before){
  var a=args(intent);var dimension=intent.expectedFootprint().dimension();var level=level(dimension);
  if(!eligible(before)||!before.instanceId().equals(intent.instanceId())||
     !before.lastKnownChunk().dimension().equals(dimension)||before.persistMode()!=a.arrivalPolicy())
   throw new IllegalStateException("fauna removal ownership/policy");
  var entity=level.getEntity(before.entityUuid());
  if(!(entity instanceof Animal sheep)||!species(sheep,a.species())||playerAdopted(sheep)||!sheep.isAlive()||sheep.isLeashed()||sheep.isPassenger()||sheep.isVehicle()||
     !EntityDirectorAttachment.read(sheep).equals(Optional.of(before)))
   throw new IllegalStateException("sheep changed, adopted, dead or unavailable");
  if(!chunksAvailable(intent,sheep,level))throw new IllegalStateException("sheep left sealed loaded chunks");
  return sheep;
 }
 private record Durable(EntityOwnershipRecord before,EntityOwnershipRecord after,ResourceKey key){}
 private Optional<Durable> durable(PreparedEffect plan,BlobBundle blobs){
  try{
   var a=args(plan.intent());var refs=plan.durableBlobs();
   if(refs.preStates().size()!=1||!refs.preStates().keySet().equals(refs.expectedPostStates().keySet())||
      !plan.capabilities().equals(capabilities())||!plan.budgetEstimate().equals(COST)||plan.claims().size()!=1)
    return Optional.empty();
   var resource=refs.preStates().keySet().iterator().next();var pre=refs.preStates().get(resource);var post=refs.expectedPostStates().get(resource);
   var before=decode(blobs,pre);var after=decode(blobs,post);var claim=plan.claims().getFirst();
   if(!eligible(before)||before.persistMode()!=a.arrivalPolicy()||!before.instanceId().equals(plan.intent().instanceId())||
      !before.lastKnownChunk().dimension().equals(plan.intent().expectedFootprint().dimension())||
      !resource.equals(key(before))||!resource.equals(key(after))||
      !after.equals(EntityCohorts.removed(before,before.entityUuid(),plan.snapshot().capturedTick()))||
      !refs.snapshots().equals(Set.of(pre))||!refs.preparedPlan().equals(pre)||!refs.stopPlan().equals(pre)||
      !plan.preconditions().revisions().equals(plan.snapshot().scopeRevisions())||
      !plan.preconditions().epochs().equals(Map.of(resource,before.generation()))||
      !plan.preconditions().fingerprints().equals(Map.of(resource,pre.hash()))||
      !plan.expectedPost().equals(Map.of(resource,post.hash()))||
      !claim.resource().equals(resource)||claim.policy()!=ClaimPolicy.EXCLUSIVE||claim.mergeFunction().isPresent()||
      !claim.expectedEpoch().equals(before.generation())||!claim.cost().equals(COST)||
      !blobs.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(Set.of(pre,post)))
    return Optional.empty();
   return Optional.of(new Durable(before,after,resource));
  }catch(RuntimeException invalid){return Optional.empty();}
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  args(intent);var before=Objects.requireNonNull(slots.apply(intent),"owned sheep slot required");live(intent,before);
  var after=EntityCohorts.removed(before,before.entityUuid(),context.snapshot().capturedTick());
  var pre=blob(before);var post=blob(after);var resource=key(before);
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(resource,before.generation()),Map.of(resource,pre.reference().hash()));
  var refs=new EffectBlobs(Set.of(pre.reference()),pre.reference(),pre.reference(),Map.of(resource,pre.reference()),Map.of(resource,post.reference()));
  var prepared=new PreparedEffect(intent,context.snapshot(),List.of(new ResourceClaim(resource,ClaimPolicy.EXCLUSIVE,COST,Optional.empty(),before.generation())),
   read,Map.of(resource,post.reference().hash()),COST,List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.of(pre,post)));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  var record=durable(plan,blobs);if(record.isEmpty())return false;
  try{var row=record.orElseThrow();if(!row.before().equals(slots.apply(plan.intent())))return false;live(plan.intent(),row.before());return true;}
  catch(RuntimeException unavailable){return false;}
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  var record=durable(plan,blobs).orElseThrow(()->new IllegalStateException("fauna removal J04 provenance"));
  if(!revalidate(plan,blobs))throw new IllegalStateException("fauna removal context changed");
  var sheep=live(plan.intent(),record.before());sheep.getNavigation().stop();sheep.discard();
  if(!sheep.isRemoved())throw new IllegalStateException("native sheep removal not observed");
  changed.accept(record.after());
  return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),Map.of(record.key(),blob(record.after()).reference().hash()),0,Set.of(),Set.of(),List.of());
 }
 public ReconcileResult reconcile(RecoveryProbe probe){
  var record=durable(probe.durableIntent(),probe.blobs());
  if(record.isEmpty())return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
  var row=record.orElseThrow();var intent=probe.durableIntent().intent();var level=level(intent.expectedFootprint().dimension());var entity=level.getEntity(row.before().entityUuid());
  if(entity==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
  if(!(entity instanceof Animal sheep)||!species(sheep,args(intent).species())||!sheep.isAlive())return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
  if(!chunksAvailable(intent,sheep,level))return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
  Optional<EntityOwnershipRecord> observed;
  try{observed=EntityDirectorAttachment.read(sheep);}catch(RuntimeException corrupt){return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
  if(observed.isPresent()){
   var owner=observed.orElseThrow();var before=row.before();
   boolean lineage=owner.entityUuid().equals(before.entityUuid())&&owner.ownerToken().equals(before.ownerToken())&&
    owner.instanceId().equals(before.instanceId())&&owner.cohortId().equals(before.cohortId())&&owner.slotId().equals(before.slotId());
   if(lineage&&(owner.adoptedBy().isPresent()||owner.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||sheep.isLeashed()||playerAdopted(sheep)))
    return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
  }
  try{live(intent,row.before());return new ReconcileResult(ReconcileStatus.SAME_PRE_PROVEN,probe.loadedProofs(),List.of());}
  catch(RuntimeException unavailable){return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
 }
}
