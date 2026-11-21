package fr.proutlost.director.integration.quest;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.*;
/** Pure TEST_ONLY regression for an actual scheduler-only aggregate update.
 * Uses the existing logical J08 ports; never claims disk, Minecraft or human qualification.
 */
public final class J08ScheduledAggregateRestoreTests {
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(5,TimeUnit.SECONDS);}
 private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 private static DirectorRequestState scheduled(DirectorRequestState before,RequestStatus status){
  var h=before.header();return new DirectorRequestState(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),new Tick(200)),
   before.requestId(),before.producerId(),before.producerKey(),before.kind(),before.selector(),before.scope(),before.constraints(),before.fulfillment(),
   status,new DurationTicks(17),before.expiresTick(),before.resultFactIds(),List.of(),before.schedulingPolicy());
 }
 public static int run()throws Exception{
  var executor=Executors.newSingleThreadExecutor();try{
   var store=new J08Tests.MemoryStore();var bus=new J08Tests.PullBus(store.session);var tick=new java.util.concurrent.atomic.AtomicLong(100);
   var policy=new RequestSchedulingPolicy(new NarrativePriority(7),PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.AUTHORIZED_ADAPTER);
   var adapter=new DurableQuestAdapter(store,bus,J08Tests.PRODUCER,executor,tick::get,32,.5);await(adapter.restore());
   var id=new RequestId(UUID.randomUUID());var original=J08Tests.request(id,"scheduler-only",FulfillmentMode.NEW_EXECUTION);
   await(adapter.submit(original,policy));var accepted=adapter.inspect(id);
   var selected=scheduled(accepted.state(),RequestStatus.SCHEDULED);
   // The engine journals only RequestAggregate: deliberately NO new DurableRequest here.
   await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
     List.of(new DurableAggregate(new RequestAggregate(selected))),List.of())));
   var restored=new DurableQuestAdapter(store,bus,J08Tests.PRODUCER,executor,tick::get,32,.5);await(restored.restore());
   var current=restored.inspect(id);
   check(current.request().equals(original),"original producer envelope survives scheduler-only state");
   check(current.state().equals(selected)&&current.revision().value()==2,"latest durable SCHEDULED aggregate is restored");
   check(current.state().schedulingPolicy().equals(policy)&&current.state().eligibleWaitTicks().value()==17,"trusted policy and proven eligible ticks preserved");
   int before=store.records.size();var event=new EventInstanceId(UUID.randomUUID());
   var binding=await(restored.bindExecution(id,current.revision(),event));
   check(binding.state()==RequestStatus.SCHEDULED&&binding.revision().value()==2&&restored.boundExecution(id,event),"binding publishes only after its durable receipt");
   var delta=List.copyOf(store.records.subList(before,store.records.size()));
   check(delta.stream().noneMatch(r->r instanceof DurableRequest||r instanceof DurableAggregate a&&a.state() instanceof RequestAggregate),
    "binding an already SCHEDULED request must not duplicate the same request revision");
   var completion=J08Tests.fact(event,EvidenceStatus.CONFIRMED);
   var fulfilled=await(restored.acceptFact(id,completion));
   check(fulfilled.state()==RequestStatus.FULFILLED&&fulfilled.revision().value()==3,"new bound event fulfills at the next request revision");
   check(fulfilled.factRefs().equals(Set.of(((FactPayload)completion.payload()).value().factId())),"actual accepted fact identity retained");
   var cold=new DurableQuestAdapter(store,bus,J08Tests.PRODUCER,executor,tick::get,32,.5);await(cold.restore());
   check(cold.inspect(id).state().status()==RequestStatus.FULFILLED&&cold.inspect(id).request().equals(original),"fold survives a second restore after fulfillment");
   var conflict=new J08Tests.MemoryStore();conflict.records.addAll(store.records);
   var wrong=new DirectorRequestState(selected.header(),selected.requestId(),selected.producerId(),selected.producerKey(),selected.kind(),selected.selector(),
     selected.scope(),selected.constraints(),selected.fulfillment(),RequestStatus.WAITING_CONTEXT,selected.eligibleWaitTicks(),selected.expiresTick(),
     selected.resultFactIds(),selected.lastReasonCodes(),selected.schedulingPolicy());
   conflict.records.add(new DurableAggregate(new RequestAggregate(wrong)));
   var rejected=new DurableQuestAdapter(conflict,new J08Tests.PullBus(conflict.session),J08Tests.PRODUCER,executor,tick::get,32,.5);
   try{await(rejected.restore());throw new AssertionError("conflicting old RequestAggregate revision accepted");}
   catch(ExecutionException expected){check(expected.getCause() instanceof IllegalArgumentException,"same-revision conflict fails closed");}
   // A real proven abort defers the obligation without fulfilling it or replaying the old event.
   var retryStore=new J08Tests.MemoryStore();var retryBus=new J08Tests.PullBus(retryStore.session);
   var retry=new DurableQuestAdapter(retryStore,retryBus,J08Tests.PRODUCER,executor,tick::get,32,.5);await(retry.restore());
   var retryId=new RequestId(UUID.randomUUID());await(retry.submit(J08Tests.request(retryId,"abort-retry",FulfillmentMode.NEW_EXECUTION),policy));
   var oldEvent=new EventInstanceId(UUID.randomUUID());await(retry.bindExecution(retryId,retry.inspect(retryId).revision(),oldEvent));
   var abortFact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:catalogue_event_aborted"),List.of(J08Tests.SCOPE),
    Optional.of(oldEvent),EvidenceStatus.CONFIRMED,Map.of("no_physical_effect",new JsonValue.Bool(true)),true,Optional.empty());
   var abortEnvelope=new DirectorEnvelope(1,new MessageId(UUID.randomUUID()),J08Tests.WORLD,new ResourceId("test:runtime"),"actual-abort",J08Tests.SCOPE,
    new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(1),Optional.of(new Sequence(6)),new Tick(220),PayloadType.DirectorFact,1,new FactPayload(abortFact));
   // The source is already in the real producer's durable batch before it reaches the adapter.
   await(retryStore.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),retryStore.session().storeGeneration(),Map.of(),
    List.of(new DurableFact(abortFact)),List.of())));
   var deferred=await(retry.acceptExecutionAbort(retryId,abortEnvelope));
   check(deferred.state()==RequestStatus.WAITING_CONTEXT&&deferred.factRefs().isEmpty(),"proven abort defers, never fulfills");
   check(!retry.boundExecution(retryId,oldEvent),"ended execution cannot authorize more effects");
   var retryCold=new DurableQuestAdapter(retryStore,retryBus,J08Tests.PRODUCER,executor,tick::get,32,.5);await(retryCold.restore());
   check(retryCold.inspect(retryId).state().status()==RequestStatus.WAITING_CONTEXT&&!retryCold.boundExecution(retryId,oldEvent),
    "actual abort receipt survives restore with its original durable source");
   var freshEvent=new EventInstanceId(UUID.randomUUID());await(retryCold.bindExecution(retryId,retryCold.inspect(retryId).revision(),freshEvent));
   var revision=retryCold.inspect(retryId).revision();
   await(retryCold.acceptExecutionAbort(retryId,abortEnvelope));
   check(retryCold.inspect(retryId).revision().equals(revision)&&retryCold.boundExecution(retryId,freshEvent),"late duplicate old abort cannot reset a fresh bound event");
   check(await(retryCold.acceptFact(retryId,J08Tests.fact(oldEvent,EvidenceStatus.CONFIRMED))).state()==RequestStatus.SCHEDULED,"ended old execution cannot fulfill a new attempt");
   check(await(retryCold.acceptFact(retryId,J08Tests.fact(freshEvent,EvidenceStatus.CONFIRMED))).state()==RequestStatus.FULFILLED,"fresh bound execution can fulfill after genuine abort/retry");
   return 15;
  }finally{executor.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{System.out.println("PASS J08 scheduled aggregate fold assertions="+run()+" pure TEST_ONLY");}
}
