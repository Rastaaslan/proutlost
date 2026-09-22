package fr.proutlost.worldprep.environment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative compare-and-write semantics for exact environmental mutations.
 * Callers stream one complete mutation group at a time; the group is preflighted,
 * durably journaled, and only then written. Thus a conflict cannot partially apply
 * a tree or structure, including one crossing a page or chunk boundary.
 */
public final class ExactMutationExecutor {
    public record Snapshot(String state, String blockEntityType, String blockEntityNbt) {
        public Snapshot { Objects.requireNonNull(state, "state"); if ((blockEntityType==null)!=(blockEntityNbt==null)) throw new IllegalArgumentException("Incomplete BlockEntity snapshot"); }
    }
    public interface WorldAccess {
        Snapshot read(EnvironmentalMutation.Position position);
        void write(EnvironmentalMutation.Position position, Snapshot snapshot);
        boolean writable(EnvironmentalMutation.Position position);
    }
    /** Must return only after the exact complete group is durable. */
    public interface DurableJournal { void append(UUID groupId, List<EnvironmentalMutation> completeGroup); }
    public enum Direction { APPLY, ROLLBACK }
    public enum Outcome { WRITTEN, SAFE_NOOP }

    public List<Outcome> executeGroup(Direction direction, UUID expectedGroup,
            Iterable<EnvironmentalMutation> source, WorldAccess world, DurableJournal journal) {
        Objects.requireNonNull(direction); Objects.requireNonNull(world); Objects.requireNonNull(journal);
        var group=new ArrayList<EnvironmentalMutation>(); var positions=new HashSet<EnvironmentalMutation.Position>();
        for(var mutation:source){
            Objects.requireNonNull(mutation,"mutation");
            if(!Objects.equals(expectedGroup,mutation.groupId()))throw new IllegalStateException("MutationGroup identity mismatch");
            if(!positions.add(mutation.position()))throw new IllegalStateException("Duplicate position in MutationGroup");
            group.add(mutation);
        }
        if(group.isEmpty())return List.of();
        var outcomes=new ArrayList<Outcome>(group.size());
        for(var mutation:group){
            if(!world.writable(mutation.position()))throw conflict(mutation,"protected, unloaded, or outside area");
            Snapshot current=world.read(mutation.position()), before=before(mutation), after=after(mutation);
            Snapshot wanted=direction==Direction.APPLY?before:after, accepted=direction==Direction.APPLY?after:before;
            if(current.equals(wanted))outcomes.add(Outcome.WRITTEN);
            else if(current.equals(accepted))outcomes.add(Outcome.SAFE_NOOP);
            else throw conflict(mutation,"third state or BlockEntity ownership mismatch");
        }
        journal.append(expectedGroup,List.copyOf(group));
        for(int i=0;i<group.size();i++)if(outcomes.get(i)==Outcome.WRITTEN){var mutation=group.get(i);world.write(mutation.position(),direction==Direction.APPLY?after(mutation):before(mutation));}
        return List.copyOf(outcomes);
    }
    private static Snapshot before(EnvironmentalMutation m){return new Snapshot(m.beforeState(),m.beforeBlockEntityType(),m.beforeBlockEntityNbt());}
    private static Snapshot after(EnvironmentalMutation m){return new Snapshot(m.afterState(),m.afterBlockEntityType(),m.afterBlockEntityNbt());}
    private static IllegalStateException conflict(EnvironmentalMutation m,String reason){return new IllegalStateException("Exact mutation conflict at "+m.position()+": "+reason);}
}
