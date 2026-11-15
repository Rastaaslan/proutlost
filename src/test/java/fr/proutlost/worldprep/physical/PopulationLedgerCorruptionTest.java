package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.DiskPopulationCounts;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retained WAL shape and identity checks, entirely on disposable filesystem. */
class PopulationLedgerCorruptionTest {
    @TempDir Path scratch;
    private static final String DIM="minecraft:overworld", REGISTRY="a".repeat(64);
    private static final UUID OPERATION=UUID.fromString("f8c0275c-5006-5ba5-9097-b3c59c7c8795");
    private static final UUID ANIMAL_ID=UUID.fromString("d370d082-ef03-5aa4-9b7c-875598370121");
    private PhysicalStore ledger()throws IOException {
        return new PhysicalStore(scratch.resolve("ledger"));
    }
    private NativeFaunaPass.Plan plan() {
        var rule=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains"),Set.of("minecraft:grass_block"),
            -64,319,-10f,10f,0,2,2,2,16,true,2,2);
        return new NativeFaunaPass.Plan(1,OPERATION,
            new WorldPrepSavedData.Area("proutlost:corrupt-lab",DIM,80,80,95,95),
            "retained-slot-shape-test",rule,
            List.of(new NativeFaunaPass.Animal(ANIMAL_ID,85,72,85)));
    }
    private void init()throws Exception {
        ledger().write("disk-baseline",new NativeBackupFaunaCensus.Baseline(
            1,"b".repeat(64),REGISTRY,
            new DiskPopulationCounts(0,Map.of(),Map.of(),Map.of()),
            "COMPLETE_QUIESCENT_BACKUP_CENSUS"));
    }
    private NativePhysicalRuntime.PopulationReservation reservation(
        UUID operation,String dimension,String habitat,String species,
        List<NativeFaunaPass.Animal> animals) {
        return new NativePhysicalRuntime.PopulationReservation(
            operation,dimension,habitat,species,animals);
    }
    private NativePhysicalRuntime.PopulationReservation valid() {
        var p=plan();
        return reservation(p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals());
    }
    private void expectUnsafeSlot(NativePhysicalRuntime.PopulationReservation bad)throws Exception {
        init();
        ledger().write("slot-0",bad);
        assertThrows(IOException.class,()->NativePhysicalRuntime.reservePopulationLedger(
            ledger(),plan(),DIM,REGISTRY));
        assertFalse(ledger().exists("slot-1"));
        assertFalse(ledger().exists("world-policy"));
    }
    @Test void validRetainedReceiptShapePasses()throws Exception {
        assertDoesNotThrow(()->NativePhysicalRuntime.validateRetainedReservation(valid()));
    }
    @Test void missingOperationNeverCountsAsReserved()throws Exception {
        expectUnsafeSlot(reservation(null,DIM,"OPEN_GRASSLAND","minecraft:cow",plan().animals()));
    }
    @Test void missingSpeciesRefusesBeforePublishing()throws Exception {
        expectUnsafeSlot(reservation(OPERATION,DIM,"OPEN_GRASSLAND",null,plan().animals()));
    }
    @Test void invalidHabitatRefusesBeforePublishing()throws Exception {
        expectUnsafeSlot(reservation(OPERATION,DIM,"FAKE_HABITAT","minecraft:cow",plan().animals()));
    }
    @Test void nonCanonicalSpeciesRefusesBeforePublishing()throws Exception {
        expectUnsafeSlot(reservation(OPERATION,DIM,"OPEN_GRASSLAND","minecraft:COW",plan().animals()));
    }
    @Test void malformedDimensionRefusesBeforePublishing()throws Exception {
        expectUnsafeSlot(reservation(OPERATION,"invalid world","OPEN_GRASSLAND",
            "minecraft:cow",plan().animals()));
    }
    @Test void duplicatedAnimalUuidInRetainedSlotRefusesBeforePublishing()throws Exception {
        var animal=plan().animals().getFirst();
        expectUnsafeSlot(reservation(OPERATION,DIM,"OPEN_GRASSLAND","minecraft:cow",
            List.of(animal,new NativeFaunaPass.Animal(animal.uuid(),89,72,90))));
    }
    @Test void impossibleRetainedWorldPositionRefusesBeforePublishing()throws Exception {
        expectUnsafeSlot(reservation(OPERATION,DIM,"OPEN_GRASSLAND","minecraft:cow",
            List.of(new NativeFaunaPass.Animal(ANIMAL_ID,Integer.MAX_VALUE,72,85))));
    }
    @Test void malformedRetainedSlotBehindValidSlotPreventsReplay()throws Exception {
        init();
        ledger().write("world-policy",Map.of("globalLimit",2,"habitatLimit",2,"regionLimit",2));
        ledger().write("species-policy-"+PhysicalStore.sha("minecraft:cow"),
            Map.of("species","minecraft:cow","limit",2));
        ledger().write("slot-0",valid());
        ledger().write("slot-1",reservation(
            UUID.fromString("2d0b3b43-e10d-50fa-9055-11c039293377"),
            DIM,"BOGUS","minecraft:cow",plan().animals()));
        assertThrows(IOException.class,()->NativePhysicalRuntime.reservePopulationLedger(
            ledger(),plan(),DIM,REGISTRY));
        assertFalse(ledger().exists("slot-2"));
    }
}
