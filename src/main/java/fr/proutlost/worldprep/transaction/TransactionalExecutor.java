package fr.proutlost.worldprep.transaction;

import fr.proutlost.worldprep.recovery.MutationSemantics;
import java.io.IOException;
import java.util.Objects;

/** Common journal-before-write executor. A caller streams bounded pages into this class. */
public final class TransactionalExecutor<K,V> {
 public interface World<K,V> { V read(K key); void write(K key,V value); }
 public interface Journal<K,V> { void publish(Mutation<K,V> mutation) throws IOException; }
 public enum Direction { APPLY, ROLLBACK }
 public enum Outcome { SUCCESS, SAFE_NOOP, CONFLICT }
 private final World<K,V> world; private final Journal<K,V> journal;
 public TransactionalExecutor(World<K,V> world, Journal<K,V> journal) { this.world=Objects.requireNonNull(world); this.journal=Objects.requireNonNull(journal); }
 public Outcome execute(Mutation<K,V> mutation, Direction direction) throws IOException {
   V current=world.read(mutation.key());
   var decision= direction==Direction.APPLY ? MutationSemantics.apply(current,mutation.before(),mutation.after()) : MutationSemantics.rollback(current,mutation.before(),mutation.after());
   if(decision==MutationSemantics.Decision.CONFLICT) return Outcome.CONFLICT;
   if(decision==MutationSemantics.Decision.NO_OP) return Outcome.SAFE_NOOP;
   journal.publish(mutation); // durability barrier must complete before the write
   world.write(mutation.key(), direction==Direction.APPLY ? mutation.after() : mutation.before());
   return Outcome.SUCCESS;
 }
 public Outcome reconcile(Mutation<K,V> mutation, Direction direction) throws IOException { return execute(mutation,direction); }
}
