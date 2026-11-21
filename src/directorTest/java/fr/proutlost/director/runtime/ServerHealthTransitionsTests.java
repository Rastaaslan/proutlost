package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic health decisions; actual disk-backed WORLD updates and readback.
 * This test does not manufacture Minecraft timings or physical cleanup proof. */
public final class ServerHealthTransitionsTests {
 private static int assertions;
 private static final WorldId WORLD=new WorldId(new UUID(9408,101));
 private static final ScopeRef SCOPE=new ScopeRef(ScopeKind.WORLD,WORLD.value());
 private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(10,TimeUnit.SECONDS);}
 private static EventDefinition definition(Set<Family> families,BudgetVector cost){
  var duration=new DurationTicks(100);var zero=new DurationTicks(0);
  return new EventDefinition(1,new Revision(1),new DefinitionId("fixture:health"),Calibration.DEFAULT_FOR_TESTING,Category.AMBIENT,families,true,new Selection(1,0),
   new Target(TargetScope.SOLO,Set.of(),PlayerPolicy.FAIR,Map.of("witness",new Role(1,1,false))),new PlayersPresent(1),new Timing(duration,new Cooldowns(zero,zero,zero)),
   new Uniqueness(UniquenessScope.REPEATABLE,ConsumeOn.EFFECT_CONFIRMED),cost,new Conflicts(Set.of(),Set.of(),Set.of(),Priority.AMBIENT,Preemption.YIELD_BEFORE_COMMIT),
   new Safety(Risk.SAFE,Set.of(),Optional.empty(),Optional.empty(),PlayerEditPolicy.YIELD,JournalPolicy.PLAYER_OPT_IN,ParticipantLoss.ABORT_IF_MIN_LOST),
   List.of(new EventStep("trace",List.of(),zero,new PrimitiveId("fixture:trace"),new Trace(new TagId("fixture:health"),new TagId("fixture:seen"),true),OnFailure.ABORT_SAFE)),
   new RecoveryPolicy(RecoveryMode.RECOVER,duration),new Completion(new FactType("fixture:seen"),Optional.empty(),new UnitValue(1)));
 }
 private static DirectorWorldState initial(StoreSession session){
  var tick=new Tick(10);var control=new EngineControlState(1,new Revision(7),tick,new Tick(110),new UnitValue(.3),tick,Optional.empty(),new UnitValue(.6));
  return new DirectorWorldState(new AggregateHeader(1,WORLD.value(),new Revision(1),new Sequence(0),tick),WORLD,session.storeGeneration(),tick,RunControl.RUNNING,HealthMode.NORMAL,
   PacingState.REST,GlobalTension.TENSE,new ResourceId("fixture:profile"),BlobContent.hash(new byte[]{1}),BlobContent.hash(new byte[]{2}),session.bootEpoch(),new Sequence(0),control);
 }
 private static DurableBatch batch(JournalStore store,DirectorWorldState value,Revision before){
  return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(SCOPE,before),List.of(new DurableAggregate(new WorldAggregate(value))),List.of());
 }
 private static DirectorWorldState read(JournalStore store){
  var q=new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.WORLD,WORLD.value())),new Sequence(0),Optional.empty(),1);var r=store.view(q);ReadContracts.validate(r,q,store.session());
  check(r.status()==ReadStatus.AVAILABLE&&r.page().orElseThrow().aggregates().size()==1,"WORLD actual readback available");
  return ((WorldAggregate)r.page().orElseThrow().aggregates().getFirst().state()).value();
 }
 private static CommitAck exact(JournalStore store,DurableBatch b)throws Exception{
  var a=await(store.append(b));check(a.ackStatus()==AckStatus.DURABLE&&a.durableSequence().value()>0,"health state genuinely durable");
  check(a.transactionId().equals(b.transactionId())&&a.transactionHash().equals(b.transactionHash())&&a.storeGeneration().equals(store.session().storeGeneration()),"exact transaction identity/hash/generation");
  check(a.forcedBlobs().isEmpty()&&a.permitAttempt().isEmpty(),"health update grants no effect authority");return a;
 }
 public static void main(String[]args)throws Exception{
  Path root=args.length==1?Path.of(args[0]):Files.createTempDirectory("P008-health-transitions-");Files.createDirectories(root);
  for(var a:HealthMode.values())for(var b:HealthMode.values()){
   check(ServerHealthTransitions.worst(a,b).ordinal()==Math.max(a.ordinal(),b.ordinal()),"mode severity maximum");
   check(ServerHealthTransitions.effective(a,b,Optional.of(HealthMode.READ_ONLY_SAFE))==HealthMode.READ_ONLY_SAFE,"pending unsafe severity cannot be hidden");
  }
  check(!ServerHealthTransitions.available(HealthMode.CRITICAL)&&!ServerHealthTransitions.available(HealthMode.READ_ONLY_SAFE),"unsafe modes close new admission");
  var safe=definition(Set.of(Family.SAFE_MICRO_EVENT),BudgetMath.zero());
  check(ServerHealthTransitions.allowsNewScene(HealthMode.NORMAL,safe)&&ServerHealthTransitions.allowsNewScene(HealthMode.DEGRADED,safe),"zero-physical-cost safe micro remains possible");
  check(!ServerHealthTransitions.allowsNewScene(HealthMode.CRITICAL,safe)&&!ServerHealthTransitions.allowsNewScene(HealthMode.READ_ONLY_SAFE,safe),"micro cannot bypass unsafe mode");
  check(!ServerHealthTransitions.allowsNewScene(HealthMode.DEGRADED,definition(Set.of(Family.SAFE_MICRO_EVENT,Family.DISASTER),BudgetMath.zero())),"danger family cannot borrow safe micro tag");
  check(!ServerHealthTransitions.allowsNewScene(HealthMode.DEGRADED,definition(Set.of(Family.AMBIENCE),BudgetMath.zero())),"ordinary scene suspended during degraded");
  for(var cost:List.of(new BudgetVector(0,0,0,1,0,0,0,0,0,0),new BudgetVector(0,0,0,0,1,0,0,0,0,0),new BudgetVector(0,0,0,0,0,1,0,0,0,0),new BudgetVector(0,0,0,0,0,0,0,0,0,1)))
   check(!ServerHealthTransitions.allowsNewScene(HealthMode.DEGRADED,definition(Set.of(Family.SAFE_MICRO_EVENT),cost)),"physical/navigation cost rejected in degraded micro");
  var options=new StoreOptions(1048620,4194304,16777216,33554432,32,0,128);
  var store=await(JournalStore.open(root.resolve("store"),WORLD,options));try{
   var session=store.session();var first=initial(session);exact(store,batch(store,first,new Revision(0)));check(read(store).equals(first),"initial actual WORLD exact");
   var critical=ServerHealthTransitions.next(first,session,new Tick(11),HealthMode.CRITICAL);
   check(critical.runControl()==first.runControl()&&critical.engineControl().equals(first.engineControl()),"health transition preserves run control and pacing state");
   check(critical.catalogHash().equals(first.catalogHash())&&critical.configHash().equals(first.configHash())&&critical.intensityProfileId().equals(first.intensityProfileId()),"health transition preserves calibrated content/profile");
   check(critical.header().revision().equals(new Revision(2))&&critical.simTick().equals(new Tick(11)),"one next WORLD revision and monotone tick");
   var criticalBatch=batch(store,critical,first.header().revision());exact(store,criticalBatch);check(read(store).equals(critical),"critical exact actual readback");
   var normal=ServerHealthTransitions.next(critical,session,new Tick(12),HealthMode.NORMAL);
   check(ServerHealthTransitions.effective(HealthMode.NORMAL,normal.healthMode(),Optional.of(critical.healthMode()))==HealthMode.CRITICAL,"published recovered WORLD remains fenced while original ACK pending");
   exact(store,batch(store,normal,critical.header().revision()));check(read(store).equals(normal),"recovery exact actual WORLD readback");
   check(ServerHealthTransitions.effective(HealthMode.NORMAL,read(store).healthMode(),Optional.empty())==HealthMode.NORMAL,"only verified recovery removes held former severity");
   var stale=ServerHealthTransitions.next(critical,session,new Tick(13),HealthMode.DEGRADED);var rejected=await(store.append(batch(store,stale,critical.header().revision())));
   check(rejected.ackStatus()==AckStatus.REJECTED&&rejected.durableSequence().value()==0,"stale WORLD transition rejected with no durable sequence");
   check(read(store).equals(normal),"stale health writer cannot overwrite recovered mode");
   try{ServerHealthTransitions.next(normal,session,new Tick(11),HealthMode.CRITICAL);throw new AssertionError("clock regression accepted");}catch(IllegalArgumentException expected){assertions++;}
   var foreign=new StoreSession(new WorldId(new UUID(9408,999)),session.storeGeneration(),session.bootEpoch(),new SessionId(new UUID(9408,998)));
   try{ServerHealthTransitions.next(normal,foreign,new Tick(13),HealthMode.CRITICAL);throw new AssertionError("foreign session accepted");}catch(IllegalArgumentException expected){assertions++;}
   await(store.checkpoint());
  }finally{await(store.closeAsync());}
  var reopened=await(JournalStore.open(root.resolve("store"),WORLD,options));try{
   var stored=read(reopened);check(stored.healthMode()==HealthMode.NORMAL&&stored.runControl()==RunControl.RUNNING,"closed/reopened actual store replays actual final WORLD health/control");
   check(stored.header().revision().equals(new Revision(3))&&stored.simTick().equals(new Tick(12)),"replay preserves exact health revision/tick");
  }finally{await(reopened.closeAsync());}
  System.out.println("PASS ServerHealthTransitionsTests assertions="+assertions+" scope=PURE_HEALTH_FENCES_AND_ACTUAL_J04_WORLD_NO_NATIVE_MSPT");
 }
}
