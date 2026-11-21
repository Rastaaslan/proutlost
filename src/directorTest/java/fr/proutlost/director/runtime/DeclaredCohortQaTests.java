package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.persistence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Declared synthetic preparation + actual private J04 control ACK/reopen.
 * No Minecraft clients, permissions, runtime effects or performance result. */
public final class DeclaredCohortQaTests {
 private static int assertions;private static final WorldId WORLD=new WorldId(new UUID(9410,24));
 private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
 private static void rejects(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException|IllegalStateException expected){assertions++;}}
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(10,TimeUnit.SECONDS);}
 private static DeclaredCohortQaPlan plan(DeclaredCohortQaPlan.Branch branch){
  var actors=new ArrayList<DeclaredCohortQaPlan.Actor>();for(int i=0;i<24;i++)actors.add(new DeclaredCohortQaPlan.Actor(new PlayerId(new UUID(9410,i+1)),"QAcohort"+i,"controller1"));
  return new DeclaredCohortQaPlan(1,"cohort24",branch,"FULL_MC24",24,0,"controller1",actors,Set.of(actors.get(0).playerId(),actors.get(1).playerId()),Map.of("candidate_sha256","a".repeat(64),"profile_sha256","b".repeat(64),"action_plan_sha256","c".repeat(64),"sources_sha256","d".repeat(64),"director_jar_sha256","e".repeat(64)),branch==DeclaredCohortQaPlan.Branch.DIRECTOR);
 }
 private static void plans(){
  var p=plan(DeclaredCohortQaPlan.Branch.DIRECTOR);var codec=new ModelCodec<>(DeclaredCohortQaPlan.class);byte[] raw=codec.encode(p);
  check(codec.decode(raw).equals(p)&&p.actualUuids().size()==24&&p.actualProfiles().size()==24,"strict complete plan codec + identities");
  check(new String(raw,java.nio.charset.StandardCharsets.UTF_8).contains("\"actual_players_target\":24"),"wire names are actual ModelCodec snake_case");
  for(var branch:DeclaredCohortQaPlan.Branch.values())check(codec.decode(codec.encode(plan(branch))).branch()==branch,"branches stay explicit");
  var duplicate=new ArrayList<>(p.actors());duplicate.set(23,duplicate.getFirst());
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,0,p.controllerId(),duplicate,p.surveyWitnesses(),p.pins(),true),"duplicate actual identity");
  var names=new ArrayList<>(p.actors());var first=names.getFirst();names.set(23,new DeclaredCohortQaPlan.Actor(names.getLast().playerId(),first.profileName().toLowerCase(Locale.ROOT),p.controllerId()));
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,0,p.controllerId(),names,p.surveyWitnesses(),p.pins(),true),"duplicate casefolded profile name");
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),"ENTITY_LOAD_24",24,0,p.controllerId(),p.actors(),p.surveyWitnesses(),p.pins(),true),"simulation cannot enter native FULL_MC mode");
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,1,p.controllerId(),p.actors(),p.surveyWitnesses(),p.pins(),true),"zero-human declaration enforced");
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),DeclaredCohortQaPlan.Branch.BASELINE,p.mode(),24,0,p.controllerId(),p.actors(),p.surveyWitnesses(),p.pins(),true),"baseline cannot request automatic selection");
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,0,p.controllerId(),p.actors(),Set.of(first.playerId(),new PlayerId(new UUID(999,999))),p.pins(),true),"survey witnesses must be actual allowed profiles");
  var pins=new HashMap<>(p.pins());pins.put("received","a".repeat(64));
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,0,p.controllerId(),p.actors(),p.surveyWitnesses(),pins,true),"unknown pin or receipt authority forbidden");
  var zero=new HashMap<>(p.pins());zero.put("candidate_sha256","0".repeat(64));
  rejects(()->new DeclaredCohortQaPlan(1,p.runId(),p.branch(),p.mode(),24,0,p.controllerId(),p.actors(),p.surveyWitnesses(),zero,true),"missing real pin rejected");
  String altered=new String(raw,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\\{","{\"capabilities\":[],");
  rejects(()->codec.decode(altered.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"unknown capability plan field rejected");
 }
 private static DirectorWorldState initial(StoreSession session,long rev,RunControl run){var tick=new Tick(rev);var hash=BlobContent.hash(new byte[]{1});return new DirectorWorldState(new AggregateHeader(1,WORLD.value(),new Revision(rev),new Sequence(0),tick),WORLD,session.storeGeneration(),tick,run,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,new ResourceId("fixture:cohort"),hash,hash,session.bootEpoch(),new Sequence(0),new EngineControlState(1,new Revision(1),tick,new Tick(100),new UnitValue(0),tick,Optional.empty(),new UnitValue(0)));}
 private static DirectorWorldState world(JournalStore store){var q=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.WORLD,WORLD.value())),new Sequence(0),Optional.empty(),1);var r=store.view(q);ReadContracts.validate(r,q,store.session());return ((WorldAggregate)r.page().orElseThrow().aggregates().getFirst().state()).value();}
 private static void journal(Path root)throws Exception{
  var options=new StoreOptions(1048620,4194304,16777216,33554432,32,0,128);var store=await(JournalStore.open(root,WORLD,options));StoreSession old;RuntimeAdminAudit historical;
  try{
   old=store.session();var initial=initial(old,1,RunControl.RUNNING);
   var ack=await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),old.storeGeneration(),Map.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()),new Revision(0)),List.of(new DurableAggregate(new WorldAggregate(initial))),List.of())));
   check(ack.ackStatus()==AckStatus.DURABLE,"actual fixture WORLD durable");
   var gate=new DeclaredCohortAutomaticGate(old);check(!gate.enabled(old,world(store)),"recovered RUNNING does not arm new process");
   var actorSession=new SessionId(new UUID(9410,100));var actualActor=new ActorIdentity(actorSession,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
   var authority=new AtomicReference<>(actualActor);ActorAuthorityPort resolver=id->Optional.ofNullable(authority.get()).filter(a->a.session().equals(id));
   var cut=new AtomicReference<>(ack.durableSequence());var callbacks=new AtomicInteger();
   var handler=new RunControlHandler(()->world(store));var mailbox=new RuntimeAdminMailbox(store,resolver,()->new AdminSnapshotStamp(WORLD,old.bootEpoch(),world(store).header().revision(),new Tick(10),cut.get()),change->cut.set(change.after().lastDurableSequence()),Map.of(AdminOperation.RESUME,handler,AdminOperation.PAUSE,handler,AdminOperation.DRAIN,handler),16,ProviderMode.TEST_ONLY);
   mailbox.afterCommitted((command,change)->{gate.newlyCommitted(command,change.after(),world(store),store.session(),resolver.current(command.actorSession()));callbacks.incrementAndGet();});
   var resume=new AdminCommand(actorSession,new RequestId(new UUID(9410,101)),new Revision(1),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));
   check(mailbox.submit(resume).status()==AdminStatus.PENDING&&!gate.enabled(old,world(store)),"queued command not arm");mailbox.pump();await(store.checkpoint());
   check(!gate.enabled(old,world(store))&&callbacks.get()==0,"actual forced WAL without actor ACK publication cannot arm");
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(mailbox.poll(new RuntimeAdminKey(actorSession,resume.requestId())).status()==AdminStatus.SUCCEEDED&&gate.enabled(old,world(store))&&callbacks.get()==1,"new exact current-session durable RESUME arms once");
   mailbox.submit(resume);mailbox.pump();check(callbacks.get()==1,"idempotent receipt never rearms or writes");
   var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),128);var rows=await(store.read(query));ReadContracts.validate(rows,query,old);
   historical=rows.page().orElseThrow().records().stream().filter(DurableRuntimeAdminAudit.class::isInstance).map(DurableRuntimeAdminAudit.class::cast).map(DurableRuntimeAdminAudit::audit).filter(a->a.command().equals(resume)).findFirst().orElseThrow();
   var pause=new AdminCommand(actorSession,new RequestId(new UUID(9410,102)),new Revision(2),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));mailbox.submit(pause);mailbox.pump();await(store.checkpoint());while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(!gate.enabled(old,world(store))&&world(store).runControl()==RunControl.PAUSED,"durable PAUSE disarms and persists");
   var revokedResume=new AdminCommand(actorSession,new RequestId(new UUID(9410,104)),new Revision(3),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));
   mailbox.submit(revokedResume);mailbox.pump();await(store.checkpoint());authority.set(null);
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(world(store).runControl()==RunControl.RUNNING&&!gate.enabled(old,world(store)),"durable RESUME whose actor was revoked while ACK in flight cannot arm");
   authority.set(actualActor);var finalPause=new AdminCommand(actorSession,new RequestId(new UUID(9410,105)),new Revision(4),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));mailbox.submit(finalPause);mailbox.pump();await(store.checkpoint());
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(world(store).runControl()==RunControl.PAUSED&&!gate.enabled(old,world(store)),"fresh authorized PAUSE can still drain after actor revocation");
   authority.set(null);var rejected=new AdminCommand(actorSession,new RequestId(new UUID(9410,103)),new Revision(5),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));
   check(mailbox.submit(rejected).status()==AdminStatus.REJECTED&&!gate.enabled(old,world(store)),"revoked actor cannot arm");
   authority.set(actualActor);var closingResume=new AdminCommand(actorSession,new RequestId(new UUID(9410,106)),new Revision(5),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));
   mailbox.submit(closingResume);mailbox.pump();await(store.checkpoint());gate.revoke();
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(mailbox.poll(new RuntimeAdminKey(actorSession,closingResume.requestId())).status()==AdminStatus.SUCCEEDED&&world(store).runControl()==RunControl.RUNNING&&!gate.enabled(old,world(store)),"shutdown revocation never poisons a legitimate durable RESUME ACK or arms automatic work");
   var closingPause=new AdminCommand(actorSession,new RequestId(new UUID(9410,107)),new Revision(6),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));mailbox.submit(closingPause);mailbox.pump();await(store.checkpoint());
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(mailbox.hasWork()&&System.nanoTime()<deadline){mailbox.pump();Thread.onSpinWait();}
   check(mailbox.poll(new RuntimeAdminKey(actorSession,closingPause.requestId())).status()==AdminStatus.SUCCEEDED&&world(store).runControl()==RunControl.PAUSED&&!gate.enabled(old,world(store)),"subsequent critical PAUSE ACK drains while gate stays revoked");
  }finally{await(store.closeAsync());}
  var reopened=await(JournalStore.open(root,WORLD,options));try{
   check(!old.equals(reopened.session())&&world(reopened).runControl()==RunControl.PAUSED,"new JVM-equivalent store session and durable PAUSE readback");
   var gate=new DeclaredCohortAutomaticGate(reopened.session());check(!gate.enabled(reopened.session(),world(reopened)),"new gate cannot borrow previous boot state");
   var callback=new AtomicInteger();var handler=new RunControlHandler(()->world(reopened));var mailbox=new RuntimeAdminMailbox(reopened,id->Optional.empty(),()->historical.after(),change->{},Map.of(AdminOperation.RESUME,handler),16,ProviderMode.TEST_ONLY);mailbox.afterCommitted((c,p)->callback.incrementAndGet());mailbox.restore(historical);mailbox.submit(historical.command());mailbox.pump();
   check(callback.get()==0&&!gate.enabled(reopened.session(),world(reopened)),"historical durable success receipt never invokes new arm callback");
   rejects(()->gate.newlyCommitted(historical.command(),historical.after(),world(reopened),old,Optional.of(historical.appliedAuthority())),"old session publication rejected after reopen");
  }finally{await(reopened.closeAsync());}
 }
 public static void main(String[]args)throws Exception{
  if(args.length==2&&args[0].equals("cold")){
   var options=new StoreOptions(1048620,4194304,16777216,33554432,32,0,128);var store=await(JournalStore.open(Path.of(args[1]),WORLD,options));try{
    check(world(store).runControl()==RunControl.PAUSED,"new JVM reads actual previous durable PAUSE");
    check(!world(store).bootEpoch().equals(store.session().bootEpoch()),"new JVM has a new boot authority before WORLD bootstrap");
    check(!new DeclaredCohortAutomaticGate(store.session()).enabled(store.session(),world(store)),"new process cannot auto-arm from retained WORLD/audit");
   }finally{await(store.closeAsync());}
   System.out.println("PASS DeclaredCohortQaColdProcess assertions="+assertions+" scope=ACTUAL_NEW_JVM_PRIVATE_J04_NO_MC_LOAD");return;
  }
  plans();journal(Path.of(args[0]));System.out.println("PASS DeclaredCohortQaTests assertions="+assertions+" scope=PURE_PLAN_AND_ACTUAL_PRIVATE_J04_NO_MC_LOAD");
 }
}
