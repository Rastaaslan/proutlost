package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.observation.NeoForgeObservation;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

/** Explicit native fixture: actual Minecraft block events -> shared owner ->
 * real private J04 ACK/readback. No human/client/J06 or main-store claim. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeAnchorMetadataLeaseGameTests {
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private DirectorNativeAnchorMetadataLeaseGameTests(){}
 private static final class Probe {
  final GameTestHelper h; final ServerLevel level; final MinecraftServer server;
  final BlockPos block; final BlockState original; final WorldId world;
  final Object lane=new Object(); final ArrayDeque<AnchorRecord> observed=new ArrayDeque<>();
  final List<AnchorRecord> observedAll=new ArrayList<>();
  final IEventBus bus=BusBuilder.builder().build();
  final DirectorNativeAnchorOwner owner; final NeoForgeObservation observation;
  CompletableFuture<JournalStore> opening; JournalStore store;
  CompletableFuture<CommitAck> writing; DurableBatch batch; AnchorRecord pending,before,metadata,broken;
  CompletableFuture<StoreReadResult> reading; StoreQuery query;
  CompletableFuture<Void> closing; int phase; long breakTick; int savedRows;
  Probe(GameTestHelper h){
   this.h=h;level=h.getLevel();server=level.getServer();
   h.assertTrue(Boolean.getBoolean("proutlost.director.nativeLab")&&Boolean.getBoolean("proutlost.director.nativeGameTests"),"explicit native LAB fixture required");
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   block=h.absolutePos(new BlockPos(1,2,1));original=level.getBlockState(block);
   owner=new DirectorNativeAnchorOwner(()->h.assertTrue(server.isSameThread(),"server actor required"),Map.of(),8,8,
    a->{observed.addLast(a);observedAll.add(a);},id->!observed.isEmpty(),()->true);
   observation=new NeoForgeObservation(server,world,()->new DirectorClock(now(),System.nanoTime(),Instant.now()),
    p->Optional.empty(),id->new ClientCapabilitySnapshot(1,Set.of(),Map.of(),false),()->HealthMode.NORMAL,
    p->List.of(),a->{throw new IllegalStateException("legacy anchor path must not be used");},
    DirectorLabObservationProfile.loadRuntime(),false,new Epoch(1),owner::observeCamp);
   observation.register(bus);
  }
  Tick now(){return new Tick(server.overworld().getGameTime());}
  void require(boolean value,String why){h.assertTrue(value,why);}
  void place(){
   var previous=level.getBlockState(block);var snapshot=BlockSnapshot.create(level.dimension(),level,block);
   level.setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),Block.UPDATE_CLIENTS);
   var event=new BlockEvent.EntityPlaceEvent(snapshot,previous,FakePlayerFactory.getMinecraft(level));bus.post(event);
   require(!event.isCanceled()&&level.getBlockState(block).is(Blocks.CAMPFIRE),"actual fixture placement failed");
  }
  void breakCamp(){
   var event=new BlockEvent.BreakEvent(level,block,level.getBlockState(block),FakePlayerFactory.getMinecraft(level));bus.post(event);
   require(!event.isCanceled(),"actual fixture break event canceled");
   level.setBlock(block,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
   require(level.getBlockState(block).isAir(),"actual fixture break did not change Minecraft");
  }
  void append(AnchorRecord value){
   require(writing==null,"one private writer");pending=value;
   batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
    List.of(new DurableAggregate(new AnchorAggregate(value))),List.of());
   writing=store.append(batch).toCompletableFuture();
  }
  boolean acked(){
   if(!writing.isDone())return false;require(!writing.isCompletedExceptionally(),"actual private metadata append failed");
   var ack=writing.getNow(null);
   require(ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.transactionId().equals(batch.transactionId())&&
    ack.transactionHash().equals(batch.transactionHash())&&ack.storeGeneration().equals(store.session().storeGeneration())&&
    ack.durableSequence().value()>0&&ack.forcedBlobs().isEmpty(),"actual exact J04 metadata ACK required");
   query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.ANCHOR,pending.anchorId().value())),new Sequence(0),Optional.empty(),16);
   var result=store.view(query);ReadContracts.validate(result,query,store.session());
   require(result.status()==ReadStatus.AVAILABLE&&!result.page().orElseThrow().hasMore(),"actual metadata point read unavailable");
   var rows=result.page().orElseThrow().aggregates();
   require(rows.size()==1&&rows.getFirst().committedSequence().equals(ack.durableSequence())&&
    rows.getFirst().state().equals(new AnchorAggregate(pending)),"ACKed metadata differs from actual J04 readback");
   owner.mergeDurable(Map.of(pending.anchorId(),((AnchorAggregate)rows.getFirst().state()).value()),ack.durableSequence().value());
   writing=null;batch=null;pending=null;return true;
  }
  AnchorRecord metadata(AnchorRecord old){
   var tags=new HashSet<>(old.tags());tags.add(new TagId("proutlost:lab_metadata_lease_evidence"));var head=old.header();
   return new AnchorRecord(new AggregateHeader(1,head.id(),head.revision().plus(1),head.lastSequence(),now()),old.anchorId(),
    old.locationId(),old.regionIds(),old.dimension(),old.position(),old.radiusProfile(),tags,old.origin(),old.evidence(),old.active());
  }
  void poll(){
   require(server.isSameThread(),"native fixture actor");
   switch(phase){
    case 0->{
     if(opening==null){opening=JournalStore.open(server.getWorldPath(LevelResource.ROOT).resolve("director-native-anchor-metadata-lease"),world,OPTIONS).toCompletableFuture();return;}
     if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"private J04 open failed");store=opening.getNow(null);
     require(!store.readOnlySafe(),"healthy actual private store required");place();
     require(observed.size()==1,"real camp placement did not reach owner exactly once");before=observed.removeFirst();append(before);phase=1;
    }
    case 1->{
     if(!acked())return;require(owner.durablyCurrent(before),"initial anchor not durably current");owner.beginMetadata(lane);
     breakTick=now().value();breakCamp();require(owner.deferredChanges()==1&&observed.isEmpty()&&
      owner.current(before.anchorId()).equals(Optional.of(before)),"block break escaped metadata lease before ACK");phase=2;
    }
    case 2->{
     if(now().value()<=breakTick)return;metadata=metadata(before);require(metadata.header().updatedTick().value()>breakTick,"real later metadata tick required");
     append(metadata);phase=3;
    }
    case 3->{
     if(!acked())return;require(owner.current(before.anchorId()).equals(Optional.of(metadata))&&owner.deferredChanges()==1&&
      observed.isEmpty(),"ACK/readback/merge did not install metadata before deferred break");owner.endMetadata(lane);
     require(!owner.traceBusy()&&!owner.failed()&&owner.deferredChanges()==0&&observed.size()==1,"metadata release did not flush one real break");
     broken=observed.removeFirst();require(!broken.active()&&broken.anchorId().equals(before.anchorId())&&
      broken.header().revision().equals(before.header().revision().plus(2))&&broken.tags().equals(metadata.tags())&&
      broken.header().updatedTick().compareTo(metadata.header().updatedTick())>=0&&broken.header().updatedTick().value()>breakTick,
      "queued break lost metadata tags/revision or moved logical clock backward");
     require(observedAll.equals(List.of(before,broken)),"metadata path duplicated a J01 camp observation");append(broken);phase=4;
    }
    case 4->{
     if(!acked())return;require(owner.durablyCurrent(broken)&&level.getBlockState(block).isAir(),"durable final break/physical AIR mismatch");
     query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);reading=store.read(query).toCompletableFuture();phase=5;
    }
    case 5->{
     if(!reading.isDone())return;require(!reading.isCompletedExceptionally(),"actual private chronological read failed");var result=reading.getNow(null);
     ReadContracts.validate(result,query,store.session());require(result.status()==ReadStatus.AVAILABLE,"actual private chronology unavailable");
     var page=result.page().orElseThrow();
     for(var row:page.records())if(row instanceof DurableAggregate a&&a.state() instanceof AnchorAggregate anchor&&anchor.value().anchorId().equals(before.anchorId())){
      var expected=List.of(before,metadata,broken);require(savedRows<3&&anchor.value().equals(expected.get(savedRows)),"duplicate or reordered anchor DATA revision");savedRows++;
     }
     if(page.hasMore()){query=new StoreQuery(world,Set.of(),new Sequence(0),page.nextCursor(),128);reading=store.read(query).toCompletableFuture();return;}
     require(savedRows==3,"expected exactly placement/metadata/deferred-break durable revisions");
     observation.close();level.setBlock(block,original,Block.UPDATE_CLIENTS);closing=store.closeAsync().toCompletableFuture();phase=6;
    }
    case 6->{
     if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"real private store close failed");
     System.out.println("DIRECTOR_NATIVE_ANCHOR_METADATA_LEASE_REAL_BLOCK_EVENT_PASS anchor="+before.anchorId().value()+
      " revisions="+before.header().revision().value()+","+metadata.header().revision().value()+","+broken.header().revision().value()+
      " break_tick="+breakTick+" metadata_tick="+metadata.header().updatedTick().value()+" final_tick="+broken.header().updatedTick().value()+
      " actual_data_rows="+savedRows+" human=false main_store=false");phase=7;
    }
   }
  }
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void actualCampBreakWaitsForMetadataAckAndKeepsRevisionTags(GameTestHelper helper){
  var probe=new Probe(helper);helper.succeedWhen(()->{probe.poll();helper.assertTrue(probe.phase==7,"actual metadata lease/J04 interleaving pending phase="+probe.phase);});
 }
}
