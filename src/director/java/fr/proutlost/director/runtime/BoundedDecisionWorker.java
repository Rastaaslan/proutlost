package fr.proutlost.director.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One world-local pure CPU lane; queue size1 is technical backpressure, never a
 * gameplay cap. A result delivers data only. No Minecraft, content owner or permit
 * is captured by this worker. FutureTask cancellation interrupts cooperative work. */
public final class BoundedDecisionWorker implements AutoCloseable {
 private final ThreadPoolExecutor executor;
 private final Set<FutureTask<?>> outstanding=ConcurrentHashMap.newKeySet();
 public record Metrics(long queueWaitNanos,long workerWallNanos,long workerCpuNanos,boolean started,boolean finished,boolean cancelled){}
 public static final class Work<T> {
  private record Delivery<T>(T value,Throwable failure){}
  private final long queued=System.nanoTime();private volatile long began,ended,wall,cpu=-1;private final FutureTask<T> task;
  private volatile Delivery<T> delivery;
  private Work(Supplier<T> calculation){
   task=new FutureTask<>(()->{began=System.nanoTime();long startedCpu=DecisionWorkBudget.threadCpuNanos();T value=null;Throwable failure=null;
    try{if(Thread.currentThread().isInterrupted())throw new CancellationException("decision work interrupted");value=Objects.requireNonNull(calculation.get());return value;}
    catch(RuntimeException|Error problem){failure=problem;throw problem;}
    finally{long endedCpu=DecisionWorkBudget.threadCpuNanos();long finished=System.nanoTime();wall=Math.max(0,finished-began);
     cpu=startedCpu>=0&&endedCpu>=startedCpu?endedCpu-startedCpu:-1;ended=finished;delivery=new Delivery<>(value,failure);}
   });
  }
  public boolean done(){return task.isCancelled()||delivery!=null;}
  /** Volatile immutable mailbox; never Future.get/join, even while FutureTask
   * is in its transient COMPLETING state and its worker is descheduled. */
  public T result(){
   if(task.isCancelled())throw new CancellationException("pure decision result revoked");var message=delivery;
   if(message==null)throw new IllegalStateException("unfinished pure decision work");
   if(message.failure() instanceof RuntimeException failure)throw failure;
   if(message.failure() instanceof Error failure)throw failure;
   if(message.failure()!=null)throw new CompletionException(message.failure());return message.value();
  }
  public void cancel(){task.cancel(true);}
  public Metrics metrics(){long started=began,finished=ended;return new Metrics(Math.max(0,(started==0?System.nanoTime():started)-queued),wall,cpu,started!=0,finished!=0,task.isCancelled());}
 }
 public BoundedDecisionWorker(String name){
  executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()){
   @Override protected void afterExecute(Runnable task,Throwable failure){super.afterExecute(task,failure);outstanding.remove(task);}
  };
 }
 public <T>Optional<Work<T>> submit(Supplier<T> calculation){
  var work=new Work<>(Objects.requireNonNull(calculation));outstanding.add(work.task);try{executor.execute(work.task);return Optional.of(work);}catch(RejectedExecutionException full){outstanding.remove(work.task);return Optional.empty();}
 }
 public int queued(){return executor.getQueue().size();}
 public int active(){return executor.getActiveCount();}
 @Override public void close(){outstanding.forEach(task->task.cancel(true));executor.shutdownNow();}
}
