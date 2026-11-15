package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** A recovered spawn slot is trustworthy only when EVERY J04 PREPARE
 * reference, claim and fingerprint agrees with the immutable entity pair.
 * No Minecraft access or permit is performed here.
 */
public final class FaunaSpawnWalFence {
    private static final ResourceId SLOT =
            new ResourceId("proutlost_director:entity_slot");
    private static final CapabilityId SPAWN =
            new CapabilityId("entity.spawn_owned_v1");

    private FaunaSpawnWalFence() {}

    /** No recovered pair may change identity, generation, chunk, policy,
     * ownership or WAL sequence under the guise of RESERVED -> PRESENT.
     * This is the only transition produced by capture().
     */
    public static boolean validTransition(EntityOwnershipRecord reserved,
                                          EntityOwnershipRecord present,
                                          Tick captureTick) {
        if (reserved == null || present == null || captureTick == null)
            return false;
        if (reserved.phase() != EntityPhase.RESERVED
                || reserved.disposition() != OwnershipDisposition.DIRECTOR_ACTIVE
                || reserved.adoptedBy().isPresent())
            return false;
        try {
            var header = new AggregateHeader(1,reserved.header().id(),
                    reserved.header().revision().plus(1),
                    reserved.header().lastSequence(),captureTick);
            var expected = new EntityOwnershipRecord(header,
                    reserved.ownerToken(),reserved.entityUuid(),
                    reserved.instanceId(),reserved.cohortId(),
                    reserved.slotId(),EntityPhase.PRESENT,reserved.disposition(),
                    reserved.lastKnownChunk(),captureTick,reserved.persistMode(),
                    reserved.cleanupPolicy(),Optional.empty(),reserved.generation());
            return present.equals(expected);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean authenticated(EffectBlobs refs, ReadSet read,
                                         Map<ResourceKey,Fingerprint> expectedPost,
                                         List<ResourceClaim> claims,
                                         Set<CapabilityId> capabilities,
                                         ResourceKey key, Epoch generation,
                                         BlobBundle hydrated,
                                         BlobContent before, BlobContent after) {
        if (refs == null || read == null || expectedPost == null
                || claims == null || capabilities == null || key == null
                || generation == null || hydrated == null
                || before == null || after == null)
            return false;
        var pre = before.reference();
        var post = after.reference();
        if (key.domain() != OwnershipDomain.ENTITY
                || !pre.codecId().equals(SLOT) || pre.codecVersion() != 1
                || !post.codecId().equals(SLOT) || post.codecVersion() != 1
                || pre.equals(post))
            return false;
        if (!refs.snapshots().equals(Set.of(pre))
                || !refs.preparedPlan().equals(post)
                || !refs.stopPlan().equals(pre)
                || !refs.preStates().equals(Map.of(key,pre))
                || !refs.expectedPostStates().equals(Map.of(key,post))
                || !read.epochs().equals(Map.of(key,generation))
                || !read.fingerprints().equals(Map.of(key,pre.hash()))
                || !expectedPost.equals(Map.of(key,post.hash()))
                || !capabilities.equals(Set.of(SPAWN))
                || claims.size() != 1)
            return false;
        var claim=claims.getFirst();
        if (!claim.resource().equals(key)
                || claim.policy() != ClaimPolicy.EXCLUSIVE
                || claim.mergeFunction().isPresent()
                || !claim.expectedEpoch().equals(generation))
            return false;
        // Recovery must reject an extra unjournaled blob even if both legitimate
        // entity records are present. BlobBundle already checks duplicate refs.
        return hydrated.contents().stream().map(BlobContent::reference)
                .collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(pre,post));
    }
}
