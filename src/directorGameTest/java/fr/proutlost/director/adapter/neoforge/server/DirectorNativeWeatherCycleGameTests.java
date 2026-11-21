package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.adapter.neoforge.server.effects.WeatherBinding;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.*;

/** Opt-in native cycle qualification. No weather freeze, synthetic ticks, fake ACK or client proof.
 * Isolated structure namespace and unique batch exclude frozen-weather fixtures.
 * Gradle -PdirectorWeatherCycleOnly=true forwards both real JVM properties before registration.
 */
@GameTestHolder("proutlost_director_weather_cycle")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWeatherCycleGameTests {
 private DirectorNativeWeatherCycleGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private static final int RAIN_BASELINE=12000,THUNDER_BASELINE=16000,LEASE=80;
 @GameTestGenerator
 public static Collection<TestFunction> liveWeatherCycleTests(){
  if(!Boolean.getBoolean("proutlost.director.nativeWeatherCycleGameTest"))return List.of();
  return List.of(new TestFunction("director_native_weather_cycle_live",
   "proutlost_director_weather_cycle.native_weather_cycle_durable_lease_restore",
   "proutlost_director_weather_cycle:smoke_empty",Rotation.NONE,6000,0,true,helper->{
    var probe=new Probe(helper);
    helper.succeedWhen(()->{probe.advance();helper.assertTrue(probe.done,"native weather cycle pending phase="+probe.phase);});
   }));
 }
 private record NativeState(int clear,int rain,int thunder,boolean raining,boolean thundering){
  static NativeState read(ServerLevelData d){return new NativeState(d.getClearWeatherTime(),d.getRainTime(),d.getThunderTime(),d.isRaining(),d.isThundering());}
  Fingerprint hash(){return BlobContent.hash(ByteBuffer.allocate(14).putInt(clear).putInt(rain).putInt(thunder).put((byte)(raining?1:0)).put((byte)(thundering?1:0)).array());}
 }
 private static final class Execution {
  EffectPreparation captured;PreparedEffect plan;PrepareRecord prepare;EventRunner runner;
  ResourceArbiter arbiter;ReservationToken token;LiveValidationSnapshot live;
  long appliedAt=-1;boolean observed;
 }
 private static final class Probe {
  final GameTestHelper helper;final ServerLevel level;final ServerLevelData data;final MinecraftServer server;
  final NativeState original;final boolean originalCycle;
  final WorldId world=new WorldId(UUID.randomUUID());final DimensionId dimension;
  final ResourceKey key;final EventInstanceId instance=new EventInstanceId(UUID.randomUUID());
  final AtomicReference<Revision> external=new AtomicReference<>(new Revision(1));
  final AtomicReference<OwnershipView> ownership=new AtomicReference<>();
  final OwnershipRecord owner;final WeatherBinding binding;final Footprint footprint;
  final java.nio.file.Path path;final CompletableFuture<JournalStore> opening;
  JournalStore store;CompletableFuture<CommitAck> initial,releasedAck;
  CompletableFuture<Void> closing;CompletableFuture<JournalStore> reopening;
  EffectPreparation forwardCapture,inverseCapture;Execution forward,inverse;
  BlobContent capturedStop;OwnershipRecord released;
  long baselineAt,forwardCaptureAt,inverseCaptureAt;int capturedThunder;long floor;int phase;boolean done,fixtureRestored;
  boolean firstFailureLogged;
  Probe(GameTestHelper helper){
   this.helper=helper;level=helper.getLevel();server=Objects.requireNonNull(level.getServer());data=(ServerLevelData)level.getLevelData();
   if(!server.isSameThread())throw new IllegalStateException("native weather cycle actor");
   original=NativeState.read(data);originalCycle=level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
   level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(true,server);
   dimension=new DimensionId(level.dimension().location().toString());key=new ResourceKey(OwnershipDomain.WEATHER,dimension,"weather");
   owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),
    key,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
    new MutationId(UUID.randomUUID()),original.hash(),new Epoch(0),Optional.empty());
   ownership.set(new OwnershipView(Map.of(key,owner)));footprint=new Footprint(dimension,Set.of(),Set.of(),List.of());
   binding=new WeatherBinding(d->level,ownership::get,d->external.get(),4);
   path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-weather-live-test-only").resolve(UUID.randomUUID().toString());
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();
  }
  Tick now(){return new Tick(level.getGameTime());}
  void check(boolean valid,String message){helper.assertTrue(valid,message);}
  boolean durable(CompletableFuture<CommitAck> ack){
   if(ack==null||!ack.isDone())return false;
   check(!ack.isCompletedExceptionally()&&ack.getNow(null)!=null&&ack.getNow(null).ackStatus()==AckStatus.DURABLE,"native weather J04 ACK not durable");return true;
  }
  void baseline(){
   level.setWeatherParameters(0,RAIN_BASELINE,false,false);data.setThunderTime(THUNDER_BASELINE);
   baselineAt=level.getGameTime();
   check(data.getClearWeatherTime()==0&&data.getRainTime()==RAIN_BASELINE&&data.getThunderTime()==THUNDER_BASELINE&&!data.isRaining()&&!data.isThundering(),"baseline fixture was not installed");
  }
  PlanningSnapshot snapshot(){
   var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
   return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),
    now(),Map.of(),context,Set.of(),Set.of(),HealthMode.NORMAL),ownership.get(),
    new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
  }
  EffectIntent intent(String weather){return new EffectIntent(new OperationToken(UUID.randomUUID()),
   new PrimitiveId("proutlost:global_weather_lease"),1,new GlobalWeatherLease(dimension,
    new ResourceId(weather),new DurationTicks(LEASE),external.get()),footprint,instance);}
  Execution submit(EffectPreparation captured){
   var e=new Execution();e.captured=captured;e.plan=captured.prepared().orElseThrow();var i=e.plan.intent();
   e.arbiter=new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,
    new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   var scope=new ScopeRef(ScopeKind.WORLD,world.value());
   var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),instance,
    new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),now().plus(2000),e.plan.claims(),Map.of(scope,e.plan.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(candidate),now(),Map.of(key,owner.epoch()),Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
   floor=e.token.generation().value();e.live=new LiveValidationSnapshot(e.plan.preconditions(),Set.of(),HealthMode.NORMAL,now(),e.token.generation());
   var authority=new CommitPermit.Authority(store,4);
   var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},s->e.live,
    Map.of(i.primitive(),binding),binding.capabilities(),4,s->new StabilizationLiveSnapshot(store.session(),e.live,ownership.get()),m->{},(m,r)->{});
   port.hydrate(e.plan,captured.blobs());
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),footprint,e.plan.preconditions(),List.of(i),e.plan.budgetEstimate());
   e.prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),e.token.generation(),
    e.plan.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),List.of(),Set.of(),List.of(),e.plan.durableBlobs());
   e.runner=new EventRunner(authority,store,port,e.arbiter,4);
   var batch=new DurableBatch(e.prepare.transactionId(),store.session().storeGeneration(),e.prepare.readSet().revisions(),
    List.of(new DurablePrepare(e.prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(e.prepare));
   e.runner.submit(batch,e.plan.expectedPost(),e.live,actual->ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
    store.session().storeGeneration(),Map.of(),List.of(new DurableResult(new ResultRecord(e.prepare.transactionId(),i.operationToken(),actual,Set.of(),List.of(),List.of()))),List.of()));
   return e;
  }
  EventRunner.View poll(Execution e){
   e.live=new LiveValidationSnapshot(e.plan.preconditions(),Set.of(),HealthMode.NORMAL,now(),e.token.generation());
   var view=e.runner.poll(e.prepare.transactionId(),e.live);
   check(view.stage()!=EventRunner.Stage.REJECTED&&view.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native weather effect rejected: "+view.failure());
   if(view.result().isPresent()&&!e.observed){
    e.observed=true;e.appliedAt=level.getGameTime();
    check(view.result().orElseThrow().outcome()==Outcome.SUCCEEDED,"native weather operation not successful");
    if(e==forward){
     check(data.isRaining()&&!data.isThundering()&&data.getClearWeatherTime()==0&&data.getRainTime()==LEASE&&data.getThunderTime()==capturedThunder,"forward must install full native TTL at actual apply");
     check(e.appliedAt-forwardCaptureAt>=6,"the fixture did not exercise real timer drift before durable permit");
     System.out.println("DIRECTOR_NATIVE_WEATHER_LIVE_APPLIED after_capture_ticks="+(e.appliedAt-baselineAt)+" rain="+data.getRainTime()+" cycle=true");
    }else{
     restoredBaseline(e.appliedAt);
     check(e.appliedAt-inverseCaptureAt>=6,"inverse did not exercise real capture/ACK drift");
     System.out.println("DIRECTOR_NATIVE_WEATHER_LIVE_AGED_RESTORE_APPLIED elapsed="+(e.appliedAt-baselineAt)+" rain="+data.getRainTime()+" thunder="+data.getThunderTime());
    }
   }
   return view;
  }
  void restoredBaseline(long at){
   long elapsed=at-baselineAt;check(elapsed>=0&&elapsed<RAIN_BASELINE,"fixture baseline expired before proof");
   check(data.getClearWeatherTime()==0&&!data.isRaining()&&!data.isThundering()&&
    data.getRainTime()==RAIN_BASELINE-elapsed&&data.getThunderTime()==THUNDER_BASELINE-elapsed,
    "inverse restarted stale timers or failed counterfactual natural decrement: elapsed="+elapsed+" actual="+NativeState.read(data));
  }
  void restoreFixture(){
   if(fixtureRestored)return;fixtureRestored=true;
   // Disposable test-fixture teardown, never counted as a J05 effect or inverse proof.
   level.setWeatherParameters(original.clear(),original.rain(),original.raining(),original.thundering());
   data.setThunderTime(original.thunder());level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(originalCycle,server);
  }
  void advance(){
   if(done)return;
   try{
    check(server.isSameThread(),"native weather must remain on server actor");
    check(level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE),"weather-cycle freeze invalidates this qualification");
    switch(phase){
     case 0->{
      if(!opening.isDone())return;check(!opening.isCompletedExceptionally(),"native weather WAL open failed");store=opening.getNow(null);
      check(store!=null&&!store.readOnlySafe(),"native weather writer unavailable");
      initial=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(owner)),List.of())).toCompletableFuture();phase=1;
     }
     case 1->{
      if(!durable(initial))return;baseline();phase=12;
     }
     case 12->{
      // Native visual thunder must fade by genuine WEATHER_CYCLE ticks. No setter
      // manufactures a rendering proof or freezes counters to make this pass.
      if(level.getThunderLevel(1.0F)>0)return;
      int actualThunder=data.getThunderTime();boolean refused=false;
      try{
       data.setThunderTime(LEASE+100);
       try{binding.capture(intent("minecraft:rain"),snapshot());}
       catch(IllegalStateException expected){refused=true;}
      }finally{data.setThunderTime(actualThunder);}
      check(refused,"RAIN admitted a thunder countdown at the unsafe lease/fade boundary");
      capturedThunder=data.getThunderTime();forwardCaptureAt=level.getGameTime();
      forwardCapture=binding.capture(intent("minecraft:rain"),snapshot());
      var p=forwardCapture.prepared().orElseThrow();capturedStop=forwardCapture.blobs().contents().stream().filter(b->b.reference().equals(p.durableBlobs().stopPlan())).findFirst().orElseThrow();
      phase=2;
     }
     case 2->{
      if(level.getGameTime()-forwardCaptureAt<6)return;restoredBaseline(level.getGameTime());
      var p=forwardCapture.prepared().orElseThrow();check(binding.revalidate(p,forwardCapture.blobs()),"natural weather decrement invalidated PRE before J04");
      var rotated=new OwnershipRecord(owner.header(),key,Set.of(new OwnerToken(UUID.randomUUID())),owner.epoch(),owner.disposition(),owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch(),owner.exceptionContractId());
      ownership.set(new OwnershipView(Map.of(key,rotated)));check(!binding.revalidate(p,forwardCapture.blobs()),"clock drift fix bypassed exact owner token");ownership.set(new OwnershipView(Map.of(key,owner)));
      external.set(new Revision(2));check(!binding.revalidate(p,forwardCapture.blobs()),"clock drift fix bypassed external revision");external.set(new Revision(1));
      forward=submit(forwardCapture);check(!data.isRaining(),"weather mutation preceded its J04 PREPARE permission");phase=3;
     }
     case 3->{if(poll(forward).stage()==EventRunner.Stage.COMMITTED)phase=4;}
     case 4->{
      if(data.isRaining()||data.getRainTime()<=LEASE)return;
      check(level.getGameTime()-forward.appliedAt>=LEASE,"rain stopped before the real native lease TTL");
      check(data.getRainTime()>LEASE,"native expiry did not perform a random vanilla reset after zero");
      check(!data.isThundering()&&!level.isThundering(),"RAIN expiry armed raw/native thunder");
      check(data.getThunderTime()==capturedThunder-(level.getGameTime()-forward.appliedAt),"observed non-thunder timer did not age natively");
      System.out.println("DIRECTOR_NATIVE_WEATHER_TRUE_CYCLE_EXPIRY_AND_RANDOM_RESET rain="+data.getRainTime()+" thunder="+data.getThunderTime());
      var inverseIntent=intent("proutlost_director:restore");binding.registerRestore(inverseIntent,capturedStop);
      inverseCapture=binding.capture(inverseIntent,snapshot());
      var targetRef=inverseCapture.prepared().orElseThrow().durableBlobs().snapshots().stream().filter(r->r.codecId().value().equals("proutlost_director:weather_restore_source_v1")).findFirst().orElseThrow();
      check(!targetRef.equals(capturedStop.reference())&&!targetRef.hash().equals(capturedStop.reference().hash()),"inverse TARGET aliases the already-forced original TIMED stop");
      inverseCaptureAt=level.getGameTime();phase=5;
     }
     case 5->{
      if(level.getGameTime()-inverseCaptureAt<6)return;
      check(binding.revalidate(inverseCapture.prepared().orElseThrow(),inverseCapture.blobs()),"natural inverse PRE decrement invalidated durable cleanup");
      inverse=submit(inverseCapture);phase=6;
     }
     case 6->{
      if(poll(inverse).stage()!=EventRunner.Stage.COMMITTED)return;
      restoredBaseline(level.getGameTime());
      released=new OwnershipRecord(new AggregateHeader(1,owner.header().id(),owner.header().revision().plus(1),owner.header().lastSequence(),now()),key,owner.ownerTokens(),owner.epoch(),OwnershipDisposition.RELEASED,owner.creationMutationId(),inverse.plan.expectedPost().get(key),owner.playerOverrideEpoch(),owner.exceptionContractId());
      releasedAck=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(released)),List.of())).toCompletableFuture();phase=7;
     }
     case 7->{
      if(!durable(releasedAck))return;check(forward.arbiter.release(forward.token)&&inverse.arbiter.release(inverse.token),"weather lease claim leaked");
      ownership.set(new OwnershipView(Map.of(key,released)));closing=store.closeAsync().toCompletableFuture();phase=8;
     }
     case 8->{if(!closing.isDone())return;check(!closing.isCompletedExceptionally(),"weather WAL close failed");reopening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=9;}
     case 9->{
      if(!reopening.isDone())return;check(!reopening.isCompletedExceptionally(),"same weather WAL reopen failed");store=reopening.getNow(null);
      check(store!=null&&!store.readOnlySafe(),"same weather WAL reopened fail-closed: "+(store==null?"unavailable":store.diagnostic()));
      var query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),64);var read=store.view(query);ReadContracts.validate(read,query,store.session());
      check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"same weather WAL not completely resident after reopen");
      var records=read.page().orElseThrow().records();
      check(records.stream().filter(r->r instanceof DurablePrepare).count()==2&&records.stream().filter(r->r instanceof DurableResult d&&d.value().result().outcome()==Outcome.SUCCEEDED).count()==2,"forward and inverse durable PREPARE/RESULT pairs missing");
      check(records.stream().filter(r->r instanceof DurableOwnership d&&d.value().equals(released)).count()==1,"weather RELEASED owner not restored from the same WAL");
      restoredBaseline(level.getGameTime());closing=store.closeAsync().toCompletableFuture();phase=10;
     }
     case 10->{
      if(!closing.isDone())return;check(!closing.isCompletedExceptionally(),"weather final WAL close failed");
      restoreFixture();done=true;phase=11;System.out.println("DIRECTOR_NATIVE_WEATHER_TRUE_CYCLE_J04_FORWARD_EXPIRED_AGED_INVERSE_REOPEN_PASS");
     }
     default->throw new IllegalStateException("native weather test phase");
    }
   }catch(RuntimeException failure){
    if(!firstFailureLogged){
     firstFailureLogged=true;
     System.err.println("DIRECTOR_NATIVE_WEATHER_FIRST_FAILURE phase="+phase+" native_tick="+level.getGameTime()+" cycle="+level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)+" baseline_at="+baselineAt+" forward_capture_at="+forwardCaptureAt+" inverse_capture_at="+inverseCaptureAt+" actual="+NativeState.read(data));
     failure.printStackTrace();
    }
    restoreFixture();if(store!=null&&closing==null)store.closeAsync();throw failure;
   }
  }
 }
}
