package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;

/** World-local cooperative server-actor lanes. Native calls already entered are
 * never preempted; overruns are measured and subsequent work stays queued.
 * Health/authority preludes are measured every tick. Critical tasks retain their
 * cursor even when one unit consumes the frame, so ACK/recovery cannot be lost
 * behind an early return from optional work. No execution permission is minted. */
public final class DirectorTickWorkBudget {
 public record Unit(String name,BooleanSupplier ready,Runnable step,boolean repeatable){
  public Unit{Objects.requireNonNull(name);Objects.requireNonNull(ready);Objects.requireNonNull(step);Checks.require(name.matches("[A-Z][A-Z0-9_]{0,63}"),"static work metric key");}
  public Unit(String name,BooleanSupplier ready,Runnable step){this(name,ready,step,false);}
 }
 public record Metrics(long tick,long frames,long globalNanos,long criticalNanos,int operationLimit,int operations,
  long mandatoryWallNanos,long criticalWallNanos,long ordinaryWallNanos,long actorWallNanos,long actorCpuNanos,
  long criticalUnits,long ordinaryUnits,long unitOverruns,long criticalOverruns,long emergencyCriticalUnits,
  int windowSamples,long actorWallP95Nanos,long actorCpuP95Nanos,long p95GoalNanos,boolean p95GoalExceeded){}
 private final Thread actor=Thread.currentThread();private final LongSupplier wall,cpu;
 private final long globalNanos,criticalNanos,p95Goal;private final int maxOperations;
 private final long[] walls,cpus;private int position,samples;private long frames;
 private List<Unit> critical=List.of(),ordinary=List.of();private long[] criticalSeen=new long[0],ordinarySeen=new long[0];
 private int criticalCursor,ordinaryCursor;private boolean installed,open;
 private long tick=-1,start,cpuStart,mandatorySpent,criticalSpent,ordinarySpent,lastWall,lastCpu=-1;
 private int operations;private long criticalUnits,ordinaryUnits,overruns,criticalOverruns,emergency;
 // Optional bounded diagnostics only; no extra work, permission or budget.
 // Logging time remains inside the full actor frame and subsequent deadlines.
 private final boolean unitDiagnostics=Boolean.getBoolean("proutlost.director.diagnostics.unit_overruns");
 private int diagnosticLines;private BooleanSupplier diagnosticReady=()->true;
 public void diagnosticGate(BooleanSupplier ready){actor();Checks.require(!open,"diagnostic gate outside frame");diagnosticReady=Objects.requireNonNull(ready);}
 private void diagnose(Unit unit,boolean urgent,long spent){
  if(unitDiagnostics&&spent>p95Goal&&diagnosticLines<64&&diagnosticReady.getAsBoolean()){
   diagnosticLines++;System.out.println("DIRECTOR_ACTOR_UNIT_OVER_GOAL native_tick="+tick+" unit="+unit.name()
    +" lane="+(urgent?"CRITICAL":"ORDINARY")+" wall_ns="+spent+" unchanged_goal_ns="+p95Goal+" diagnostic_ordinal="+diagnosticLines);
  }
 }
 public DirectorTickWorkBudget(ConfigView config){this(config,System::nanoTime,DecisionWorkBudget::threadCpuNanos);}
 public DirectorTickWorkBudget(ConfigView config,LongSupplier wall,LongSupplier cpu){
  this.wall=Objects.requireNonNull(wall);this.cpu=Objects.requireNonNull(cpu);
  globalNanos=nanos(config,"budget.tick_ms");criticalNanos=nanos(config,"budget.critical_tick_ms");p95Goal=nanos(config,"budget.tick_p95_ms");
  maxOperations=Objects.requireNonNull(config.values().get("budget.snapshot_ops")).value().intValueExact();
  int capacity=Objects.requireNonNull(config.values().get("health.metrics_window_ticks")).value().intValueExact();
  Checks.require(globalNanos>0&&criticalNanos>0&&criticalNanos<=globalNanos&&p95Goal>0&&maxOperations>0,"global/critical CPU profile");
  Checks.require(capacity>0&&capacity<=ServerHealthGuard.MAX_METRIC_SAMPLES,"Director CPU metrics window");walls=new long[capacity];cpus=new long[capacity];
 }
 private static long nanos(ConfigView config,String key){return Objects.requireNonNull(config.values().get(key),key).value().multiply(java.math.BigDecimal.valueOf(1_000_000)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();}
 private void actor(){Checks.require(Thread.currentThread()==actor,"Director work actor");}
 public void install(List<Unit> critical,List<Unit> ordinary){
  actor();Checks.require(!installed,"work queues already installed");this.critical=List.copyOf(critical);this.ordinary=List.copyOf(ordinary);
  var names=new HashSet<String>();for(var u:this.critical)Checks.require(names.add(u.name()),"duplicate critical work key");for(var u:this.ordinary)Checks.require(names.add(u.name()),"duplicate ordinary work key");
  criticalSeen=new long[critical.size()];ordinarySeen=new long[ordinary.size()];Arrays.fill(criticalSeen,-1);Arrays.fill(ordinarySeen,-1);installed=true;
 }
 public boolean frameOpen(){actor();return open;}
 public void begin(long serverTick){begin(serverTick,wall.getAsLong(),cpu.getAsLong());}
 public void begin(long serverTick,long beganWall,long beganCpu){
  actor();Checks.require(!open&&serverTick>=0&&(tick<0||serverTick>tick),"one monotonic Director frame per server tick");
  tick=serverTick;start=beganWall;cpuStart=beganCpu;mandatorySpent=criticalSpent=ordinarySpent=0;operations=0;criticalUnitsThisFrame=0;open=true;
 }
 /** Consecutive measured health/authority checks are a bounded prelude, not a
  * queue whose starvation could erase the health guard's entry/recovery window. */
 public void mandatory(Runnable step){actor();Checks.require(open,"Director frame closed");long began=wall.getAsLong();try{step.run();}finally{mandatorySpent+=elapsed(began);}}
 private long elapsed(long began){return Math.max(0,wall.getAsLong()-began);}
 public boolean ordinaryAvailable(){return open&&elapsed(start)<globalNanos;}
 public boolean takeObservationOperation(){actor();if(operations>=maxOperations||!ordinaryAvailable()||remainingNanos()<=0)return false;operations++;return true;}
 public long remainingNanos(){actor();if(!open)return 0;long available=Math.max(0,globalNanos-elapsed(start));return insideCritical?Math.min(available,Math.max(0,criticalNanos-criticalSpent-elapsed(activeUnitBegan))):available;}
 private boolean criticalAvailable(){return open&&(criticalUnitsThisFrame==0||criticalSpent<criticalNanos&&elapsed(start)<globalNanos);}
 private int criticalUnitsThisFrame;private boolean insideCritical;private long activeUnitBegan;
 public void runCritical(){actor();Checks.require(open&&installed,"work lanes not ready");run(true);}
 public void runOrdinary(){actor();Checks.require(open&&installed,"work lanes not ready");run(false);}
 private void run(boolean urgent){
  var units=urgent?critical:ordinary;var seen=urgent?criticalSeen:ordinarySeen;if(units.isEmpty())return;
  int cursor=urgent?criticalCursor:ordinaryCursor,withoutProgress=0;
  try{while(withoutProgress<units.size()){
   int index=cursor;var unit=units.get(index);cursor=(cursor+1)%units.size();withoutProgress++;
   if(!unit.repeatable()&&seen[index]==tick||!unit.ready().getAsBoolean())continue;
   if(!(urgent?criticalAvailable():ordinaryAvailable())){cursor=index;break;}
   long began=wall.getAsLong();boolean extra=urgent&&(elapsed(start)>=globalNanos||criticalSpent>=criticalNanos);seen[index]=tick;
   insideCritical=urgent;activeUnitBegan=began;try{unit.step().run();}
   finally{long spent=elapsed(began);if(urgent){criticalSpent+=spent;criticalUnits++;criticalUnitsThisFrame++;if(extra)emergency++;if(spent>criticalNanos){criticalOverruns++;overruns++;}}
    else{ordinarySpent+=spent;ordinaryUnits++;if(spent>globalNanos)overruns++;}insideCritical=false;diagnose(unit,urgent,spent);}
   withoutProgress=0;
  }}finally{if(urgent)criticalCursor=cursor;else ordinaryCursor=cursor;insideCritical=false;}
 }
 public void finish(){
  actor();Checks.require(open,"Director frame closed");lastWall=elapsed(start);long ended=cpu.getAsLong();lastCpu=cpuStart>=0&&ended>=cpuStart?ended-cpuStart:-1;
  walls[position]=lastWall;cpus[position]=lastCpu;position=(position+1)%walls.length;if(samples<walls.length)samples++;frames++;open=false;
 }
 /** Snapshot/decision operation count has its own scope; macro ACK/cleanup
  * units consume the global/critical time allowance, not snapshot slots. */
 /** Optional diagnostic snapshot; bounded sort is outside physical/ACK units.
  * This is Director actor duration, never serverMSPT or worker CPU. */
 public Metrics metrics(){
  actor();long[] ws=Arrays.copyOf(walls,samples),cs=Arrays.stream(cpus,0,samples).filter(value->value>=0).toArray();Arrays.sort(ws);Arrays.sort(cs);
  long wp=p95(ws),cp=p95(cs);return new Metrics(tick,frames,globalNanos,criticalNanos,maxOperations,operations,mandatorySpent,criticalSpent,ordinarySpent,lastWall,lastCpu,
   criticalUnits,ordinaryUnits,overruns,criticalOverruns,emergency,samples,wp,cp,p95Goal,wp>p95Goal);
 }
 private static long p95(long[] values){return values.length==0?-1:values[(int)((95L*values.length+99)/100)-1];}
}
