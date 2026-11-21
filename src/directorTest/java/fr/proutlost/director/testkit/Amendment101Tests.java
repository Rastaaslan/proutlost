package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.permission.*;
import java.util.*;
import java.nio.file.*;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;
/** TEST_ONLY amendment fixtures; no physical stop or disk-durability qualification. */
public final class Amendment101Tests {
 private Amendment101Tests(){}
 private static int count;
 private static void check(boolean b){count++;if(!b)throw new AssertionError("API1.0.1 fixture");}
 private static void rejects(Runnable r){count++;try{r.run();}catch(IllegalArgumentException|IllegalStateException|ArithmeticException expected){return;}throw new AssertionError("expected refusal");}
 private static <T> void round(Class<T> type,T value){var codec=new ModelCodec<>(type);check(codec.decode(codec.encode(value)).equals(value));}
 public static int run(Path spec)throws Exception {histogram(spec);stops();return count+Expanded101Tests.run(spec);}
 private static void histogram(Path spec)throws Exception {
  var config=(JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(spec.resolve("DEFAULT_FOR_TESTING.json")));
  var values=(JsonValue.ObjectValue)config.values().get("values");int bins=((JsonValue.Number)values.values().get("pattern.activity_time_bins")).value().intValueExact();check(bins==12);
  var counts=new ArrayList<Long>();for(long n=1;n<=bins;n++)counts.add(n);
  var indexed=new ActivityTimeCounts(bins,counts);counts.set(0,99L);check(indexed.counts().getFirst()==1);try{indexed.counts().set(0,2L);throw new AssertionError("mutable counts");}catch(UnsupportedOperationException expected){check(true);}round(ActivityTimeCounts.class,indexed);
  rejects(()->new ActivityTimeCounts(0,List.of()));rejects(()->new ActivityTimeCounts(4097,Collections.nCopies(4097,0L)));rejects(()->new ActivityTimeCounts(12,List.of(1L)));rejects(()->new ActivityTimeCounts(1,List.of(-1L)));rejects(()->new ActivityTimeCounts(2,List.of(Long.MAX_VALUE,1L)));
  rejects(()->new ModelCodec<>(ActivityTimeCounts.class).decode("{\"bin_count\":2,\"counts\":[\"-1\",\"2\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  var h=new ActivityHistogram(Map.of(),Map.of(),Map.of(DayPhase.DAY,78L),Map.of(),Optional.of(indexed));round(ActivityHistogram.class,h);round(CampSnapshot.class,new CampSnapshot(new AnchorId(new UUID(0,1)),1,new DurationTicks(78),Map.of(),Map.of(),1,new UnitValue(.5),Optional.of(indexed)));
  check(new ActivityHistogram(Map.of(),Map.of(),Map.of(DayPhase.DAY,78L),Map.of()).activityTime().isEmpty());
  var cs=new ArrayList<CampWindowContribution>();for(int i=1;i<=3;i++)cs.add(new CampWindowContribution(new EvidenceWindow(new EvidenceWindowId(new UUID(0,i)),new Tick(i*10),new Tick(i*10+10),Set.of(new EpisodeId(new UUID(0,i)))),1,new DurationTicks(10),h));
  var union=new HashMap<EvidenceWindowId,CampWindowContribution>();for(var group:List.of(List.of(cs.get(0),cs.get(1)),List.of(cs.get(1),cs.get(2))))for(var c:group){var prior=union.putIfAbsent(c.window().id(),c);check(prior==null||prior.equals(c));}
  var totals=new ArrayList<Long>(Collections.nCopies(bins,0L));for(var c:union.values())for(int i=0;i<bins;i++)totals.set(i,Math.addExact(totals.get(i),c.histogram().activityTime().orElseThrow().counts().get(i)));
  for(int i=0;i<bins;i++)check(totals.get(i)==3L*(i+1));
  var sum=new ActivityHistogram(Map.of(),Map.of(),Map.of(DayPhase.DAY,234L),Map.of(),Optional.of(new ActivityTimeCounts(bins,totals)));
  var camp=new CampRoutineMemory(new AnchorId(new UUID(0,1)),List.copyOf(union.values()),3,new DurationTicks(30),sum,new UnitValue(.5));round(CampRoutineMemory.class,camp);round(CampWindowContribution.class,cs.getFirst());
  var c=cs.getFirst();round(CampEpisodeObservation.class,new CampEpisodeObservation(new ObservationId(new UUID(0,1)),new RegionId(new UUID(0,1)),camp.anchorId(),c.window().id(),new EpisodeId(new UUID(0,1)),c.window().startTick(),c.window().endTick(),c.occupiedTicks(),h));
 }
 private static final UUID ID=new UUID(0,1);
 private static final DimensionId DIM=new DimensionId("minecraft:overworld");
 private record Fixture(TestDurableStore store,CommitPermit.Authority authority,DurableBatch batch,PrepareRecord prepare,StabilizationPlan plan,LiveValidationSnapshot initial){}
 private static Fixture fixture(){
  var store=new TestDurableStore();var authority=new CommitPermit.Authority(store,4);var chunk=new ChunkRef(DIM,0,0);var f=new Footprint(DIM,Set.of(chunk),Set.of(new BlockPosition(DIM,1,1,1)),List.of());
  var key=new ResourceKey(OwnershipDomain.BLOCK,DIM,"1,1,1");var hash=new Fingerprint("a".repeat(64));var epoch=new Epoch(2);var read=new ReadSet(Map.of(),Map.of(key,epoch),Map.of(key,hash));
  var ownership=new OwnershipRecord(new AggregateHeader(1,ID,new Revision(2),new Sequence(2),new Tick(2)),key,Set.of(new OwnerToken(ID)),epoch,OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(ID),hash,new Epoch(0),Optional.empty());
  var stopIntent=new EffectIntent(new OperationToken(new UUID(0,3)),new PrimitiveId("fixture:stop"),1,new TargetParticles("target",new ResourceId("fixture:stop"),0,0),f,new EventInstanceId(ID));
  var stop=new PreparedSlice(new SliceId(new UUID(0,2)),new OperationToken(new UUID(0,2)),f,read,List.of(stopIntent),BudgetMath.zero());
  var plan=new StabilizationPlan(List.of(new StabilizationSlice(stop,Map.of(key,ownership))));var blob=plan.blob();
  var impact=new PreparedSlice(new SliceId(ID),new OperationToken(ID),f,read,List.of(),BudgetMath.zero());
  var reservation=new ReservationToken(new ReservationId(ID),new WorldId(ID),new EventInstanceId(ID),new Epoch(1),new Tick(200),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch)));
  var prepare=new PrepareRecord(new TransactionId(ID),impact.operationToken(),new Epoch(1),read,new Tick(200),impact,reservation,new ValidationResult(true,List.of(),read),List.of(),Set.of(),List.of(stopIntent),new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of()),Optional.of(plan));
  var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),read.revisions(),List.of(new DurablePrepare(prepare)),List.of(blob),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  return new Fixture(store,authority,batch,prepare,plan,new LiveValidationSnapshot(read,f.loadedChunks(),HealthMode.NORMAL,new Tick(100),new Epoch(1)));
 }
 private static CommitPermit grant(Fixture f){f.authority().submit(f.batch(),f.initial());check(f.authority().poll(f.batch().transactionId(),f.initial()).status()==PermitResult.Status.PENDING);f.store().force(f.batch().transactionId());return f.authority().poll(f.batch().transactionId(),f.initial()).permit().orElseThrow();}
 private static StabilizationLiveSnapshot live(Fixture f,HealthMode health){return new StabilizationLiveSnapshot(f.store().session(),new LiveValidationSnapshot(f.plan().slices().getFirst().slice().preconditions(),f.initial().loadedChunks(),health,new Tick(1000),new Epoch(1)),new OwnershipView(f.plan().slices().getFirst().expectedOwnership()));}
 private static void stops()throws Exception {
  check(Arrays.stream(CommitPermit.StabilizationPermit.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers())));
  rejects(()->new ModelCodec<>(CommitPermit.StabilizationPermit.class));
  for(var health:List.of(HealthMode.CRITICAL,HealthMode.READ_ONLY_SAFE)){
   var f=fixture();round(PrepareRecord.class,f.prepare());round(StabilizationPlan.class,f.plan());var impact=grant(f);var stop=impact.stabilizationPermit().orElseThrow();impact.consume(f.authority(),f.prepare().slice(),f.initial());f.store().loseWriter();
   check(f.store().append(f.batch()).toCompletableFuture().isCompletedExceptionally());var snapshot=live(f,health);round(StabilizationLiveSnapshot.class,snapshot);
   var result=new SliceResult(Outcome.SUCCEEDED,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());var port=new TestWorldEffectPort(f.authority(),f.initial(),result,p->{});
   check(port.stabilize(f.plan().slices().getFirst().slice(),stop,snapshot).equals(result));check(port.stopMarkers().size()==1);round(StabilizationMarker.class,port.stopMarkers().getFirst());
   rejects(()->port.stabilize(f.plan().slices().getFirst().slice(),stop,snapshot));rejects(()->impact.consume(f.authority(),f.prepare().slice(),snapshot.validation()));
  }
  var f=fixture();var impact=grant(f);var permit=impact.stabilizationPermit().orElseThrow();var slice=f.plan().slices().getFirst().slice();var snapshot=live(f,HealthMode.READ_ONLY_SAFE);
  rejects(()->impact.consume(f.authority(),f.prepare().slice(),new LiveValidationSnapshot(f.initial().readSet(),f.initial().loadedChunks(),HealthMode.CRITICAL,new Tick(101),new Epoch(1))));
  rejects(()->permit.consume(new CommitPermit.Authority(f.store(),4),slice,snapshot));
  var error=new AtomicReference<Throwable>();var thread=new Thread(()->{try{permit.consume(f.authority(),slice,snapshot);}catch(Throwable e){error.set(e);}});thread.start();thread.join();check(error.get() instanceof IllegalStateException);
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(new StoreSession(new WorldId(ID),new Epoch(7),new Epoch(0),new SessionId(ID)),snapshot.validation(),snapshot.ownership())));
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),new LiveValidationSnapshot(slice.preconditions(),Set.of(),HealthMode.CRITICAL,new Tick(1000),new Epoch(1)),snapshot.ownership())));
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),new LiveValidationSnapshot(slice.preconditions(),snapshot.validation().loadedChunks(),HealthMode.CRITICAL,new Tick(1000),new Epoch(2)),snapshot.ownership())));
  var key=snapshot.ownership().records().keySet().iterator().next();var owner=snapshot.ownership().records().get(key);
  for(var disposition:List.of(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.SHARED_EXPLICIT,OwnershipDisposition.CONFLICT_ISOLATED)){
   var changed=new OwnershipRecord(owner.header(),key,owner.ownerTokens(),owner.epoch(),disposition,owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch().plus(1),Optional.empty());
   rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),snapshot.validation(),new OwnershipView(Map.of(key,changed)))));
  }
  var otherOwner=new OwnershipRecord(owner.header(),key,Set.of(new OwnerToken(new UUID(0,99))),owner.epoch(),owner.disposition(),owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch(),Optional.empty());
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),snapshot.validation(),new OwnershipView(Map.of(key,otherOwner)))));
  var overridden=new OwnershipRecord(owner.header(),key,owner.ownerTokens(),owner.epoch(),owner.disposition(),owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch().plus(1),Optional.empty());
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),snapshot.validation(),new OwnershipView(Map.of(key,overridden)))));
  var badHash=new ReadSet(Map.of(),slice.preconditions().epochs(),Map.of(key,new Fingerprint("b".repeat(64))));
  rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),new LiveValidationSnapshot(badHash,snapshot.validation().loadedChunks(),HealthMode.CRITICAL,new Tick(1000),new Epoch(1)),snapshot.ownership())));
  var stale=new ReadSet(Map.of(),Map.of(key,new Epoch(3)),slice.preconditions().fingerprints());rejects(()->permit.consume(f.authority(),slice,new StabilizationLiveSnapshot(snapshot.session(),new LiveValidationSnapshot(stale,snapshot.validation().loadedChunks(),HealthMode.READ_ONLY_SAFE,new Tick(1000),new Epoch(1)),snapshot.ownership())));
  rejects(()->permit.consume(f.authority(),new PreparedSlice(slice.sliceId(),slice.operationToken(),slice.footprint(),slice.preconditions(),List.of(),slice.maximumWork()),snapshot));
  var enlarged=new Footprint(DIM,Set.of(new ChunkRef(DIM,0,0),new ChunkRef(DIM,1,0)),slice.footprint().cells(),List.of());rejects(()->permit.consume(f.authority(),new PreparedSlice(slice.sliceId(),slice.operationToken(),enlarged,slice.preconditions(),slice.operations(),slice.maximumWork()),snapshot));
  var wrongBlob=BlobContent.capture(new ResourceId("fixture:wrong"),1,new byte[]{1}).reference();
  var p=f.prepare();rejects(()->new PrepareRecord(p.transactionId(),p.operationToken(),p.reservationGeneration(),p.readSet(),p.expiryTick(),p.slice(),p.reservation(),p.hardValidation(),p.uniqueness(),p.noninvertibleEffects(),p.authorizedStabilization(),new EffectBlobs(Set.of(),wrongBlob,wrongBlob,Map.of(),Map.of()),p.stabilizationPlan()));
  // Refusals did not consume the valid stop; a fresh validated capture can still stop the hazard.
  round(StabilizationMarker.class,permit.consume(f.authority(),slice,snapshot));
  var reboot=fixture();var old=grant(reboot).stabilizationPermit().orElseThrow();reboot.store().reboot();rejects(()->old.consume(reboot.authority(),reboot.plan().slices().getFirst().slice(),live(reboot,HealthMode.CRITICAL)));
  var noforce=fixture();noforce.authority().submit(noforce.batch(),noforce.initial());var submitted=noforce.store().submitted(noforce.batch().transactionId());noforce.store().acknowledge(new CommitAck(submitted.transactionId(),new Epoch(7),new Sequence(1),submitted.transactionHash(),AckStatus.DURABLE,submitted.permitAttempt(),Set.of()));check(noforce.authority().poll(submitted.transactionId(),noforce.initial()).status()==PermitResult.Status.REJECTED);
 }
}
