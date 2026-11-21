package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * Checks that a J04 navigation PREPARE binds exactly one immutable route to
 * its ENTITY claim. Starting navigation is never proof of arrival.
 *
 * This validator has no Minecraft dependencies and cannot grant a permit.
 */
public final class FaunaNavigationWalFence {
    private static final ResourceId ROUTE_CODEC =
            new ResourceId("proutlost_director:animal_route");
    private static final CapabilityId NAVIGATION =
            new CapabilityId("entity.navigation_owned_v1");

    private FaunaNavigationWalFence() {}

    public static boolean authenticated(EffectBlobs refs, ReadSet read,
                                        Map<ResourceKey, Fingerprint> expectedPost,
                                        List<ResourceClaim> claims,
                                        Set<CapabilityId> capabilities,
                                        ResourceKey key, Epoch generation,
                                        BlobContent captured) {
        if (refs == null || read == null || expectedPost == null ||
                claims == null || capabilities == null || key == null ||
                generation == null || captured == null)
            return false;
        BlobRef route = captured.reference();
        if (!route.codecId().equals(ROUTE_CODEC) || route.codecVersion() != 1 ||
                key.domain() != OwnershipDomain.ENTITY)
            return false;
        if (!refs.preparedPlan().equals(route) || !refs.stopPlan().equals(route) ||
                !refs.snapshots().equals(Set.of(route)) ||
                !refs.preStates().equals(Map.of(key, route)) ||
                !refs.expectedPostStates().isEmpty() ||
                !expectedPost.isEmpty() ||
                !read.epochs().equals(Map.of(key, generation)) ||
                !read.fingerprints().equals(Map.of(key, route.hash())) ||
                !capabilities.equals(Set.of(NAVIGATION)) ||
                claims.size() != 1)
            return false;
        var claim = claims.getFirst();
        return claim.resource().equals(key) &&
                claim.policy() == ClaimPolicy.EXCLUSIVE &&
                claim.mergeFunction().isEmpty() &&
                claim.expectedEpoch().equals(generation);
    }
}
