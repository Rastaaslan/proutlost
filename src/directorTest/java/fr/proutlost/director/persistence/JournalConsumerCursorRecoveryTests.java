package fr.proutlost.director.persistence;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.director.api.codec.ModelCodec;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
/** Real J04 close/reopen and stable-consumer cursor regression, no Minecraft/client claim. */
public final class JournalConsumerCursorRecoveryTests {
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(20,TimeUnit.SECONDS);}
 private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static int run(Path directory)throws Exception{
  var world=new WorldId(UUID.randomUUID());var consumer=new ConsumerId(UUID.randomUUID());var scope=new ScopeRef(ScopeKind.WORLD,world.value());
  var messages=new ArrayList<DirectorEnvelope>();var rows=new ArrayList<DurableRecord>();
  for(int i=1;i<=2;i++){
   var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("fixture:cursor"),List.of(scope),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());
   var envelope=new DirectorEnvelope(1,new MessageId(UUID.randomUUID()),world,new ResourceId("fixture:producer"),"cursor-"+i,scope,new CorrelationId(UUID.randomUUID()),
    Optional.empty(),new Sequence(i),Optional.empty(),new Tick(i),PayloadType.DirectorFact,1,new FactPayload(fact));
   messages.add(envelope);rows.add(new DurableFact(fact));rows.add(new DurableOutbox(envelope));
  }
  var store=await(JournalStore.open(directory,world,OPTIONS));
  var firstSession=store.session();
  check(store.consumerCursor(consumer).orElseThrow().cursor().value()==0,"fresh stable consumer starts at proven durable cursor0");
  await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of())));
  var spec=new ConsumerSpec(consumer,Set.of(),new Sequence(1),1);var delivery=await(store.readSubscription(spec));ReadContracts.validate(delivery,spec,store.session());
  var page=delivery.page().orElseThrow();var firstIds=Set.of(page.batch().getFirst().messageId());var firstThrough=page.nextSequence().value()-1;
  var wrong=await(store.acknowledge(new ConsumerAck(consumer,firstIds,new Sequence(firstThrough),new Sequence(1))));
  check(wrong.status()==AckStatus.REJECTED&&store.consumerCursor(consumer).orElseThrow().cursor().value()==0,"wrong expectedCursor neither advances nor overwrites");
  var ack=await(store.acknowledge(new ConsumerAck(consumer,firstIds,new Sequence(firstThrough),new Sequence(0))));
  check(ack.status()==AckStatus.DURABLE,"matching real delivered IDs and original CAS acknowledge durably");
  var expected=store.consumerCursor(consumer).orElseThrow().cursor();await(store.closeAsync());
  check(store.consumerCursor(consumer).isEmpty(),"closed store exposes UNKNOWN, not an invented cursor");
  store=await(JournalStore.open(directory,world,OPTIONS));
  var restored=store.consumerCursor(consumer).orElseThrow();
  check(!restored.session().equals(firstSession)&&restored.session().equals(store.session())&&restored.cursor().equals(expected),"same consumer cursor restored under actual new J04 session");
  var nextSpec=new ConsumerSpec(consumer,Set.of(),restored.cursor().plus(1),1);
  var next=await(store.readSubscription(nextSpec));ReadContracts.validate(next,nextSpec,store.session());
  check(next.page().orElseThrow().batch().size()==1&&next.page().orElseThrow().batch().getFirst().messageId().equals(messages.get(1).messageId()),"cold consumer delivers second message only");
  var nextPage=next.page().orElseThrow();var secondIds=Set.of(nextPage.batch().getFirst().messageId());var through=new Sequence(nextPage.nextSequence().value()-1);
  check(await(store.acknowledge(new ConsumerAck(consumer,secondIds,through,new Sequence(0)))).status()==AckStatus.REJECTED,"old pre-reboot cursor cannot overwrite the durable cursor");
  check(store.consumerCursor(consumer).orElseThrow().cursor().equals(expected),"rejected old ACK preserves restored cursor");
  check(await(store.acknowledge(new ConsumerAck(consumer,secondIds,through,expected))).status()==AckStatus.DURABLE,"new exact cursor can finish the actual second delivery");
  await(store.closeAsync());return 9+largeBlobDigestAndRecovery(directory.resolve("large-blob"));
 }

 private static int largeBlobDigestAndRecovery(Path directory)throws Exception{
  var world=new WorldId(UUID.randomUUID());var id=new TransactionId(UUID.randomUUID());var generation=new Epoch(1);
  byte[] bytes=new byte[96*1024];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17+11);
  var large=BlobContent.capture(new ResourceId("fixture:large_context"),1,bytes);
  var a=BlobContent.capture(new ResourceId("fixture:small_context"),1,new byte[32*1024]);
  byte[] changed=new byte[32*1024];changed[0]=1;
  var b=BlobContent.capture(new ResourceId("fixture:small_context"),1,changed);
  var small=ResultTransaction.batch(id,generation,Map.of(),List.of(),List.of(a,b));
  var unsigned=new DurableBatch(id,generation,Map.of(),List.of(),List.of(a,b),Optional.empty(),new Fingerprint("0".repeat(64)));
  check(small.transactionHash().equals(BlobContent.hash(new ModelCodec<>(DurableBatch.class).encode(unsigned))),"two32KiB blobs preserve the exact legacy batch hash despite64KiB total");
  var ordered=ResultTransaction.batch(id,generation,Map.of(),List.of(),List.of(large,a));
  var reversed=ResultTransaction.batch(id,generation,Map.of(),List.of(),List.of(a,large));
  var varied=ResultTransaction.batch(id,generation,Map.of(),List.of(),List.of(large,b));
  check(!ordered.transactionHash().equals(reversed.transactionHash()),"large-blob digest binds blob order");
  check(!ordered.transactionHash().equals(varied.transactionHash()),"large-blob digest binds changed exact content references");
  var eventId=new EventInstanceId(UUID.randomUUID());
  var event=new EventInstance(new AggregateHeader(1,eventId.value(),new Revision(1),new Sequence(0),new Tick(0)),eventId,
    new DefinitionRef(new DefinitionId("fixture:large_blob"),new Revision(1),new Fingerprint("a".repeat(64))),generation,
    EventState.PROPOSED,"PURE_J04_LARGE_CONTEXT_FIXTURE",new RoleAssignments(Map.of()),Set.of(),large.reference(),
    Map.of("fixture",StepState.WAITING),Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.empty(),
    new DurationTicks(0),new DurationTicks(1200),RecoveryMode.CLEANUP,Optional.empty());
  List<DurableRecord> records=List.of(new DurableAggregate(new EventAggregate(event)));
  check(DurableBlobReferences.collect(records).contains(large.reference()),"96KiB blob is genuinely referenced by the durable event context");
  JournalStore store=await(JournalStore.open(directory,world,OPTIONS));
  try{
   var first=store.session();
   var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),first.storeGeneration(),Map.of(),records,List.of(large));
   var ack=await(store.append(batch));
   check(ack.ackStatus()==AckStatus.DURABLE,"real referenced96KiB blob append receives the normal durable ACK");
   check(await(store.append(batch)).equals(ack),"same live transaction is idempotent with the exact original ACK");
   await(store.closeAsync());store=null;
   store=await(JournalStore.open(directory,world,OPTIONS));
   check(!store.session().equals(first),"close/reopen establishes a real new J04 session");
   var read=await(store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),Set.of(large.reference()))));
   check(read.status()==AckStatus.DURABLE&&read.contents().size()==1&&read.contents().getFirst().reference().equals(large.reference())
      &&Arrays.equals(bytes,read.contents().getFirst().bytes()),"cold referenced blob retains exact bytes and content hash");
   check(await(store.append(batch)).equals(ack),"same transaction after cold reopen returns its original durable ACK");
  }finally{if(store!=null)await(store.closeAsync());}
  return 9;
 }
 public static void main(String[] args)throws Exception{System.out.println("PASS J04 stable consumer close/reopen assertions="+run(Path.of(args[0])));}
}
