package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.*;
import fr.proutlost.worldprep.storage.BatchManifestStore.Stage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EcologyRecoveryHistoryTest {
    final UUID batch=UUID.randomUUID(), child=UUID.randomUUID(), planId=UUID.randomUUID(), snapshot=UUID.randomUUID();
    final String tile="proutlost:batch_fixture_0", hash="a".repeat(64);
    Stage receipt(long sequence,String stage) {
        return new Stage(1,batch,sequence,0,tile,stage,"plans/"+planId+"/manifest.wpm",hash,
            "VANILLA_DEVELOPMENT_PALETTE","COMPLETED",child,planId,snapshot,52,52,hash);
    }
    BlockWorldPrepData.Plan plan() {
        var p=new BlockWorldPrepData.Plan(tile,"FLORA","development",hash,hash);
        p.planId(planId);p.entryCount(52);return p;
    }
    BlockWorldPrepData.Journal journal() {
        var j=new BlockWorldPrepData.Journal(snapshot,child,planId,tile,"FLORA",hash);j.entryCount(52);return j;
    }
    Job job() {return new Job(child,Operation.APPLY_FLORA,tile,JobState.COMPLETED,52,"",snapshot);}
    @Test void onlyExactLaterCompletedPhysicalReceiptCanBeExcludedFromHistoricalIdentity() throws Exception {
        var h=EcologyRecoveryHistory.EMPTY.withLater(receipt(2,"APPLY_SOILS"),10,receipt(6,"APPLY_FLORA"),job(),plan(),journal());
        assertEquals(Set.of(child),h.jobs());assertEquals(Set.of(snapshot),h.journals());
        assertThrows(UnsupportedOperationException.class,()->h.jobs().add(UUID.randomUUID()));
        assertThrows(java.io.IOException.class,()->h.withLater(receipt(2,"APPLY_SOILS"),10,receipt(6,"APPLY_FLORA"),job(),plan(),journal()));
        assertTrue(EcologyRecoveryHistory.EMPTY.jobs().isEmpty());
    }
    @Test void priorFutureUnownedWrongStageAndNoncompletedHistoryRefuse() {
        Stage current=receipt(2,"APPLY_SOILS"),later=receipt(6,"APPLY_FLORA");
        for(var state:JobState.values()) if(state!=JobState.COMPLETED)
            assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,job().withState(state),plan(),journal()));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,6,later,job(),plan(),journal()));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(later,10,current,job(),plan(),journal()));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,receipt(5,"APPLY_FLORA"),job(),plan(),journal()));
        var foreign=new Job(UUID.randomUUID(),Operation.APPLY_FLORA,tile,JobState.COMPLETED,52,"",snapshot);
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,foreign,plan(),journal()));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,job().withCursor(51),plan(),journal()));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,job(),plan(),null));
        var wrong=journal();wrong.entryCount(51);
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,job(),plan(),wrong));
        var altered=plan();altered.fingerprint("b".repeat(64));
        assertThrows(java.io.IOException.class,()->EcologyRecoveryHistory.EMPTY.withLater(current,10,later,job(),altered,journal()));
    }
}
