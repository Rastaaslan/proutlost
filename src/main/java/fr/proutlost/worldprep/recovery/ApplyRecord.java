package fr.proutlost.worldprep.recovery;

import fr.proutlost.worldprep.pipeline.PassId;
import java.util.UUID;

/** Explicit ownership record: rollback always targets its id, never a "latest" lookup. */
public record ApplyRecord(UUID id, UUID pipelineRunId, UUID planId, PassId passId, String areaId,
        UUID journalId, long parentEnvironmentRevision, long resultEnvironmentRevision, State state) {
    public enum State { APPLYING, PARTIALLY_APPLIED, RECOVERY_REQUIRED, RECONCILING, APPLIED, ROLLING_BACK, ROLLED_BACK, CONFLICT }
    public ApplyRecord {
        if (resultEnvironmentRevision != Math.addExact(parentEnvironmentRevision, 1))
            throw new IllegalArgumentException("Environment revision must advance exactly once");
    }
}
