package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.LongSupplier;

/** Cooperative actor work only. A unit already inside vanilla/GC cannot be preempted.
 * Resume the same frozen work after a denied take; never turn a timed partial scan
 * into a complete proposal. Queue wait and worker CPU are deliberately separate. */
public final class DecisionWorkBudget {
 private static final java.lang.management.ThreadMXBean THREAD_CPU=ManagementFactory.getThreadMXBean();
 private final LongSupplier wall,cpu;
 private java.util.function.BooleanSupplier sharedGate=()->true;private final long nanos;private final int maxOperations;
 private long tick=-1,start,actorWall,actorCpu,unitOverruns;private boolean cpuSampled,cpuUnavailable;private int operations;private long slices;
 public record Metrics(long slices,int currentOperations,int maxOperations,long budgetNanos,
  long actorWallNanos,long actorCpuNanos,long unpreemptibleUnitOverruns){}
 public DecisionWorkBudget(ConfigView config){this(config,System::nanoTime,DecisionWorkBudget::threadCpuNanos);}
 public DecisionWorkBudget(ConfigView config,LongSupplier wall,LongSupplier cpu){
  this.wall=Objects.requireNonNull(wall);this.cpu=Objects.requireNonNull(cpu);
  nanos=Objects.requireNonNull(config.values().get("budget.decision_ms")).value().multiply(java.math.BigDecimal.valueOf(1_000_000)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
  maxOperations=Objects.requireNonNull(config.values().get("budget.snapshot_ops")).value().intValueExact();
  Checks.require(nanos>0&&maxOperations>0,"positive decision work budget");
 }
 public static long threadCpuNanos(){return THREAD_CPU.isCurrentThreadCpuTimeSupported()?THREAD_CPU.getCurrentThreadCpuTime():-1;}
 /** All polls at the same logical tick share one slice, even after an async reply. */
 public void begin(long currentTick){
  Checks.require(currentTick>=0&&(tick<0||currentTick>=tick),"decision budget tick order");
  if(currentTick!=tick){tick=currentTick;start=wall.getAsLong();operations=0;slices++;}
 }
 public boolean take(){
  Checks.require(tick>=0,"decision budget not started");
  if(operations>=maxOperations||Math.max(0,wall.getAsLong()-start)>=nanos)return false;
  if(!sharedGate.getAsBoolean())return false;operations++;return true;
 }
 /** Accounts the complete actor poll, including queue/result bookkeeping. */
 public <T>T account(java.util.function.Supplier<T> poll){
  long began=wall.getAsLong(),beganCpu=cpu.getAsLong();try{return poll.get();}
  finally{long finishedCpu=cpu.getAsLong();actorWall=Math.addExact(actorWall,Math.max(0,wall.getAsLong()-began));
   if(beganCpu>=0&&finishedCpu>=beganCpu){actorCpu=Math.addExact(actorCpu,finishedCpu-beganCpu);cpuSampled=true;}else cpuUnavailable=true;}
 }
 public void sharedGate(java.util.function.BooleanSupplier gate){sharedGate=Objects.requireNonNull(gate);}
 public void run(Runnable unit){
  long began=wall.getAsLong();try{unit.run();}finally{if(Math.max(0,wall.getAsLong()-began)>nanos)unitOverruns++;}
 }
 public Metrics metrics(){return new Metrics(slices,operations,maxOperations,nanos,actorWall,cpuUnavailable||!cpuSampled?-1:actorCpu,unitOverruns);}
}
