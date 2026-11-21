package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
import java.util.*;
import java.util.concurrent.*;
/** TEST_ONLY bounded scripted read mailbox; no disk, archive implementation, crash or G3 claim. */
final class ColdRead102Tests {
 private static int n;private static final UUID ID=new UUID(0,1);
 private static final StoreSession SESSION=new StoreSession(new WorldId(ID),new Epoch(1),new Epoch(1),new SessionId(ID));
 private static void check(boolean b){n++;if(!b)throw new AssertionError("cold read contract");}
 private static void reject(Runnable r){n++;try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
 private static <T> T round(Class<T> c,T value){var codec=new ModelCodec<>(c);var restored=codec.decode(codec.encode(value));check(value.equals(restored));return restored;}
 private static StoreReadResult miss(ReadStatus s){return new StoreReadResult(s,Optional.empty(),Optional.empty(),List.of());}
 private static SubscriptionReadResult subMiss(ReadStatus s){return new SubscriptionReadResult(s,Optional.empty(),Optional.empty(),List.of());}
 private static final class ColdFixture implements DurableStore,FactBusPort,TestOnlyBackend {
  private CompletableFuture<StoreReadResult> store;private CompletableFuture<SubscriptionReadResult> bus;private int acknowledgements;
  public StoreSession session(){return SESSION;}
  public StoreReadResult view(StoreQuery q){return miss(ReadStatus.NOT_RESIDENT);}
  public SubscriptionReadResult subscribe(ConsumerSpec q){return subMiss(ReadStatus.NOT_RESIDENT);}
  public CompletionStage<StoreReadResult> read(StoreQuery q){if(store!=null&&!store.isDone())return CompletableFuture.completedFuture(miss(ReadStatus.UNAVAILABLE));store=new CompletableFuture<>();return store;}
  public CompletionStage<SubscriptionReadResult> readSubscription(ConsumerSpec q){if(bus!=null&&!bus.isDone())return CompletableFuture.completedFuture(subMiss(ReadStatus.UNAVAILABLE));bus=new CompletableFuture<>();return bus;}
  public CompletionStage<CommitAck> append(DurableBatch b){return CompletableFuture.failedFuture(new UnsupportedOperationException("TEST_ONLY read fixture"));}
  public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest r){return CompletableFuture.completedFuture(new BlobReadResult(AckStatus.UNAVAILABLE,List.of(),List.of()));}
  public CompletionStage<ConsumeAck> acknowledge(ConsumerAck a){acknowledgements++;return CompletableFuture.failedFuture(new UnsupportedOperationException("TEST_ONLY no ACK persistence"));}
 }
 static int run()throws Exception {
  var query=new StoreQuery(SESSION.worldId(),Set.of(),new Sequence(3),Optional.empty(),2);var stamp=new ReadStamp(SESSION,new Sequence(20),ReadContracts.queryHash(query));
  check(stamp.queryHash().value().equals("0f0652a1ba377354c6c1096d760ac7c01bd042e2a74cc44664a4528530843f61"));
  var empty=new StoreView(List.of(),List.of(),stamp.snapshotSequence(),false,Optional.empty());var available=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(empty),Optional.of(stamp),List.of());round(StoreReadResult.class,available);ReadContracts.validate(available,query,SESSION);
  for(var status:ReadStatus.values())if(status!=ReadStatus.AVAILABLE){round(StoreReadResult.class,miss(status));round(SubscriptionReadResult.class,subMiss(status));reject(()->new StoreReadResult(status,Optional.of(empty),Optional.of(stamp),List.of()));}
  reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.empty(),Optional.of(stamp),List.of()));reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(empty),Optional.empty(),List.of()));reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(List.of(),List.of(),new Sequence(19),false,Optional.empty())),Optional.of(stamp),List.of()));
  var foreign=new StoreSession(new WorldId(new UUID(0,2)),SESSION.storeGeneration(),SESSION.bootEpoch(),SESSION.authoritySession());reject(()->ReadContracts.validate(available,query,foreign));
  var reboot=new StoreSession(SESSION.worldId(),SESSION.storeGeneration(),new Epoch(2),new SessionId(new UUID(0,2)));reject(()->ReadContracts.validate(available,query,reboot));
  var cursor=ReadContracts.encodeCursor(new StoreReadCursor(stamp,"offset:2"));var next=new StoreQuery(query.worldId(),query.aggregateKeys(),query.sequence(),Optional.of(cursor),query.limit());check(ReadContracts.queryHash(next).equals(stamp.queryHash()));check(ReadContracts.decodeCursor(cursor).stamp().equals(stamp));ReadContracts.validate(available,next,SESSION);
  for(var changed:List.of(new StoreQuery(query.worldId(),Set.of(),new Sequence(4),Optional.of(cursor),2),new StoreQuery(query.worldId(),Set.of(),query.sequence(),Optional.of(cursor),1),new StoreQuery(query.worldId(),Set.of(new AggregateKey(AggregateKind.values()[0],ID)),query.sequence(),Optional.of(cursor),2)))reject(()->ReadContracts.validate(available,changed,SESSION));
  var moved=new ReadStamp(SESSION,new Sequence(21),stamp.queryHash());var movedPage=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(List.of(),List.of(),moved.snapshotSequence(),false,Optional.empty())),Optional.of(moved),List.of());reject(()->ReadContracts.validate(movedPage,next,SESSION));
  var continued=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(List.of(),List.of(),stamp.snapshotSequence(),true,Optional.of(cursor))),Optional.of(stamp),List.of());round(StoreReadResult.class,continued);
  reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(List.of(),List.of(),stamp.snapshotSequence(),true,Optional.of(ReadContracts.encodeCursor(new StoreReadCursor(moved,"offset:2"))))),Optional.of(stamp),List.of()));
  var consumer=new ConsumerSpec(new ConsumerId(ID),Set.of(),new Sequence(7),2);check(ReadContracts.queryHash(consumer).value().equals("92cee8eaca961a20c0724412175deab5c6a6456e5093d5f7addb23047d16b416"));var subStamp=new SubscriptionReadStamp(new ReadStamp(SESSION,new Sequence(20),ReadContracts.queryHash(consumer)),new Sequence(7),new Sequence(9),new Sequence(8));
  var messages=new ArrayList<>(List.of(message(7),message(8)));var page=new Subscription(consumer,messages,new Sequence(9),true);messages.clear();check(page.batch().size()==2);var result=new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(page),Optional.of(subStamp),List.of());round(SubscriptionReadResult.class,result);ReadContracts.validate(result,consumer,SESSION);check(result.stamp().orElseThrow().store().snapshotSequence().value()==20&&page.batch().get(0).worldSequence().orElseThrow().value()==7&&page.batch().get(1).worldSequence().orElseThrow().value()==8);reject(()->ReadContracts.validate(result,consumer,reboot));
  var filtered=new ConsumerSpec(consumer.consumerId(),Set.of(new FactType("fixture:other")),new Sequence(7),2);var filteredStamp=new SubscriptionReadStamp(new ReadStamp(SESSION,new Sequence(20),ReadContracts.queryHash(filtered)),new Sequence(7),new Sequence(9),new Sequence(8));var filteredPage=new Subscription(filtered,List.of(),new Sequence(9),true);round(SubscriptionReadResult.class,new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(filteredPage),Optional.of(filteredStamp),List.of()));check(filteredPage.hasMore()&&filteredPage.batch().isEmpty());reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(new Subscription(filtered,page.batch(),new Sequence(9),true)),Optional.of(filteredStamp),List.of()));
  reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(new Subscription(consumer,page.batch(),new Sequence(21),true)),Optional.of(subStamp),List.of()));reject(()->new SubscriptionReadResult(ReadStatus.NOT_RESIDENT,Optional.of(page),Optional.of(subStamp),List.of()));reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(page),Optional.empty(),List.of()));reject(()->new SubscriptionReadStamp(subStamp.store(),new Sequence(7),new Sequence(9),new Sequence(5)));reject(()->new SubscriptionReadStamp(subStamp.store(),new Sequence(7),new Sequence(9),new Sequence(10)));reject(()->new ConsumerSpec(consumer.consumerId(),Set.of(),new Sequence(1),0));reject(()->new ConsumerSpec(consumer.consumerId(),Set.of(),new Sequence(1),1025));
  var wrongWorld=new SubscriptionReadStamp(new ReadStamp(foreign,new Sequence(20),ReadContracts.queryHash(consumer)),new Sequence(7),new Sequence(9),new Sequence(8));reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(page),Optional.of(wrongWorld),List.of()));
  var fixture=new ColdFixture();var pending=fixture.read(query).toCompletableFuture();check(!pending.isDone());check(fixture.view(query).status()==ReadStatus.NOT_RESIDENT);check(fixture.read(query).toCompletableFuture().getNow(null).status()==ReadStatus.UNAVAILABLE);var pendingBus=fixture.readSubscription(consumer).toCompletableFuture();check(!pendingBus.isDone()&&fixture.subscribe(consumer).status()==ReadStatus.NOT_RESIDENT);var worker=new Thread(()->{fixture.store.complete(available);fixture.bus.complete(result);},"TEST_ONLY DTO delivery");worker.start();worker.join();check(pending.getNow(null).equals(available)&&pendingBus.getNow(null).equals(result));check(fixture.acknowledgements==0&&consumer.nextSequence().value()==7);for(var failure:List.of(ReadStatus.STALE,ReadStatus.CORRUPT,ReadStatus.UNAVAILABLE)){var waiting=fixture.read(query).toCompletableFuture();fixture.store.complete(miss(failure));check(waiting.getNow(null).page().isEmpty()&&fixture.acknowledgements==0);}
  var error=new SystemError(fr.proutlost.director.api.error.ReasonCode.values()[0],Severity.ERROR,true,Optional.empty(),Set.of(),new CorrelationId(ID),SafeState.UNCHANGED);
  reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(empty),Optional.of(stamp),List.of(error)));
  reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(page),Optional.of(subStamp),List.of(error)));
  reject(()->new StoreReadResult(ReadStatus.CORRUPT,Optional.empty(),Optional.empty(),Collections.nCopies(33,error)));
  reject(()->new SubscriptionReadResult(ReadStatus.CORRUPT,Optional.empty(),Optional.empty(),Collections.nCopies(33,error)));
  var errors=new ArrayList<>(List.of(error));var corrupt=new StoreReadResult(ReadStatus.CORRUPT,Optional.empty(),Optional.of(stamp),errors);errors.clear();check(corrupt.errors().size()==1);round(StoreReadResult.class,corrupt);
  var record=new DurableFact(((FactPayload)message(7).payload()).value());
  reject(()->new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(Collections.nCopies(1025,record),List.of(),stamp.snapshotSequence(),false,Optional.empty())),Optional.of(stamp),List.of()));
  var full=new StoreReadResult(ReadStatus.AVAILABLE,Optional.of(new StoreView(Collections.nCopies(1024,record),List.of(),stamp.snapshotSequence(),false,Optional.empty())),Optional.of(stamp),List.of());check(full.page().orElseThrow().records().size()==1024);reject(()->ReadContracts.validate(full,query,SESSION));
  var wire=new String(new ModelCodec<>(StoreReadResult.class).encode(available),java.nio.charset.StandardCharsets.UTF_8).replace("\"AVAILABLE\"","\"NOT_RESIDENT\"");reject(()->new ModelCodec<>(StoreReadResult.class).decode(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  reject(()->new StoreQuery(query.worldId(),Set.of(),query.sequence(),Optional.of("x".repeat(4097)),2));
  reject(()->ReadContracts.decodeCursor(cursor+"="));
  reject(()->ReadContracts.encodeCursor(new StoreReadCursor(stamp,"\u0000".repeat(512))));
  var maxPosition=ReadContracts.encodeCursor(new StoreReadCursor(stamp,"x".repeat(512)));check(ReadContracts.decodeCursor(maxPosition).position().length()==512);
  var small=new ConsumerSpec(consumer.consumerId(),Set.of(),consumer.nextSequence(),1);var smallStamp=new SubscriptionReadStamp(new ReadStamp(SESSION,new Sequence(20),ReadContracts.queryHash(small)),new Sequence(7),new Sequence(9),new Sequence(8));reject(()->new SubscriptionReadResult(ReadStatus.AVAILABLE,Optional.of(new Subscription(small,page.batch(),new Sequence(9),true)),Optional.of(smallStamp),List.of()));
  reject(()->ReadContracts.validate(result,filtered,SESSION));

  try{page.batch().clear();throw new AssertionError("mutable page");}catch(UnsupportedOperationException expected){check(true);}return n;
 }
 private static DirectorEnvelope message(long sequence){var fact=new DirectorFact(new FactId(new UUID(0,sequence)),new FactType("fixture:fact"),List.of(new ScopeRef(ScopeKind.WORLD,ID)),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty());return new DirectorEnvelope(1,new MessageId(new UUID(0,sequence)),SESSION.worldId(),new ResourceId("fixture:producer"),"key"+sequence,new ScopeRef(ScopeKind.WORLD,ID),new CorrelationId(ID),Optional.empty(),new Sequence(sequence),Optional.of(new Sequence(sequence)),new Tick(1),PayloadType.DirectorFact,1,new FactPayload(fact));}
}
