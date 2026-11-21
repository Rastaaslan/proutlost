package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Private, real-fsync LAB regression. No production context or visit is authored.
 * A 6002-DATA history must publish while another real writer appends every 200
 * actual server ticks, then keep its last complete memory during an idle tail. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeMemoryTailGameTests {
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,16777216,33554432,67108864,32,0,128);
 private DirectorNativeMemoryTailGameTests(){}
 private static final class Probe {
  final GameTestHelper h;final MinecraftServer server;final WorldId world;
  final RegionId regionId=new RegionId(UUID.randomUUID());final RegionRecord region;
  final CompletableFuture<JournalStore> opening;
  JournalStore store;DirectorNativeMemoryProjectionQualification projection;
  CompletableFuture<CommitAck> writing;DurableBatch batch;CompletableFuture<Void> closing;
  long started,lastAckCut;int phase,writes,seeded;RegionMemory latest;boolean initialDeadlineMissed;
  List<DurableRecord> seedRows;String failedMessage;
  Probe(GameTestHelper helper){
   h=helper;server=h.getLevel().getServer();
   require(Boolean.getBoolean("proutlost.director.nativeLab")&&Boolean.getBoolean("proutlost.director.nativeGameTests"),"explicit native LAB required");
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   var pos=h.absolutePos(new BlockPos(1,2,1));var dimension=new DimensionId(h.getLevel().dimension().location().toString());
   var point=new BlockPosition(dimension,pos.getX(),pos.getY(),pos.getZ());
   var header=new AggregateHeader(1,regionId.value(),new Revision(1),new Sequence(0),now());
   region=new RegionRecord(header,regionId,dimension,RegionStatus.ACTIVE,
    new SupportShape(List.of(),List.of(new SupportDisc(point,6)),List.of()),Set.of(),new UnitValue(.5),
    Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
   latest=memory(1);
   opening=JournalStore.open(server.getWorldPath(LevelResource.ROOT).resolve("director-native-memory-tail")
    .resolve(UUID.randomUUID().toString()),world,OPTIONS).toCompletableFuture();
  }
  Tick now(){return new Tick(server.overworld().getGameTime());}
  void require(boolean value,String detail){h.assertTrue(value,detail);}
  RegionMemory memory(int revision){
   return new RegionMemory(new AggregateHeader(1,regionId.value(),new Revision(revision),new Sequence(0),now()),
    regionId,Map.of(),new Tick(0),new Tick(0),
    Set.of(new ResourceId("proutlost_director:memory_tail_fixture_revision_"+revision)),Set.of(),
    new RegionActivityState(Map.of(),Map.of()));
  }
  void append(List<DurableRecord> rows){
   require(writing==null,"one private writer");
   batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
   writing=store.append(batch).toCompletableFuture();
  }
  boolean acked(){
   if(!writing.isDone())return false;
   require(!writing.isCompletedExceptionally(),"actual private J04 append failed");
   var ack=writing.getNow(null);
   require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(batch.transactionId())
    &&ack.transactionHash().equals(batch.transactionHash())&&ack.storeGeneration().equals(store.session().storeGeneration())
    &&ack.durableSequence().value()>lastAckCut&&ack.forcedBlobs().isEmpty(),"actual exact fsync ACK required");
   lastAckCut=ack.durableSequence().value();writing=null;batch=null;return true;
  }
  List<DurableRecord> seed(){
   var rows=new ArrayList<DurableRecord>();
   for(int i=0;i<6000;i++)rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),
    new FactType("proutlost_director:native_memory_tail_old_fixture"),
    List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.empty(),EvidenceStatus.POSSIBLE,
    Map.of("native_fixture",new JsonValue.Bool(true)),true,Optional.empty())));
   rows.add(new DurableAggregate(new RegionAggregate(region)));
   rows.add(new DurableAggregate(new RegionMemoryAggregate(latest)));
   return rows;
  }
  void poll(){
   require(server.isSameThread(),"native server actor required");
   switch(phase){
    case 0->{
     if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"private real J04 open failed");
     store=opening.getNow(null);require(!store.readOnlySafe(),"private J04 must be healthy");
     seedRows=seed();seeded=Math.min(2000,seedRows.size());append(seedRows.subList(0,seeded));phase=1;
    }
    case 1->{
     if(!acked())return;
     if(seeded<seedRows.size()){
      int end=Math.min(seeded+2000,seedRows.size());
      append(seedRows.subList(seeded,end));seeded=end;return;
     }
     // Every seed chunk has its own real fsync ACK before the timed scan.
     require(seeded==6002&&lastAckCut>5120,"fixture must exceed 5120 actual durable DATA rows");
     seedRows=null;
     var query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),512);
     var read=store.view(query);ReadContracts.validate(read,query,store.session());
     require(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().hasMore(),"actual long resident chronology required");
     projection=new DirectorNativeMemoryProjectionQualification(server,world,new DirectorLabReadOnlyStore(store),DirectorLabObservationProfile.loadRuntime());
     started=now().value();phase=2;
    }
    case 2->{
     projection.poll();var status=projection.status();long elapsed=now().value()-started;
     require(!status.failClosed(),"idle future tail or genuine append failed the projection");
     if(elapsed>=160&&status.completedScans()==0)initialDeadlineMissed=true;
     if(writing!=null)acked();
     if(writing==null&&writes<2&&elapsed>=(writes+1)*200L){
      writes++;latest=memory(writes+1);append(List.of(new DurableAggregate(new RegionMemoryAggregate(latest))));
     }
     if(elapsed<440||writes!=2||writing!=null)return;
     require(!initialDeadlineMissed,"long scan starved behind 20-tick per-page cadence");
     require(status.complete()&&status.completedScans()>=3&&status.lastDurableSequence()>=lastAckCut,
      "real 200-tick writers did not reach an atomically complete memory tail");
     var actual=projection.view().orElseThrow();
     require(actual.regions().stream().filter(m->m.regionId().equals(regionId)).findFirst().orElseThrow().equals(latest),
      "last fully published memory lost the actual latest revision");
     require(actual.regionRecords().get(regionId).equals(region),"incremental tail dropped an unchanged region");
     projection.close();closing=store.closeAsync().toCompletableFuture();phase=3;
    }
    case 3->{
     if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"private J04 close failed");
     System.out.println("DIRECTOR_NATIVE_MEMORY_TAIL_REAL_6002_DATA_200T_WRITER_PASS sequence="+lastAckCut+
      " memory_revision="+latest.header().revision().value()+" elapsed_ticks="+(now().value()-started)+" human=false main_store=false");
     phase=4;
    }
   }
  }
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=4000)
 public static void longHistoryAndRealPeriodicWriterKeepPublishedMemory(GameTestHelper helper){
  var probe=new Probe(helper);helper.succeedWhen(()->{
   if(probe.failedMessage!=null)helper.fail(probe.failedMessage);
   try{probe.poll();}catch(GameTestAssertException assertion){throw assertion;}
   catch(RuntimeException unexpected){probe.failedMessage=unexpected.toString();helper.fail(probe.failedMessage);}
   helper.assertTrue(probe.phase==4,"actual memory tail regression pending phase="+probe.phase);
  });
 }
}
