package fr.proutlost.worldprep;
import fr.proutlost.worldprep.transaction.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TransactionalExecutorTest {
 @Test void exactApplyAndRollbackAreIdempotentAndConflictSafe() throws Exception {
   Map<String,String> world=new HashMap<>(Map.of("p","before")); List<Mutation<String,String>> journal=new ArrayList<>();
   var executor=new TransactionalExecutor<String,String>(new TransactionalExecutor.World<>() { public String read(String k){return world.get(k);} public void write(String k,String v){world.put(k,v);} }, journal::add);
   var m=new Mutation<>("p","before","after");
   assertEquals(TransactionalExecutor.Outcome.SUCCESS,executor.execute(m,TransactionalExecutor.Direction.APPLY));
   assertEquals(TransactionalExecutor.Outcome.SAFE_NOOP,executor.execute(m,TransactionalExecutor.Direction.APPLY));
   assertEquals(TransactionalExecutor.Outcome.SUCCESS,executor.execute(m,TransactionalExecutor.Direction.ROLLBACK));
   assertEquals(TransactionalExecutor.Outcome.SAFE_NOOP,executor.execute(m,TransactionalExecutor.Direction.ROLLBACK));
   world.put("p","third"); assertEquals(TransactionalExecutor.Outcome.CONFLICT,executor.execute(m,TransactionalExecutor.Direction.APPLY)); assertEquals("third",world.get("p"));
 }
 @Test void failedJournalNeverMutatesWorld() {
   Map<String,String> world=new HashMap<>(Map.of("p","before"));
   var executor=new TransactionalExecutor<String,String>(new TransactionalExecutor.World<>() { public String read(String k){return world.get(k);} public void write(String k,String v){world.put(k,v);} }, m->{throw new IOException("disk full");});
   assertThrows(IOException.class,()->executor.execute(new Mutation<>("p","before","after"),TransactionalExecutor.Direction.APPLY)); assertEquals("before",world.get("p"));
 }
 @Test void recoveryReconcilesActualWorldNotCursor() throws Exception {
   Map<String,String> world=new HashMap<>(Map.of("p","after")); var executor=new TransactionalExecutor<String,String>(new TransactionalExecutor.World<>() {public String read(String k){return world.get(k);} public void write(String k,String v){world.put(k,v);}},m->{});
   assertEquals(TransactionalExecutor.Outcome.SAFE_NOOP,executor.reconcile(new Mutation<>("p","before","after"),TransactionalExecutor.Direction.APPLY));
 }
}
