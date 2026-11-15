package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.gametest.*;

/** Opt-in native STORM provider/J04 IO boundary, frozen-cycle fixture only.
 * Two real physical rain writes, one per loss mode, each followed by actual
 * private WAL close/reopen, native SAME_POST readback and one durable inverse.
 * No Minecraft cold restart, natural weather, WARN/J06, player damage or G5 claim. */
@GameTestHolder("proutlost_director_writer_loss") @PrefixGameTestTemplate(false)
public final class NativeStormWriterLossGameTests {
 private NativeStormWriterLossGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 @GameTestGenerator public static Collection<TestFunction> writerLossStorm(){
  if(!Boolean.getBoolean("proutlost.director.nativeWriterLossGameTest"))return List.of();
  return List.of(new TestFunction("native_writer_loss_storm","native_storm_actual_io_loss_and_cleanup",
   "proutlost_director_writer_loss:smoke_empty",Rotation.NONE,16000,0,true,h->{
    final Suite[] suite={null};h.succeedWhen(()->{
     if(suite[0]==null)suite[0]=new Suite(h);
     suite[0].poll();h.assertTrue(suite[0].done,"native STORM writer-loss IO/reopen/cleanup pending");
    });
   }));
 }
 private static final class Suite {
  final GameTestHelper h;final MinecraftServer server;int index;Case current;boolean acquired,done;
  Suite(GameTestHelper h){this.h=h;server=h.getLevel().getServer();}
  void poll(){
   if(done)return;if(!acquired){if(!NativeWriterLossCases.tryAcquire(server,this))return;acquired=true;}
   if(current==null)current=new Case(h,NativeWriterLossProbe.FailurePoint.values()[index]);
   current.poll();if(!current.done)return;
   if(++index<NativeWriterLossProbe.FailurePoint.values().length){current=null;return;}
   NativeWriterLossCases.release(server,this);done=true;
   System.out.println("DIRECTOR_NATIVE_STORM_WRITER_LOSS_TWO_REAL_IO_BOUNDARIES_PASS full_disaster=false natural_cycle=false minecraft_cold=false client=false g5=false");
  }
 }
 private static final class Case {
  final GameTestHelper h;final MinecraftServer server;final ServerLevel level;final ServerLevelData data;
  final NativeWriterLossProbe.FailurePoint point;final WorldId world;final DimensionId dimension;final ResourceKey key;
  final int clearBefore,rainBefore,thunderBefore;final boolean rainingBefore,thunderingBefore,cycleBefore;
  final java.nio.file.Path path;final UUID event=UUID.randomUUID();final OwnershipRecord owner;
  final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>();final Footprint footprint;
  final AtomicInteger applyCalls=new AtomicInteger();final CompletableFuture<NativeWriterLossProbe> opening;
  NativeWriterLossProbe store;WeatherBinding binding;EffectPreparation captured;
  NativeWriterLossCases.Execution forward,inverse;CompletableFuture<CommitAck> ack;
  CompletableFuture<Void> closing;CompletableFuture<NativeWriterLossProbe> reopening;
  CompletableFuture<BlobReadResult> hydrating;BlobBundle recovered;int phase;boolean done;
  Case(GameTestHelper h,NativeWriterLossProbe.FailurePoint point){
   this.h=h;this.point=point;level=h.getLevel();server=level.getServer();data=(ServerLevelData)level.getLevelData();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   clearBefore=data.getClearWeatherTime();rainBefore=data.getRainTime();thunderBefore=data.getThunderTime();
   rainingBefore=data.isRaining();thunderingBefore=data.isThundering();cycleBefore=level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
   // Explicit native-only fixture setup, before admission and any physical permit.
   level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
   level.setWeatherParameters(0,0,false,false);data.setThunderTime(0);
   dimension=new DimensionId(level.dimension().location().toString());key=new ResourceKey(OwnershipDomain.WEATHER,dimension,"weather");
   footprint=new Footprint(dimension,Set.of(),Set.of(),List.of());
   owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),key,
    Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
    new MutationId(UUID.randomUUID()),nativeHash(),new Epoch(0),Optional.empty());owners.put(key,owner);
   path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-writer-loss-storm-"+event);
   opening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();
  }
  Tick now(){return new Tick(server.overworld().getGameTime());}
  void check(boolean ok,String why){h.assertTrue(ok,why);}
  Fingerprint nativeHash(){return BlobContent.hash(ByteBuffer.allocate(14).putInt(data.getClearWeatherTime()).putInt(data.getRainTime())
   .putInt(data.getThunderTime()).put((byte)(data.isRaining()?1:0)).put((byte)(data.isThundering()?1:0)).array());}
  PlanningSnapshot snapshot(){return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),
   new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL),
   new OwnershipView(owners),new BudgetView(NativeWriterLossCases.CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));}
  WeatherBinding bind(){return new WeatherBinding(d->{check(d.equals(dimension),"actual native dimension");return level;},()->new OwnershipView(owners),d->new Revision(1),8);}
  ServerEffectPort.Binding counted(WeatherBinding real){return new ServerEffectPort.Binding(){
   public ProviderMode mode(){return real.mode();}public Set<PrimitiveId> supportedPrimitives(){return real.supportedPrimitives();}
   public Set<CapabilityId> capabilities(){return real.capabilities();}public EffectPreparation capture(EffectIntent i,PlanningSnapshot s){return real.capture(i,s);}
   public boolean revalidate(PreparedEffect p,BlobBundle b){return real.revalidate(p,b);}
   public SliceResult apply(PreparedEffect p,BlobBundle b){applyCalls.incrementAndGet();return real.apply(p,b);}
   public ReconcileResult reconcile(RecoveryProbe p){return real.reconcile(p);}
  };}
  void append(List<DurableRecord> rows){ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of())).toCompletableFuture();}
  boolean acked(){if(!ack.isDone())return false;check(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE,"real private J04 ACK");return true;}
  List<DurableRecord> rows(){var q=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);var r=store.view(q);
   ReadContracts.validate(r,q,store.session());check(r.status()==ReadStatus.AVAILABLE&&!r.page().orElseThrow().hasMore(),"bounded same-WAL read");return r.page().orElseThrow().records();}
  void poll(){
   check(server.isSameThread(),"STORM fixture actor");if(done)return;
   switch(phase){
    case 0->{if(!opening.isDone())return;check(!opening.isCompletedExceptionally(),"real private store open");store=opening.getNow(null);check(!store.readOnlySafe(),"private writer starts live");append(List.of(new DurableOwnership(owner)));phase=1;}
    case 1->{if(!acked())return;binding=bind();var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:global_weather_lease"),1,
     new GlobalWeatherLease(dimension,new ResourceId("minecraft:rain"),new DurationTicks(4000),new Revision(1)),footprint,new EventInstanceId(event));
     captured=binding.capture(intent,snapshot());forward=new NativeWriterLossCases.Execution(server,world,store,counted(binding),captured,()->new OwnershipView(owners),this::now,r->List.of(),Optional.of(point));
     forward.submit();check(!data.isRaining()&&applyCalls.get()==0,"native weather must wait for real PREPARE fsync");phase=2;}
    case 2->{var v=forward.poll();check(v.stage()!=EventRunner.Stage.REJECTED,"physical weather rejected before injected IO loss "+v.failure());
     if(v.stage()!=EventRunner.Stage.RECOVERY_REQUIRED)return;
     check(store.injection().isPresent()&&store.readOnlySafe()&&store.authoritySessionRevoked(),"real IO loss must revoke J04 writer session");
     var a=forward.actual().orElseThrow();check(a.outcome()==Outcome.SUCCEEDED&&a.appliedOperationIds().equals(Set.of(forward.prepare().operationToken()))
      &&a.observedHashes().equals(forward.plan().expectedPost())&&nativeHash().equals(forward.plan().expectedPost().get(key))&&data.isRaining()&&!data.isThundering()&&applyCalls.get()==1,
      "one ACTUAL rain application before writer loss");closing=store.closeAsync().toCompletableFuture();phase=3;}
    case 3->{if(!closing.isDone())return;check(!closing.isCompletedExceptionally(),"failed writer drain/release");reopening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();phase=4;}
    case 4->{if(!reopening.isDone())return;check(!reopening.isCompletedExceptionally(),"same private WAL reopen");store=reopening.getNow(null);check(!store.readOnlySafe(),"reopened actual writer live");
     check(rows().stream().filter(DurablePrepare.class::isInstance).count()==1,"exact original PREPARE");
     var refs=DurableBlobReferences.collect(forward.prepare());hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();phase=5;}
    case 5->{if(!hydrating.isDone())return;check(!hydrating.isCompletedExceptionally(),"real forced-blob hydration");var b=hydrating.getNow(null);
     check(b.status()==AckStatus.DURABLE&&b.errors().isEmpty(),"forced original blob bytes available");recovered=new BlobBundle(b.contents());
     check(new HashSet<>(b.contents().stream().map(BlobContent::reference).toList()).equals(DurableBlobReferences.collect(forward.prepare())),"exact original sealed reference set");
     binding=bind();var proof=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(nativeHash()),Optional.of(owner.ownerTokens().iterator().next()),owner.epoch(),owner.playerOverrideEpoch(),false);
     check(binding.reconcile(new RecoveryProbe(forward.plan(),List.of(proof),recovered)).status()==ReconcileStatus.SAME_POST&&applyCalls.get()==1,"actual native POST/owner reconciliation without rain replay");
     var old=rows().stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value).toList();
     check(old.size()==(point==NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE?0:1),"actual absent RESULT vs forced ACK-lost RESULT distinction");
     if(old.isEmpty()){
      var observed=new SliceResult(Outcome.RECONCILED,Set.of(forward.prepare().operationToken()),Map.of(key,nativeHash()),0,Set.of(),Set.of(),List.of());
      append(List.of(new DurableResult(new ResultRecord(forward.prepare().transactionId(),forward.prepare().operationToken(),observed,Set.of(),List.of(),List.of()))));phase=6;
     }else{var r=old.getFirst();check(r.prepareTransactionId().equals(forward.prepare().transactionId())&&r.operationToken().equals(forward.prepare().operationToken())
       &&r.result().outcome()==Outcome.SUCCEEDED&&r.result().observedHashes().equals(forward.plan().expectedPost()),"keep real forced RESULT, never duplicate");phase=7;}
    }
    case 6->{if(!acked())return;phase=7;}
    case 7->{var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:global_weather_lease"),1,
      new GlobalWeatherLease(dimension,new ResourceId("proutlost_director:restore"),new DurationTicks(4000),new Revision(1)),footprint,new EventInstanceId(event));
     var stop=recovered.contents().stream().filter(b->b.reference().equals(forward.plan().durableBlobs().stopPlan())).findFirst().orElseThrow();binding.registerRestore(intent,stop);
     var inverseCapture=binding.capture(intent,snapshot());inverse=new NativeWriterLossCases.Execution(server,world,store,counted(binding),inverseCapture,()->new OwnershipView(owners),this::now,r->{
      check(r.outcome()==Outcome.SUCCEEDED&&nativeHash().equals(owner.expectedPostHash()),"real native inverse POST");
      var released=new OwnershipRecord(new AggregateHeader(1,owner.header().id(),owner.header().revision().plus(1),new Sequence(0),now()),key,owner.ownerTokens(),owner.epoch(),OwnershipDisposition.RELEASED,
       owner.creationMutationId(),nativeHash(),owner.playerOverrideEpoch(),owner.exceptionContractId());return List.of(new DurableOwnership(released));
     },Optional.empty());inverse.submit();phase=8;}
    case 8->{var v=inverse.poll();check(v.stage()!=EventRunner.Stage.REJECTED&&v.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"new inverse must commit "+v.failure());if(v.stage()!=EventRunner.Stage.COMMITTED)return;
     inverse.releaseCommitted();check(!data.isRaining()&&!data.isThundering()&&nativeHash().equals(owner.expectedPostHash())&&applyCalls.get()==2,"exact native baseline with only forward+inverse writes");
     var rs=rows();check(rs.stream().filter(DurablePrepare.class::isInstance).count()==2&&rs.stream().filter(DurableResult.class::isInstance).count()==2&&
      rs.stream().anyMatch(r->r instanceof DurableOwnership o&&o.value().disposition()==OwnershipDisposition.RELEASED),"two real prepares/results plus RELEASED");
     closing=store.closeAsync().toCompletableFuture();phase=9;}
    case 9->{if(!closing.isDone())return;check(!closing.isCompletedExceptionally(),"private final close");
     // Remove fixture configuration only after the owned lease has its real inverse RESULT.
     level.setWeatherParameters(clearBefore,rainBefore,rainingBefore,thunderingBefore);data.setThunderTime(thunderBefore);
     level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(cycleBefore,server);done=true;
     System.out.println("DIRECTOR_NATIVE_STORM_PRIVATE_WRITER_LOSS_PASS point="+point+" event="+event+" path="+path+" apply_calls=2 cold_minecraft=false client=false");}
    default->throw new IllegalStateException("native STORM writer-loss phase");
   }
  }
 }
}
