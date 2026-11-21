package fr.proutlost.director.persistence;

import fr.proutlost.director.runtime.*;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.bootstrap.DirectorAdminAssembly;
import fr.proutlost.director.persistence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual private J04 commit/reopen; no Minecraft admin command or live world. */
public final class AdminAcknowledgementLaneTests {
 private static int assertions;
 private static final WorldId WORLD=new WorldId(new UUID(9408,301));
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,4194304,16777216,33554432,32,0,128);
 private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(10,TimeUnit.SECONDS);}
 private static DirectorWorldState world(JournalStore store){var q=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.WORLD,WORLD.value())),new Sequence(0),Optional.empty(),1);var r=store.view(q);ReadContracts.validate(r,q,store.session());if(r.status()!=ReadStatus.AVAILABLE)throw new AssertionError("actual WORLD unavailable");return ((WorldAggregate)r.page().orElseThrow().aggregates().getFirst().state()).value();}
 private static DirectorWorldState initial(StoreSession session){var tick=new Tick(10);var control=new EngineControlState(1,new Revision(1),tick,new Tick(100),new UnitValue(0),tick,Optional.empty(),new UnitValue(0));return new DirectorWorldState(new AggregateHeader(1,WORLD.value(),new Revision(1),new Sequence(0),tick),WORLD,session.storeGeneration(),tick,RunControl.RUNNING,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,new ResourceId("fixture:admin_ack"),BlobContent.hash(new byte[]{1}),BlobContent.hash(new byte[]{2}),session.bootEpoch(),new Sequence(0),control);}
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);var armed=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS,point->{if(point.equals("BEFORE_WAL_FORCE")&&armed.compareAndSet(true,false)){entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new java.io.IOException("owned ACK test barrier timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}}}));
  try{
   var session=store.session();var first=initial(session);var ack=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),Map.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()),new Revision(0)),List.of(new DurableAggregate(new WorldAggregate(first))),List.of())));
   check(ack.ackStatus()==AckStatus.DURABLE,"private fixture WORLD really committed");
   var actorSession=new SessionId(new UUID(9408,302));var actor=new ActorIdentity(actorSession,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);ActorAuthorityPort authority=id->id.equals(actorSession)?Optional.of(actor):Optional.empty();
   var cut=new AtomicReference<>(ack.durableSequence());var applied=new AtomicInteger();
   var mailbox=new RuntimeAdminMailbox(store,authority,()->{var w=world(store);return new AdminSnapshotStamp(WORLD,session.bootEpoch(),w.header().revision(),new Tick(11),cut.get());},change->{check(world(store).header().revision().equals(change.after().snapshotRevision()),"actual journal WORLD matches ACK publication");cut.set(change.after().lastDurableSequence());applied.incrementAndGet();},Map.of(AdminOperation.PAUSE,new RunControlHandler(()->world(store)),AdminOperation.RESUME,new RunControlHandler(()->world(store))),8,ProviderMode.TEST_ONLY);
   var facade=new AdminFacade(mailbox,authority,WORLD,session.bootEpoch(),8);var access=new AdminAccessGuard(facade,authority,()->11,Runnable::run,16,8,20,20,20);
   var calculations=new AdminReadOnlyCalculations(input->{throw new IllegalArgumentException("no simulation input in ACK-only fixture");},id->{throw new IllegalArgumentException("no fixture");},(java.util.function.Function<DecisionId,DecisionInput>)id->{throw new IllegalArgumentException("no archive");});
   var readOnly=new AdminReadOnlyCommands(calculations,new AdminAuditExporter(root.resolve("exports"),Runnable::run,1048576),id->{throw new IllegalArgumentException("no audit requested");},authority,Runnable::run,8);
   var components=new DirectorAdminAssembly.Components(mailbox,facade,access,readOnly);
   var pause=new AdminCommand(actorSession,new RequestId(new UUID(9408,303)),new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
   var resume=new AdminCommand(actorSession,new RequestId(new UUID(9408,304)),new Revision(2),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));
   var result=facade.submit(pause,actor).toCompletableFuture();armed.set(true);components.tick(true);
   check(entered.await(2,TimeUnit.SECONDS)&&mailbox.hasPendingWrites(),"actual PAUSE append reaches owned force boundary");
   facade.submit(resume,actor);components.tick(false);
   check(!result.isDone()&&applied.get()==0&&world(store).runControl()==RunControl.RUNNING,"ACK-only phase cannot invent completion before actual force");
   release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(mailbox.hasPendingWrites()&&System.nanoTime()<deadline){components.tick(false);Thread.sleep(1);}
   System.out.println("ACK_ONLY_READBACK pending="+mailbox.hasPendingWrites()+" publications="+applied.get()+" actual_control="+world(store).runControl()+" actual_revision="+world(store).header().revision().value());
   check(!mailbox.hasPendingWrites()&&applied.get()==1,"active exact ACK drains while new WORLD writes forbidden");
   check(result.isDone()&&result.getNow(null).status()==AdminStatus.SUCCEEDED,"original caller receives exact committed PAUSE result");
   check(world(store).runControl()==RunControl.PAUSED&&world(store).header().revision().equals(new Revision(2)),"actual WORLD changed once after ACK");var confirmedCut=cut.get();
   for(int i=0;i<5;i++)components.tick(false);
   check(mailbox.poll(new RuntimeAdminKey(actorSession,resume.requestId())).status()==AdminStatus.PENDING,"queued second control remains queued in ACK-only phase");
   check(applied.get()==1&&cut.get().equals(confirmedCut)&&world(store).runControl()==RunControl.PAUSED,"ACK drain does not start second transaction or resume gameplay");await(store.checkpoint());
  }finally{release.countDown();await(store.closeAsync());}
  var reopened=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));try{check(world(reopened).runControl()==RunControl.PAUSED&&world(reopened).header().revision().equals(new Revision(2)),"checkpoint/close/reopen proves exact durable control state");}finally{await(reopened.closeAsync());}
  System.out.println("PASS AdminAcknowledgementLaneTests assertions="+assertions+" scope=ACTUAL_PRIVATE_J04_ACK_COMPONENT_NO_NATIVE_COMMAND");
 }
}
