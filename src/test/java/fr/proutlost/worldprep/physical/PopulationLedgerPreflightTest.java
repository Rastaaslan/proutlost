package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.DiskPopulationCounts;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable ledger-only tests; never opens a Minecraft world. */
class PopulationLedgerPreflightTest {
    @TempDir Path scratch;
    private static final String DIM="minecraft:overworld", REGISTRY="a".repeat(64);
    private PhysicalStore ledger()throws IOException {return new PhysicalStore(scratch.resolve("ledger"));}
    private NativeFaunaPass.Plan plan(String id,int x,int z) {
        var rule=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains"),Set.of("minecraft:grass_block"),
            -64,319,-10f,10f,0,2,2,2,16,true,2,2);
        var animal=new NativeFaunaPass.Animal(UUID.fromString(id),x,72,z);
        return new NativeFaunaPass.Plan(1,
            UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            new WorldPrepSavedData.Area("proutlost:lab/fauna",DIM,x,z,x+15,z+15),
            "test/"+id,rule,List.of(animal));
    }
    private NativeFaunaPass.Plan first(){return plan("d370d082-ef03-5aa4-9b7c-875598370121",100,100);}
    private NativeFaunaPass.Plan second(){return plan("e470d082-ef03-5aa4-9b7c-875598370122",650,650);}
    private NativeFaunaPass.Plan third(){return plan("f570d082-ef03-5aa4-9b7c-875598370123",1200,1200);}
    private void census(DiskPopulationCounts counts)throws Exception {
        ledger().write("disk-baseline",new NativeBackupFaunaCensus.Baseline(
            1,"b".repeat(64),REGISTRY,counts,"COMPLETE_QUIESCENT_BACKUP_CENSUS"));
    }
    private void emptyCensus()throws Exception {census(new DiskPopulationCounts(0,Map.of(),Map.of(),Map.of()));}
    private void reserve(NativeFaunaPass.Plan p)throws Exception {
        NativePhysicalRuntime.reservePopulationLedger(ledger(),p,DIM,REGISTRY);
    }
    private String speciesKey(){return "species-policy-"+PhysicalStore.sha("minecraft:cow");}
    private void assertNoReservationWritten()throws Exception {
        assertFalse(ledger().exists("world-policy"));
        assertFalse(ledger().exists(speciesKey()));
        assertFalse(ledger().exists("slot-0"));
    }
    @Test void missingBackupCensusLeavesNoPolicy()throws Exception {
        assertThrows(IOException.class,()->reserve(first()));
        assertNoReservationWritten();
    }
    @Test void changedEntityRegistryLeavesNoPolicy()throws Exception {
        emptyCensus();
        assertThrows(IOException.class,()->NativePhysicalRuntime.reservePopulationLedger(
            ledger(),first(),DIM,"c".repeat(64)));
        assertNoReservationWritten();
    }
    @Test void exhaustedDiskPopulationLeavesNoPolicy()throws Exception {
        census(new DiskPopulationCounts(2,Map.of("minecraft:cow",2),
            Map.of(DIM+":0:0",2),Map.of(DIM+":6:6",2)));
        assertThrows(NativePhysicalRuntime.PopulationBudgetRefusal.class,()->reserve(first()));
        assertNoReservationWritten();
    }
    @Test void existingAnimalInCandidateChunkRefusesWithoutPolicy()throws Exception {
        census(new DiskPopulationCounts(1,Map.of("minecraft:cow",1),
            Map.of(DIM+":0:0",1),Map.of(DIM+":6:6",1)));
        assertThrows(NativePhysicalRuntime.PopulationBudgetRefusal.class,()->reserve(first()));
        assertNoReservationWritten();
    }
    @Test void reservationIsDurableAndIdempotentOnReopen()throws Exception {
        emptyCensus();reserve(first());
        assertTrue(ledger().exists("world-policy"));
        assertTrue(ledger().exists(speciesKey()));
        String original=ledger().readText("slot-0");
        reserve(first());
        assertEquals(original,ledger().readText("slot-0"));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void exhaustedThirdReservationCannotMutateFrozenPolicies()throws Exception {
        emptyCensus();reserve(first());reserve(second());
        String world=ledger().readText("world-policy");
        assertThrows(NativePhysicalRuntime.PopulationBudgetRefusal.class,()->reserve(third()));
        assertFalse(ledger().exists("slot-2"));
        assertEquals(world,ledger().readText("world-policy"));
    }
    @Test void foreignFrozenWorldPolicyRefusesWithoutNewWrites()throws Exception {
        emptyCensus();ledger().write("world-policy",Map.of(
            "globalLimit",7,"habitatLimit",7,"regionLimit",7));
        assertThrows(IOException.class,()->reserve(first()));
        assertFalse(ledger().exists(speciesKey()));
        assertFalse(ledger().exists("slot-0"));
    }
    @Test void foreignFrozenSpeciesPolicyRefusesWithoutNewWrites()throws Exception {
        emptyCensus();ledger().write(speciesKey(),Map.of("species","minecraft:cow","limit",7));
        assertThrows(IOException.class,()->reserve(first()));
        assertFalse(ledger().exists("world-policy"));
        assertFalse(ledger().exists("slot-0"));
    }
    @Test void retainedReservationWithoutPoliciesIsRejected()throws Exception {
        emptyCensus();var p=first();
        ledger().write("slot-0",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        var error=assertThrows(IOException.class,()->reserve(first()));
        assertTrue(error.getMessage().contains("missing frozen budget policy"));
    }
    @Test void changedHabitatCannotReplayAnExistingUUIDReservation()throws Exception {
        emptyCensus();var p=first();
        ledger().write("world-policy",Map.of("globalLimit",2,"habitatLimit",2,"regionLimit",2));
        ledger().write(speciesKey(),Map.of("species","minecraft:cow","limit",2));
        ledger().write("slot-0",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"DEEP_MARINE",p.rule().species(),p.animals()));
        assertThrows(IOException.class,()->reserve(first()));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void missingFirstSlotCannotHideLaterReservation()throws Exception {
        emptyCensus();var p=second();
        ledger().write("slot-1",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        var failure=assertThrows(IOException.class,()->reserve(first()));
        assertTrue(failure.getMessage().contains("Non-contiguous"));
        assertFalse(ledger().exists("slot-0"));
        assertFalse(ledger().exists("world-policy"));
    }
    @Test void internalHoleAfterFirstSlotNeverGetsFilledOverLaterReservation()throws Exception {
        emptyCensus();reserve(first());var p=second();
        ledger().write("slot-2",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        var failure=assertThrows(IOException.class,()->reserve(third()));
        assertTrue(failure.getMessage().contains("Non-contiguous"));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void replayOfFirstSlotStillChecksLaterTamperedSlot()throws Exception {
        emptyCensus();reserve(first());var p=second();
        ledger().write("slot-2",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        var failure=assertThrows(IOException.class,()->reserve(first()));
        assertTrue(failure.getMessage().contains("Non-contiguous"));
    }
    @Test void foreignOperationCannotReuseAnAlreadyReservedAnimalUuid()throws Exception {
        emptyCensus();reserve(first());
        var other=second();
        var cloned=new NativeFaunaPass.Animal(first().animals().getFirst().uuid(),650,72,650);
        var forged=new NativeFaunaPass.Plan(1,other.operation(),other.area(),
            other.config(),other.rule(),List.of(cloned));
        var failure=assertThrows(IOException.class,()->reserve(forged));
        assertTrue(failure.getMessage().contains("UUID reused"));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void duplicatedAnimalUuidAcrossExistingSlotsIsRefusedOnReplay()throws Exception {
        emptyCensus();reserve(first());var other=second();
        var original=first().animals().getFirst();
        var duplicate=new NativeFaunaPass.Animal(original.uuid(),650,72,650);
        ledger().write("slot-1",new NativePhysicalRuntime.PopulationReservation(
            other.operation(),DIM,"OPEN_GRASSLAND",other.rule().species(),List.of(duplicate)));
        var failure=assertThrows(IOException.class,()->reserve(first()));
        assertTrue(failure.getMessage().contains("Duplicate animal UUID"));
    }
    @Test void duplicatedOperationInTwoSlotsIsRefusedOnReplay()throws Exception {
        emptyCensus();reserve(first());var p=first();
        ledger().write("slot-1",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        var failure=assertThrows(IOException.class,()->reserve(first()));
        assertTrue(failure.getMessage().contains("Duplicated retained population operation"));
    }
    @Test void duplicateUuidInsideNewPlanCannotCreateReservation()throws Exception {
        emptyCensus();var base=first();
        var same=base.animals().getFirst();
        var clone=new NativeFaunaPass.Animal(same.uuid(),200,72,200);
        var forged=new NativeFaunaPass.Plan(1,base.operation(),base.area(),
            base.config(),base.rule(),List.of(same,clone));
        var failure=assertThrows(IOException.class,()->reserve(forged));
        assertTrue(failure.getMessage().contains("duplicate animal UUID"));
        assertFalse(ledger().exists("slot-0"));
    }
}
