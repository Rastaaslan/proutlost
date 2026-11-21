package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real J05 control handler and mailbox, TEST_ONLY writer; no platform/admin facade integration. */
public final class AdminRuntimeTests {
 static int assertions;
 static UUID id(long n){return new UUID(0,n);}
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);assertions++;}
 private record ControlFixture(RuntimeLifecycleTests.Store store,SessionId session,
   AtomicReference<ActorIdentity> authority,AtomicReference<DirectorWorldState> world,
   RuntimeAdminMailbox mailbox,fr.proutlost.director.admin.server.AdminFacade facade){}
 private static ControlFixture controlFixture(){
  var store=new RuntimeLifecycleTests.Store();var worldId=store.session().worldId();
  var header=new AggregateHeader(1,worldId.value(),new Revision(1),new Sequence(1),new Tick(1));
  var engine=new EngineControlState(1,new Revision(1),new Tick(1),new Tick(20),new UnitValue(0),new Tick(1),Optional.empty(),new UnitValue(0));
  var hash=new Fingerprint("a".repeat(64));
  var world=new AtomicReference<>(new DirectorWorldState(header,worldId,store.session().storeGeneration(),
   new Tick(1),RunControl.RUNNING,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,
   new ResourceId("proutlost_director:test"),hash,hash,new Epoch(1),new Sequence(1),engine));
  var stamp=new AtomicReference<>(new AdminSnapshotStamp(worldId,new Epoch(1),new Revision(1),new Tick(1),new Sequence(1)));
  var session=new SessionId(id(801));
  var authority=new AtomicReference<>(new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true));
  fr.proutlost.director.api.port.ActorAuthorityPort resolver=s->Optional.ofNullable(authority.get()).filter(a->a.session().equals(s));
  var handler=new RunControlHandler(world::get);
  var mailbox=new RuntimeAdminMailbox(store,resolver,stamp::get,c->{
   world.set(((WorldAggregate)((DurableAggregate)c.records().getFirst()).state()).value());stamp.set(c.after());
  },Map.of(AdminOperation.PAUSE,handler),4,ProviderMode.TEST_ONLY);
  var facade=new fr.proutlost.director.admin.server.AdminFacade(mailbox,resolver,worldId,new Epoch(1),4);
  return new ControlFixture(store,session,authority,world,mailbox,facade);
 }
 private static AdminCommand pause(ControlFixture f){
  return new AdminCommand(f.session(),new RequestId(id(802)),new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
 }
 private static void revokedQueuedBeforeWriterIdle(){
  var f=controlFixture();var command=pause(f);var actor=f.authority().get();
  var reply=f.facade().submit(command,actor).toCompletableFuture();
  check(f.mailbox().hasWork()&&!reply.isDone(),"queued control holds work before WORLD idle");
  f.authority().set(null);f.mailbox().rejectRevokedQueued();f.facade().tick();
  check(reply.getNow(null).status()==AdminStatus.REJECTED&&!f.mailbox().hasWork(),"queued revocation is terminal before apply");
  f.authority().set(actor);f.mailbox().pump();f.facade().tick();
  check(f.store().batches.isEmpty()&&f.world().get().runControl()==RunControl.RUNNING,
   "re-OP cannot revive a rejected unprepared control");
 }
 private static void revokedAfterAppendStillDrains(){
  var f=controlFixture();var command=pause(f);
  var reply=f.facade().submit(command,f.authority().get()).toCompletableFuture();
  f.mailbox().pump();var tx=f.store().batches.keySet().iterator().next();
  check(f.mailbox().hasWork()&&f.world().get().runControl()==RunControl.RUNNING,"appended control waits actual ACK");
  f.authority().set(null);f.mailbox().rejectRevokedQueued();f.facade().tick();
  check(reply.getNow(null).status()==AdminStatus.REJECTED&&f.mailbox().hasWork(),
   "client authority is revoked while durable engagement remains");
  f.store().ack(tx);f.mailbox().pump();f.facade().tick();
  check(f.world().get().runControl()==RunControl.PAUSED&&!f.mailbox().hasWork(),
   "existing WORLD write drains and publishes only after ACK");
  check(reply.getNow(null).status()==AdminStatus.REJECTED&&f.store().batches.size()==1,
   "revoked client never receives success and no second transaction appears");
 }
 private static void afterCommittedOnce(){
  var f=controlFixture();var command=pause(f);var calls=new java.util.concurrent.atomic.AtomicInteger();var actor=Thread.currentThread();
  f.mailbox().afterCommitted((actual,change)->{
   check(Thread.currentThread()==actor&&actual.equals(command),"commit callback stays on actor and exact command");
   check(f.world().get().runControl()==RunControl.PAUSED&&change.after().lastDurableSequence().value()>0,"callback observes published state and actual durable sequence");
   calls.incrementAndGet();
  });
  rejects(()->f.mailbox().afterCommitted((actual,change)->{}));
  f.mailbox().submit(command);f.mailbox().pump();var tx=f.store().pending.keySet().iterator().next();
  check(calls.get()==0,"no callback before exact ACK");f.store().ack(tx);check(calls.get()==0,"IO delivery never calls actor callback");
  f.mailbox().pump();check(calls.get()==1,"new committed publication invokes callback once");
  f.mailbox().poll(new RuntimeAdminKey(f.session(),command.requestId()));f.mailbox().submit(command);f.mailbox().pump();f.mailbox().pump();
  check(calls.get()==1&&f.store().batches.size()==1,"poll and idempotent submit never repeat callback or append");
  var audit=f.store().batches.get(tx).records().stream().filter(DurableRuntimeAdminAudit.class::isInstance).map(DurableRuntimeAdminAudit.class::cast).findFirst().orElseThrow().audit();
  var restored=controlFixture();var oldCalls=new java.util.concurrent.atomic.AtomicInteger();restored.mailbox().afterCommitted((actual,change)->oldCalls.incrementAndGet());
  restored.mailbox().restore(audit);restored.mailbox().submit(command);restored.mailbox().poll(new RuntimeAdminKey(restored.session(),command.requestId()));restored.mailbox().pump();
  check(oldCalls.get()==0&&restored.store().batches.isEmpty(),"restored terminal receipt never notifies or reapplies");
  var wrong=controlFixture();var wrongCalls=new java.util.concurrent.atomic.AtomicInteger();wrong.mailbox().afterCommitted((actual,change)->wrongCalls.incrementAndGet());
  wrong.mailbox().submit(pause(wrong));wrong.mailbox().pump();var bad=wrong.store().pending.keySet().iterator().next();wrong.store().ackWithForeignBlob(bad);wrong.mailbox().pump();
  check(wrongCalls.get()==0&&wrong.world().get().runControl()==RunControl.RUNNING,"foreign ACK attestation cannot publish or notify");
  var broken=controlFixture();var brokenCalls=new java.util.concurrent.atomic.AtomicInteger();broken.mailbox().afterCommitted((actual,change)->{brokenCalls.incrementAndGet();throw new IllegalStateException("callback failure");});
  var brokenCommand=pause(broken);broken.mailbox().submit(brokenCommand);broken.mailbox().pump();var pending=broken.store().pending.keySet().iterator().next();broken.store().ack(pending);broken.mailbox().pump();broken.mailbox().pump();broken.mailbox().submit(brokenCommand);
  check(brokenCalls.get()==1&&broken.mailbox().hasWork()&&broken.mailbox().poll(new RuntimeAdminKey(broken.session(),brokenCommand.requestId())).error().orElseThrow().code()==fr.proutlost.director.api.error.ReasonCode.RECOVERY_AMBIGUOUS,"callback exception fences receipt and never reruns notification");
 }
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException|IllegalStateException e){assertions++;return;}throw new AssertionError("expected rejection");}
 private static void worldControls(){
  var f=controlFixture();var worldId=f.world().get().worldId();
  var profileId=new ResourceId("proutlost_director:approved_test_fixture");
  var profile=new ConfigView(new Revision(1),new Fingerprint("b".repeat(64)),Calibration.APPROVED,Map.of());
  var profiles=Map.of(profileId,profile);
  var handler=new AdminWorldControlHandler(f.world()::get,profiles,()->10,ProviderMode.PRODUCTION);
  var handlers=new EnumMap<AdminOperation,RuntimeAdminMailbox.Handler>(AdminOperation.class);
  AdminWorldControlHandler.OPERATIONS.forEach(op->handlers.put(op,handler));
  var mailbox=new RuntimeAdminMailbox(f.store(),session->Optional.ofNullable(f.authority().get()).filter(a->a.session().equals(session)),
   ()->new AdminSnapshotStamp(worldId,new Epoch(1),f.world().get().header().revision(),new Tick(10),new Sequence(1)),
   change->f.world().set(((WorldAggregate)((DurableAggregate)change.records().getFirst()).state()).value()),handlers,16,ProviderMode.PRODUCTION);
  var intensity=new AdminCommand(f.session(),new RequestId(id(850)),new Revision(1),AdminOperation.INTENSITY,new IntensityPayload(profileId));
  check(mailbox.submit(intensity).status()==AdminStatus.PENDING,"intensity queued through real mailbox");mailbox.pump();
  check(!f.world().get().intensityProfileId().equals(profileId),"profile unchanged before durable ACK");
  var tx=f.store().pending.keySet().iterator().next();f.store().ack(tx);mailbox.pump();
  check(f.world().get().intensityProfileId().equals(profileId)&&f.world().get().configHash().equals(profile.provenanceHash()),"durable profile binds ID and configuration hash");
  check(AdminWorldControlHandler.configuration(f.world().get(),profiles)==profile,"runtime resolves exact registered decision configuration");
  var unknown=new AdminCommand(f.session(),new RequestId(id(851)),new Revision(2),AdminOperation.INTENSITY,new IntensityPayload(new ResourceId("test:missing")));
  mailbox.submit(unknown);mailbox.pump();
  check(mailbox.poll(new RuntimeAdminKey(f.session(),unknown.requestId())).error().orElseThrow().code()==fr.proutlost.director.api.error.ReasonCode.NO_TARGET&&f.store().batches.size()==1,"unknown profile refused before WAL");
  var tension=new AdminCommand(f.session(),new RequestId(id(852)),new Revision(2),AdminOperation.TENSION,new TensionPayload(GlobalTension.TENSE,new DurationTicks(20),new Tick(30)));
  mailbox.submit(tension);mailbox.pump();tx=f.store().pending.keySet().iterator().next();
  var records=f.store().batches.get(tx).records();
  check(records.size()==3&&records.stream().anyMatch(r->r instanceof DurableFact)&&records.stream().anyMatch(r->r instanceof DurableRuntimeAdminAudit),"tension WORLD, durable override and admin audit atomic");
  check(f.world().get().tension()==GlobalTension.CALM,"tension unchanged while ACK pending");
  f.store().ack(tx);mailbox.pump();check(f.world().get().tension()==GlobalTension.TENSE,"tension published after exact ACK");
  var expired=new AdminCommand(f.session(),new RequestId(id(853)),new Revision(3),AdminOperation.TENSION,new TensionPayload(GlobalTension.CALM,new DurationTicks(20),new Tick(10)));
  mailbox.submit(expired);mailbox.pump();
  check(mailbox.poll(new RuntimeAdminKey(f.session(),expired.requestId())).error().orElseThrow().code()==fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT&&f.store().batches.size()==2,"expired override rejected with typed reason");
  check(mailbox.submit(intensity).status()==AdminStatus.SUCCEEDED&&f.store().batches.size()==2,"successful profile retry never reapplies");
  var testing=new ConfigView(profile.revision(),profile.provenanceHash(),Calibration.DEFAULT_FOR_TESTING,profile.values());
  rejects(()->new AdminWorldControlHandler(f.world()::get,Map.of(profileId,testing),()->10,ProviderMode.PRODUCTION));
  rejects(()->AdminWorldControlHandler.configuration(f.world().get(),Map.of(profileId,new ConfigView(profile.revision(),new Fingerprint("c".repeat(64)),Calibration.APPROVED,Map.of()))));
 }
 private static AdminOperationDispatcher physical(ControlFixture f,AdminOperationDispatcher.Hooks hooks){
  return new AdminOperationDispatcher(f.store(),session->Optional.ofNullable(f.authority().get()).filter(a->a.session().equals(session)),
   ()->new AdminSnapshotStamp(f.world().get().worldId(),f.store().session().bootEpoch(),f.world().get().header().revision(),new Tick(10),new Sequence(1)),f.mailbox(),hooks,16);
 }
 private static void physicalDispatch(){
  var f=controlFixture();var calls=new java.util.concurrent.atomic.AtomicInteger();
  var progress=new AtomicReference<AdminResult>();
  var hooks=new AdminOperationDispatcher.Hooks(){
   public AdminResult launch(AdminCommand command,ActorIdentity actor){calls.incrementAndGet();return new AdminResult(AdminStatus.PENDING,command.requestId(),command.expectedRevision(),Optional.empty(),Optional.empty());}
   public Optional<AdminResult> progress(RuntimeAdminKey key){return Optional.ofNullable(progress.get());}
  };
  var dispatcher=physical(f,hooks);var region=new RegionId(id(870));var source=RuntimeLifecycleTests.event();
  var command=new AdminCommand(f.session(),new RequestId(id(871)),new Revision(1),AdminOperation.TRIGGER,
   new TriggerPayload(source.definition().id(),region,Set.of(),Set.of()));var key=new RuntimeAdminKey(f.session(),command.requestId());
  check(dispatcher.submit(command).status()==AdminStatus.PENDING,"physical operation queues durable intent");
  dispatcher.pump();check(calls.get()==0,"no launch before intent ACK");
  var intentTx=f.store().pending.keySet().iterator().next();var intent=((DurableFact)f.store().batches.get(intentTx).records().getFirst()).value();
  f.store().ack(intentTx);check(calls.get()==0,"IO callback cannot launch physical operation");dispatcher.pump();
  check(calls.get()==1&&f.store().batches.size()==1,"exact ACK dispatches once, no early terminal audit");
  var started=new EventInstance(source.header(),source.instanceId(),source.definition(),source.catalogGeneration(),source.state(),source.phase(),source.roleAssignments(),Set.of(region),
   source.contextRef(),source.stepStates(),source.reservationRefs(),source.effectRefs(),source.mutationRefs(),source.chainId(),source.requirementIds(),source.startedTick(),source.elapsedActiveTicks(),source.maxDurationTicks(),source.recoveryPolicy(),source.outcome());
  var row=new AdminRow(new ScopeRef(ScopeKind.EVENT,started.instanceId().value()),started.header().revision(),new Tick(10),new EventAdminData(started,List.of(),List.of(),List.of()));
  progress.set(new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),new Revision(1),Optional.of(row),Optional.empty()));dispatcher.pump();
  check(dispatcher.poll(key).status()==AdminStatus.PENDING&&f.store().batches.size()==2,"actual Started still waits terminal audit ACK");
  var terminalTx=f.store().pending.keySet().iterator().next();var audit=((DurableRuntimeAdminAudit)f.store().batches.get(terminalTx).records().getFirst()).audit();
  f.store().ack(terminalTx);dispatcher.pump();check(dispatcher.poll(key).status()==AdminStatus.SUCCEEDED,"physical success only after exact terminal audit ACK");
  dispatcher.submit(command);dispatcher.pump();check(calls.get()==1&&f.store().batches.size()==2,"idempotent physical retry never reenqueues");
  var recovered=physical(f,hooks);recovered.restore(intent);recovered.pump();
  check(recovered.submit(command).status()==AdminStatus.DEFERRED&&calls.get()==1,"intent-only restart requires recovery, never launches again");
  var facade=new fr.proutlost.director.admin.server.AdminFacade(recovered,session->Optional.of(f.authority().get()),f.world().get().worldId(),new Epoch(1),16);
  var reply=facade.submit(command,f.authority().get()).toCompletableFuture();
  check(reply.isDone()&&reply.getNow(null).error().orElseThrow().code()==fr.proutlost.director.api.error.ReasonCode.RECOVERY_AMBIGUOUS,"recovery refusal is delivered, not left forever PENDING");
  recovered.restore(audit);check(recovered.submit(command).status()==AdminStatus.SUCCEEDED&&calls.get()==1,"durable terminal restore returns original receipt");
  var denied=controlFixture();var rejected=physical(denied,hooks);
  rejected.submit(command);var deniedTx=denied.store().pending.keySet().iterator().next();denied.store().ackWithForeignBlob(deniedTx);rejected.pump();
  check(calls.get()==1&&rejected.poll(key).status()==AdminStatus.DEFERRED,"foreign ACK attestation never launches hook");
  var bogus=controlFixture();var noStarted=physical(bogus,new AdminOperationDispatcher.Hooks(){
   public AdminResult launch(AdminCommand c,ActorIdentity actor){return new AdminResult(AdminStatus.SUCCEEDED,c.requestId(),c.expectedRevision(),Optional.empty(),Optional.empty());}
   public Optional<AdminResult> progress(RuntimeAdminKey request){return Optional.empty();}
  });
  noStarted.submit(command);var bogusTx=bogus.store().pending.keySet().iterator().next();bogus.store().ack(bogusTx);noStarted.pump();
  check(noStarted.poll(key).status()==AdminStatus.DEFERRED&&bogus.store().batches.size()==1,"success without matching Started proof never receives terminal audit");
  var revoked=controlFixture();var revokeDispatch=physical(revoked,hooks);revokeDispatch.submit(command);
  var revokeTx=revoked.store().pending.keySet().iterator().next();revoked.store().ack(revokeTx);revoked.authority().set(null);revokeDispatch.pump();
  check(calls.get()==1&&revoked.store().batches.size()==2,"revoked actor never launches; terminal refusal is audited");
 }
 public static void main(String[] args){
  revokedQueuedBeforeWriterIdle();revokedAfterAppendStillDrains();afterCommittedOnce();worldControls();physicalDispatch();
  var store=new RuntimeLifecycleTests.Store();var worldId=store.session().worldId();
  var header=new AggregateHeader(1,worldId.value(),new Revision(1),new Sequence(1),new Tick(1));
  var engine=new EngineControlState(1,new Revision(1),new Tick(1),new Tick(20),new UnitValue(0),new Tick(1),Optional.empty(),new UnitValue(0));
  var hash=new Fingerprint("a".repeat(64));
  var world=new AtomicReference<>(new DirectorWorldState(header,worldId,store.session().storeGeneration(),new Tick(1),RunControl.RUNNING,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,new ResourceId("proutlost_director:test"),hash,hash,new Epoch(1),new Sequence(1),engine));
  var stamp=new AtomicReference<>(new AdminSnapshotStamp(worldId,new Epoch(1),new Revision(1),new Tick(1),new Sequence(1)));
  var session=new SessionId(id(1));var authority=new AtomicReference<>(new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true));
  var handler=new RunControlHandler(world::get);
  var mailbox=new RuntimeAdminMailbox(store,s->Optional.ofNullable(authority.get()),stamp::get,c->{world.set(((WorldAggregate)((DurableAggregate)c.records().getFirst()).state()).value());stamp.set(c.after());},Map.of(AdminOperation.PAUSE,handler,AdminOperation.RESUME,handler,AdminOperation.DRAIN,handler),4,ProviderMode.PRODUCTION);
  var command=new AdminCommand(session,new RequestId(id(2)),new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
  check(mailbox.submit(command).status()==AdminStatus.PENDING,"queue receipt is not success");check(mailbox.submit(command).status()==AdminStatus.PENDING,"idempotent pending");
  check(mailbox.submit(new AdminCommand(session,command.requestId(),new Revision(1),AdminOperation.DRAIN,new ControlPayload(RunControl.DRAINING))).status()==AdminStatus.REJECTED,"payload substitution rejected");
  mailbox.pump();check(world.get().runControl()==RunControl.RUNNING,"control unchanged before ACK");
  var tx=store.batches.keySet().iterator().next();var batch=store.batches.get(tx);
  check(batch.records().size()==2&&batch.records().stream().anyMatch(r->r instanceof DurableRuntimeAdminAudit)&&batch.records().stream().anyMatch(r->r instanceof DurableAggregate),"audit and actual control atomically appended");
  store.ack(tx);check(world.get().runControl()==RunControl.RUNNING,"ACK callback has no mutation authority");mailbox.pump();
  check(world.get().runControl()==RunControl.PAUSED&&mailbox.poll(new RuntimeAdminKey(session,command.requestId())).status()==AdminStatus.SUCCEEDED,"publish only on actor after ACK");
  check(mailbox.submit(command).status()==AdminStatus.SUCCEEDED&&store.batches.size()==1,"success replay never appends again");
  var resume=new AdminCommand(session,new RequestId(id(3)),new Revision(2),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING));mailbox.submit(resume);authority.set(null);mailbox.pump();
  check(mailbox.poll(new RuntimeAdminKey(session,resume.requestId())).status()==AdminStatus.REJECTED&&store.batches.size()==1,"revocation rechecked before application");
  check(mailbox.poll(new RuntimeAdminKey(session,new RequestId(id(90)))).status()==AdminStatus.DEFERRED,"unknown historical request never reruns");
  var projections=new AdminProjectionService(10);
  var query=new AdminQuery(session,new RequestId(id(4)),AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),1);
  check(projections.query(query).status()==AdminStatus.DEFERRED,"no fabricated empty snapshot");projections.publish(stamp.get(),List.of());
  check(projections.query(query).status()==AdminStatus.SUCCEEDED&&projections.query(query).snapshot().equals(Optional.of(stamp.get())),"empty success retains coherent stamp");
  var stale=new AdminQuery(session,query.requestId(),query.panel(),query.filter(),Optional.empty(),Optional.of(new Revision(1)),1);
  check(projections.query(stale).status()==AdminStatus.DEFERRED,"stale revision defers");rejects(()->projections.publish(stamp.get(),List.of()));
  authority.set(new ActorIdentity(session,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true));
  var drain=new AdminCommand(session,new RequestId(id(5)),new Revision(2),AdminOperation.DRAIN,new ControlPayload(RunControl.DRAINING));mailbox.submit(drain);mailbox.pump();
  var next=store.pending.keySet().iterator().next();store.fail(next);mailbox.pump();
  check(world.get().runControl()==RunControl.PAUSED&&mailbox.poll(new RuntimeAdminKey(session,drain.requestId())).status()==AdminStatus.DEFERRED,"failed write leaves control and fences mailbox");
  check(mailbox.submit(new AdminCommand(session,new RequestId(id(6)),new Revision(2),AdminOperation.RESUME,new ControlPayload(RunControl.RUNNING))).status()==AdminStatus.DEFERRED,"ambiguous writer prevents subsequent changes");
  check(mailbox.hasWork(),"uncertain durable engagement retains WORLD ownership");
  System.out.println("PASS J05 admin runtime assertions="+assertions+"; TEST_ONLY durable store, native NOT_RUN");
 }
}
