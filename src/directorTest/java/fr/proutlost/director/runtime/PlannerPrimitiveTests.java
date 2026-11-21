package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Concrete pure weather planning/lifecycle rules, not a native weather lease qualification. */
public final class PlannerPrimitiveTests {
 static int assertions;
 static UUID id(long n){return new UUID(0,n);}
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);assertions++;}
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException|IllegalStateException e){assertions++;return;}throw new AssertionError("expected rejection");}
 public static void main(String[] args){
  var dim=new DimensionId("minecraft:overworld");var world=new WorldId(id(1));var primitiveId=new PrimitiveId("proutlost:global_weather_lease");
  var descriptor=new PrimitiveDescriptor(primitiveId,1,new ResourceId("proutlost:global_weather_lease"),Set.of(new CapabilityId("weather.global_lease_v1")),BudgetMath.zero(),new ResourceId("proutlost:dimension"),Set.of(new ResourceId("proutlost_director:weather_state")),Set.of(OwnershipDomain.WEATHER),Set.of(),new DurationTicks(200),PausePolicy.CLEANUP,RecoveryMode.RECOVER,new ResourceId("proutlost:weather_restore"),Optional.of(primitiveId),Optional.empty(),ProviderMode.PRODUCTION);
  var bytes=BlobContent.capture(new ResourceId("proutlost_director:weather_state"),1,ByteBuffer.allocate(14).putInt(5).putInt(10).putInt(20).put((byte)0).put((byte)0).array());
  var resource=new ResourceKey(OwnershipDomain.WEATHER,dim,"weather");
  var owner=new OwnershipRecord(new AggregateHeader(1,id(2),new Revision(1),new Sequence(1),new Tick(1)),resource,Set.of(new OwnerToken(id(3))),new Epoch(2),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(id(4)),bytes.reference().hash(),new Epoch(0),Optional.empty());
  var foot=new Footprint(dim,Set.of(new ChunkRef(dim,0,0)),Set.of(),List.of());var scope=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
  var snapshot=new SnapshotBatch(new RequestId(id(5)),scope,new Tick(1),Map.of(),context,Set.of(),foot.loadedChunks(),HealthMode.NORMAL);
  var bindings=new PlanningBindings(new CandidateId(id(6)),new DefinitionRef(new DefinitionId("proutlost:test"),new Revision(1),bytes.reference().hash()),new EventInstanceId(id(7)),new OperationToken(id(8)),"weather",new RoleAssignments(Map.of()),scope,foot,Set.of(),true);
  var budget=new BudgetVector(100,100,100,100,100,100,100000,100,100000,100);
  var planning=new PlanningSnapshot(snapshot,new OwnershipView(Map.of(resource,owner)),new BudgetView(budget,BudgetMath.zero(),Map.of()),new BlobBundle(List.of(bytes)),Optional.of(bindings));
  var hydrated=new AtomicReference<BlobBundle>();var fixture=RuntimeLifecycleTests.fixture();
  var primitive=new GlobalWeatherPrimitive(descriptor,fixture.effects(),p->new PreparedSlice(new SliceId(p.intent().operationToken().value()),p.intent().operationToken(),p.intent().expectedFootprint(),p.preconditions(),List.of(p.intent()),p.budgetEstimate()),p->hydrated.get());
  var weather=new GlobalWeatherLease(dim,new ResourceId("minecraft:thunder"),new DurationTicks(100),new Revision(1));
  var plan=primitive.plan(weather,planning);hydrated.set(primitive.materialize(weather,planning,plan));
  check(plan.equals(primitive.plan(weather,planning)),"pure deterministic weather plan");
  check(hydrated.get().contents().stream().map(BlobContent::reference).toList().containsAll(fr.proutlost.director.api.permission.CommitPermit.Authority.references(plan.durableBlobs())),"materialized pre/post/ownership bytes complete");
  var post=hydrated.get().contents().stream().filter(b->b.reference().equals(plan.durableBlobs().preparedPlan())).findFirst().orElseThrow();
  check(Arrays.equals(post.bytes(),ByteBuffer.allocate(14).putInt(0).putInt(100).putInt(100).put((byte)1).put((byte)1).array()),"native thunder state is exact");
  check(plan.intent().instanceId().equals(bindings.instanceId())&&plan.snapshot().equals(snapshot),"real event identity and full snapshot retained");
  var live=new LiveValidationSnapshot(plan.preconditions(),foot.loadedChunks(),HealthMode.NORMAL,new Tick(2),new Epoch(1));
  check(primitive.validate(plan,live).valid(),"fresh validation");
  check(!primitive.validate(plan,new LiveValidationSnapshot(plan.preconditions(),Set.of(),HealthMode.NORMAL,new Tick(2),new Epoch(1))).valid(),"unloaded weather footprint rejected");
  var current=new OwnershipRecord(owner.header(),resource,owner.ownerTokens(),owner.epoch(),owner.disposition(),owner.creationMutationId(),post.reference().hash(),owner.playerOverrideEpoch(),owner.exceptionContractId());
  check(primitive.cleanup(plan,CleanupReason.COMPLETED,new OwnershipView(Map.of(resource,current))).operations().equals(plan.inversePlan()),"owned exact post authorizes inverse proposal");
  var ceded=new OwnershipRecord(current.header(),resource,current.ownerTokens(),current.epoch(),OwnershipDisposition.YIELDED_TO_PLAYER,current.creationMutationId(),current.expectedPostHash(),new Epoch(1),Optional.empty());
  var cleanup=primitive.cleanup(plan,CleanupReason.COMPLETED,new OwnershipView(Map.of(resource,ceded)));
  check(cleanup.operations().isEmpty()&&cleanup.yieldedResources().contains(resource),"cession prohibits rollback");
  rejects(()->primitive.plan(new GlobalWeatherLease(dim,new ResourceId("minecraft:thunder"),new DurationTicks(201),new Revision(1)),planning));
  rejects(()->primitive.plan(new GlobalWeatherLease(dim,new ResourceId("example:unregistered"),new DurationTicks(100),new Revision(1)),planning));
  rejects(()->primitive.applySlice(plan,new EffectCursor(1,0),null));
  var registry=new PrimitivePlanningService(List.of(new PrimitivePlanningService.Registration<>(GlobalWeatherLease.class,primitive,Set.of(SensorKind.OWNERSHIP,SensorKind.HEALTH))),ProviderMode.PRODUCTION);
  var step=new EventStep("weather",List.of(),new DurationTicks(0),primitiveId,weather,OnFailure.ABORT_SAFE);
  check(registry.plan(new PlanningRequest(step,planning)) instanceof PlannedPrimitive,"PlanningPort reaches concrete production primitive");
  var unbound=new PlanningSnapshot(snapshot,planning.ownership(),planning.budgets(),planning.blobs());
  check(registry.plan(new PlanningRequest(step,unbound)) instanceof DeferredPlanning,"missing identity defers with observations");
  System.out.println("PASS J05 concrete weather/planner assertions="+assertions+"; native weather NOT_RUN");
 }
}
