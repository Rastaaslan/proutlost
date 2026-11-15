package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure runtime fixtures, not native transport receipts or human/client qualification. */
final class DisasterWarningPublicationTests {
 private static int assertions;
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);assertions++;}
 private static void rejects(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError(message);}
 private static UUID id(long value){return new UUID(0,value);}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static byte[] proof(DisasterInstance state,StoreSession session,int ttl,int corners,boolean received,long bootDelta){
  var bounds=state.envelope().bounds().getFirst();var receipts=new ArrayList<JsonValue>();
  var names=List.of("NW","NE","SE","SW");
  for(int index=0;index<corners;index++){
   var corner=names.get(index);boolean east=corner.equals("NE")||corner.equals("SE"),south=corner.equals("SW")||corner.equals("SE");
   var header=new PerceptionHeader(1,session.worldId(),session.bootEpoch().plus(bootDelta),new Epoch(1),state.envelope().dimension(),state.eventInstanceId(),new EffectId(id(1800+index)),new Sequence(index+1),new DurationTicks(ttl));
   var transform=new PerceptionTransform(east?bounds.maxXExclusive()-.25:bounds.minX()+.25,bounds.minY()+.8,south?bounds.maxZExclusive()-.25:bounds.minZ()+.25,0,0,1);
   receipts.add(new JsonValue.ObjectValue(Map.of("player",text(RuntimeLifecycleTests.PLAYER.value()),"marker",text(corner),"received",new JsonValue.Bool(received),"header",text(Base64.getEncoder().encodeToString(new ModelCodec<>(PerceptionHeader.class).encode(header))),"transform",text(Base64.getEncoder().encodeToString(new ModelCodec<>(PerceptionTransform.class).encode(transform))),"sent_tick",text(11))));
  }
  var fields=new TreeMap<String,JsonValue>();fields.put("schema",text(1));fields.put("world",text(session.worldId().value()));fields.put("boot",text(session.bootEpoch().value()));fields.put("disaster",text(state.disasterId().value()));fields.put("event",text(state.eventInstanceId().value()));fields.put("warn_entered",text(10));fields.put("deployed",text(12));fields.put("lead",text(5));fields.put("audience_revision",text(1));fields.put("envelope_hash",text(BlobContent.hash(new ModelCodec<>(Footprint.class).encode(state.envelope())).value()));fields.put("receipts",new JsonValue.Array(receipts));return CanonicalJsonV1.encode(new JsonValue.ObjectValue(fields));
 }
 private static WarningEvidence evidence(byte[] proof){return new WarningEvidence(Set.of(RuntimeLifecycleTests.PLAYER),new Tick(12),new DurationTicks(5),BlobContent.hash(proof),new Epoch(1),true,true);}
 static int run(){
  assertions=0;var store=new RuntimeLifecycleTests.Store();var runner=new DurableDisasterRunner(store,2,d->new ScopeRef(ScopeKind.EVENT,d.header().id()));
  var footprint=new Footprint(RuntimeLifecycleTests.DIM,RuntimeLifecycleTests.FOOT.loadedChunks(),Set.of(),List.of(new Aabb(RuntimeLifecycleTests.DIM,0,0,0,2,2,2)));
  var initialWarning=new WarningEvidence(Set.of(RuntimeLifecycleTests.PLAYER),new Tick(1),new DurationTicks(5),RuntimeLifecycleTests.BLOB.reference().hash(),new Epoch(1),true,true);
  var initial=new DisasterInstance(new AggregateHeader(1,id(1700),new Revision(1),new Sequence(1),new Tick(1)),new DisasterId(id(1700)),RuntimeLifecycleTests.EVENT,new DefinitionId("proutlost:test_disaster"),DisasterStage.WATCH,Map.of(),new UnitValue(.9),footprint,initialWarning,Set.of(RuntimeLifecycleTests.PLAYER),List.of(),RuntimeLifecycleTests.BLOB.reference(),Set.of(),"idle",Set.of());
  runner.restore(initial);var warnTx=new TransactionId(id(1701));runner.propose(initial.disasterId(),warnTx,new UnitValue(.5),new DisasterRunner.Conditions(new Tick(10),HealthMode.NORMAL,Set.of(RuntimeLifecycleTests.PLAYER),new Epoch(1),true,false,false));
  check(runner.view(initial.disasterId()).stage()==DisasterStage.WATCH,"WARN remains unpublished before ACK");store.ackSequence(warnTx,new Sequence(2));runner.poll(initial.disasterId());
  check(runner.warnEnteredAt(initial.disasterId()).equals(Optional.of(new Tick(10))),"original durable WARN entry retained");
  check(store.batches.get(warnTx).records().stream().anyMatch(r->r instanceof DurableFact f&&f.value().factType().equals(DurableDisasterRunner.WARN_ENTRY)),"WARN entry journaled atomically with phase");
  var state=runner.view(initial.disasterId());var session=store.session();
  for(var invalid:List.of(proof(state,session,100,3,true,0),proof(state,session,100,4,false,0),proof(state,session,1,4,true,0),proof(state,session,100,4,true,1))){
   int before=store.batches.size();rejects(()->runner.publishWarning(state.disasterId(),new TransactionId(UUID.randomUUID()),evidence(invalid),invalid,new Tick(12)),"incomplete/unreceived/expired/foreign-boot proof rejected");check(store.batches.size()==before,"invalid proof never journals a warning revision");
  }
  var bytes=proof(state,session,100,4,true,0);var verified=evidence(bytes);var publishTx=new TransactionId(id(1702));runner.publishWarning(state.disasterId(),publishTx,verified,bytes,new Tick(12));
  check(runner.poll(state.disasterId()).status()==DurableDisasterRunner.Status.PENDING&&runner.view(state.disasterId()).warningEvidence().equals(initialWarning),"new evidence waits for exact durable ACK");
  rejects(()->runner.propose(state.disasterId(),new TransactionId(id(1703)),new UnitValue(.5),new DisasterRunner.Conditions(new Tick(17),HealthMode.NORMAL,Set.of(RuntimeLifecycleTests.PLAYER),new Epoch(1),true,false,false)),"pending warning cannot authorize IMPACT");
  store.ackSequence(publishTx,new Sequence(3));runner.poll(state.disasterId());
  check(runner.view(state.disasterId()).header().updatedTick().equals(new Tick(12))&&runner.warnEnteredAt(state.disasterId()).equals(Optional.of(new Tick(10))),"proof revision does not move WARN entry forward");
  check(store.batches.get(publishTx).records().stream().anyMatch(r->r instanceof DurableFact f&&f.value().factType().equals(DurableDisasterRunner.WARN_PROOF)),"full inline proof persists with evidence aggregate");
  rejects(()->runner.propose(state.disasterId(),new TransactionId(id(1704)),new UnitValue(.5),new DisasterRunner.Conditions(new Tick(16),HealthMode.NORMAL,Set.of(RuntimeLifecycleTests.PLAYER),new Epoch(1),true,false,false)),"full post-publication lead must elapse");
  var restored=new DurableDisasterRunner(new RuntimeLifecycleTests.Store(),2,d->new ScopeRef(ScopeKind.EVENT,d.header().id()));restored.restore(runner.view(state.disasterId()),new Tick(10));
  rejects(()->restored.propose(state.disasterId(),new TransactionId(id(1705)),new UnitValue(.5),new DisasterRunner.Conditions(new Tick(17),HealthMode.NORMAL,Set.of(RuntimeLifecycleTests.PLAYER),new Epoch(1),true,false,false)),"restored WARN needs a newly committed current-boot proof");
  var impactTx=new TransactionId(id(1706));runner.propose(state.disasterId(),impactTx,new UnitValue(.5),new DisasterRunner.Conditions(new Tick(17),HealthMode.NORMAL,Set.of(RuntimeLifecycleTests.PLAYER),new Epoch(1),true,false,false));
  check(runner.view(state.disasterId()).stage()==DisasterStage.WARN,"valid proof is not a physical or phase permit before ACK");store.ackSequence(impactTx,new Sequence(4));check(runner.poll(state.disasterId()).state().stage()==DisasterStage.IMPACT,"fresh same-WARN evidence can finally publish IMPACT after full lead and exact ACK");
  return assertions;
 }
}
