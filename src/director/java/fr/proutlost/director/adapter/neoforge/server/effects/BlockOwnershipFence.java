package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.Optional;

/** Exact phase-aware block-owner CAS for a durable J04 physical mutation.
 * A committed block has a new local owner revision/hash; requiring the
 * original PRE revision on recovery would incorrectly reject legitimate POST.
 * This class never grants permission to write: it validates observed evidence.
 */
public final class BlockOwnershipFence {
    private BlockOwnershipFence() {}

    private static boolean identity(OwnershipRecord captured, OwnershipRecord live) {
        return captured != null && live != null
            && captured.resourceKey().domain() == OwnershipDomain.BLOCK
            && captured.resourceKey().equals(live.resourceKey())
            && captured.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
            && live.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
            && !captured.ownerTokens().isEmpty()
            && captured.ownerTokens().equals(live.ownerTokens())
            && captured.epoch().equals(live.epoch())
            && captured.playerOverrideEpoch().equals(live.playerOverrideEpoch())
            && captured.creationMutationId().equals(live.creationMutationId())
            && captured.exceptionContractId().equals(live.exceptionContractId())
            && captured.header().id().equals(live.header().id())
            && captured.header().schemaVersion() == live.header().schemaVersion()
            && captured.header().lastSequence().equals(live.header().lastSequence());
    }

    public static boolean exactPre(OwnershipRecord captured, OwnershipRecord live) {
        return identity(captured, live) && captured.header().equals(live.header())
            && captured.expectedPostHash().equals(live.expectedPostHash());
    }

    public static boolean exactPost(OwnershipRecord captured, OwnershipRecord live, Fingerprint expectedPost) {
        return expectedPost != null && identity(captured, live)
            && live.header().revision().equals(captured.header().revision().plus(1))
            && live.header().updatedTick().compareTo(captured.header().updatedTick()) >= 0
            && live.expectedPostHash().equals(expectedPost);
    }

    /** A proof about a different owner, epoch or observed state is never
     * usable, even when the block bytes currently happen to match the plan. */
    public static boolean confirmedAtCurrentOwner(RecoveryEvidence proof,
            OwnershipRecord captured, OwnershipRecord live, Fingerprint observed) {
        return proof != null && observed != null
            && (exactPre(captured, live) || exactPost(captured, live, observed))
            && proof.resource().equals(live.resourceKey())
            && proof.status() == EvidenceStatus.CONFIRMED
            && proof.ownerEpoch().equals(live.epoch())
            && proof.playerOverrideEpoch().equals(live.playerOverrideEpoch())
            && proof.ownerToken().filter(live.ownerTokens()::contains).isPresent()
            && proof.observedHash().equals(Optional.of(observed));
    }
}
