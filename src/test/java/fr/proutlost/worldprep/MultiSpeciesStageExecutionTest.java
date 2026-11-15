package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MultiSpeciesStageExecutionTest {
    @TempDir Path directory;
    private static final UUID PARENT=UUID.fromString("ae72dbcf-d9ae-5090-8ee1-a4f63d0d0c18");

    private static MultiSpeciesFaunaPlan.Manifest plan(){
        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:beach","minecraft:forest","minecraft:plains"),
            Set.of("minecraft:dirt","minecraft:grass_block"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,32);
        return MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow,cod));
    }

    private static MultiSpeciesStageExecution.Validator verifier(){
        return (stage,evidence)->{
            if(!evidence.equals("disk-verified/"+stage.species()+"/"+stage.childBatch()))
                throw new IOException("Child species proof is not bound to this stage");
        };
    }

    private static MultiSpeciesStageExecution.Factory fakeFactory(AtomicInteger openings){
        return stage->{
            openings.incrementAndGet();
            return new QualificationOrchestrator.Task(){
                int ticks;
                public Optional<String> step() {
                    return ++ticks<2?Optional.empty()
                        :Optional.of("disk-verified/"+stage.species()+"/"+stage.childBatch());
                }
            };
        };
    }

    private static void complete(MultiSpeciesStageExecution execution)throws Exception {
        for(int i=0;i<30;i++)if(execution.step().isPresent())return;
        fail("Bounded multi-species test did not complete");
    }

    @Test void bothDistinctChildStagesCompleteWithImmutableReceipts() throws Exception {
        var openings=new AtomicInteger();
        var store=new PhysicalStore(directory.resolve("multi"));
        var plan=plan();
        try(var execution=new MultiSpeciesStageExecution(plan,store,fakeFactory(openings),verifier())){
            complete(execution);
            assertEquals(2,execution.stageCursor());
        }
        assertEquals(2,openings.get());
        assertTrue(store.exists("stage-0"));
        assertTrue(store.exists("stage-1"));
        assertTrue(store.exists("aggregate"));
        var aggregate=store.read("aggregate",MultiSpeciesStageExecution.Aggregate.class);
        assertEquals("ALL_SPECIES_RECEIPTS_COMPLETE_NOT_MAP_READY",aggregate.status());
        assertEquals(2,aggregate.stages());
        assertNotEquals(store.read("stage-0",MultiSpeciesStageExecution.Receipt.class).childBatch(),
                        store.read("stage-1",MultiSpeciesStageExecution.Receipt.class).childBatch());
    }

    @Test void afterRestartAlreadyCompletedSpeciesNeverReopens() throws Exception {
        var store=new PhysicalStore(directory.resolve("multi"));
        var priorOpenings=new AtomicInteger();
        try(var first=new MultiSpeciesStageExecution(plan(),store,fakeFactory(priorOpenings),verifier())){
            for(int i=0;i<3;i++)first.step();
            assertEquals(1,first.stageCursor());
        }
        assertEquals(1,priorOpenings.get());
        var newOpenings=new AtomicInteger();
        try(var recovered=new MultiSpeciesStageExecution(plan(),store,fakeFactory(newOpenings),verifier())){
            complete(recovered);
        }
        assertEquals(1,newOpenings.get(),"First species should replay proof, never run again");
    }

    @Test void childErrorPoisonPreventsWrongCompletionAndNoAggregate() throws Exception {
        var store=new PhysicalStore(directory.resolve("multi"));
        try(var coordinator=new MultiSpeciesStageExecution(plan(),store,stage->()->
                {throw new IOException("Native disk readback mismatch");},verifier())){
            coordinator.step();
            assertThrows(IOException.class,coordinator::step);
            assertThrows(IOException.class,coordinator::step);
        }
        assertFalse(store.exists("stage-0"));
        assertFalse(store.exists("aggregate"));
    }

    @Test void tamperedStageReceiptFailsRecoveryBeforeNextSpecies() throws Exception {
        var store=new PhysicalStore(directory.resolve("multi"));
        var started=new AtomicInteger();
        try(var coordinator=new MultiSpeciesStageExecution(plan(),store,fakeFactory(started),verifier())){
            for(int i=0;i<4;i++)coordinator.step();
            assertEquals(1,coordinator.stageCursor());
        }
        var file=directory.resolve("multi/stage-0.json");
        var original=java.nio.file.Files.readString(file);
        java.nio.file.Files.writeString(file,original.replace("disk-verified","disk-changed"));
        try(var resumed=new MultiSpeciesStageExecution(plan(),store,fakeFactory(new AtomicInteger()),verifier())){
            assertThrows(IOException.class,resumed::step);
            assertFalse(store.exists("stage-1"));
        }
    }

    @Test void changedParentRulesCannotOverwriteFrozenMultiSpeciesIntent() throws Exception {
        var store=new PhysicalStore(directory.resolve("multi"));
        new MultiSpeciesStageExecution(plan(),store,fakeFactory(new AtomicInteger()),verifier()).close();
        var prior=plan();
        // A syntactically valid NEW batch cannot replace the immutable old one.
        var conflicting=MultiSpeciesFaunaPlan.prepare(UUID.randomUUID(),
            prior.stages().stream().map(MultiSpeciesFaunaPlan.Stage::rule).toList());
        assertThrows(IOException.class,()->
            new MultiSpeciesStageExecution(conflicting,store,fakeFactory(new AtomicInteger()),verifier()));
        // And a forged digest/child cannot even reach the persisted store.
        var forged=new MultiSpeciesFaunaPlan.Manifest(5,MultiSpeciesFaunaPlan.CONTRACT,
            UUID.randomUUID(),prior.stages(),prior.manifestSha256());
        assertThrows(IllegalArgumentException.class,()->
            new MultiSpeciesStageExecution(forged,store,fakeFactory(new AtomicInteger()),verifier()));
    }
}
