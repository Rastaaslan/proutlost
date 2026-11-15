package fr.proutlost.director.adapter.neoforge.server;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

/** Real lifecycle owner, real BlockEvents, and the actual LAB Trace saga. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeTraceAnchorGameTests {
 private DirectorNativeTraceAnchorGameTests(){}
 private enum Mode { ROUND_TRIP, BEFORE_APPLY, AFTER_APPLY }
 private static final Map<MinecraftServer,Probe> ACTIVE=new IdentityHashMap<>();
 private static final class Probe {
  final GameTestHelper helper;final Mode mode;final ServerLevel level;final MinecraftServer server;
  final DirectorServerLifecycle life;final BlockPos block;final BlockPosition position;final BlockState original;
  DirectorNativeGameplay gameplay;DirectorNativeAnchorOwner owner;AnchorId id;
  AnchorRecord before,after,callbackPost;UUID event;
  int phase,captureCallbacks,applyCallbacks;boolean deferredCheckpoint;
  Probe(GameTestHelper helper,Mode mode){
   this.helper=helper;this.mode=mode;level=helper.getLevel();server=Objects.requireNonNull(level.getServer());
   life=DirectorStandaloneMod.directorLifecycle();block=helper.absolutePos(new BlockPos(1,2,1));original=level.getBlockState(block);
   position=new BlockPosition(new DimensionId(level.dimension().location().toString()),block.getX(),block.getY(),block.getZ());
  }
  void place(){
   var previous=level.getBlockState(block);var snapshot=BlockSnapshot.create(level.dimension(),level,block);
   level.setBlock(block,Blocks.CAMPFIRE.defaultBlockState(),Block.UPDATE_CLIENTS);
   var event=new BlockEvent.EntityPlaceEvent(snapshot,previous,FakePlayerFactory.getMinecraft(level));NeoForge.EVENT_BUS.post(event);
   helper.assertTrue(!event.isCanceled(),"camp placement canceled");
   helper.assertTrue(level.getBlockState(block).is(BlockTags.CAMPFIRES),"camp placement did not change Minecraft");
  }
  void breakCamp(){
   var event=new BlockEvent.BreakEvent(level,block,level.getBlockState(block),FakePlayerFactory.getMinecraft(level));
   NeoForge.EVENT_BUS.post(event);helper.assertTrue(!event.isCanceled(),"camp break canceled");
   level.setBlock(block,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
   helper.assertTrue(level.getBlockState(block).isAir(),"camp break did not change Minecraft");
  }
  AnchorRecord projected(){return life.labMemoryView(server).map(view->view.anchors().get(id)).orElse(null);}
  boolean settled(AnchorRecord expected){
   return expected!=null&&expected.equals(projected())&&expected.equals(owner.current(id).orElse(null))&&
    !owner.traceBusy()&&owner.deferredChanges()==0&&life.labAnchorWritesQuiescent(server);
  }
  void sameIdentity(AnchorRecord a,AnchorRecord b){
   helper.assertTrue(a.anchorId().equals(b.anchorId())&&a.header().id().equals(b.header().id())&&
    a.header().schemaVersion()==b.header().schemaVersion()&&a.locationId().equals(b.locationId())&&
    a.regionIds().equals(b.regionIds())&&a.dimension().equals(b.dimension())&&a.position().equals(b.position())&&
    a.radiusProfile().equals(b.radiusProfile())&&a.origin()==b.origin()&&a.evidence()==b.evidence()&&
    b.header().updatedTick().compareTo(a.header().updatedTick())>=0,"Trace/block hook changed anchor identity or metadata");
  }
  AnchorRecord version(long revision,boolean active,Set<TagId> tags){
   var actual=projected();if(!settled(actual))return null;
   helper.assertTrue(actual.header().revision().value()==revision,"unexpected durable anchor revision: "+actual.header());
   helper.assertTrue(actual.active()==active,"unexpected durable anchor active flag");
   helper.assertTrue(actual.tags().equals(tags),"durable block hook lost or invented Trace tags");sameIdentity(before,actual);
   helper.assertTrue(owner.at(position).map(AnchorRecord::anchorId).filter(id::equals).isPresent(),"replacement selected different UUID");
   return actual;
  }
  void captured(){
   captureCallbacks++;if(mode!=Mode.BEFORE_APPLY)return;
   callbackPost=owner.current(id).orElseThrow();int queued=owner.deferredChanges();breakCamp();
   deferredCheckpoint=owner.traceBusy()&&owner.deferredChanges()==queued+1&&callbackPost.equals(owner.current(id).orElse(null))&&callbackPost.equals(before);
  }
  void applied(){
   applyCallbacks++;if(mode!=Mode.AFTER_APPLY)return;
   callbackPost=owner.current(id).orElseThrow();int queued=owner.deferredChanges();breakCamp();
   deferredCheckpoint=owner.traceBusy()&&owner.deferredChanges()==queued+1&&callbackPost.equals(owner.current(id).orElse(null))&&
    callbackPost.header().revision().equals(before.header().revision().plus(1));
  }
  void finish(String marker){
   helper.assertTrue(!owner.failed()&&!owner.traceBusy(),"Trace left shared owner unavailable");
   level.setBlock(block,original,Block.UPDATE_CLIENTS);ACTIVE.remove(server,this);phase=9;
   System.out.println(marker+" event="+event+" anchor="+id.value()+" before_revision="+before.header().revision().value()+
    " durable_revision="+projected().header().revision().value());
  }
  void advance(){
   helper.assertTrue(server.isSameThread(),"Minecraft actor required");
   if(phase==0){
    gameplay=life.labGameplay(server).orElse(null);if(gameplay==null)return;
    owner=gameplay.labAnchorOwner().orElse(null);if(owner==null||owner.traceBusy()||!life.labAnchorWritesQuiescent(server))return;
    var current=ACTIVE.get(server);if(current!=null&&current!=this)return;
    ACTIVE.put(server,this);place();phase=1;return;
   }
   if(phase==1){
    var memory=life.labMemoryView(server);if(memory.isEmpty())return;
    var matches=memory.orElseThrow().anchors().values().stream().filter(a->a.active()&&a.origin()==AnchorOrigin.CAMP&&a.position().equals(position)).toList();
    if(matches.isEmpty())return;helper.assertTrue(matches.size()==1,"placement produced multiple active camp anchors");
    id=matches.getFirst().anchorId();before=matches.getFirst();if(!settled(before))return;
    event=gameplay.labTestBeginTrace(id,this::captured,this::applied);if(event==null)return;phase=2;return;
   }
   if(phase==2){
    var status=gameplay.labTraceStatus();if(!event.equals(status.eventId())||!"IDLE".equals(status.phase()))return;
    helper.assertTrue(captureCallbacks==1,"Trace capture callback skipped or replayed");
    helper.assertTrue(status.before().filter(before::equals).isPresent(),"Trace did not capture actual durable camp version");
    if(mode==Mode.BEFORE_APPLY){
     helper.assertTrue("ABORTED_SAFE".equals(status.outcome())&&applyCallbacks==0&&deferredCheckpoint,
      "edited captured anchor applied or not safely aborted");phase=3;return;
    }
    helper.assertTrue("COMMITTED".equals(status.outcome())&&status.resultSequence()>0&&applyCallbacks==1,"Trace did not complete real J04 RESULT");
    after=status.after().orElseThrow();sameIdentity(before,after);var added=new HashSet<>(after.tags());added.removeAll(before.tags());
    helper.assertTrue(after.active()&&after.header().revision().equals(before.header().revision().plus(1))&&
     after.tags().containsAll(before.tags())&&added.size()==1&&after.tags().size()==before.tags().size()+1,"Trace did not add exactly one persistent tag");
    if(mode==Mode.AFTER_APPLY)helper.assertTrue(deferredCheckpoint&&after.equals(callbackPost),"block event changed applied Trace before RESULT");
    phase=3;return;
   }
   if(phase==3&&mode==Mode.BEFORE_APPLY){
    if(version(before.header().revision().value()+1,false,before.tags())==null)return;
    helper.assertTrue(level.getBlockState(block).isAir(),"aborted Trace restored broken camp");
    finish("DIRECTOR_NATIVE_TRACE_EDIT_BEFORE_APPLY_ABORTED_SAFE_PASS");return;
   }
   if(phase==3&&mode==Mode.AFTER_APPLY){
    if(version(after.header().revision().value()+1,false,after.tags())==null)return;
    helper.assertTrue(level.getBlockState(block).isAir(),"deferred break physically overwritten");
    finish("DIRECTOR_NATIVE_TRACE_EDIT_AFTER_APPLY_DEFERRED_J04_PASS");return;
   }
   if(phase==3){
    if(!settled(after))return;
    helper.assertTrue(level.getBlockState(block).is(BlockTags.CAMPFIRES),"Trace changed physical camp");breakCamp();phase=4;return;
   }
   if(phase==4){if(version(after.header().revision().value()+1,false,after.tags())==null)return;place();phase=5;return;}
   if(phase==5){if(version(after.header().revision().value()+2,true,after.tags())==null)return;breakCamp();phase=6;return;}
   if(phase==6){
    if(version(after.header().revision().value()+3,false,after.tags())==null)return;
    finish("DIRECTOR_NATIVE_TRACE_REPLACEMENT_SAME_ID_TAGS_REVISION_PASS");
   }
  }
 }
 private static void run(GameTestHelper helper,Mode mode){
  var probe=new Probe(helper,mode);
  helper.succeedWhen(()->{probe.advance();helper.assertTrue(probe.phase==9,"actual Trace saga/block hooks/J04 pending: "+mode+" phase="+probe.phase);});
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void durableTraceSurvivesCampBreakAndReplacement(GameTestHelper h){run(h,Mode.ROUND_TRIP);}
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void editAfterCaptureAbortsBeforeTraceApply(GameTestHelper h){run(h,Mode.BEFORE_APPLY);}
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void editAfterTraceApplyWaitsForDurableResult(GameTestHelper h){run(h,Mode.AFTER_APPLY);}
}
