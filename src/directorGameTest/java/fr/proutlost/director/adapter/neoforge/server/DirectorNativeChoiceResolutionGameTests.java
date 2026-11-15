package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

/** SAME SharedChoiceResolutionBinding and native anchor/J04 actor lane.
 * Explicit constant laboratory inputs: no synthetic client, receipt, or ACK.
 * This qualifies binding/branch durability only; NOT J06 offer/input or GROUP2..6.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeChoiceResolutionGameTests {
 private DirectorNativeChoiceResolutionGameTests(){}
 private static final FactType FIXTURE=new FactType("proutlost_director:lab_choice_native_resolution_fixture");
 private static final ModelCodec<DirectorFact> FACT=new ModelCodec<>(DirectorFact.class);
 private static final ModelCodec<AnchorRecord> ANCHOR=new ModelCodec<>(AnchorRecord.class);
 private static final Map<MinecraftServer,Object> ACTIVE=new IdentityHashMap<>();
 private enum Mode { LISTEN, LEAVE_TRACE, CANCEL_BEFORE_APPLY }
 @GameTestGenerator public static Collection<TestFunction> technicalResolutionTests(){
  if(!Boolean.getBoolean("proutlost.director.nativeChoiceFixtureTest"))return List.of();
  if(Boolean.getBoolean("proutlost.director.nativeChoiceRecoveryOnly"))return List.of(test("same_world_metadata_recovery",h->{var p=new Recovery(h);h.succeedWhen(()->{p.advance();h.assertTrue(p.done,"Choice native metadata recovery pending");});}));
  var result=new ArrayList<TestFunction>();for(var mode:Mode.values())result.add(test(mode.name().toLowerCase(Locale.ROOT),h->{var p=new Probe(h,mode);h.succeedWhen(()->{p.advance();h.assertTrue(p.done,"Choice same-provider fixture pending "+mode+" phase="+p.phase);});}));return result;
 }
 private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body){return new TestFunction("native_choice_binding","proutlost_director.native_choice_binding_"+name,"proutlost_director:smoke_empty",Rotation.NONE,12000,0,true,body);}

 private static final class Scan {
  final DurableStore store;final Map<OperationToken,PrepareRecord> prepares=new HashMap<>();
  final Map<OperationToken,ResultRecord> results=new HashMap<>();final Map<FactId,DirectorFact> facts=new HashMap<>();
  final Map<AnchorId,AnchorRecord> anchors=new HashMap<>();final Map<BlobRef,BlobContent> blobs=new HashMap<>();
  Optional<String> cursor=Optional.empty();StoreQuery query;CompletableFuture<StoreReadResult> read;
  CompletableFuture<BlobReadResult> hydration;boolean walDone,done;
  Scan(DurableStore store){this.store=store;}
  void anchor(AnchorRecord next){var old=anchors.get(next.anchorId());if(old==null||next.header().revision().compareTo(old.header().revision())>0)anchors.put(next.anchorId(),next);}
  void row(DurableRecord r){
   if(r instanceof DurablePrepare p)prepares.put(p.value().operationToken(),p.value());
   if(r instanceof DurableResult v)results.put(v.value().operationToken(),v.value());
   if(r instanceof DurableFact f)facts.put(f.value().factId(),f.value());
   if(r instanceof DurableAggregate a&&a.state() instanceof AnchorAggregate v)anchor(v.value());
  }
  boolean poll(){
   if(done)return true;
   if(!walDone){
    if(read==null){query=new StoreQuery(store.session().worldId(),Set.of(),new Sequence(0),cursor,128);read=store.read(query).toCompletableFuture();return false;}
    if(!read.isDone())return false;if(read.isCompletedExceptionally())throw new IllegalStateException("Choice J04 read failed");var result=read.getNow(null);read=null;ReadContracts.validate(result,query,store.session());
    if(result.status()==ReadStatus.STALE){prepares.clear();results.clear();facts.clear();anchors.clear();cursor=Optional.empty();return false;}
    if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("Choice J04 read "+result.status());
    var page=result.page().orElseThrow();page.records().forEach(this::row);for(var a:page.aggregates())if(a.state() instanceof AnchorAggregate v)anchor(v.value());cursor=page.nextCursor();walDone=!page.hasMore();return false;
   }
   if(hydration==null){
    var refs=new HashSet<BlobRef>();for(var p:prepares.values())if(p.durableBlobs().preparedPlan().codecId().equals(SharedChoiceResolutionBinding.PLAN_CODEC))refs.addAll(CommitPermit.Authority.references(p.durableBlobs()));
    if(refs.isEmpty()){done=true;return true;}if(refs.size()>256)throw new IllegalStateException("Choice fixture blob-read bound");
    hydration=store.readBlobs(new BlobReadRequest(store.session().worldId(),store.session().storeGeneration(),refs)).toCompletableFuture();return false;
   }
   if(!hydration.isDone())return false;if(hydration.isCompletedExceptionally())throw new IllegalStateException("Choice fixture hydration failed");var result=hydration.getNow(null);
   if(result.status()!=AckStatus.DURABLE)throw new IllegalStateException("Choice fixture hydration "+result.status());for(var b:result.contents())blobs.put(b.reference(),b);done=true;return true;
  }
  byte[] bytes(BlobRef ref){return Objects.requireNonNull(blobs.get(ref),"exact sealed Choice blob unavailable").bytes();}
  DirectorFact decision(PrepareRecord p){return FACT.decode(bytes(p.durableBlobs().preparedPlan()));}
  List<PrepareRecord> fixtures(){return prepares.values().stream().filter(p->p.durableBlobs().preparedPlan().codecId().equals(SharedChoiceResolutionBinding.PLAN_CODEC)&&decision(p).factType().equals(FIXTURE)).toList();}
 }

 private static void check(GameTestHelper h,boolean value,String message){h.assertTrue(value,message);}
 private static DirectorFact verify(GameTestHelper h,Scan scan,PrepareRecord prepare,boolean aborted){
  var decision=scan.decision(prepare);var event=decision.eventId().orElseThrow();
  check(h,decision.factType().equals(FIXTURE)&&decision.durable()&&decision.evidenceStatus()==EvidenceStatus.CONFIRMED&&decision.payload().get("nativeFixture").equals(new JsonValue.Bool(true))&&decision.payload().get("humanInteraction").equals(new JsonValue.Bool(false))&&!decision.payload().containsKey("actor"),"fixture must never impersonate human interaction");
  check(h,prepare.slice().operations().size()==1&&prepare.slice().operations().getFirst().primitive().equals(SharedChoiceOfferBinding.ID)&&prepare.slice().operations().getFirst().typedArgs().equals(SharedChoiceOfferBinding.LAB_ARGS)&&prepare.slice().operations().getFirst().instanceId().equals(event),"test did not execute the real SharedChoice resolution primitive");
  var refs=prepare.durableBlobs();check(h,refs.preStates().size()==1&&refs.expectedPostStates().size()==1,"Choice must seal one anchor PRE/POST pair");
  var resource=refs.preStates().keySet().iterator().next();var before=ANCHOR.decode(scan.bytes(refs.preStates().get(resource)));var after=ANCHOR.decode(scan.bytes(refs.expectedPostStates().get(resource)));
  check(h,resource.equals(TraceBinding.key(before))&&before.anchorId().equals(after.anchorId())&&before.position().equals(after.position())&&before.locationId().equals(after.locationId())&&before.regionIds().equals(after.regionIds())&&before.evidence()==after.evidence()&&before.origin()==after.origin()&&after.header().revision().equals(before.header().revision().plus(1)),"Choice changed the original anchor lineage");
  var tags=new HashSet<>(before.tags());tags.add(SharedChoiceOfferBinding.RESOLVED);check(h,!before.tags().contains(SharedChoiceOfferBinding.RESOLVED)&&after.tags().equals(tags),"real Choice provider did not seal its exact resolved marker");
  var result=scan.results.get(prepare.operationToken());check(h,result!=null&&result.prepareTransactionId().equals(prepare.transactionId())&&result.operationToken().equals(prepare.operationToken()),"Choice has no exact durable RESULT");
  var current=scan.anchors.get(before.anchorId());check(h,current!=null&&!current.active(),"native fixture camp was not durably retired");
  if(aborted){check(h,result.result().outcome()==Outcome.ABORTED_SAFE&&result.result().appliedOperationIds().isEmpty()&&result.result().observedHashes().isEmpty()&&!scan.facts.containsKey(decision.factId())&&current.tags().equals(before.tags()),"cancellation fabricated a branch result or changed anchor state");}
  else{check(h,result.result().outcome()==Outcome.SUCCEEDED&&result.result().appliedOperationIds().equals(Set.of(prepare.operationToken()))&&result.result().observedHashes().equals(Map.of(resource,refs.expectedPostStates().get(resource).hash()))&&Objects.equals(scan.facts.get(decision.factId()),decision)&&current.tags().equals(after.tags()),"branch fact, physical POST and durable result disagree");}
  check(h,scan.facts.values().stream().noneMatch(f->f.eventId().filter(event::equals).isPresent()&&f.factType().equals(SharedChoiceResolutionBinding.SELECTED)),"native fixture fabricated a genuine player selection");return decision;
 }

 private static final class Probe {
  final GameTestHelper h;final Mode mode;final ServerLevel level;final MinecraftServer server;
  final DirectorServerLifecycle life;final BlockPos block;final BlockPosition position;final BlockState original;
  DirectorNativeGameplay gameplay;DirectorNativeAnchorOwner owner;AnchorId anchor;AnchorRecord before,after;
  UUID event=UUID.randomUUID();int phase;boolean done,offerRefused,applied;Scan scan;
  Probe(GameTestHelper h,Mode mode){this.h=h;this.mode=mode;level=h.getLevel();server=level.getServer();life=DirectorStandaloneMod.directorLifecycle();block=h.absolutePos(new BlockPos(1,2,1));position=new BlockPosition(new DimensionId(level.dimension().location().toString()),block.getX(),block.getY(),block.getZ());original=level.getBlockState(block);}
  ResourceId branch(){return mode==Mode.LEAVE_TRACE?SharedChoiceOfferBinding.LEAVE_TRACE:SharedChoiceOfferBinding.LISTEN;}
  void place(){var previous=level.getBlockState(block);var snapshot=BlockSnapshot.create(level.dimension(),level,block);level.setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),Block.UPDATE_CLIENTS);var e=new BlockEvent.EntityPlaceEvent(snapshot,previous,FakePlayerFactory.getMinecraft(level));NeoForge.EVENT_BUS.post(e);check(h,!e.isCanceled(),"native camp place canceled");}
  void breakCamp(){var e=new BlockEvent.BreakEvent(level,block,level.getBlockState(block),FakePlayerFactory.getMinecraft(level));NeoForge.EVENT_BUS.post(e);check(h,!e.isCanceled(),"native camp break canceled");level.setBlock(block,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);}
  AnchorRecord projected(){return life.labMemoryView(server).map(m->m.anchors().get(anchor)).orElse(null);}
  boolean settled(AnchorRecord record){return record!=null&&record.equals(projected())&&record.equals(owner.current(anchor).orElse(null))&&!owner.traceBusy()&&life.labAnchorWritesQuiescent(server);}
  void advance(){
   check(h,server.isSameThread(),"Choice server actor");
   if(phase==0){
    gameplay=life.labGameplay(server).orElse(null);if(gameplay==null)return;owner=gameplay.labAnchorOwner().orElse(null);if(owner==null||owner.traceBusy()||!life.labAnchorWritesQuiescent(server))return;
    var current=ACTIVE.get(server);if(current!=null&&current!=this)return;ACTIVE.put(server,this);place();phase=1;return;
   }
   if(phase==1){
    var memory=life.labMemoryView(server);if(memory.isEmpty())return;var matches=memory.orElseThrow().anchors().values().stream().filter(a->a.position().equals(position)&&a.active()&&a.origin()==AnchorOrigin.CAMP).toList();if(matches.isEmpty())return;check(h,matches.size()==1,"ambiguous native camp anchor");before=matches.getFirst();anchor=before.anchorId();if(!settled(before))return;
    if(!offerRefused){
     try{new SharedChoiceOfferBinding(server,owner,before,List.of(new PlayerId(UUID.randomUUID())),new PerceptionTransform(position.x()+.5,position.y()+1,position.z()+.5,0,0,1),100,()->new Tick(life.labAnchorClock(server)));}
     catch(IllegalStateException expected){offerRefused="choice SYMBOL not negotiated".equals(expected.getMessage());}
     check(h,offerRefused&&before.equals(owner.current(anchor).orElse(null)),"real offer accepted an unnegotiated client or changed the camp");
    }
    var started=gameplay.labTestBeginAnchorMutation(anchor,SharedChoiceOfferBinding.ID,SharedChoiceOfferBinding.LAB_ARGS,event,
     lease->SharedChoiceResolutionBinding.nativeFixture(server,owner,lease,()->new Tick(life.labAnchorClock(server)),event,branch()),
     ()->{if(mode==Mode.CANCEL_BEFORE_APPLY)breakCamp();},()->applied=true);
    if(started==null)return;check(h,event.equals(started),"native fixture event substituted");phase=2;return;
   }
   if(phase==2){
    var status=gameplay.labTraceStatus();if(!event.equals(status.eventId())||!"IDLE".equals(status.phase()))return;
    check(h,status.before().filter(before::equals).isPresent(),"Choice captured a different anchor version");
    check(h,(mode==Mode.CANCEL_BEFORE_APPLY?"ABORTED_SAFE":"COMMITTED").equals(status.outcome()),"native same-provider resolution failed: "+status);
    if(mode==Mode.CANCEL_BEFORE_APPLY){check(h,!applied,"canceled branch reached apply");phase=4;return;}
    check(h,applied&&status.resultSequence()>0,"Choice branch lacked physical apply and durable RESULT");after=status.after().orElseThrow();phase=3;return;
   }
   if(phase==3){if(!settled(after))return;breakCamp();phase=4;return;}
   if(phase==4){var current=projected();if(!settled(current)||current.active())return;var tags=mode==Mode.CANCEL_BEFORE_APPLY?before.tags():after.tags();check(h,current.tags().equals(tags),"camp retirement lost branch metadata");scan=new Scan(life.labReadOnlyStoreSnapshot(server).orElseThrow());phase=5;return;}
   if(phase==5){
    if(!scan.poll())return;var matches=scan.fixtures().stream().filter(p->p.reservation().instanceId().value().equals(event)).toList();check(h,matches.size()==1,"native Choice fixture PREPARE missing or replayed");var decision=verify(h,scan,matches.getFirst(),mode==Mode.CANCEL_BEFORE_APPLY);check(h,decision.payload().get("action").equals(new JsonValue.Text(branch().value())),"chosen branch changed in J04");
    check(h,!owner.failed()&&!owner.traceBusy(),"Choice lease not closed after durable outcome");level.setBlock(block,original,Block.UPDATE_CLIENTS);ACTIVE.remove(server,this);done=true;phase=6;
    System.out.println("DIRECTOR_NATIVE_SHARED_CHOICE_BINDING_"+mode+"_WAL_PASS event="+event+" anchor="+anchor.value()+" human_input=false j06_offer_input=false group_2_to_6=false");
   }
  }
 }
 private static final class Recovery {
  final GameTestHelper h;final MinecraftServer server;final DirectorServerLifecycle life;Scan scan;boolean done;
  Recovery(GameTestHelper h){this.h=h;server=h.getLevel().getServer();life=DirectorStandaloneMod.directorLifecycle();}
  void advance(){
   var gameplay=life.labGameplay(server).orElse(null);if(gameplay==null||gameplay.labAnchorOwner().isEmpty()||!life.labAnchorWritesQuiescent(server))return;
   if(scan==null){scan=new Scan(life.labReadOnlyStoreSnapshot(server).orElseThrow());return;}if(!scan.poll())return;
   var fixtures=scan.fixtures();int listen=0,leave=0,aborted=0;
   for(var prepare:fixtures){var result=scan.results.get(prepare.operationToken());check(h,result!=null,"unresolved native Choice fixture survived recovery");boolean cancel=result.result().outcome()==Outcome.ABORTED_SAFE;var decision=verify(h,scan,prepare,cancel);if(cancel)aborted++;else if(decision.payload().get("action").equals(new JsonValue.Text(SharedChoiceOfferBinding.LISTEN.value())))listen++;else leave++;}
   check(h,listen>=1&&leave>=1&&aborted>=1,"same-world recovery needs both committed branches and one canceled native fixture");
   check(h,!gameplay.labAnchorOwner().orElseThrow().failed()&&!gameplay.labAnchorOwner().orElseThrow().traceBusy(),"recovered Choice left an anchor lease live");done=true;
   System.out.println("DIRECTOR_NATIVE_SHARED_CHOICE_BINDING_SAME_WORLD_METADATA_RECOVERY_PASS boot_epoch="+scan.store.session().bootEpoch().value()+" committed_listen="+listen+" committed_leave="+leave+" canceled="+aborted+" human_input=false j06_offer_input=false group_2_to_6=false");
  }
 }
}
