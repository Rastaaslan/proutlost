package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Operation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BatchUnsealedPreviewRecoveryTest {
    private static final String AREA="proutlost:batch_test_0";
    private static final String LOST=WorldPrepRuntime.PreviewResumeRequired.class.getName()+": Unsealed preview scan lost after restart";
    private static Job failed(Operation op,String cause,UUID snapshot) {
        return new Job(UUID.randomUUID(),op,AREA,JobState.FAILED,1024,cause,snapshot);
    }
    @Test void onlyOwnedBatchReadOnlyPreviewLossesMayRestart() {
        var allowed=Set.of(Operation.PREVIEW_SOILS,Operation.PREVIEW_TREES,Operation.PREVIEW_FLORA,Operation.PREVIEW_AQUATIC);
        for(var op:Operation.values())
            assertEquals(allowed.contains(op),EcologyBatchRuntime.lostUnsealedPreview(failed(op,LOST,null)),op.name());
        assertFalse(EcologyBatchRuntime.lostUnsealedPreview(failed(Operation.PREVIEW_TREES,"IllegalStateException: ownership conflict",null)));
        assertFalse(EcologyBatchRuntime.lostUnsealedPreview(failed(Operation.PREVIEW_TREES,LOST,UUID.randomUUID())));
        assertFalse(EcologyBatchRuntime.lostUnsealedPreview(failed(Operation.PREVIEW_TREES,LOST,null).withState(JobState.COMPLETED)));
        assertFalse(EcologyBatchRuntime.lostUnsealedPreview(null));
    }
    @Test void physicalHistoryRefusesBeforeAnyPreviewRestart() {
        var child=failed(Operation.PREVIEW_TREES,LOST,null);
        var samePass=new BlockWorldPrepData.Journal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),AREA,"TREES","a".repeat(64));
        assertThrows(IllegalStateException.class,()->EcologyBatchRuntime.requireUnsealedPreviewWithoutPhysicalHistory(child,List.of(samePass)));
        var earlierSoils=new BlockWorldPrepData.Journal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),AREA,"SOILS","b".repeat(64));
        assertDoesNotThrow(()->EcologyBatchRuntime.requireUnsealedPreviewWithoutPhysicalHistory(child,List.of(earlierSoils)));
        assertThrows(IllegalStateException.class,()->EcologyBatchRuntime.requireUnsealedPreviewWithoutPhysicalHistory(failed(Operation.APPLY_TREES,LOST,null),List.of()));
    }
}
