package fr.proutlost.worldprep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.proutlost.worldprep.environment.EnvironmentalMutation;
import fr.proutlost.worldprep.environment.EnvironmentalMutationCodec;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.Direction;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.Snapshot;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.WorldAccess;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.runtime.EnvironmentalMutationRuntime;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentalMutationRuntimeTest {
    @Test void groupCrossesPagesAndDurableJournalAloneRollsBack(@TempDir Path root) throws Exception {
        UUID group=UUID.randomUUID(), planId=UUID.randomUUID(), journalId=UUID.randomUUID();
        var mutations=List.of(mutation(group,0),mutation(group,1),mutation(group,17));
        var publication=PagedPlanStore.publish(root.resolve("plans"),planId,PassId.TREES,"test:area",
                "minecraft:overworld","profile",PlanFingerprint.of("input").value(),"engine","planner",1,
                mutations,EnvironmentalMutationCodec.INSTANCE);
        var world=new MemoryWorld();mutations.forEach(m->world.values.put(m.position(),before()));
        var identity=PagedJournalStore.empty(journalId,planId,publication.manifest().rootFingerprint(),
                PassId.TREES,"minecraft:overworld","test:area");
        var journal=new EnvironmentalMutationRuntime.PagedJournal(root.resolve("journal"),identity);

        assertEquals(3,EnvironmentalMutationRuntime.executePlan(publication.directory(),publication.manifest(),Direction.APPLY,world,journal));
        mutations.forEach(m->assertEquals(after(),world.values.get(m.position())));
        assertEquals(3,journal.manifest().entryCount());

        assertEquals(3,EnvironmentalMutationRuntime.rollbackJournal(root.resolve("journal"),journal.manifest(),world));
        mutations.forEach(m->assertEquals(before(),world.values.get(m.position())));
    }

    @Test void discontinuousGroupFailsClosed(@TempDir Path root) throws Exception {
        UUID group=UUID.randomUUID(), other=UUID.randomUUID(), planId=UUID.randomUUID();
        var publication=PagedPlanStore.publish(root,planId,PassId.STRUCTURES,"a","d","p",
                PlanFingerprint.of("i").value(),"e","v",2,
                List.of(mutation(group,0),mutation(other,1),mutation(group,2)),EnvironmentalMutationCodec.INSTANCE);
        var world=new MemoryWorld();world.values.put(mutation(group,0).position(),before());world.values.put(mutation(other,1).position(),before());world.values.put(mutation(group,2).position(),before());
        assertThrows(IllegalStateException.class,()->EnvironmentalMutationRuntime.executePlan(
                publication.directory(),publication.manifest(),Direction.APPLY,world,(id,entries)->{}));
    }

    private static EnvironmentalMutation mutation(UUID group,int x){return new EnvironmentalMutation(PassId.TREES,x,64,0,"minecraft:air","minecraft:oak_log",null,null,null,null,group);}
    private static Snapshot before(){return new Snapshot("minecraft:air",null,null);}
    private static Snapshot after(){return new Snapshot("minecraft:oak_log",null,null);}
    private static final class MemoryWorld implements WorldAccess {
        final Map<EnvironmentalMutation.Position,Snapshot> values=new HashMap<>();
        public Snapshot read(EnvironmentalMutation.Position position){return values.get(position);}
        public void write(EnvironmentalMutation.Position position,Snapshot snapshot){values.put(position,snapshot);}
        public boolean writable(EnvironmentalMutation.Position position){return values.containsKey(position);}
    }
}
