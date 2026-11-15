package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.Optional;

/** Weather ownership is immutable throughout one sealed physical lease.
 * A new token, owner revision or override must never reuse a WAL permit.
 * Observations are evidence only; they are not permission to mutate weather.
 */
public final class WeatherOwnershipFence {
    private WeatherOwnershipFence() {}

    public static boolean sameOwner(OwnershipRecord captured, OwnershipRecord live) {
        return captured != null && live != null
            && captured.resourceKey().domain() == OwnershipDomain.WEATHER
            && captured.resourceKey().equals(live.resourceKey())
            && captured.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
            && live.disposition() == OwnershipDisposition.DIRECTOR_ACTIVE
            && !captured.ownerTokens().isEmpty()
            && captured.equals(live);
    }

    public static boolean confirmedAtCurrentOwner(RecoveryEvidence proof,
                                                   OwnershipRecord captured,
                                                   OwnershipRecord live,
                                                   Fingerprint observed) {
        return proof != null && observed != null && sameOwner(captured, live)
            && proof.resource().equals(live.resourceKey())
            && proof.status() == EvidenceStatus.CONFIRMED
            && proof.ownerEpoch().equals(live.epoch())
            && proof.playerOverrideEpoch().equals(live.playerOverrideEpoch())
            && proof.ownerToken().filter(live.ownerTokens()::contains).isPresent()
            && proof.observedHash().equals(Optional.of(observed));
    }
}
