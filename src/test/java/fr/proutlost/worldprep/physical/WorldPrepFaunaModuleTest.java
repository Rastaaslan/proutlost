package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pure contract and durable-file tests. Real native eligibility/finalization
 * is deliberately qualified on the installed candidate, never mocked here. */
class WorldPrepFaunaModuleTest {
    @TempDir Path directory;
    static final String SHA="a".repeat(64);
    static final UUID BATCH=UUID.fromString("a5f6ef12-0efe-43bf-a919-7ac558d59cad");
    static WorldPrepFaunaModule.Position p(int x){return new WorldPrepFaunaModule.Position(x,91,0);}
    static NativeFaunaHabitatPolicy.Profile cow() {
        return new NativeFaunaHabitatPolicy.Profile("minecraft:cow","LAND",4,4,2.0,16,24,16,true);
    }
    static WorldPrepFaunaModule.Group group(String id,int center) {
        return new WorldPrepFaunaModule.Group(id,"minecraft:cow","minecraft:plains",p(center),4,
            List.of(p(center),p(center+2),p(center+4),p(center+6)));
    }
    static WorldPrepFaunaModule.Request request(WorldPrepFaunaModule.Budgets budgets,
                                                List<WorldPrepFaunaModule.Group> groups) {
        return new WorldPrepFaunaModule.Request(1,BATCH,"minecraft:overworld",17,SHA,budgets,List.of(cow()),groups,List.of());
    }
    static WorldPrepFaunaModule.Budgets budgets(int global,int chunk) {
        return new WorldPrepFaunaModule.Budgets(global,global,global,global,chunk,2);
    }
    static NativeFaunaModulePopulation.Intent intent(UUID uuid,String species) {
        return new NativeFaunaModulePopulation.Intent(BATCH,BATCH,SHA,"herd",species,uuid,p(0),SHA);
    }

    @Test void durableIntentRemainsAmbiguousAfterStoreReopenUntilIssuedExists()throws Exception {
        UUID uuid=UUID.randomUUID();var intent=intent(uuid,"minecraft:cow");
        PhysicalStore first=new PhysicalStore(directory);
        assertEquals(NativeFaunaModulePopulation.Retained.NEW,NativeFaunaModulePopulation.retained(first,intent));
        first.write("intent-"+uuid,intent);
        PhysicalStore reopened=new PhysicalStore(directory);
        assertEquals(NativeFaunaModulePopulation.Retained.INTENT_ONLY,NativeFaunaModulePopulation.retained(reopened,intent));
        reopened.write("issued-"+uuid,intent);
        assertEquals(NativeFaunaModulePopulation.Retained.ISSUED,
            NativeFaunaModulePopulation.retained(new PhysicalStore(directory),intent));
    }

    @Test void issuedWithoutIntentAndDifferentOwnershipAreNeverReplayPermissions()throws Exception {
        UUID uuid=UUID.randomUUID();var intent=intent(uuid,"minecraft:cow");
        PhysicalStore store=new PhysicalStore(directory);
        store.write("issued-"+uuid,intent);
        assertThrows(IOException.class,()->NativeFaunaModulePopulation.retained(store,intent));
        store.write("intent-"+uuid,intent);
        var wrongSpecies=intent(uuid,"minecraft:sheep");
        assertThrows(IOException.class,()->NativeFaunaModulePopulation.retained(store,wrongSpecies));
        assertThrows(IOException.class,()->store.write("issued-"+uuid,wrongSpecies));
    }

    @Test void deterministicIdentitySurvivesRestartWithoutCrossGroupCollisions() {
        UUID first=NativeFaunaModulePopulation.operation(BATCH,"north");
        UUID same=NativeFaunaModulePopulation.operation(BATCH,"north");
        UUID south=NativeFaunaModulePopulation.operation(BATCH,"south");
        assertEquals(first,same);
        assertEquals(NativeFaunaModulePopulation.animalId(first,p(8)),NativeFaunaModulePopulation.animalId(same,p(8)));
        assertNotEquals(NativeFaunaModulePopulation.animalId(first,p(8)),NativeFaunaModulePopulation.animalId(south,p(8)));
        assertNotEquals(NativeFaunaModulePopulation.animalId(first,p(8)),NativeFaunaModulePopulation.animalId(first,p(9)));
    }

