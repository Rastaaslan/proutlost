package fr.proutlost.worldprep;

import fr.proutlost.worldprep.environment.*;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.*;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExactEnvironmentalRuntimeTest {
 @Test void codecPreservesBothBlockEntitiesAndGroup(@TempDir Path root)throws Exception{
  UUID group=UUID.randomUUID(),plan=UUID.randomUUID();var mutation=new EnvironmentalMutation(PassId.STRUCTURES,17,4,-1,"minecraft:chest","minecraft:barrel","minecraft:chest","{Items:[]}","minecraft:barrel","{Items:[{}]}",group);
  var publication=PagedPlanStore.publish(root,plan,PassId.STRUCTURES,"a","minecraft:overworld","p",PlanFingerprint.of("input").value(),"e","v",1,List.of(mutation),EnvironmentalMutationCodec.INSTANCE);var read=new ArrayList<EnvironmentalMutation>();PagedPlanStore.read(root.resolve(plan.toString()),publication.manifest(),EnvironmentalMutationCodec.INSTANCE,read::add);assertEquals(List.of(mutation),read);
 }
 @Test void completeGroupIsPreflightedBeforeJournalOrWrite(){
  UUID group=UUID.randomUUID();var first=mutation(group,0);var second=mutation(group,1);var world=new MemoryWorld();world.states.put(first.position(),before());world.states.put(second.position(),new Snapshot("minecraft:gold_block",null,null));var journaled=new ArrayList<EnvironmentalMutation>();assertThrows(IllegalStateException.class,()->new ExactMutationExecutor().executeGroup(Direction.APPLY,group,List.of(first,second),world,(id,entries)->journaled.addAll(entries)));assertEquals(before(),world.states.get(first.position()));assertTrue(journaled.isEmpty());
 }
 @Test void journalPrecedesWritesAndRollbackIsIdempotent(){
  UUID group=UUID.randomUUID();var m=mutation(group,0);var world=new MemoryWorld();world.states.put(m.position(),before());var calls=new ArrayList<String>();new ExactMutationExecutor().executeGroup(Direction.APPLY,group,List.of(m),world,(id,e)->calls.add("journal"));assertEquals(after(),world.states.get(m.position()));assertEquals(List.of("journal","write"),concat(calls,world.calls));world.calls.clear();new ExactMutationExecutor().executeGroup(Direction.APPLY,group,List.of(m),world,(id,e)->calls.add("duplicate-journal"));assertTrue(world.calls.isEmpty());new ExactMutationExecutor().executeGroup(Direction.ROLLBACK,group,List.of(m),world,(id,e)->{});assertEquals(before(),world.states.get(m.position()));
 }
 private static EnvironmentalMutation mutation(UUID group,int x){return new EnvironmentalMutation(PassId.TREES,x,1,1,"minecraft:air","minecraft:oak_log",null,null,null,null,group);}
 private static Snapshot before(){return new Snapshot("minecraft:air",null,null);}private static Snapshot after(){return new Snapshot("minecraft:oak_log",null,null);}
 private static List<String> concat(List<String>a,List<String>b){var r=new ArrayList<>(a);r.addAll(b);return r;}
 private static final class MemoryWorld implements WorldAccess{final Map<EnvironmentalMutation.Position,Snapshot> states=new HashMap<>();final List<String> calls=new ArrayList<>();public Snapshot read(EnvironmentalMutation.Position p){return states.get(p);}public void write(EnvironmentalMutation.Position p,Snapshot s){calls.add("write");states.put(p,s);}public boolean writable(EnvironmentalMutation.Position p){return states.containsKey(p);}}
}
