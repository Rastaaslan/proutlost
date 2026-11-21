package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real J04 disk/ACK integration. Metadata fixture only; no Minecraft or client claim.
 * Exercises the same adapter append that previously duplicated REQUEST in one batch.
 */
public final class J08RealJournalRoundTripTests {
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static int assertions;
 private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
 private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(20,TimeUnit.SECONDS);}
 public static int run(Path directory)throws Exception{
  assertions=0;JournalStore store=null;
  var executor=Executors.newSingleThreadExecutor();var tick=new AtomicLong(100);
  var world=new WorldId(UUID.randomUUID());var producer=new ResourceId("fixture:j08_real_adapter");
  var actor=new PlayerId(UUID.randomUUID());var scope=new ScopeRef(ScopeKind.PLAYER,actor.value());
  var id=new RequestId(UUID.randomUUID());var event=new EventInstanceId(UUID.randomUUID());
  var observed=new FactType("fixture:j08_real_observed");
  var payload=new DirectorRequirement(id,new AnyOfEvents(Set.of(new DefinitionId("fixture:required_signal"))),
   new TargetConstraints(Set.of(),Set.of(actor)),new FactMatch(observed,scope,EvidenceStatus.CONFIRMED,Map.of()),FulfillmentMode.NEW_EXECUTION);
  var original=new DirectorRequest(new MessageId(UUID.randomUUID()),world,producer,"actual-j04-roundtrip",scope,
   new CorrelationId(UUID.randomUUID()),Optional.empty(),new Sequence(1),new Tick(100),payload);
  var policy=new RequestSchedulingPolicy(new NarrativePriority(7),PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.AUTHORIZED_ADAPTER);
  try{
   store=await(JournalStore.open(directory,world,OPTIONS));
   var firstSession=store.session();
   var adapter=new DurableQuestAdapter(store,store,producer,executor,tick::get,32,.5);
   await(adapter.restoreHydrated());
   var accepted=await(adapter.submit(original,policy));
   check(accepted.state()==RequestStatus.ACCEPTED&&accepted.revision().value()==1&&accepted.reasons().isEmpty(),
    "actual J04 accepts one original DurableRequest without duplicate aggregate");
   var key=new AggregateKey(AggregateKind.REQUEST,id.value());
   var point=new StoreQuery(world,Set.of(key),new Sequence(0),Optional.empty(),1);
   var read=await(store.read(point));ReadContracts.validate(read,point,store.session());
   check(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().aggregates().size()==1,
    "DurableRequest alone publishes the actual positive REQUEST head");
   var row=read.page().orElseThrow().aggregates().getFirst();
   check(row.committedSequence().value()>0&&row.state() instanceof RequestAggregate request&&request.value().equals(adapter.inspect(id).state()),
    "published REQUEST head equals the acknowledged adapter state");
   var before=adapter.inspect(id).state();var h=before.header();tick.set(200);
   var scheduled=new DirectorRequestState(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),new Tick(200)),
    before.requestId(),before.producerId(),before.producerKey(),before.kind(),before.selector(),before.scope(),before.constraints(),
    before.fulfillment(),RequestStatus.SCHEDULED,new DurationTicks(17),before.expiresTick(),before.resultFactIds(),List.of(),before.schedulingPolicy());
   var scheduling=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
    List.of(new DurableAggregate(new RequestAggregate(scheduled))),List.of())));
   check(scheduling.ackStatus()==AckStatus.DURABLE,"real scheduler-only REQUEST revision persists");
   await(adapter.refreshDurable(Set.of(id)));
   var binding=await(adapter.bindExecution(id,adapter.inspect(id).revision(),event));
   check(binding.state()==RequestStatus.SCHEDULED&&binding.revision().value()==2&&adapter.boundExecution(id,event),
    "already SCHEDULED binding ACK does not duplicate its revision");
   var fact=new DirectorFact(new FactId(UUID.randomUUID()),observed,List.of(scope),Optional.of(event),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());
   var envelope=new DirectorEnvelope(1,new MessageId(UUID.randomUUID()),world,new ResourceId("fixture:actual_j04_fact"),
    "completion",scope,original.correlationId(),Optional.empty(),new Sequence(1),Optional.empty(),new Tick(200),PayloadType.DirectorFact,1,new FactPayload(fact));
   var factAck=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
    List.of(new DurableFact(fact),new DurableOutbox(envelope)),List.of())));
   check(factAck.ackStatus()==AckStatus.DURABLE,"fixture source fact and outbox share a real J04 commit");
   var consumer=new ConsumerId(UUID.randomUUID());var spec=new ConsumerSpec(consumer,Set.of(observed),new Sequence(1),16);
   var delivered=await(store.readSubscription(spec));ReadContracts.validate(delivered,spec,store.session());
   check(delivered.status()==ReadStatus.AVAILABLE&&delivered.page().orElseThrow().batch().size()==1,
    "actual FactBus delivers the committed filtered envelope");
   var actual=delivered.page().orElseThrow().batch().getFirst();
   check(actual.worldSequence().isPresent()&&actual.messageId().equals(envelope.messageId())&&actual.payload().equals(envelope.payload()),
    "world sequence is assigned by J04 rather than invented by this fixture");
   var fulfilled=await(adapter.acceptFact(id,actual));
   check(fulfilled.state()==RequestStatus.FULFILLED&&fulfilled.revision().value()==3&&fulfilled.factRefs().equals(Set.of(fact.factId())),
    "real bound FactBus evidence fulfills at the next durable request revision");
   await(adapter.drain());await(store.closeAsync());store=null;
   tick.set(300);store=await(JournalStore.open(directory,world,OPTIONS));
   check(!store.session().equals(firstSession),"same private store reopens under a new actual session");
   var cold=new DurableQuestAdapter(store,store,producer,executor,tick::get,32,.5);
   await(cold.restoreHydrated());var restored=cold.inspect(id);
   check(restored.request().equals(original)&&restored.state().status()==RequestStatus.FULFILLED&&restored.revision().value()==3
     &&restored.state().schedulingPolicy().equals(policy)&&restored.state().eligibleWaitTicks().value()==17,
    "cold fold retains original identity/policy, scheduler state, binding and fulfillment");
   var cut=await(store.read(point)).page().orElseThrow().lastDurableSequence();
   var duplicate=await(cold.submit(original,policy));
   check(duplicate.equals(fulfilled)&&await(store.read(point)).page().orElseThrow().lastDurableSequence().equals(cut),
    "same producer key replays its durable ACK after reboot without a new write");
   await(cold.drain());
   return assertions;
  }finally{
   try{if(store!=null)await(store.closeAsync());}
   finally{executor.shutdown();if(!executor.awaitTermination(20,TimeUnit.SECONDS))executor.shutdownNow();}
  }
 }
 public static void main(String[] args)throws Exception{
  System.out.println("PASS J08 real JournalStore submit/scheduler/binding/fact/reopen assertions="+run(Path.of(args[0])));
 }
}
