package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.DiskPopulationCounts;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Resume must be verification ONLY, not a second admission. No Minecraft world needed. */
class PopulationResumeReservationTest {
    @TempDir Path root;
    private static final String DIM="minecraft:overworld",REGISTRY="a".repeat(64);
    private PhysicalStore ledger()throws IOException {
        return new PhysicalStore(root.resolve("world-budget"));
    }
    private NativeFaunaPass.Plan plan(String uuid,int x,int z) {
        var rule=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains"),Set.of("minecraft:grass_block"),
            -64,319,-10f,10f,0,4,4,1,16,true,4,4);
        var animal=new NativeFaunaPass.Animal(UUID.fromString(uuid),x,72,z);
        return new NativeFaunaPass.Plan(1,
            UUID.nameUUIDFromBytes(uuid.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            new WorldPrepSavedData.Area("proutlost:resume-test",DIM,x,z,x+15,z+15),
            "resume-test/"+uuid,rule,List.of(animal));
    }
    private NativeFaunaPass.Plan first() {
        return plan("d370d082-ef03-5aa4-9b7c-875598370121",100,100);
    }
    private NativeFaunaPass.Plan second() {
        return plan("e470d082-ef03-5aa4-9b7c-875598370122",650,650);
    }
    private void baseline()throws IOException {
        ledger().write("disk-baseline",new NativeBackupFaunaCensus.Baseline(
            1,"b".repeat(64),REGISTRY,new DiskPopulationCounts(0,Map.of(),Map.of(),Map.of()),
            "COMPLETE_QUIESCENT_BACKUP_CENSUS"));
    }
    private void admission(NativeFaunaPass.Plan p)throws IOException {
        NativePhysicalRuntime.reservePopulationLedger(ledger(),p,DIM,REGISTRY,false);
    }
    private void resume(NativeFaunaPass.Plan p)throws IOException {
        NativePhysicalRuntime.reservePopulationLedger(ledger(),p,DIM,REGISTRY,true);
    }
    @Test void missingOperationNeverAllocatesNewSlot()throws Exception {
        baseline();
        var error=assertThrows(IOException.class,()->resume(first()));
        assertTrue(error.getMessage().contains("MISSING_RETAINED_POPULATION_RESERVATION"));
        assertFalse(ledger().exists("slot-0"));
        assertFalse(ledger().exists("world-policy"));
    }
    @Test void alreadyReservedOperationReopensExactlyAndWithoutWrites()throws Exception {
        baseline();admission(first());
        var slot=ledger().readText("slot-0");
        var policy=ledger().readText("world-policy");
        assertDoesNotThrow(()->resume(first()));
        assertEquals(slot,ledger().readText("slot-0"));
        assertEquals(policy,ledger().readText("world-policy"));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void laterOperationMustNotBeCreatedDuringEarlierResume()throws Exception {
        baseline();admission(first());
        var error=assertThrows(IOException.class,()->resume(second()));
        assertTrue(error.getMessage().contains("MISSING_RETAINED_POPULATION_RESERVATION"));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void orphanedSlotWithoutFrozenWorldPolicyCannotResume()throws Exception {
        baseline();var p=first();
        ledger().write("slot-0",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        assertThrows(IOException.class,()->resume(p));
        assertFalse(ledger().exists("world-policy"));
    }
    @Test void changedRetainedOperationCannotBeReplacedByNewSlot()throws Exception {
        baseline();admission(first());var p=first();
        var foreign=new NativeFaunaPass.Plan(1,p.operation(),p.area(),p.config(),p.rule(),
            List.of(new NativeFaunaPass.Animal(p.animals().getFirst().uuid(),101,72,100)));
        assertThrows(IOException.class,()->resume(foreign));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void interruptedLedgerWithGapRemainsBlockedInReplay()throws Exception {
        baseline();admission(first());var p=second();
        ledger().write("slot-2",new NativePhysicalRuntime.PopulationReservation(
            p.operation(),DIM,"OPEN_GRASSLAND",p.rule().species(),p.animals()));
        assertThrows(IOException.class,()->resume(first()));
        assertFalse(ledger().exists("slot-1"));
    }
    @Test void firstAdmissionAfterMissingReplayIsStillPossibleOnlyExplicitly()throws Exception {
        baseline();
        assertThrows(IOException.class,()->resume(first()));
        admission(first());
        assertDoesNotThrow(()->resume(first()));
    }
}
