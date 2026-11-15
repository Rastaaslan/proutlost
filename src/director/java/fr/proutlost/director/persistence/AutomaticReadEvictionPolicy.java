package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.ReadStatus;
import java.util.Objects;

/**
 * J04 LAB-ONLY checkpoint-triggered chronology eviction policy.
 * A disabled policy is the unconditional production default.
 *
 * This can release ONLY read chronology; authoritative WAL and operational
 * indexes (facts, ownership, outbox, uniqueness, results, receipts) stay intact.
 * It cannot enable any Director gameplay event or background worker.
 */
public record AutomaticReadEvictionPolicy(
        int minimumEligibleRows,long maximumCheckpointBytes,
        int minimumGenerationSpacing) {
    public AutomaticReadEvictionPolicy {
        if(minimumEligibleRows<16 || maximumCheckpointBytes<65_536 ||
            maximumCheckpointBytes>64L*1024*1024 ||
            minimumGenerationSpacing<1 || minimumGenerationSpacing>128)
            throw new IllegalArgumentException("unsafe laboratory eviction thresholds");
    }

    public enum Reason {
        DISABLED, BELOW_THRESHOLD, COOLDOWN, ARCHIVE_TOO_LARGE,
        NO_VERIFIED_CHECKPOINT, EVICTED, FAILED_SAFE
    }

    public record Outcome(
            Reason reason,ReadStatus status,long generation,
            int residentRowsBefore,int residentRowsAfter,
            int releasedRows,long archivedRows) {
        public Outcome {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(status);
            if(generation<0 || residentRowsBefore<0 || residentRowsAfter<0 ||
                releasedRows<0 || archivedRows<0 || releasedRows>residentRowsBefore)
                throw new IllegalArgumentException("invalid automatic eviction outcome");
        }
    }
}
