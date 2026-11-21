package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.config.TestingConfig;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real pure evaluations on bounded threads; synthetic actor clocks/plans are
 * declared unit fixtures. No Minecraft thread, permit or physical effect claimed. */
public final class DecisionCpuWorkTests {
 private static int assertions;
 private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
 private static ConfigView changed(ConfigView c,String key,String value){var values=new HashMap<>(c.values());values.put(key,new JsonValue.Number(new BigDecimal(value)));return new ConfigView(c.revision(),c.provenanceHash(),c.calibration(),values);}
 private static <T>T finished(BoundedDecisionWorker.Work<T> work)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(!work.done()&&System.nanoTime()<deadline)Thread.sleep(1);
  check(work.done(),"bounded worker finishes observed task");return work.result();
 }
 private static void budgets(ConfigView config){
  var wall=new AtomicLong();var cpu=new AtomicLong();var b=new DecisionWorkBudget(config,wall::get,cpu::get);b.begin(10);
  check(b.take(),"first bounded operation starts");b.account(()->{b.run(()->{wall.addAndGet(100000);cpu.addAndGet(70000);});return null;});
  check(b.take(),"second operation before deadline starts");b.account(()->{b.run(()->{wall.addAndGet(150000);cpu.addAndGet(80000);});return null;});
  check(!b.take(),"no operation starts at exhausted configured0.25ms deadline");b.begin(10);check(!b.take(),"same actor tick cannot reset budget");
  check(b.metrics().currentOperations()==2&&b.metrics().actorWallNanos()==250000&&b.metrics().actorCpuNanos()==150000,"actor wall and CPU measured separately");
  b.begin(11);check(b.take(),"next tick resumes same work with fresh slice");
  b.account(()->{b.run(()->{wall.addAndGet(300000);cpu.addAndGet(200000);});return null;});
  check(b.metrics().unpreemptibleUnitOverruns()==1&&!b.take(),"unpreemptible overrun recorded and further units deferred");
  var capped=new DecisionWorkBudget(changed(config,"budget.snapshot_ops","2"),wall::get,cpu::get);capped.begin(12);
  check(capped.take()&&capped.take()&&!capped.take(),"operation counter bounds fast units independently of duration");
  var tiny=new DecisionWorkBudget(changed(config,"budget.decision_ms","0.0000000001"),wall::get,cpu::get);check(tiny.metrics().budgetNanos()==1,"subnanosecond positive budget rounds to one representable nanosecond");
  try{b.begin(9);throw new AssertionError("tick regression accepted");}catch(IllegalArgumentException expected){assertions++;}
 }
 private static void backpressureAndCancel()throws Exception{
  var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var ranQueued=new AtomicBoolean();
  try(var worker=new BoundedDecisionWorker("P008-pure-cancel")){
   var running=worker.submit(()->{entered.countDown();try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException stop){interrupted.countDown();Thread.currentThread().interrupt();throw new CancellationException("owned test cancellation");}return 1;}).orElseThrow();
   check(entered.await(2,TimeUnit.SECONDS),"owned pure worker actually running");
   var queued=worker.submit(()->{ranQueued.set(true);return 2;}).orElseThrow();check(worker.queued()==1,"technical CPU queue bounded to one");
   check(worker.submit(()->3).isEmpty(),"third task refused with typed backpressure");check(!ranQueued.get(),"queued result not executed on caller actor");
   running.cancel();check(interrupted.await(2,TimeUnit.SECONDS),"running task cancellation really interrupts cooperative work");
   check(running.done()&&running.metrics().cancelled(),"cancelled task cannot deliver late success");
   try{running.result();throw new AssertionError("cancelled result published");}catch(CancellationException expected){assertions++;}
   check(finished(queued)==2&&ranQueued.get(),"original queued task can progress after cancellation");
  }
  var entered2=new CountDownLatch(1);var stopped2=new CountDownLatch(1);var worker=new BoundedDecisionWorker("P008-pure-close");
  var active=worker.submit(()->{entered2.countDown();try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException stop){stopped2.countDown();throw new CancellationException();}return 1;}).orElseThrow();
  check(entered2.await(2,TimeUnit.SECONDS),"close test has owned active work");var queued=worker.submit(()->2).orElseThrow();worker.close();
  check(stopped2.await(2,TimeUnit.SECONDS)&&active.done()&&queued.done(),"world-local close cancels active and queued tasks");
  check(worker.submit(()->3).isEmpty(),"closed lane refuses new work");
 }
 private static DecisionInput fixture(Path root)throws Exception{
  var loaded=CatalogueEventLedgerFixtures.load(root);var event=loaded.event("safe_micro");var primitive=event.steps().getFirst().primitive();
  var rid=new ResourceId("fixture:pure_cpu");var cap=event.safety().requiredCapabilities().iterator().next();
  var descriptor=new PrimitiveDescriptor(primitive,1,rid,Set.of(cap),BudgetMath.zero(),rid,Set.of(),Set.of(),Set.of(),new DurationTicks(100),PausePolicy.CLEANUP,RecoveryMode.CLEANUP,rid,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var hash=BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(event));var catalog=new CatalogSnapshot(new Epoch(1),hash,Map.of(event.id(),event),Map.of(),Map.of(primitive,descriptor));
  var base=CatalogueEventLedgerFixtures.input(new CatalogueEventLedgerFixtures.Loaded(loaded.config(),catalog),CatalogueEventLedgerFixtures.REGION,List.of(CatalogueEventLedgerFixtures.FIRST,CatalogueEventLedgerFixtures.SECOND),new Tick(20000));
  var old=base.context().context();var capabilities=Map.of(cap,new CapabilityDescriptor(cap,1,ProviderMode.TEST_ONLY,CapabilityStatus.AVAILABLE,rid,Set.of("DECLARED_UNIT_FIXTURE")));
  var context=new ObservationContext(old.scopes(),old.players(),old.groups(),old.regions(),old.anchors(),old.locations(),old.terrain(),capabilities);
  var snapshot=new SnapshotBatch(base.context().requestId(),base.context().scopes(),base.context().capturedTick(),base.context().scopeRevisions(),context,base.context().missingData(),base.context().loadedChunks(),HealthMode.NORMAL);
  var state=base.state();var scopes=Map.of(new ScopeRef(ScopeKind.REGION,CatalogueEventLedgerFixtures.REGION.value()),BudgetMath.zero());
  var decision=new DecisionState(state.world(),new BudgetView(CatalogueEventLedgerFixtures.CAP,BudgetMath.zero(),scopes),state.ownership(),state.cooldowns(),state.uniqueness(),state.facts(),state.events(),state.chains(),state.disasters());
  return new DecisionInput(snapshot,base.memory(),catalog,base.config(),base.requests(),base.chains(),42,decision,Optional.empty());
 }
 private static void frozenPlanning(Path root)throws Exception{
  var actor=Thread.currentThread();var input=fixture(root);var inputBytes=new ModelCodec<>(DecisionInput.class).encode(input);var workerName=new AtomicReference<String>();
  try(var worker=new BoundedDecisionWorker("P008-pure-decision-test")){
   var cancelled=worker.submit(()->{Thread.currentThread().interrupt();return new WorldDirector(ProviderMode.TEST_ONLY).evaluate(input);}).orElseThrow();
   long stopDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!cancelled.done()&&System.nanoTime()<stopDeadline)Thread.sleep(1);
   check(cancelled.done(),"interrupted pure engine work terminates cooperatively");
   try{cancelled.result();throw new AssertionError("interrupted pure engine returned proposal");}catch(CancellationException expected){assertions++;}
   var first=worker.submit(()->{check(Thread.currentThread()!=actor,"pure first pass is off actor");workerName.set(Thread.currentThread().getName());return new WorldDirector(ProviderMode.TEST_ONLY).evaluateDetailed(input);}).orElseThrow();
   var computed=finished(first);check(workerName.get().equals("P008-pure-decision-test"),"owned CPU worker identity captured");
   check(computed.proposal().candidates().size()==2&&computed.generatedCandidates().size()==2,"exact generated metadata accompanies two actual candidates");
   var snapshots=new HashMap<CandidateId,List<PlanningSnapshot>>();var planned=new HashMap<OperationToken,PlannedPrimitive>();
   var wall=new AtomicLong();var cpu=new AtomicLong();var budget=new DecisionWorkBudget(input.config(),wall::get,cpu::get);int index=0;long tick=1,cleanupPolls=0;
   var candidates=computed.proposal().candidates();
   while(index<candidates.size()){
    cleanupPolls++;budget.begin(tick++); // Actual runtime keeps maintenance before its decision poll.
    while(index<candidates.size()&&budget.take()){
     var candidate=candidates.get(index++);budget.account(()->{budget.run(()->{
      check(Thread.currentThread()==actor,"captured primitive planning stays on actor");
      check(candidate.reasons().equals(List.of(ReasonCode.CAPABILITY_MISSING)),"declared unit candidate otherwise eligible");
      var generated=computed.generatedCandidates().get(candidate.id());var definition=input.catalog().events().get(candidate.definition().id());var step=definition.steps().getFirst();
      var event=new EventInstanceId(candidate.id().value());var operation=new OperationToken(UUID.nameUUIDFromBytes((candidate.id()+"/"+step.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var footprint=new Footprint(CatalogueEventLedgerFixtures.DIM,input.context().loadedChunks(),Set.of(),List.of());
      var players=new HashSet<PlayerId>();candidate.roles().roles().values().forEach(players::addAll);
      var binding=new PlanningBindings(candidate.id(),candidate.definition(),event,operation,step.id(),candidate.roles(),candidate.scopes(),footprint,players,true);
      var snapshot=new PlanningSnapshot(input.context(),input.state().ownership(),input.state().budgets(),new BlobBundle(List.of()),Optional.of(binding));
      check(CandidatePlanner.validateCapturedSnapshot(generated,input,snapshot,step).valid(),"shared actor/worker planning preflight accepts exact evidence");
      var bad=new PlanningBindings(candidate.id(),candidate.definition(),event,operation,step.id(),candidate.roles(),candidate.scopes(),footprint,players,false);
      check(!CandidatePlanner.validateCapturedSnapshot(generated,input,new PlanningSnapshot(snapshot.snapshot(),snapshot.ownership(),snapshot.budgets(),snapshot.blobs(),Optional.of(bad)),step).valid(),"incomplete audience defers before actor primitive plan");
      var blob=BlobContent.capture(new ResourceId("fixture:pure_cpu_blob"),1,new byte[]{1});
      var prepared=new PreparedEffect(new EffectIntent(operation,step.primitive(),1,step.args(),footprint,event),input.context(),List.of(),new ReadSet(input.context().scopeRevisions(),Map.of(),Map.of()),Map.of(),definition.budget(),List.of(),definition.safety().requiredCapabilities(),new EffectBlobs(Set.of(blob.reference()),blob.reference(),blob.reference(),Map.of(),Map.of()));
      planned.put(operation,new PlannedPrimitive(binding,prepared));snapshots.put(candidate.id(),List.of(snapshot));wall.addAndGet(300000);cpu.addAndGet(100000);
     });return null;});
    }
   }
   check(cleanupPolls==2&&budget.metrics().slices()==2&&snapshots.size()==2,"actor continuation preserves all candidates without timing-based omission");
   var frozenSnapshots=Map.copyOf(snapshots);var frozenPlans=Map.copyOf(planned);var portThread=new AtomicReference<Thread>();
   PlanningPort immutable=request->{portThread.set(Thread.currentThread());return Objects.requireNonNull(frozenPlans.get(request.snapshot().bindings().orElseThrow().operationToken()));};
   var second=worker.submit(()->new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(immutable,frozenSnapshots)).evaluate(input)).orElseThrow();
   var actual=finished(second);check(portThread.get()!=actor,"pure final pass never invokes actor primitive owner");
   var expected=new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(immutable,frozenSnapshots)).evaluate(input);
   check(actual.equals(expected)&&actual.selectedPlan().isPresent(),"sliced frozen planning produces exact synchronous selected proposal");
   check(new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(immutable,frozenSnapshots)).replay(input,actual.audit()).equals(actual),"same recorded input/seed/plans replay exact proposal");
   check(Arrays.equals(inputBytes,new ModelCodec<>(DecisionInput.class).encode(input)),"frozen input bytes unchanged by queue and continuations");
   check(first.metrics().workerCpuNanos()>0&&second.metrics().workerCpuNanos()>0,"actual worker thread CPU measured separately");
   check(first.metrics().queueWaitNanos()>0&&first.metrics().workerWallNanos()>0,"queue age and worker wall measured separately");
   System.out.println("FROZEN_WORK actor_slices="+budget.metrics().slices()+" actor_cpu_ns="+budget.metrics().actorCpuNanos()+" worker_first_cpu_ns="+first.metrics().workerCpuNanos()+" worker_final_cpu_ns="+second.metrics().workerCpuNanos()+" input_immutable=true exact_replay=true");
  }
 }
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);var config=TestingConfig.load(Files.readAllBytes(root.resolve("DEFAULT_FOR_TESTING.json"))).view();
  budgets(config);backpressureAndCancel();frozenPlanning(root);
  System.out.println("PASS DecisionCpuWorkTests assertions="+assertions+" scope=PURE_COOPERATIVE_BUDGET_AND_ACTUAL_BOUNDED_CPU_WORKER_NOT_NATIVE_MSPT");
 }
}
