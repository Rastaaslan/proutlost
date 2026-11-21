package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;

/** Real J04 retention/ACK/reopen of the new regional index; synthetic plan, no native claim. */
public final class RegionalWeatherJournalTests {
 private static int checks;
 private static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 private static void reject(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("accepted invalid "+checks);}
 public static void main(String[] ignored)throws Exception{
  var root=Files.createTempDirectory("director-regional-journal-TEST_ONLY-");Files.writeString(root.resolve("TEST_ONLY"),"J04_CODEC_TEST_ONLY");
  var world=new WorldId(UUID.randomUUID());var event=new EventInstanceId(UUID.randomUUID());var dimension=new DimensionId("minecraft:overworld");
  var footprint=new Footprint(dimension,Set.of(new ChunkRef(dimension,0,0)),Set.of(),List.of(new Aabb(dimension,4,-64,4,12,320,12)));
  var key=new ResourceKey(OwnershipDomain.WEATHER,dimension,"region:"+event.value());
  var owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),new Tick(1)),key,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),BlobContent.hash(new byte[]{1}),new Epoch(0),Optional.empty());
  var request=new RegionalWeatherJournal.Request(event,new OperationToken(UUID.randomUUID()),footprint,new DurationTicks(1000),BlobContent.hash(new byte[]{2}));
  var empty=new RegionalWeatherJournal.State(1,1,request,owner,Optional.empty(),List.of(),false,false,"OWNER_ONLY");
  var sealed=new ModelCodec<>(RegionalWeatherJournal.State.class);check(sealed.decode(sealed.encode(empty)).equals(empty));
  var codec=new ResourceId("proutlost_director:weathercore_regional_witness_v2");
  var active=new WeatherCoreRegionalState(WeatherCoreRegionalState.Kind.ACTIVE,UUID.randomUUID(),event.value(),request.operation().value(),owner.ownerTokens().iterator().next().value(),owner.creationMutationId().value(),1,0,1001,new WeatherCoreRegionalState.Region(8,8,4));
  var pre=BlobContent.capture(codec,2,new WeatherCoreRegionalWitness(WeatherCoreRegionalState.absent(),false).bytes());
  var post=BlobContent.capture(codec,2,new WeatherCoreRegionalWitness(active,true).bytes());
  var stop=BlobContent.capture(codec,2,new WeatherCoreRegionalWitness(active.retired(),false).bytes());
  var ownerBlob=BlobContent.capture(new ResourceId("proutlost_director:owner_v1"),1,new ModelCodec<>(OwnershipRecord.class).encode(owner));
  var intent=new EffectIntent(request.operation(),new PrimitiveId("proutlost:regional_weather_lease"),1,new RegionalWeatherLease(dimension,new ResourceId("proutlost_director:regional_mist"),request.duration(),new Revision(1)),footprint,event);
  var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),new Tick(1),Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  var read=new ReadSet(Map.of(),Map.of(key,owner.epoch()),Map.of(key,pre.reference().hash()));
  var budget=new BudgetVector(0,0,0,0,0,1,0,0,0,0);
  var plan=new PreparedEffect(intent,snapshot,List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,budget,Optional.empty(),owner.epoch())),read,Map.of(key,post.reference().hash()),budget,List.of(),Set.of(new CapabilityId("weather.regional_scope_v1")),
     new EffectBlobs(Set.of(pre.reference(),ownerBlob.reference()),post.reference(),stop.reference(),Map.of(key,pre.reference()),Map.of(key,post.reference())));
  var blobs=new BlobBundle(List.of(pre,post,stop,ownerBlob));
  var state=new RegionalWeatherJournal.State(1,2,request,owner,Optional.of(plan),RegionalWeatherJournal.seal(blobs),true,false,"DELIVERY_UNCERTAIN_PRESERVED");
  check(sealed.decode(sealed.encode(state)).equals(state));var fact=RegionalWeatherJournal.fact(world,state);
  check(RegionalWeatherJournal.state(fact).equals(state));check(DurableBlobReferences.collect(fact).equals(DurableBlobReferences.collect(plan.durableBlobs())));
  reject(()->new RegionalWeatherJournal.State(1,3,request,owner,Optional.of(plan),List.of(),false,false,"missing bytes"));
  reject(()->new RegionalWeatherJournal.Bytes(post.reference(),Base64.getEncoder().encodeToString(new byte[]{1})));
  reject(()->new RegionalWeatherJournal.State(2,3,request,owner,Optional.empty(),List.of(),false,false,"unknown schema"));
  var options=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
  var store=JournalStore.open(root.resolve("j04"),world,options).toCompletableFuture().join();
  var initial=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(owner),new DurableFact(RegionalWeatherJournal.fact(world,empty))),List.of());
  check(store.append(initial).toCompletableFuture().join().ackStatus()==AckStatus.DURABLE);
  var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableFact(fact)),blobs.contents());
  var ack=store.append(batch).toCompletableFuture().join();check(ack.ackStatus()==AckStatus.DURABLE&&ack.forcedBlobs().equals(DurableBlobReferences.collect(fact)));
  check(store.retainedFact(fact.factId()).filter(fact::equals).isPresent());
  var old=store.session();store.closeAsync().toCompletableFuture().join();
  store=JournalStore.open(root.resolve("j04"),world,options).toCompletableFuture().join();check(!old.equals(store.session()));
  check(store.retainedFact(fact.factId()).map(RegionalWeatherJournal::state).filter(state::equals).isPresent());
  var reopened=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),DurableBlobReferences.collect(fact))).toCompletableFuture().join();
  check(reopened.status()==AckStatus.DURABLE&&new HashSet<>(reopened.contents()).equals(new HashSet<>(blobs.contents())));
  check(RegionalWeatherJournal.state(store.retainedFact(fact.factId()).orElseThrow()).uncertain());
  store.closeAsync().toCompletableFuture().join();
  System.out.println("REGIONAL_WEATHER_JOURNAL_J04_TESTS_PASS assertions="+checks+" root="+root+" native=false production=false");
 }
}

