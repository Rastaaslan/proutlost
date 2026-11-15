package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.Optional;

/** Exact ownership CAS for durable decorative Display moves and their recovery.
 * Proofs are observations, never authority to move an entity.
 */
public final class DecorativeOwnershipFence {
    private DecorativeOwnershipFence() {}

    public static boolean sameOwner(OwnershipRecord captured, OwnershipRecord live) {
        return captured != null && live != null
                && captured.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
                && live.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
                && !captured.ownerTokens().isEmpty()
                && captured.resourceKey().domain() == OwnershipDomain.DECORATIVE_OBJECT
                // Bind the complete durable owner snapshot, including aggregate identity,
                // WAL sequence, provenance and exception contract. A matching token/epoch
                // alone must not authorize a different owner's decorative entity move.
                && captured.equals(live);
    }

    /** A physical move must change the canonical entity snapshot before J04 PREPARE. */
    static boolean changesState(Fingerprint before, Fingerprint after) {
        return before != null && after != null && !before.equals(after);
    }

    public static boolean confirmedAtCurrentOwner(RecoveryEvidence proof,
                                                   OwnershipRecord captured,
                                                   OwnershipRecord live,
                                                   Fingerprint observed) {
        if (proof == null || observed == null || !sameOwner(captured, live)) return false;
        return proof.resource().equals(live.resourceKey())
                && proof.status() == EvidenceStatus.CONFIRMED
                && proof.ownerEpoch().equals(live.epoch())
                && proof.playerOverrideEpoch().equals(live.playerOverrideEpoch())
                && proof.ownerToken().filter(live.ownerTokens()::contains).isPresent()
                && proof.observedHash().equals(Optional.of(observed));
    }
}
