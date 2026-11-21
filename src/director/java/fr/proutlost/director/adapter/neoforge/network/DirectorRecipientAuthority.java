package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * Actor-supplied, read-only J06 recipient gate for a future J03/J05 producer.
 * A connected player or a cached F8 session is not proof of a usable renderer:
 * the current J04 world/boot, physical dimension and negotiated asset digest
 * must ALL match. This does not issue an effect permit or send a packet.
 */
public final class DirectorRecipientAuthority {
    private DirectorRecipientAuthority() {}

    public static boolean negotiated(SessionIdentity identity, Capabilities capabilities,
            WorldId walWorld, Epoch walBoot, PlayerId player, DimensionId liveDimension,
            PerceptionKind kind, ResourceId asset, Fingerprint digest) {
        Objects.requireNonNull(walWorld);
        Objects.requireNonNull(walBoot);
        Objects.requireNonNull(player);
        Objects.requireNonNull(liveDimension);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(asset);
        Objects.requireNonNull(digest);
        return identity!=null && capabilities!=null
            && identity.worldId().equals(walWorld)
            && identity.bootEpoch().equals(walBoot)
            && identity.playerId().equals(player)
            && identity.dimension().equals(liveDimension)
            && capabilities.connectionEpoch().equals(identity.connectionEpoch())
            && capabilities.supportedEffects().contains(kind)
            && digest.equals(capabilities.assetHashes().get(asset));
    }
}
