package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure proposals and rollback eligibility. Does not persist cession or authorize a write.
 * Adapter must install/dirty the local tombstone in the same actor turn, invalidate queued
 * operations and journal the proposed record. Durable/local reconciliation is separate.
 */
public final class OwnershipRules {
    private OwnershipRules() {}
    public enum ExternalChange { PLAYER, PISTON, EXPLOSION, FLUID, THIRD_PARTY, UNKNOWN }
    public enum Rollback { ELIGIBLE, YIELDED, CONFLICT_ISOLATED }

    public static OwnershipRecord cede(OwnershipRecord before, ExternalChange cause, Tick tick) {
        Objects.requireNonNull(cause); Objects.requireNonNull(tick);
        if (tick.compareTo(before.header().updatedTick()) < 0) throw new IllegalArgumentException("clock regression");
        // Once yielded, later external physics cannot remove the player's permanent tombstone.
        boolean player = cause == ExternalChange.PLAYER || before.disposition() == OwnershipDisposition.YIELDED_TO_PLAYER;
        var header = new AggregateHeader(before.header().schemaVersion(), before.header().id(),
                before.header().revision().plus(1), before.header().lastSequence(), tick);
        return new OwnershipRecord(header, before.resourceKey(), before.ownerTokens(), before.epoch().plus(1),
                player ? OwnershipDisposition.YIELDED_TO_PLAYER : OwnershipDisposition.CONFLICT_ISOLATED,
                before.creationMutationId(), before.expectedPostHash(),
                cause == ExternalChange.PLAYER ? before.playerOverrideEpoch().plus(1) : before.playerOverrideEpoch(),
                before.exceptionContractId());
    }
    /** Missing observations are represented explicitly, never treated as matching hashes.
     * SHARED_EXPLICIT needs registered contributor compensation; ordinary rollback refuses it.
     */
    public static Rollback rollback(OwnershipRecord current, OwnerToken expectedOwner, Epoch expectedEpoch,
                                    Epoch expectedOverride, Fingerprint capturedPost,
                                    Optional<Fingerprint> observed, boolean inverseStillSafe) {
        Objects.requireNonNull(current); Objects.requireNonNull(expectedOwner); Objects.requireNonNull(expectedEpoch);
        Objects.requireNonNull(expectedOverride); Objects.requireNonNull(capturedPost); Objects.requireNonNull(observed);
        if (current.disposition() == OwnershipDisposition.YIELDED_TO_PLAYER
                || !current.playerOverrideEpoch().equals(expectedOverride)) return Rollback.YIELDED;
        boolean owned = current.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
                || current.disposition() == OwnershipDisposition.DIRECTOR_DURABLE;
        if (!owned || !current.ownerTokens().equals(Set.of(expectedOwner)) || !current.epoch().equals(expectedEpoch)
                || !current.expectedPostHash().equals(capturedPost) || !observed.equals(Optional.of(capturedPost))
                || !inverseStillSafe) return Rollback.CONFLICT_ISOLATED;
        return Rollback.ELIGIBLE;
    }
}
