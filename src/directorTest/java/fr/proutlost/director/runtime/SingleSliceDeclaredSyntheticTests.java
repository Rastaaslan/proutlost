package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Declared synthetic contracts only. No Minecraft, native effect, or durable ACK. */
public final class SingleSliceDeclaredSyntheticTests {
 private static int checks;
 private static final ResourceId ID=new ResourceId("proutlost:declared_synthetic_contract");
 private static final PrimitiveId PRIMITIVE=new PrimitiveId(ID.value());
 private static final DimensionId DIM=new DimensionId("minecraft:overworld");
 private static final BudgetVector ZERO=BudgetMath.zero();
 private static final class Effects implements WorldEffectPort {
  int calls;RecoveryProbe observed;
  public EffectPreparation prepare(EffectIntent intent){throw new AssertionError("no planning effect authority");}
  public SliceResult apply(PreparedSlice slice,CommitPermit permit){if(permit==null)throw new IllegalStateException("real permit still required");calls++;throw new AssertionError("test never supplies an execution permit");}
  public SliceResult stabilize(PreparedSlice slice,CommitPermit.StabilizationPermit permit,StabilizationLiveSnapshot live){throw new AssertionError("no stabilization authority");}
  public ReconcileResult reconcile(RecoveryProbe probe){observed=probe;return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
  public CleanupResult cleanup(CleanupIntent intent,CommitPermit permit){throw new AssertionError("no cleanup effect authority");}
 }
 private static final class Primitive extends SingleSlicePrimitive<PrimitiveArgs> {
  Primitive(PrimitiveDescriptor descriptor,Effects effects){super(descriptor,effects,SingleSliceDeclaredSyntheticTests::slice,p->new BlobBundle(List.of()));}
  Primitive(PrimitiveDescriptor descriptor,Effects effects,DescriptorProvenance provenance){super(descriptor,effects,SingleSliceDeclaredSyntheticTests::slice,p->new BlobBundle(List.of()),provenance);}
  public PreparedEffect plan(PrimitiveArgs args,PlanningSnapshot snapshot){throw new AssertionError("no fabricated native planning");}
 }
 private static PrimitiveDescriptor descriptor(ProviderMode mode){return new PrimitiveDescriptor(PRIMITIVE,1,ID,Set.of(),ZERO,ID,Set.of(),Set.of(),Set.of(),new DurationTicks(100),PausePolicy.CLEANUP,RecoveryMode.RECOVER,ID,Optional.empty(),Optional.empty(),mode);}
 private static PreparedSlice slice(PreparedEffect plan){return new PreparedSlice(new SliceId(plan.intent().operationToken().value()),plan.intent().operationToken(),plan.intent().expectedFootprint(),plan.preconditions(),List.of(plan.intent()),plan.budgetEstimate());}
 private static PreparedEffect plan(){
  var world=new WorldId(new UUID(0,1));var footprint=new Footprint(DIM,Set.of(new ChunkRef(DIM,0,0)),Set.of(),List.of());
  var observation=new SnapshotBatch(new RequestId(new UUID(0,2)),new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),new Tick(1),Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  var intent=new EffectIntent(new OperationToken(new UUID(0,3)),PRIMITIVE,1,new Trace(new TagId("proutlost:before"),new TagId("proutlost:after"),true),footprint,new EventInstanceId(new UUID(0,4)));
  var blob=BlobContent.capture(ID,1,new byte[]{1});var blobs=new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of());
  return new PreparedEffect(intent,observation,List.of(),new ReadSet(Map.of(),Map.of(),Map.of()),Map.of(),ZERO,List.of(),Set.of(),blobs);
 }
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 private static void rejects(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException|NullPointerException expected){checks++;return;}throw new AssertionError(message);}
 public static void main(String[] args){
  var effects=new Effects();var production=descriptor(ProviderMode.PRODUCTION);var synthetic=descriptor(ProviderMode.TEST_ONLY);
  rejects(()->new Primitive(synthetic,effects),"default production constructor must still reject TEST_ONLY");
  check(new Primitive(production,effects).descriptor().equals(production),"default PRODUCTION remains valid");
  rejects(()->new Primitive(synthetic,effects,SingleSlicePrimitive.DescriptorProvenance.PRODUCTION),"explicit production rejects TEST_ONLY");
  rejects(()->new Primitive(production,effects,SingleSlicePrimitive.DescriptorProvenance.DECLARED_SYNTHETIC_TEST_ONLY),"synthetic contract rejects relabelled production descriptor");
  rejects(()->new Primitive(synthetic,effects,null),"missing provenance rejected");
  var qa=new Primitive(synthetic,effects,SingleSlicePrimitive.DescriptorProvenance.DECLARED_SYNTHETIC_TEST_ONLY);
  check(qa.descriptor().equals(synthetic)&&qa.descriptor().mode()==ProviderMode.TEST_ONLY,"same descriptor remains explicitly TEST_ONLY");
  check(effects.calls==0,"construction grants no effects or permit");
  rejects(()->new PrimitivePlanningService(List.of(new PrimitivePlanningService.Registration<>(PrimitiveArgs.class,qa,Set.of())),ProviderMode.PRODUCTION),"QA primitive cannot enter PRODUCTION planning registry");
  new PrimitivePlanningService(List.of(new PrimitivePlanningService.Registration<>(PrimitiveArgs.class,qa,Set.of())),ProviderMode.TEST_ONLY);checks++;
  var prepared=plan();var live=new LiveValidationSnapshot(prepared.preconditions(),prepared.intent().expectedFootprint().loadedChunks(),HealthMode.NORMAL,new Tick(2),new Epoch(1));
  check(qa.validate(prepared,live).valid(),"same final shared validation admits fresh loaded observation");
  check(!qa.validate(prepared,new LiveValidationSnapshot(prepared.preconditions(),Set.of(),HealthMode.NORMAL,new Tick(2),new Epoch(1))).valid(),"QA retains unloaded-chunk rejection");
  check(!qa.validate(prepared,new LiveValidationSnapshot(prepared.preconditions(),live.loadedChunks(),HealthMode.READ_ONLY_SAFE,new Tick(2),new Epoch(1))).valid(),"QA retains health rejection");
  check(!qa.validate(prepared,new LiveValidationSnapshot(new ReadSet(Map.of(new ScopeRef(ScopeKind.WORLD,new UUID(0,1)),new Revision(9)),Map.of(),Map.of()),live.loadedChunks(),HealthMode.NORMAL,new Tick(2),new Epoch(1))).valid(),"QA retains stale read-set rejection");
  rejects(()->qa.applySlice(prepared,new EffectCursor(1,0),null),"single-tranche cursor remains strict");
  rejects(()->qa.applySlice(prepared,new EffectCursor(0,1),null),"explicit QA contract does not manufacture an execution permit");
  check(effects.calls==0,"no mutation performed in negative tests");
  var resource=new ResourceKey(OwnershipDomain.ANCHOR,DIM,"synthetic-only");var evidence=new RecoveryEvidence(resource,EvidenceStatus.UNKNOWN,Optional.empty(),Optional.empty(),new Epoch(0),new Epoch(0),false);
  check(qa.reconcile(prepared,evidence).status()==ReconcileStatus.AMBIGUOUS&&effects.observed.durableIntent().equals(prepared),"same reconcile delegates actual captured plan without claiming recovery success");
  check(qa.cleanup(prepared,CleanupReason.COMPLETED,new OwnershipView(Map.of())).operations().equals(prepared.inversePlan()),"same final cleanup returns proposal only");
  System.out.println("PASS SingleSlice declared synthetic contracts checks="+checks+"; native Minecraft/effects NOT_RUN");
 }
}
