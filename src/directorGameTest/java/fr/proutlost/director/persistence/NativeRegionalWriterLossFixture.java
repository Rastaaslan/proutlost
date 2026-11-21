package fr.proutlost.director.persistence;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Native QA only. Arms from an actual current private J04 PREPARE on the actor,
 * then throws in the real writer before the first RESULT WAL frame.
 * No fabricated ACK/result, batch-count guess, world callback on the writer,
 * or modification of the normal shared Director store.
 */
public final class NativeRegionalWriterLossFixture {
 private static final AtomicBoolean armed=new AtomicBoolean(),hit=new AtomicBoolean();
 private static final AtomicInteger frames=new AtomicInteger();
 private static volatile JournalStore journal;private static WorldId world;
 public static CompletionStage<JournalStore> open(Path path,WorldId id,StoreOptions options){
  if(!Boolean.getBoolean("proutlost.director.nativeGameTests")||!Boolean.getBoolean("proutlost.director.nativeLab")
    ||!path.toAbsolutePath().normalize().getParent().getFileName().toString().equals("director-regional-runtime-loss-a04")||journal!=null)
    throw new IllegalStateException("explicit unique private regional TEST_ONLY writer");
  world=id;return JournalStore.open(path,id,options,point->{
   if(point.equals("BEFORE_WAL_WRITE")){
    int count=frames.incrementAndGet();
    if(armed.get()&&hit.compareAndSet(false,true)){
     System.out.println("DIRECTOR_NATIVE_REGIONAL_REAL_BEFORE_RESULT_WAL_LOSS frame="+count+" armed_from_actual_durable_prepare=true");
     throw new IOException("TEST_ONLY actual regional RESULT WAL write loss");
    }
   }
  }).thenApply(store->{journal=store;return store;});
 }
 public static void armAfterDurablePrepare(JournalStore actual,OperationToken operation){
  if(actual!=journal)throw new IllegalStateException("only exact real private store");
  if(hit.get())return;
  var q=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);var read=actual.view(q);ReadContracts.validate(read,q,actual.session());
  if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore())throw new IllegalStateException("complete private J04 read required");
  var records=read.page().orElseThrow().records();
  boolean prepared=records.stream().anyMatch(r->r instanceof DurablePrepare p&&p.value().operationToken().equals(operation));
  boolean result=records.stream().anyMatch(r->r instanceof DurableResult p&&p.value().operationToken().equals(operation));
  if(result)throw new IllegalStateException("too late to arm actual result boundary");
  if(prepared)armed.set(true);
 }
 public static boolean injected(){return armed.get()&&hit.get();}
 private NativeRegionalWriterLossFixture(){}
}
