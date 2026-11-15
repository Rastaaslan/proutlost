package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure Java fence tests; native NeoForge physical weather remains a separate gate. */
public final class WeatherOwnershipFenceTests {
    private static int count;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }
    private static UUID id(int n) { return new UUID(0L,n); }
    private static OwnershipRecord owner(ResourceKey key, int token, int epoch,
                                         int override, int rev, Fingerprint hash,
                                         OwnershipDisposition disposition) {
        return new OwnershipRecord(
            new AggregateHeader(1,id(1),new Revision(rev),new Sequence(1),new Tick(1)),
            key,Set.of(new OwnerToken(id(token))),new Epoch(epoch),disposition,
            new MutationId(id(2)),hash,new Epoch(override),Optional.empty());
    }
    public static void main(String[] args) {
        var d=new DimensionId("minecraft:overworld");
        var key=new ResourceKey(OwnershipDomain.WEATHER,d,"weather");
        var other=new ResourceKey(OwnershipDomain.WEATHER,d,"other");
        var hash=new Fingerprint("a".repeat(64));
        var changed=new Fingerprint("b".repeat(64));
        var before=owner(key,4,5,0,6,hash,OwnershipDisposition.DIRECTOR_ACTIVE);
        var proof=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,
            Optional.of(hash),Optional.of(new OwnerToken(id(4))),
            before.epoch(),before.playerOverrideEpoch(),false);
        check(WeatherOwnershipFence.sameOwner(before,before),"exact owner");
        check(WeatherOwnershipFence.confirmedAtCurrentOwner(proof,before,before,hash),"valid recovery");
        check(!WeatherOwnershipFence.sameOwner(null,before),"captured missing");
        check(!WeatherOwnershipFence.sameOwner(before,null),"live missing");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,9,5,0,6,hash,OwnershipDisposition.DIRECTOR_ACTIVE)),"rotated token with unchanged epoch");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,4,7,0,6,hash,OwnershipDisposition.DIRECTOR_ACTIVE)),"epoch advance");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,4,5,1,6,hash,OwnershipDisposition.DIRECTOR_ACTIVE)),"override");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,4,5,0,7,hash,OwnershipDisposition.DIRECTOR_ACTIVE)),"owner revision");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,4,5,0,6,changed,OwnershipDisposition.DIRECTOR_ACTIVE)),"provenance");
        check(!WeatherOwnershipFence.sameOwner(before,owner(other,4,5,0,6,hash,OwnershipDisposition.DIRECTOR_ACTIVE)),"wrong resource");
        check(!WeatherOwnershipFence.sameOwner(before,owner(key,4,5,0,6,hash,OwnershipDisposition.YIELDED_TO_PLAYER)),"yield");
        check(!WeatherOwnershipFence.confirmedAtCurrentOwner(proof,before,before,changed),"wrong observed hash");
        check(!WeatherOwnershipFence.confirmedAtCurrentOwner(new RecoveryEvidence(
            key,EvidenceStatus.CONFIRMED,Optional.empty(),proof.ownerToken(),
            proof.ownerEpoch(),proof.playerOverrideEpoch(),false),before,before,hash),"missing hash");
        check(!WeatherOwnershipFence.confirmedAtCurrentOwner(new RecoveryEvidence(
            key,EvidenceStatus.CONFIRMED,proof.observedHash(),Optional.of(new OwnerToken(id(99))),
            proof.ownerEpoch(),proof.playerOverrideEpoch(),false),before,before,hash),"false token");
        check(!WeatherOwnershipFence.confirmedAtCurrentOwner(new RecoveryEvidence(
            key,EvidenceStatus.UNKNOWN,proof.observedHash(),proof.ownerToken(),
            proof.ownerEpoch(),proof.playerOverrideEpoch(),false),before,before,hash),"unconfirmed");
        check(!WeatherOwnershipFence.confirmedAtCurrentOwner(proof,before,null,hash),"missing owner");
        System.out.println("PASS J05 weather ownership CAS assertions="+count+"; native NOT_RUN");
    }
}
