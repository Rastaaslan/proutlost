package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.permission.PermitResult;
import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.testkit.TestDurableStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Tests the real dispatch boundary with an in-memory physical binding. Not native qualification. */
public final class StabilizationTests {
 static int assertions;
 static UUID id(long n){return new UUID(0,n);}
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);assertions++;}
 static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException|IllegalStateException e){assertions++;return;}throw new AssertionError("expected rejection");}
 static final class Binding implements ServerEffectPort.Binding {
  int writes;boolean stale;boolean fail;boolean foreignToken;boolean foreignHash;boolean releasesClaim;boolean foreignRelease;boolean ghostSuccess;boolean incompleteSuccess;boolean missingPost;boolean wrongPost;boolean changedCapabilities;boolean foreignFactHash;boolean duplicateFact;boolean validFact;boolean emptyConfirmedFact;
  // Advertise PRODUCTION only to exercise the boundary's production dispatch in this test process.
  public ProviderMode mode(){return ProviderMode.PRODUCTION;}
  public Set<PrimitiveId> supportedPrimitives(){return Set.of(new PrimitiveId("proutlost_director:test_stop"));}
  public Set<CapabilityId> capabilities(){return changedCapabilities?Set.of(new CapabilityId("unbound.physical")):Set.of();}
  public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){throw new AssertionError("explicit hydration fixture");}
  public boolean revalidate(PreparedEffect p,BlobBundle b){return !stale;}
  public SliceResult apply(PreparedEffect p,BlobBundle b){
    writes++;if(fail)throw new IllegalStateException("uncertain write");
    if(ghostSuccess)return new SliceResult(Outcome.SUCCEEDED,Set.of(),p.expectedPost(),0,Set.of(),Set.of(),List.of());
    if(incompleteSuccess)return new SliceResult(Outcome.SUCCEEDED,Set.of(p.intent().operationToken()),p.expectedPost(),1,Set.of(),Set.of(),List.of());
    if(missingPost)return new SliceResult(Outcome.SUCCEEDED,Set.of(p.intent().operationToken()),Map.of(),0,Set.of(),Set.of(),List.of());
    if(wrongPost)return new SliceResult(Outcome.SUCCEEDED,Set.of(p.intent().operationToken()),
        Map.of(p.expectedPost().keySet().iterator().next(),new Fingerprint("c".repeat(64))),0,Set.of(),Set.of(),List.of());
    if(foreignToken)return new SliceResult(Outcome.SUCCEEDED,Set.of(new OperationToken(id(999))),p.expectedPost(),0,Set.of(),Set.of(),List.of());
    if(foreignHash)return new SliceResult(Outcome.SUCCEEDED,Set.of(p.intent().operationToken()),
        Map.of(new ResourceKey(OwnershipDomain.BLOCK,p.intent().expectedFootprint().dimension(),"foreign"),new Fingerprint("b".repeat(64))),0,Set.of(),Set.of(),List.of());
    Set<ResourceKey> releases=foreignRelease?Set.of(new ResourceKey(OwnershipDomain.BLOCK,p.intent().expectedFootprint().dimension(),"foreign")):
        releasesClaim?p.preconditions().epochs().keySet():Set.of();
    var factId=new FactId(id(700));
    var verified=new FactEvidence(factId,EvidenceStatus.CONFIRMED,p.expectedPost());
    var evidence=foreignFactHash?List.of(new FactEvidence(factId,EvidenceStatus.CONFIRMED,
        Map.of(p.expectedPost().keySet().iterator().next(),new Fingerprint("d".repeat(64))))):
        duplicateFact?List.of(verified,verified):emptyConfirmedFact?List.of(new FactEvidence(factId,EvidenceStatus.CONFIRMED,Map.of())):validFact?List.of(verified):List.<FactEvidence>of();
    return new SliceResult(Outcome.SUCCEEDED,Set.of(p.intent().operationToken()),p.expectedPost(),0,releases,Set.of(),evidence);
  }
  public ReconcileResult reconcile(RecoveryProbe p){throw new AssertionError("not exercised");}
 }
 record Fixture(TestDurableStore store,CommitPermit.Authority authority,CommitPermit impact,PreparedSlice stop,
                AtomicReference<StabilizationLiveSnapshot> live,ServerEffectPort port,Binding binding,List<StabilizationMarker> markers,List<SliceResult> outcomes,PreparedEffect plan,BlobBundle hydrated){}
 static Fixture fixture(HealthMode health){
  var store=new TestDurableStore();var authority=new CommitPermit.Authority(store,4);
  var dim=new DimensionId("minecraft:overworld");var foot=new Footprint(dim,Set.of(new ChunkRef(dim,0,0)),Set.of(new BlockPosition(dim,1,1,1)),List.of());
  var key=new ResourceKey(OwnershipDomain.BLOCK,dim,"1,1,1");var hash=new Fingerprint("a".repeat(64));var epoch=new Epoch(2);
  var read=new ReadSet(Map.of(),Map.of(key,epoch),Map.of(key,hash));
  var owner=new OwnershipRecord(new AggregateHeader(1,id(1),new Revision(2),new Sequence(2),new Tick(2)),key,Set.of(new OwnerToken(id(1))),epoch,OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(id(1)),hash,new Epoch(0),Optional.empty());
  var primitive=new PrimitiveId("proutlost_director:test_stop");var event=new EventInstanceId(id(1));
  var op=new EffectIntent(new OperationToken(id(3)),primitive,1,new TargetParticles("target",new ResourceId("proutlost_director:test"),0,0),foot,event);
  var stop=new PreparedSlice(new SliceId(id(2)),new OperationToken(id(2)),foot,read,List.of(op),BudgetMath.zero());
  var plan=new StabilizationPlan(List.of(new StabilizationSlice(stop,Map.of(key,owner))));var blob=plan.blob();
  var impactSlice=new PreparedSlice(new SliceId(id(1)),new OperationToken(id(1)),foot,read,List.of(),BudgetMath.zero());
  var reservation=new ReservationToken(new ReservationId(id(1)),store.session().worldId(),event,new Epoch(1),new Tick(200),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch)));
  var blobs=new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of());
  var prepare=new PrepareRecord(new TransactionId(id(1)),impactSlice.operationToken(),reservation.generation(),read,new Tick(200),impactSlice,reservation,new ValidationResult(true,List.of(),read),List.of(),Set.of(),List.of(op),blobs,Optional.of(plan));
  var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),Map.of(),List.of(new DurablePrepare(prepare)),List.of(blob),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  var initial=new LiveValidationSnapshot(read,foot.loadedChunks(),HealthMode.NORMAL,new Tick(100),reservation.generation());
  authority.submit(batch,initial);check(authority.poll(batch.transactionId(),initial).status()==PermitResult.Status.PENDING,"requires forced ACK");store.force(batch.transactionId());
  var impact=authority.poll(batch.transactionId(),initial).permit().orElseThrow();impact.consume(authority,impactSlice,initial);store.loseWriter();
  check(store.append(batch).toCompletableFuture().isCompletedExceptionally(),"writer actually unavailable");
  var live=new AtomicReference<>(new StabilizationLiveSnapshot(store.session(),new LiveValidationSnapshot(read,foot.loadedChunks(),health,new Tick(1000),reservation.generation()),new OwnershipView(Map.of(key,owner))));
  var binding=new Binding();var markers=new ArrayList<StabilizationMarker>();var outcomes=new ArrayList<SliceResult>();
  var port=new ServerEffectPort(authority,i->{throw new AssertionError();},s->initial,Map.of(primitive,binding),Set.of(),4,s->live.get(),markers::add,(m,r)->outcomes.add(r));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
  var snapshot=new SnapshotBatch(new RequestId(id(1)),new ScopeSet(store.session().worldId(),Set.of()),new Tick(100),Map.of(),context,Set.of(),foot.loadedChunks(),HealthMode.NORMAL);
  var prepared=new PreparedEffect(op,snapshot,reservation.claims(),read,Map.of(key,hash),BudgetMath.zero(),List.of(),Set.of(),blobs);
  var hydrated=new BlobBundle(List.of(blob));port.hydrate(prepared,hydrated);
  return new Fixture(store,authority,impact,stop,live,port,binding,markers,outcomes,prepared,hydrated);
 }
 static SliceResult stop(Fixture f){return f.port.stabilize(f.stop,f.impact.stabilizationPermit().orElseThrow(),f.live.get());}
 public static void main(String[] args)throws Exception {
  // Real J05 dispatch must not accept an arbitrary primitive ID merely
  // because the same native binding advertises matching capabilities.
  var foreign=fixture(HealthMode.CRITICAL);
  rejects(()->new ServerEffectPort(foreign.authority(),
      i->{throw new AssertionError("no planning in registration");},
      s->{throw new AssertionError("no live capture in registration");},
      Map.of(new PrimitiveId("proutlost_director:foreign_effect"),foreign.binding()),
      Set.of(),4,s->{throw new AssertionError("no stop in registration");},
      marker->{},(marker,outcome)->{}));
  check(foreign.binding().writes==0,"foreign registration cannot perform physical effects");
  for(var health:List.of(HealthMode.CRITICAL,HealthMode.READ_ONLY_SAFE)){
   var f=fixture(health);check(stop(f).outcome()==Outcome.SUCCEEDED,"stop after spent impact, expiry and lost writer");
   check(f.binding.writes==1&&f.markers.size()==1&&f.outcomes.size()==1,"one local marker and outcome");rejects(()->stop(f));check(f.binding.writes==1,"replay no write");
  }
  var f=fixture(HealthMode.CRITICAL);var original=f.live.get();
  var other=new PreparedSlice(new SliceId(id(99)),f.stop.operationToken(),f.stop.footprint(),f.stop.preconditions(),f.stop.operations(),f.stop.maximumWork());
  rejects(()->f.port.stabilize(other,f.impact.stabilizationPermit().orElseThrow(),original));
  var op=f.stop.operations().getFirst();var substituted=new EffectIntent(new OperationToken(id(98)),op.primitive(),op.version(),op.typedArgs(),op.expectedFootprint(),op.instanceId());
  rejects(()->f.port.stabilize(new PreparedSlice(f.stop.sliceId(),f.stop.operationToken(),f.stop.footprint(),f.stop.preconditions(),List.of(substituted),f.stop.maximumWork()),f.impact.stabilizationPermit().orElseThrow(),original));
  var owner=original.ownership().records().values().iterator().next();
  var ceded=new OwnershipRecord(owner.header(),owner.resourceKey(),owner.ownerTokens(),owner.epoch(),OwnershipDisposition.YIELDED_TO_PLAYER,owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch().plus(1),Optional.empty());
  f.live.set(new StabilizationLiveSnapshot(original.session(),original.validation(),new OwnershipView(Map.of(owner.resourceKey(),ceded))));
  rejects(()->stop(f));rejects(()->f.port.stabilize(f.stop,f.impact.stabilizationPermit().orElseThrow(),original));check(f.binding.writes==0&&f.markers.isEmpty(),"stale ownership never writes");
  f.live.set(original);f.binding.stale=true;rejects(()->stop(f));f.binding.stale=false;check(stop(f).outcome()==Outcome.SUCCEEDED,"rejected validation does not spend stop");
  var uncertain=fixture(HealthMode.CRITICAL);uncertain.binding.fail=true;rejects(()->stop(uncertain));rejects(()->stop(uncertain));check(uncertain.binding.writes==1&&uncertain.markers.size()==1&&uncertain.outcomes.isEmpty(),"ambiguous write cannot replay");
  // Even a production-tagged binding cannot emit another operation's applied
  // token or another resource's hash after the J04 permit has been consumed.
  var forgedToken=fixture(HealthMode.CRITICAL);forgedToken.binding.foreignToken=true;
  rejects(()->stop(forgedToken));rejects(()->stop(forgedToken));
  check(forgedToken.binding.writes==1&&forgedToken.outcomes.isEmpty(),"foreign operation outcome refuses ACK/replay");
  var forgedHash=fixture(HealthMode.CRITICAL);forgedHash.binding.foreignHash=true;
  rejects(()->stop(forgedHash));rejects(()->stop(forgedHash));
  check(forgedHash.binding.writes==1&&forgedHash.outcomes.isEmpty(),"foreign resource outcome refuses ACK/replay");
  var released=fixture(HealthMode.CRITICAL);released.binding.releasesClaim=true;
  var actualRelease=stop(released);var expectedRelease=released.stop.preconditions().epochs().keySet();
  check(actualRelease.releasedClaims().equals(expectedRelease),"physical released claims preserved in returned slice");
  check(released.outcomes.size()==1&&released.outcomes.getFirst().releasedClaims().equals(expectedRelease),"world-local stabilization records exact release");
  var forgedRelease=fixture(HealthMode.CRITICAL);forgedRelease.binding.foreignRelease=true;
  rejects(()->stop(forgedRelease));rejects(()->stop(forgedRelease));
  check(forgedRelease.binding.writes==1&&forgedRelease.outcomes.isEmpty(),"foreign released claim refuses ACK/replay");
  var ghost=fixture(HealthMode.CRITICAL);ghost.binding.ghostSuccess=true;
  rejects(()->stop(ghost));rejects(()->stop(ghost));
  check(ghost.binding.writes==1&&ghost.outcomes.isEmpty(),"successful receipt without applied token never ACKs");
  var incomplete=fixture(HealthMode.CRITICAL);incomplete.binding.incompleteSuccess=true;
  rejects(()->stop(incomplete));rejects(()->stop(incomplete));
  check(incomplete.binding.writes==1&&incomplete.outcomes.isEmpty(),"successful receipt with remaining work never ACKs");
  var absentPost=fixture(HealthMode.CRITICAL);absentPost.binding.missingPost=true;
  rejects(()->stop(absentPost));rejects(()->stop(absentPost));
  check(absentPost.binding.writes==1&&absentPost.outcomes.isEmpty(),"missing durable post-state refuses stop ACK/replay");
  var falsePost=fixture(HealthMode.CRITICAL);falsePost.binding.wrongPost=true;
  rejects(()->stop(falsePost));rejects(()->stop(falsePost));
  check(falsePost.binding.writes==1&&falsePost.outcomes.isEmpty(),"foreign durable post-state refuses stop ACK/replay");
  var validEvidence=fixture(HealthMode.CRITICAL);validEvidence.binding.validFact=true;
  check(stop(validEvidence).factEvidence().size()==1,"confirmed physical evidence matches observed outcome");
  var forgedEvidence=fixture(HealthMode.CRITICAL);forgedEvidence.binding.foreignFactHash=true;
  rejects(()->stop(forgedEvidence));rejects(()->stop(forgedEvidence));
  check(forgedEvidence.binding.writes==1&&forgedEvidence.outcomes.isEmpty(),"foreign confirmed fact cannot escape physical boundary");
  var emptyEvidence=fixture(HealthMode.CRITICAL);emptyEvidence.binding.emptyConfirmedFact=true;
  rejects(()->stop(emptyEvidence));rejects(()->stop(emptyEvidence));
  check(emptyEvidence.binding.writes==1&&emptyEvidence.outcomes.isEmpty(),"zero-observation CONFIRMED fact cannot fabricate physical success");
  var duplicateEvidence=fixture(HealthMode.CRITICAL);duplicateEvidence.binding.duplicateFact=true;
  rejects(()->stop(duplicateEvidence));rejects(()->stop(duplicateEvidence));
  check(duplicateEvidence.binding.writes==1&&duplicateEvidence.outcomes.isEmpty(),"duplicate physical fact cannot be ACKed");
  var revoked=fixture(HealthMode.CRITICAL);revoked.binding.changedCapabilities=true;
  rejects(()->stop(revoked));
  check(revoked.binding.writes==0&&revoked.markers.isEmpty(),"capability drift rejected before permit consumption");
  revoked.binding.changedCapabilities=false;
  check(stop(revoked).outcome()==Outcome.SUCCEEDED,"capability drift refusal preserves original permit");
  var overlapping=fixture(HealthMode.CRITICAL);
  var first=overlapping.stop.operations().getFirst();
  var second=new EffectIntent(new OperationToken(id(44)),first.primitive(),first.version(),first.typedArgs(),first.expectedFootprint(),first.instanceId());
  var originalPlan=overlapping.plan;
  overlapping.port.hydrate(new PreparedEffect(second,originalPlan.snapshot(),originalPlan.claims(),originalPlan.preconditions(),
      originalPlan.expectedPost(),originalPlan.budgetEstimate(),originalPlan.inversePlan(),originalPlan.capabilities(),originalPlan.durableBlobs()),overlapping.hydrated);
  var colliding=new PreparedSlice(overlapping.stop.sliceId(),overlapping.stop.operationToken(),overlapping.stop.footprint(),
      overlapping.stop.preconditions(),List.of(first,second),overlapping.stop.maximumWork());
  rejects(()->overlapping.port.stabilize(colliding,overlapping.impact.stabilizationPermit().orElseThrow(),overlapping.live.get()));
  check(overlapping.binding.writes==0&&overlapping.markers.isEmpty(),"overlapping post-state rejected before durable permit consumption");
  check(stop(overlapping).outcome()==Outcome.SUCCEEDED,"collision rejection preserves original prepared stop permit");
  // The durable PREPARE names exact blob digests, not a subset. A valid
  // extra blob would otherwise be available to a physical binding without
  // any WAL record authorizing its bytes.
  var exact=fixture(HealthMode.CRITICAL);
  exact.port.releaseHydrated(exact.plan.intent().operationToken());
  rejects(()->exact.port.hydrate(exact.plan,new BlobBundle(List.of())));
  var unjournaled=BlobContent.capture(new ResourceId("proutlost_director:foreign"),1,new byte[]{1,2,3});
  var extras=new ArrayList<BlobContent>(exact.hydrated.contents());extras.add(unjournaled);
  rejects(()->exact.port.hydrate(exact.plan,new BlobBundle(extras)));
  rejects(()->stop(exact));
  check(exact.binding.writes==0&&exact.markers.isEmpty(),"unjournaled bytes cannot spend physical permit");
  exact.port.hydrate(exact.plan,exact.hydrated);
  check(stop(exact).outcome()==Outcome.SUCCEEDED,"exact J04 PREPARE hydration remains applicable");
  var misbound=fixture(HealthMode.CRITICAL);
  misbound.port.releaseHydrated(misbound.plan.intent().operationToken());
  var wrongCapability=new PreparedEffect(misbound.plan.intent(),misbound.plan.snapshot(),
      misbound.plan.claims(),misbound.plan.preconditions(),misbound.plan.expectedPost(),
      misbound.plan.budgetEstimate(),misbound.plan.inversePlan(),
      Set.of(new CapabilityId("unbound.physical")),misbound.plan.durableBlobs());
  rejects(()->misbound.port.hydrate(wrongCapability,misbound.hydrated));
  rejects(()->stop(misbound));
  check(misbound.binding.writes==0&&misbound.markers.isEmpty(),"mismatched PREPARE capabilities never spend permit");
  misbound.port.hydrate(misbound.plan,misbound.hydrated);
  check(stop(misbound).outcome()==Outcome.SUCCEEDED,"restored exact capabilities can still stabilize");
  var actor=fixture(HealthMode.CRITICAL);var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try{stop(actor);}catch(Throwable e){failure.set(e);}});thread.start();thread.join();check(failure.get() instanceof IllegalStateException&&actor.binding.writes==0,"worker has no world authority");
  System.out.println("PASS J05 stabilization dispatch assertions="+assertions+"; in-memory binding, native/disk NOT_RUN");
 }
}