    static NativeFaunaModulePopulation.Plan plan(WorldPrepFaunaModule.Request r,int index,int existing) {
        var g=r.groups().get(index);UUID operation=NativeFaunaModulePopulation.operation(r.batch(),g.id());
        var baseline=new ArrayList<NativeFaunaModulePopulation.Observed>();
        for(int i=0;i<existing;i++)
            baseline.add(new NativeFaunaModulePopulation.Observed(UUID.nameUUIDFromBytes((g.id()+"/old/"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                g.species(),g.expectedBiome(),g.center().x()+0.5+i,91,0.5));
        var animals=new ArrayList<NativeFaunaModulePopulation.Animal>();
        for(int i=0;i<4-existing;i++) {
            var position=g.candidates().get(i);
            animals.add(new NativeFaunaModulePopulation.Animal(NativeFaunaModulePopulation.animalId(operation,position),
                position,new NativeFaunaHabitatPolicy.HabitatEvidence(g.species(),g.expectedBiome(),"CREATURE",
                    "LAND","minecraft:grass_block",0,"NATIVE_CHUNK_GENERATION_V1")));
        }
        return new NativeFaunaModulePopulation.Plan(BATCH,SHA,index,operation,g,cow(),baseline,animals);
    }

    @Test void nativeGroupMinimumAppliesToTargetIncludingExistingNotTheNewDeficit()throws Exception {
        var r=request(budgets(8,8),List.of(group("existing_three",0)));
        var plan=plan(r,0,3);
        assertEquals(1,plan.animals().size());
        assertDoesNotThrow(()->NativeFaunaModulePopulation.validateRetained(plan,r,SHA,0));
        assertEquals(1,WorldPrepFaunaModule.validateBudgets(r,List.of(plan)).get("newAnimals"));
    }

    @Test void summedRegionalAndGlobalBudgetsAreCheckedAcrossDistinctGroupsBeforeSpawning()throws Exception {
        var r=request(budgets(7,4),List.of(group("north",0),group("south",64)));
        assertThrows(IOException.class,()->WorldPrepFaunaModule.validateBudgets(r,List.of(plan(r,0,0),plan(r,1,0))));
        var okay=request(budgets(8,4),r.groups());
        assertEquals(8,WorldPrepFaunaModule.validateBudgets(okay,List.of(plan(okay,0,0),plan(okay,1,0))).get("newAnimals"));
    }

    @Test void chunkBudgetCountsActualCandidatePositionsAndNegativeCoordinates()throws Exception {
        var r=request(budgets(8,3),List.of(group("negative",-15)));
        var plan=plan(r,0,0);
        assertThrows(IOException.class,()->WorldPrepFaunaModule.validateBudgets(r,List.of(plan)));
        var okay=request(budgets(8,4),r.groups());
        var report=WorldPrepFaunaModule.validateBudgets(okay,List.of(plan(okay,0,0)));
        assertEquals(Map.of("-1,0",4),report.get("byChunk"));
        assertEquals(Map.of("-1,0",4),report.get("byRegion512"));
    }

    @Test void overlappingSameSpeciesCensusCannotDoubleCountExistingAnimals() {
        assertThrows(IllegalArgumentException.class,()->request(budgets(8,4),
            List.of(group("first",0),group("overlapping",32))));
        assertDoesNotThrow(()->request(budgets(8,4),List.of(group("first",0),group("separate",33))));
    }

    @Test void pendingLeasesArePolledOnlyOncePerServerTickEvenWhenStepBudgetIsLarger() {
        var scheduler=new WorldPrepFaunaModule.LeaseStepScheduler(9);
        int[] calls=new int[9];
        for(int tick=0;tick<201;tick++) {
            Set<Integer> thisTick=new HashSet<>();
            assertFalse(scheduler.advance(16,4_000_000L,()->0L,index->{
                assertTrue(thisTick.add(index),"Repeated lease poll in one tick: "+index);
                calls[index]++;return false;
            }));
            assertEquals(9,thisTick.size());
        }
        for(int count:calls)assertEquals(201,count);
    }

    @Test void roundRobinMakesProgressWithinDistinctStepBudgetAndRetainsReadyLeases() {
        var scheduler=new WorldPrepFaunaModule.LeaseStepScheduler(49);
        int[] calls=new int[49];boolean complete=false;
        for(int tick=0;tick<10&&!complete;tick++) {
            Set<Integer> thisTick=new HashSet<>();
            complete=scheduler.advance(16,4_000_000L,()->0L,index->{
                assertTrue(thisTick.add(index));
                calls[index]++;
                return index%2==0||calls[index]>=2;
            });
            assertTrue(thisTick.size()<=16);
        }
        assertTrue(complete,"Pending leases must not starve behind earlier pending or ready leases");
        for(int i=0;i<calls.length;i++)assertEquals(i%2==0?1:2,calls[i],"Ready lease polled again");
        assertTrue(scheduler.advance(16,4_000_000L,()->0L,index->{fail("Completed scheduler polled a lease");return false;}));
    }

    @Test void schedulerStopsAdmittingDistinctStepsAtTheMonotonicTimeBudget() {
        var scheduler=new WorldPrepFaunaModule.LeaseStepScheduler(9);
        long[] nanos={0L};List<Integer> calls=new ArrayList<>();
        assertFalse(scheduler.advance(16,4_000_000L,()->nanos[0],index->{
            calls.add(index);nanos[0]+=2_000_000L;return false;
        }));
        assertEquals(List.of(0,1),calls);
        calls.clear();
        assertFalse(scheduler.advance(16,4_000_000L,()->nanos[0],index->{
            calls.add(index);nanos[0]+=2_000_000L;return false;
        }));
        assertEquals(List.of(2,3),calls,"Next tick must continue round-robin after the previous budget ended");
    }

    @Test void changedRetainedGroupAndForgedDeficitAreRejected()throws Exception {
        var r=request(budgets(8,4),List.of(group("north",0)));
        var plan=plan(r,0,3);
        assertThrows(IOException.class,()->NativeFaunaModulePopulation.validateRetained(plan,r,"b".repeat(64),0));
        var impossible=new NativeFaunaModulePopulation.Plan(plan.batch(),plan.requestSha(),0,plan.operation(),
            plan.group(),plan.profile(),plan.baseline(),List.of());
        assertThrows(IOException.class,()->NativeFaunaModulePopulation.validateRetained(impossible,r,SHA,0));
    }
}
