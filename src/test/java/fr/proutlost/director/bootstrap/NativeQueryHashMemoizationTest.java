package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeQueryHashMemoizationTest {
 private static final WorldId WORLD=new WorldId(new UUID(0x50303035,38));
 private static StoreQuery query(long sequence){return new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.WORLD,WORLD.value())),new Sequence(sequence),Optional.empty(),1024);}
 private static Fingerprint direct(StoreQuery q){return BlobContent.hash(new ModelCodec<>(StoreQuery.class).encode(new StoreQuery(q.worldId(),q.aggregateKeys(),q.sequence(),Optional.empty(),q.limit())));}
 @Test void warmedCanonicalHashesKeepEveryCorrelationFieldAndIgnoreOnlyCursor(){
  var q=query(7);assertEquals(direct(q),ReadContracts.queryHash(q));
  var cursor=new StoreQuery(q.worldId(),q.aggregateKeys(),q.sequence(),Optional.of("not-authority"),q.limit());
  assertEquals(direct(cursor),ReadContracts.queryHash(cursor));
  for(var other:List.of(query(8),new StoreQuery(q.worldId(),Set.of(),q.sequence(),Optional.empty(),q.limit()),
    new StoreQuery(q.worldId(),q.aggregateKeys(),q.sequence(),Optional.empty(),1),
    new StoreQuery(new WorldId(UUID.randomUUID()),q.aggregateKeys(),q.sequence(),Optional.empty(),q.limit()))){
   assertNotEquals(ReadContracts.queryHash(q),ReadContracts.queryHash(other));assertEquals(direct(other),ReadContracts.queryHash(other));
  }
 }
 @Test void warmCacheNeverAcceptsStaleSessionOrForeignQueryStamp(){
  var q=query(7);var session=new StoreSession(WORLD,new Epoch(1),new Epoch(2),new SessionId(UUID.randomUUID()));
  var valid=new StoreReadResult(ReadStatus.STALE,Optional.empty(),Optional.of(new ReadStamp(session,new Sequence(20),ReadContracts.queryHash(q))),List.of());
  ReadContracts.validate(valid,q,session);
  var cold=new StoreSession(WORLD,new Epoch(1),new Epoch(3),new SessionId(UUID.randomUUID()));
  assertThrows(IllegalArgumentException.class,()->ReadContracts.validate(valid,q,cold));
  assertThrows(IllegalArgumentException.class,()->ReadContracts.validate(valid,query(8),session));
 }
 @Test void concurrentUseAndEvictionRetainCanonicalDigest() throws Exception {
  var pool=Executors.newFixedThreadPool(4);
  try {List<Future<?>> tasks=new ArrayList<>();
   for(int worker=0;worker<4;worker++)tasks.add(pool.submit(()->{
    for(int i=0;i<600;i++){var q=query(i);assertEquals(direct(q),ReadContracts.queryHash(q));}
   }));
   for(var task:tasks)task.get(20,TimeUnit.SECONDS);
   assertEquals(direct(query(0)),ReadContracts.queryHash(query(0)));
  }finally{pool.shutdownNow();}
 }
}
