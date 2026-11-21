package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Tiny actual J04 IO behind an owned three-second maximum test barrier.
 * Pressure ages are telemetry, not native TPS/MSPT or execution permission.
 */
public final class JournalHealthPressureTests {
 private JournalHealthPressureTests(){}
 private static final WorldId WORLD=new WorldId(new UUID(9408,1));
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,4194304,16777216,33554432,32,0,128);
 private static int assertions;
 private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
 private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(10,TimeUnit.SECONDS);}
 private static JournalStore.HealthPressure drained(JournalStore store){
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  do{var p=store.healthPressure();if(p.queuedBytes()==0&&p.queuedTasks()==0&&p.oldestTaskAgeNanos()==0&&p.oldestAppendAgeNanos()==0){assertions++;return p;}
   LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
  }while(System.nanoTime()<deadline);
  throw new AssertionError("completed work leaked charged bytes, tasks, or age entries");
 }
 public static void main(String[]args)throws Exception{
  Path root=args.length==1?Path.of(args[0]):Files.createTempDirectory("P008-journal-health-");Files.createDirectories(root);
  var armed=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS,point->{
   if(point.equals("BEFORE_WAL_FORCE")&&armed.compareAndSet(true,false)){
    System.out.println("OWNED_IO_BARRIER_ENTERED thread="+Thread.currentThread().getName());entered.countDown();
    try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("owned test barrier timeout");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("owned test barrier interrupted",e);}
    System.out.println("OWNED_IO_BARRIER_RELEASED");
   }
  }));var session=store.session();
  try{
   check(!store.readOnlySafe(),"actual private J04 opens writable");var idle=drained(store);
   check(idle.session().equals(session)&&!idle.readOnlySafe(),"idle telemetry is bound to actual healthy session");
   var fact=new DirectorFact(new FactId(new UUID(9408,2)),new FactType("fixture:health_pressure"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("value",new JsonValue.Text("synthetic tiny health fact")),true,Optional.empty());
   var batch=ResultTransaction.batch(new TransactionId(new UUID(9408,3)),session.storeGeneration(),Map.of(),List.of(new DurableFact(fact)),List.of());
   armed.set(true);var pending=store.append(batch).toCompletableFuture();
   check(entered.await(2,TimeUnit.SECONDS),"actual append reaches identified BEFORE_WAL_FORCE boundary");
   long started=System.nanoTime();var p=store.healthPressure();long getterNs=System.nanoTime()-started;
   check(release.getCount()==1&&!pending.isDone(),"synchronous getter returns before blocked IO is released or finishes");
   check(p.session().equals(session)&&!p.readOnlySafe(),"pending telemetry has same authority session");
   check(p.queuedBytes()>=OPTIONS.transactionBytes()&&p.queuedTasks()>=1,"real active append has positive bytes and task pressure");
   check(p.oldestTaskAgeNanos()>0&&p.oldestAppendAgeNanos()>0,"real pending task and append ages are positive");
   var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),4);var queuedRead=store.read(query).toCompletableFuture();
   check(!queuedRead.isDone(),"real second read stays queued behind owned IO barrier");var q=store.healthPressure();
   check(q.queuedTasks()>=2&&q.queuedBytes()>p.queuedBytes(),"queued read is included in actual tasks and charges");
   check(q.oldestTaskAgeNanos()>=p.oldestTaskAgeNanos()&&q.oldestAppendAgeNanos()>=p.oldestAppendAgeNanos(),"oldest ages advance while same append remains pending");
   check(release.getCount()==1&&!pending.isDone(),"repeated telemetry is independent from blocked IO writer");
   System.out.println("BLOCKED_PRESSURE_BYTES="+q.queuedBytes()+" TASKS="+q.queuedTasks()+" TASK_AGE_NS="+q.oldestTaskAgeNanos()+" APPEND_AGE_NS="+q.oldestAppendAgeNanos()+" GETTER_NS="+getterNs);
   release.countDown();var ack=await(pending);
   check(ack.ackStatus()==AckStatus.DURABLE&&ack.durableSequence().value()>0,"released append genuinely reaches durable ACK");
   check(ack.transactionId().equals(batch.transactionId())&&ack.transactionHash().equals(batch.transactionHash())&&ack.storeGeneration().equals(session.storeGeneration()),"ACK preserves exact transaction ID, hash and generation");
   check(ack.forcedBlobs().isEmpty()&&ack.permitAttempt().isEmpty(),"tiny fact ACK invents no blob or execution authority");
   var read=await(queuedRead);ReadContracts.validate(read,query,session);
   check(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().records().contains(new DurableFact(fact)),"queued actual read sees actual committed fact");
   var done=drained(store);check(done.session().equals(session)&&!done.readOnlySafe(),"drained writer remains same healthy session");
   System.out.println("DRAINED_BYTES="+done.queuedBytes()+" TASKS="+done.queuedTasks()+" TASK_AGE_NS="+done.oldestTaskAgeNanos()+" APPEND_AGE_NS="+done.oldestAppendAgeNanos());
   var sample=await(store.sampleHealthPressure());check(sample.isPresent(),"actual async disk-floor sample is available");var s=sample.orElseThrow();
   check(s.session().equals(session)&&!s.readOnlySafe(),"async sample is bound to healthy actual session");
   check(s.queuedBytes()>=0&&s.queuedTasks()>=0&&s.oldestTaskAgeNanos()>=0&&s.oldestAppendAgeNanos()==0,"async sample has nonnegative pressure and no old completed append age");
   drained(store);
  }finally{release.countDown();await(store.closeAsync());}
  var closed=store.healthPressure();check(closed.readOnlySafe(),"closed getter is explicitly not writable");
  check(closed.queuedBytes()==0&&closed.queuedTasks()==0&&closed.oldestTaskAgeNanos()==0&&closed.oldestAppendAgeNanos()==0,"closed getter has no leaked completed work");
  check(await(store.sampleHealthPressure()).isEmpty(),"closed async sample is unavailable rather than fake healthy");
  System.out.println("CLOSED_HEALTH_READ_ONLY=true ASYNC_SAMPLE_PRESENT=false");
  System.out.println("PASS JournalHealthPressureTests assertions="+assertions+" scope=ACTUAL_J04_IO_TELEMETRY_ONLY_NOT_NATIVE_TPS_MSPT");
 }
}
