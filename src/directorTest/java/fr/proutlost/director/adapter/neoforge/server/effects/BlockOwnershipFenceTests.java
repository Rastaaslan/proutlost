package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure Java boundary tests; native world/WAL must be qualified separately. */
public final class BlockOwnershipFenceTests {
    static int checks;
    static UUID id(int i) { return new UUID(0, i); }
    static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    static OwnershipRecord owner(ResourceKey key, int token, int epoch, int override,
            OwnershipDisposition disposition, int revision, Fingerprint hash, int mutation) {
        return new OwnershipRecord(new AggregateHeader(1, id(1), new Revision(revision),
                new Sequence(1), new Tick(revision)), key,
                Set.of(new OwnerToken(id(token))), new Epoch(epoch), disposition,
                new MutationId(id(mutation)), hash, new Epoch(override), Optional.empty());
    }
    static RecoveryEvidence proof(OwnershipRecord owner, Fingerprint hash, boolean absence) {
        return new RecoveryEvidence(owner.resourceKey(), EvidenceStatus.CONFIRMED,
                Optional.of(hash), Optional.of(owner.ownerTokens().iterator().next()),
                owner.epoch(), owner.playerOverrideEpoch(), absence);
    }
    public static void main(String[] args) {
        var dim = new DimensionId("minecraft:overworld");
        var key = new ResourceKey(OwnershipDomain.BLOCK, dim, "1,2,3");
        var foreign = new ResourceKey(OwnershipDomain.BLOCK, dim, "4,5,6");
        var preHash = new Fingerprint("a".repeat(64));
        var postHash = new Fingerprint("b".repeat(64));
        var pre = owner(key, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 2);
        var post = owner(key, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 7, postHash, 2);
        check(BlockOwnershipFence.exactPre(pre, pre), "matching PRE");
        check(!BlockOwnershipFence.exactPre(pre, post), "POST never passes as PRE");
        check(BlockOwnershipFence.exactPost(pre, post, postHash), "legitimate POST revision and hash");
        check(!BlockOwnershipFence.exactPost(pre, pre, postHash), "old revision not POST");
        check(!BlockOwnershipFence.exactPost(pre, post, preHash), "post fingerprint mismatch");
        check(BlockOwnershipFence.confirmedAtCurrentOwner(proof(pre, preHash, true), pre, pre, preHash), "genuine PRE absence proof");
        check(BlockOwnershipFence.confirmedAtCurrentOwner(proof(post, postHash, false), pre, post, postHash), "genuine POST proof");
        for (var changed: List.of(
                owner(key, 9, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 2),
                owner(key, 4, 7, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 2),
                owner(key, 4, 5, 1, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 2),
                owner(key, 4, 5, 0, OwnershipDisposition.YIELDED_TO_PLAYER, 6, preHash, 2),
                owner(key, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 8, preHash, 2),
                owner(key, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, postHash, 2),
                owner(key, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 9),
                owner(foreign, 4, 5, 0, OwnershipDisposition.DIRECTOR_ACTIVE, 6, preHash, 2))) {
            check(!BlockOwnershipFence.exactPre(pre, changed), "changed owner PRE denied");
            check(!BlockOwnershipFence.confirmedAtCurrentOwner(proof(changed, preHash, true), pre, changed, preHash),
                    "changed owner recovery denied");
        }
        var good = proof(post, postHash, false);
        for (var forged: List.of(
                new RecoveryEvidence(key, EvidenceStatus.CONFIRMED, Optional.of(preHash), good.ownerToken(), post.epoch(), post.playerOverrideEpoch(), false),
                new RecoveryEvidence(key, EvidenceStatus.CONFIRMED, Optional.empty(), good.ownerToken(), post.epoch(), post.playerOverrideEpoch(), false),
                new RecoveryEvidence(key, EvidenceStatus.CONFIRMED, good.observedHash(), Optional.of(new OwnerToken(id(99))), post.epoch(), post.playerOverrideEpoch(), false),
                new RecoveryEvidence(key, EvidenceStatus.CONFIRMED, good.observedHash(), good.ownerToken(), new Epoch(9), post.playerOverrideEpoch(), false),
                new RecoveryEvidence(key, EvidenceStatus.CONFIRMED, good.observedHash(), good.ownerToken(), post.epoch(), new Epoch(9), false),
                new RecoveryEvidence(foreign, EvidenceStatus.CONFIRMED, good.observedHash(), good.ownerToken(), post.epoch(), post.playerOverrideEpoch(), false),
                new RecoveryEvidence(key, EvidenceStatus.UNKNOWN, good.observedHash(), good.ownerToken(), post.epoch(), post.playerOverrideEpoch(), false))) {
            check(!BlockOwnershipFence.confirmedAtCurrentOwner(forged, pre, post, postHash), "foreign proof denied");
        }
        check(!BlockOwnershipFence.exactPre(null, pre), "missing PRE owner");
        check(!BlockOwnershipFence.exactPost(pre, null, postHash), "missing POST owner");
        check(!BlockOwnershipFence.confirmedAtCurrentOwner(null, pre, post, postHash), "no proof");
        System.out.println("PASS block owner phase/CAS and recovery assertions=" + checks + "; native NOT_RUN");
    }
}
