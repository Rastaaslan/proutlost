package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.function.Function;

/** Common executable lifecycle for a bounded primitive tranche, not a planning implementation.
 * Hydrated bytes and exact prepared slices are supplied by the actor coordinator, never fetched
 * with IO here. Subclasses compute their own immutable plans from observations.
 */
public abstract class SingleSlicePrimitive<A extends PrimitiveArgs> implements DirectorPrimitive<A> {
 protected static final ResourceId OWNER_CODEC=new ResourceId("proutlost_director:captured_ownership");
 private final PrimitiveDescriptor descriptor;
 private final WorldEffectPort effects;
 private final Function<PreparedEffect,PreparedSlice> slices;
 protected final Function<PreparedEffect,BlobBundle> hydrated;
 protected SingleSlicePrimitive(PrimitiveDescriptor descriptor,WorldEffectPort effects,Function<PreparedEffect,PreparedSlice> slices,Function<PreparedEffect,BlobBundle> hydrated){
  this.descriptor=Objects.requireNonNull(descriptor);this.effects=Objects.requireNonNull(effects);this.slices=Objects.requireNonNull(slices);this.hydrated=Objects.requireNonNull(hydrated);
  if(descriptor.mode()!=ProviderMode.PRODUCTION)throw new IllegalArgumentException("TEST_ONLY production primitive");
 }
 public final PrimitiveDescriptor descriptor(){return descriptor;}
 protected static BlobContent ownerBlob(OwnershipRecord owner){return BlobContent.capture(OWNER_CODEC,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));}
 protected static OperationToken inverseToken(OperationToken operation){return new OperationToken(UUID.nameUUIDFromBytes((operation.value()+":inverse").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 public final ValidationResult validate(PreparedEffect plan,LiveValidationSnapshot live){
  var reasons=new ArrayList<ReasonCode>();
  if(!plan.intent().primitive().equals(descriptor.id())||plan.intent().version()!=descriptor.version()||!plan.preconditions().equals(live.readSet()))reasons.add(ReasonCode.STALE_CONTEXT);
  if(!live.loadedChunks().containsAll(plan.intent().expectedFootprint().loadedChunks()))reasons.add(ReasonCode.CHUNK_UNAVAILABLE);
  if(live.health()!=HealthMode.NORMAL&&live.health()!=HealthMode.DEGRADED)reasons.add(ReasonCode.WAL_UNAVAILABLE);
  return new ValidationResult(reasons.isEmpty(),reasons,live.readSet());
 }
 public final EffectProgress applySlice(PreparedEffect plan,EffectCursor cursor,CommitPermit permit){
  if(cursor.nextOperation()!=0||cursor.remainingWork()!=1)throw new IllegalArgumentException("single bounded tranche cursor");
  var slice=Objects.requireNonNull(slices.apply(plan));
  if(!slice.operations().equals(List.of(plan.intent()))||!slice.preconditions().equals(plan.preconditions())||!slice.footprint().equals(plan.intent().expectedFootprint())||!BudgetMath.fits(plan.budgetEstimate(),slice.maximumWork()))throw new IllegalArgumentException("coordinator slice substitution");
  var result=effects.apply(slice,permit);
  return new EffectProgress(new EffectCursor(1,result.remainingWork()),result);
 }
 public final ReconcileResult reconcile(PreparedEffect plan,RecoveryEvidence evidence){
  return effects.reconcile(new RecoveryProbe(plan,List.of(evidence),hydrated.apply(plan)));
 }
 public final CleanupPlan cleanup(PreparedEffect plan,CleanupReason reason,OwnershipView live){
  Objects.requireNonNull(reason);var saved=new HashMap<ResourceKey,OwnershipRecord>();
  for(var blob:hydrated.apply(plan).contents())if(plan.durableBlobs().snapshots().contains(blob.reference())&&blob.reference().codecId().equals(OWNER_CODEC)){
   var owner=new ModelCodec<>(OwnershipRecord.class).decode(blob.bytes());if(saved.putIfAbsent(owner.resourceKey(),owner)!=null)throw new IllegalArgumentException("duplicate captured owner");
  }
  var yielded=new HashSet<ResourceKey>();
  for(var resource:plan.preconditions().epochs().keySet()){
   var before=saved.get(resource);var current=live.records().get(resource);
   if(before==null||current==null||current.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||!current.ownerTokens().equals(before.ownerTokens())||!current.epoch().equals(before.epoch())||!current.playerOverrideEpoch().equals(before.playerOverrideEpoch())||!current.expectedPostHash().equals(plan.expectedPost().get(resource)))yielded.add(resource);
  }
  // A multi-resource inverse is atomic at planning time: a partial ownership loss cannot silently
  // turn a whole-object inverse into destructive cleanup of its remaining resources.
  if(!yielded.isEmpty())return new CleanupPlan(List.of(),yielded,List.of());
  return new CleanupPlan(plan.inversePlan(),Set.of(),List.of());
 }
}
