package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import fr.proutlost.director.adapter.neoforge.server.observation.ObservationBudget;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Declared fake monotonic actor clocks/units; no physical IMPACT or nativeMSPT claim. */
public final class DirectorTickWorkBudgetTests {
 private static int assertions;
 private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
 private static ConfigView with(ConfigView c,String key,long value){var m=new HashMap<>(c.values());m.put(key,new JsonValue.Number(BigDecimal.valueOf(value)));return new ConfigView(c.revision(),c.provenanceHash(),c.calibration(),m);}
 private static void fairness(ConfigView config){
  var wall=new AtomicLong();var cpu=new AtomicLong();var seen=new ArrayList<String>();var b=new DirectorTickWorkBudget(config,wall::get,cpu::get);
  var critical=new ArrayList<DirectorTickWorkBudget.Unit>();for(var name:List.of("ACK","IMPACT","RECOVERY"))critical.add(new DirectorTickWorkBudget.Unit(name,()->true,()->{seen.add(name);wall.addAndGet(300000);cpu.addAndGet(200000);}));
  var ordinary=new ArrayList<DirectorTickWorkBudget.Unit>();for(var name:List.of("SNAPSHOT","DECISION","ADMIN"))ordinary.add(new DirectorTickWorkBudget.Unit(name,()->true,()->{seen.add(name);wall.addAndGet(200000);cpu.addAndGet(100000);}));
  b.install(critical,ordinary);int health=0;
  for(int tick=1;tick<=6;tick++){
   b.begin(tick);b.mandatory(()->{wall.addAndGet(10000);cpu.addAndGet(5000);});health++;int start=seen.size();b.runCritical();b.runOrdinary();b.finish();
   var frame=seen.subList(start,seen.size());check(frame.size()==4,"critical reserve and remaining global frame defer additional work");
   check(!frame.get(0).equals("SNAPSHOT")&&!frame.get(1).equals("DECISION")&&Set.of("ACK","IMPACT","RECOVERY").containsAll(frame.subList(0,2)),"critical units precede all optional units");
   check(b.metrics().criticalWallNanos()==600000&&b.metrics().ordinaryWallNanos()==400000,"indivisible final unit overrun measured without truncation");
  }
  for(var key:List.of("ACK","IMPACT","RECOVERY","SNAPSHOT","DECISION","ADMIN"))check(Collections.frequency(seen,key)==4,"persistent lane cursor fairly resumes every deferred key");
  check(health==6&&b.metrics().frames()==6,"measured health prelude remains consecutive every actor frame");
  check(b.metrics().actorCpuNanos()==605000&&b.metrics().actorWallNanos()==1010000,"actor real-clock domains separate CPU from wall");
 }
 private static void overrunAndAck(ConfigView config){
  var wall=new AtomicLong();var budget=new DirectorTickWorkBudget(config,wall::get,()->-1);var seen=new ArrayList<String>();var acks=new AtomicInteger(5);
  budget.install(List.of(new DirectorTickWorkBudget.Unit("EXPENSIVE_IMPACT",()->true,()->{seen.add("impact");wall.addAndGet(1200000);}),
   new DirectorTickWorkBudget.Unit("DURABLE_ACK",()->acks.get()>0,()->{acks.decrementAndGet();seen.add("ack");wall.addAndGet(10000);},true)),
   List.of(new DirectorTickWorkBudget.Unit("NEW_DECISION",()->true,()->seen.add("decision"))));
  budget.begin(1);budget.runCritical();budget.runOrdinary();budget.finish();check(seen.equals(List.of("impact")),"one started overrun completes; no new optional unit follows expired global deadline");
  check(budget.metrics().criticalOverruns()==1&&budget.metrics().actorWallNanos()==1200000,"critical overrun retained as observed metric");
  budget.begin(2);budget.runCritical();budget.runOrdinary();budget.finish();check(seen.get(1).equals("ack"),"next frame begins with previously deferred ACK rather than restarting expensive first unit");
  for(int t=3;t<=8;t++){budget.begin(t);budget.runCritical();budget.runOrdinary();budget.finish();}
  check(acks.get()==0,"durable ACK queue drains despite repeated IMPACT overruns");check(budget.metrics().actorCpuNanos()==-1,"unsupported actorCPU is unknown instead of fake zero");
 }
 private static void mandatoryAndDeferred(ConfigView config){
  var wall=new AtomicLong();var b=new DirectorTickWorkBudget(config,wall::get,wall::get);var counts=new int[3];
  b.install(List.of(new DirectorTickWorkBudget.Unit("RECOVER_A",()->true,()->{counts[0]++;wall.addAndGet(1000);}),new DirectorTickWorkBudget.Unit("RECOVER_B",()->true,()->{counts[1]++;wall.addAndGet(1000);})),List.of(new DirectorTickWorkBudget.Unit("OPTIONAL",()->true,()->counts[2]++)));
  for(int tick=1;tick<=4;tick++){b.begin(tick);b.mandatory(()->wall.addAndGet(1100000));b.runCritical();b.runCritical();b.runOrdinary();b.finish();}
  check(counts[0]==2&&counts[1]==2&&counts[2]==0,"one emergency critical progress unit per frame survives expensive mandatory prelude with fairness");
  check(b.metrics().emergencyCriticalUnits()==4,"mandatory-pressure exception explicitly measured");
  check(b.metrics().criticalUnits()==4,"same tick cannot reset critical allowance");
 }
 private static void sharedLimits(ConfigView config){
  var wall=new AtomicLong();var b=new DirectorTickWorkBudget(with(config,"budget.snapshot_ops",5),wall::get,wall::get);var captured=new AtomicInteger();
  b.install(List.of(),List.of(new DirectorTickWorkBudget.Unit("SHARED_OBSERVATION",()->true,()->{
   var local=new ObservationBudget(128,10000000,wall::get,b::takeObservationOperation);while(local.take()){captured.incrementAndGet();wall.addAndGet(1);}
  })));
  b.begin(1);b.runCritical();b.runOrdinary();b.finish();check(captured.get()==5&&b.metrics().operations()==5,"snapshot units share the operation bound while macro ACK/cleanup has its own time scope");
  var global=new DirectorTickWorkBudget(config,wall::get,wall::get);var local=new DecisionWorkBudget(config,wall::get,wall::get);var done=new AtomicInteger();
  local.sharedGate(global::takeObservationOperation);
  global.install(List.of(),List.of(new DirectorTickWorkBudget.Unit("SHARED_DECISION",()->true,()->{
   local.begin(2);while(local.take()){done.incrementAndGet();wall.addAndGet(50000);}
  })));
  global.begin(2);global.mandatory(()->wall.addAndGet(990000));global.runOrdinary();global.finish();
  check(done.get()==1,"B07 continuation stops on shared deadline before its own local allowance is exhausted");
  check(global.metrics().actorWallNanos()==1040000,"intrinsic final unit overrun counted, unfinished continuation deferred");
 }
 private static void windows(ConfigView config){
  var wall=new AtomicLong();var b=new DirectorTickWorkBudget(with(config,"health.metrics_window_ticks",5),wall::get,()->-1);b.install(List.of(),List.of());
  for(int tick=1;tick<=5;tick++){b.begin(tick);b.mandatory(()->wall.addAndGet(3000000));b.runCritical();b.runOrdinary();b.finish();}
  check(b.metrics().windowSamples()==5&&b.metrics().actorWallP95Nanos()==3000000&&b.metrics().p95GoalExceeded(),"actual Director actor rolling p95 consumes central2ms goal");
  for(int tick=6;tick<=10;tick++){b.begin(tick);b.mandatory(()->wall.addAndGet(100000));b.runCritical();b.runOrdinary();b.finish();}
  check(b.metrics().windowSamples()==5&&b.metrics().actorWallP95Nanos()==100000&&!b.metrics().p95GoalExceeded(),"bounded window evicts old pressure rather than keeping historical max");
  check(b.metrics().actorCpuP95Nanos()==-1,"CPU percentile unknown when thread CPU unsupported");
  try{b.begin(10);throw new AssertionError("duplicate frame accepted");}catch(IllegalArgumentException expected){assertions++;}
 }
 public static void main(String[]args)throws Exception{
  var config=TestingConfig.load(Files.readAllBytes(Path.of(args[0]))).view();fairness(config);overrunAndAck(config);mandatoryAndDeferred(config);sharedLimits(config);windows(config);
  System.out.println("PASS DirectorTickWorkBudgetTests assertions="+assertions+" scope=SYNTHETIC_ACTOR_UNITS_SHARED_MONOTONIC_CLOCK_NOT_NATIVE_IMPACT_OR_MSPT");
 }
}
