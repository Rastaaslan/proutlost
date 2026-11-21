package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.admin.server.NativeReadCommandFence;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Actual fsynced/reopened J04 fixture for read-only inspection.
 * The mutation/ownership records are explicit test fixtures. This test does not
 * claim a physical effect, graphical client, human choice or live OP4 session. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeMutationInspectGameTests {
 private DirectorNativeMutationInspectGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(
  1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

 private static final class Probe {
  final MinecraftServer server;
  final WorldId world;
  final BlockPosition position,missingPosition;
  final ResourceKey resource,missingResource;
  final MutationId target=new MutationId(UUID.randomUUID()),replacement=new MutationId(UUID.randomUUID());
  final MutationId missingOwner=new MutationId(UUID.randomUUID()),tooWide=new MutationId(UUID.randomUUID());
  final EventInstanceId event=new EventInstanceId(UUID.randomUUID());
  final EventInstanceId otherEvent=new EventInstanceId(UUID.randomUUID());
  final OwnerToken token=new OwnerToken(UUID.randomUUID()),replacementToken=new OwnerToken(UUID.randomUUID());
  final UUID ownerId=UUID.randomUUID();
  final SessionId actorSession=new SessionId(UUID.randomUUID());
  final BlobContent pre=BlockTransformBinding.stateBlob(Blocks.STONE.defaultBlockState());
  final BlobContent post=BlockTransformBinding.stateBlob(Blocks.MOSS_BLOCK.defaultBlockState());
  final MutationRecord first,rolledBack,replacementRecord,orphan,oversized;
  final OwnershipRecord before,after;
  final Path directory;
  final CompletableFuture<JournalStore> opening;
  JournalStore store;
  CompletableFuture<CommitAck> committed;
  CompletableFuture<Void> closed;
  CompletableFuture<JournalStore> reopened;
  int phase;
  Probe(GameTestHelper helper){
   server=Objects.requireNonNull(helper.getLevel().getServer());actor();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   var p=helper.absolutePos(new BlockPos(1,2,1));var d=new DimensionId(helper.getLevel().dimension().location().toString());
   position=new BlockPosition(d,p.getX(),p.getY(),p.getZ());
   missingPosition=new BlockPosition(d,p.getX()+2,p.getY(),p.getZ());
   resource=key(position);missingResource=key(missingPosition);
   var footprint=foot(Set.of(position));
   first=mutation(target,event,MutationState.VERIFIED,1,footprint,resource,token,1);
   rolledBack=mutation(target,event,MutationState.ROLLED_BACK,2,footprint,resource,token,1);
   replacementRecord=mutation(replacement,otherEvent,MutationState.VERIFIED,1,footprint,resource,replacementToken,2);
   orphan=mutation(missingOwner,event,MutationState.VERIFIED,1,foot(Set.of(missingPosition)),missingResource,token,1);
   var wide=new HashSet<BlockPosition>();
   for(int i=0;i<17;i++)wide.add(new BlockPosition(d,p.getX()+i,p.getY()+2,p.getZ()));
   oversized=mutation(tooWide,event,MutationState.VERIFIED,1,foot(wide),resource,token,1);
   before=owner(1,target,token,1,post.reference().hash());
   after=owner(2,replacement,replacementToken,2,pre.reference().hash());
   directory=server.getWorldPath(LevelResource.ROOT).resolve("director-mutation-inspect-test-only").resolve(UUID.randomUUID().toString());
   opening=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();
  }
  void actor(){if(!server.isSameThread())throw new IllegalStateException("native mutation inspection actor");}
  ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
  Footprint foot(Set<BlockPosition> cells){
   var chunks=new HashSet<ChunkRef>();for(var p:cells)chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
   return new Footprint(position.dimension(),chunks,cells,List.of());
  }
  MutationRecord mutation(MutationId id,EventInstanceId instance,MutationState state,long revision,
      Footprint fp,ResourceKey read,OwnerToken own,long epoch){
   return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(revision),new Sequence(0),new Tick(20+revision)),
    id,instance,new SliceId(UUID.randomUUID()),state,new PrimitiveId("proutlost:trace"),1,fp,
    pre.reference(),post.reference(),own,new Epoch(epoch),
    new ReadSet(Map.of(),Map.of(read,new Epoch(epoch)),Map.of(read,pre.reference().hash())),
    Set.of(),List.of(),Optional.empty(),new Sequence(1));
  }
  OwnershipRecord owner(long revision,MutationId creation,OwnerToken own,long epoch,Fingerprint expected){
   return new OwnershipRecord(new AggregateHeader(1,ownerId,new Revision(revision),new Sequence(0),new Tick(20+revision)),
    resource,Set.of(own),new Epoch(epoch),OwnershipDisposition.DIRECTOR_ACTIVE,creation,expected,new Epoch(0),Optional.empty());
  }
  DirectorWorldState state(){
   var t=new Tick(40);var hash=pre.reference().hash();
   return new DirectorWorldState(new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),t),
    world,store.session().storeGeneration(),t,RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,PacingState.REST,GlobalTension.CALM,
    new ResourceId("proutlost_director:lab"),hash,DirectorLabObservationProfile.load().provenanceHash(),
    store.session().bootEpoch(),new Sequence(0),new EngineControlState(1,new Revision(0),t,t,new UnitValue(0),t,Optional.empty(),new UnitValue(.5)));
  }
  DirectorWorldState sessionState(DirectorWorldState w,Epoch boot,Epoch generation){
   return new DirectorWorldState(w.header(),w.worldId(),generation,w.simTick(),w.runControl(),w.healthMode(),w.pacingState(),
    w.tension(),w.intensityProfileId(),w.catalogHash(),w.configHash(),boot,w.lastCheckpointSeq(),w.engineControl());
  }
  AdminQuery query(MutationId id){
   return new AdminQuery(actorSession,new RequestId(UUID.randomUUID()),AdminPanel.ACTIVE,new MutationFilter(id),
    Optional.empty(),Optional.empty(),1);
  }
  AdminSnapshot inspect(GameTestHelper h,AdminQuery q,DirectorWorldState w){
   var result=DirectorNativeMutationReadBridge.page(store,q,w);
   h.assertTrue(result.isPresent(),"real J04 mutation lookup unavailable");
   var page=result.orElseThrow();
   h.assertTrue(page.rows().size()<=1&&page.nextCursor().isEmpty(),"direct mutation lookup must be bounded and final");
   h.assertTrue(new ModelCodec<>(AdminSnapshot.class).encode(page).length<=22000,"mutation row exceeded native wire cap");
   h.assertTrue(!new String(new ModelCodec<>(AdminSnapshot.class).encode(page),java.nio.charset.StandardCharsets.UTF_8).contains("bytes_base64"),
    "mutation inspection must transmit references, never blob contents");
   return page;
  }
  void verify(GameTestHelper h,boolean reused){
   actor();var w=state();var q=query(target);var page=inspect(h,q,w);
   h.assertTrue(!target.value().equals(event.value())&&page.rows().size()==1,"fixture MutationId and EventId must differ; exact mutation missing");
   var row=page.rows().getFirst();
   h.assertTrue(row.scope().equals(new ScopeRef(ScopeKind.EVENT,event.value()))&&row.data() instanceof MutationAdminData,
    "mutation row must use explicit data and genuine linked event scope");
   var data=(MutationAdminData)row.data();
   h.assertTrue(data.mutation().equals(reused?rolledBack:first)&&data.inspectedResources().equals(Set.of(resource)),
    "mutation metadata/physical inspected resources changed");
   h.assertTrue(data.ownership().records().equals(Map.of(resource,reused?after:before)),"current exact WAL owner not joined");
   h.assertTrue(data.ownership().records().get(resource).creationMutationId().equals(target)!=reused,
    "owner reuse must be shown honestly, never rejected or relabelled as this mutation");
   h.assertTrue(data.mutation().preStateRef().equals(pre.reference())&&data.mutation().postStateRef().equals(post.reference()),
    "PRE/POST references were substituted");
   var command=new AdminCommand(actorSession,q.requestId(),w.header().revision(),AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(target));
   h.assertTrue(NativeReadCommandFence.query(command).orElseThrow().filter().equals(new MutationFilter(target)),
    "MUTATION_INSPECT must have its own typed query");
   h.assertTrue(NativeReadCommandFence.answer(command,Optional.of(page)).status()==AdminStatus.SUCCEEDED,
    "actual mutation read was not accepted by native command fence");
   var wrapped=DirectorNativeMutationReadBridge.page(new DirectorLabReadOnlyStore(store),q,w);
   h.assertTrue(wrapped.isPresent()&&wrapped.get().rows().equals(page.rows())&&wrapped.get().lastDurableSequence().equals(page.lastDurableSequence()),
    "read-only wrapper must preserve mutation/owners and exact cut");
   var noEvent=store.view(new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.EVENT,event.value())),new Sequence(0),Optional.empty(),1));
   h.assertTrue(noEvent.status()==ReadStatus.AVAILABLE&&noEvent.page().orElseThrow().aggregates().isEmpty(),"test must never manufacture EventAggregate");
   h.assertTrue(DirectorNativeAdminReadBridge.sameWalCut(Optional.of(page),page.lastDurableSequence()).isPresent()
    &&DirectorNativeAdminReadBridge.sameWalCut(Optional.of(page),new Sequence(page.lastDurableSequence().value()+1)).isEmpty(),"outer exact WORLD/WAL cut fence lost");
   var absent=inspect(h,query(new MutationId(UUID.randomUUID())),w);
   h.assertTrue(absent.rows().isEmpty(),"missing mutation fabricated a row");
   var orphanPage=inspect(h,query(missingOwner),w);
   h.assertTrue(orphanPage.rows().size()==1&&orphanPage.rows().getFirst().data() instanceof MutationAdminData orphanData
    &&orphanData.inspectedResources().equals(Set.of(missingResource))&&orphanData.ownership().records().isEmpty(),
    "known absent owner must remain an explicit inspected resource with no invented record");
   h.assertTrue(DirectorNativeMutationReadBridge.page(store,query(tooWide),w).isEmpty(),"seventeen physical resources must refuse, not truncate");
   var forgedCursor=new AdminQuery(q.actorSession(),new RequestId(UUID.randomUUID()),q.panel(),q.filter(),Optional.of("forged"),Optional.empty(),1);
   h.assertTrue(DirectorNativeMutationReadBridge.page(store,forgedCursor,w).isEmpty(),"direct lookup accepted unrelated cursor");
   h.assertTrue(DirectorNativeMutationReadBridge.page(store,q,sessionState(w,w.bootEpoch().plus(1),w.storeGeneration())).isEmpty(),"mutation read accepted stale/future boot");
   h.assertTrue(DirectorNativeMutationReadBridge.page(store,q,sessionState(w,w.bootEpoch(),w.storeGeneration().plus(1))).isEmpty(),"mutation read accepted foreign generation");
   System.out.println("DIRECTOR_NATIVE_F8_MUTATION_2048_NOISE_NO_EVENT_OWNER_REUSE_EXACT_CUT_PASS boot="+store.session().bootEpoch().value()+" reused="+reused);
  }
  List<DurableRecord> initial(){
   var records=new ArrayList<DurableRecord>();
   for(int i=0;i<2048;i++)records.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),
    new FactType("proutlost_director:native_mutation_inspect_old_noise"),List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
    Optional.empty(),EvidenceStatus.POSSIBLE,Map.of("native_fixture",new JsonValue.Bool(true)),true,Optional.empty())));
   records.addAll(List.of(new DurableAggregate(new MutationAggregate(first)),new DurableAggregate(new MutationAggregate(orphan)),
    new DurableAggregate(new MutationAggregate(oversized)),new DurableAggregate(new OwnershipAggregate(before))));
   return records;
  }
  void advance(GameTestHelper h){
   actor();
   switch(phase){
    case 0->{if(!opening.isDone())return;h.assertTrue(!opening.isCompletedExceptionally(),"native mutation WAL open failed");store=opening.getNow(null);
     committed=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),initial(),List.of(pre,post))).toCompletableFuture();phase=1;}
    case 1->{if(!committed.isDone())return;h.assertTrue(!committed.isCompletedExceptionally()&&committed.getNow(null).ackStatus()==AckStatus.DURABLE,"native mutation fixture not fsynced");
     verify(h,false);committed=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
      List.of(new DurableAggregate(new MutationAggregate(rolledBack)),new DurableAggregate(new MutationAggregate(replacementRecord)),new DurableAggregate(new OwnershipAggregate(after))),List.of())).toCompletableFuture();phase=2;}
    case 2->{if(!committed.isDone())return;h.assertTrue(!committed.isCompletedExceptionally()&&committed.getNow(null).ackStatus()==AckStatus.DURABLE,"native replacement owner not durable");
     verify(h,true);closed=store.closeAsync().toCompletableFuture();phase=3;}
    case 3->{if(!closed.isDone())return;h.assertTrue(!closed.isCompletedExceptionally(),"native mutation close failed");reopened=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();phase=4;}
    case 4->{if(!reopened.isDone())return;h.assertTrue(!reopened.isCompletedExceptionally(),"native mutation same-WAL reopen failed");store=reopened.getNow(null);verify(h,true);closed=store.closeAsync().toCompletableFuture();phase=5;}
    case 5->{if(!closed.isDone())return;h.assertTrue(!closed.isCompletedExceptionally(),"native mutation final close failed");phase=6;}
    default->{}
   }
  }
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void mutationInspectReadsRealWalWithoutEventAndSurvivesOwnerReuse(GameTestHelper helper){
  var probe=new Probe(helper);helper.succeedWhen(()->{probe.advance(helper);helper.assertTrue(probe.phase==6,"native mutation inspection/reopen pending");});
 }
}
