package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure Java only: not evidence of native gameplay qualification. */
public final class DecorativeOwnershipFenceTests {
    private static int count;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);count++;}
    private static UUID uuid(int n){return new UUID(0,n);}
    private static OwnershipRecord owner(ResourceKey key,int token,int generation,int override,OwnershipDisposition disposition,int revision,Fingerprint hash){
        return new OwnershipRecord(new AggregateHeader(1,uuid(1),new Revision(revision),new Sequence(1),new Tick(1)),
            key,Set.of(new OwnerToken(uuid(token))),new Epoch(generation),disposition,new MutationId(uuid(2)),hash,new Epoch(override),Optional.empty());
    }
    private static OwnershipRecord rebase(OwnershipRecord original,AggregateHeader header,MutationId mutation) {
        return new OwnershipRecord(header,original.resourceKey(),original.ownerTokens(),original.epoch(),
            original.disposition(),mutation,original.expectedPostHash(),original.playerOverrideEpoch(),original.exceptionContractId());
    }
    private static RecoveryEvidence proof(OwnershipRecord o,Fingerprint h) {
        return new RecoveryEvidence(o.resourceKey(),EvidenceStatus.CONFIRMED,Optional.of(h),
            Optional.of(o.ownerTokens().iterator().next()),o.epoch(),o.playerOverrideEpoch(),false);
    }
    public static void main(String[] args){
        var d=new DimensionId("minecraft:overworld");
        var key=new ResourceKey(OwnershipDomain.DECORATIVE_OBJECT,d,uuid(3).toString());
        var badKey=new ResourceKey(OwnershipDomain.DECORATIVE_OBJECT,d,uuid(8).toString());
        var h=new Fingerprint("a".repeat(64));var changed=new Fingerprint("b".repeat(64));
        var captured=owner(key,4,5,0,OwnershipDisposition.DIRECTOR_ACTIVE,6,h);
        var evidence=proof(captured,h);
        check(DecorativeOwnershipFence.sameOwner(captured,captured),"same CAS");
        check(DecorativeOwnershipFence.confirmedAtCurrentOwner(evidence,captured,captured,h),"exact owner+observation");
        check(DecorativeOwnershipFence.changesState(h,changed),"physical delta allowed");
        check(!DecorativeOwnershipFence.changesState(h,h),"no-op decorative move refused");
        check(!DecorativeOwnershipFence.changesState(null,h),"missing before-state refused");
        check(!DecorativeOwnershipFence.changesState(h,null),"missing after-state refused");
        check(!DecorativeOwnershipFence.sameOwner(null,captured),"missing captured");
        check(!DecorativeOwnershipFence.sameOwner(captured,null),"missing live");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,9,5,0,OwnershipDisposition.DIRECTOR_ACTIVE,6,h)),"token change");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,4,7,0,OwnershipDisposition.DIRECTOR_ACTIVE,6,h)),"epoch change");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,4,5,1,OwnershipDisposition.DIRECTOR_ACTIVE,6,h)),"player override");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,4,5,0,OwnershipDisposition.YIELDED_TO_PLAYER,6,h)),"yielded");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,4,5,0,OwnershipDisposition.DIRECTOR_ACTIVE,7,h)),"revision");
        var head=captured.header();
        check(!DecorativeOwnershipFence.sameOwner(captured,rebase(captured,
            new AggregateHeader(1,uuid(99),head.revision(),head.lastSequence(),head.updatedTick()),captured.creationMutationId())),"aggregate id substitution");
        check(!DecorativeOwnershipFence.sameOwner(captured,rebase(captured,
            new AggregateHeader(1,head.id(),head.revision(),new Sequence(99),head.updatedTick()),captured.creationMutationId())),"sequence substitution");
        check(!DecorativeOwnershipFence.sameOwner(captured,rebase(captured,
            new AggregateHeader(1,head.id(),head.revision(),head.lastSequence(),new Tick(99)),captured.creationMutationId())),"timestamp substitution");
        check(!DecorativeOwnershipFence.sameOwner(captured,rebase(captured,
            head,new MutationId(uuid(99)))),"creation mutation substitution");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(key,4,5,0,OwnershipDisposition.DIRECTOR_ACTIVE,6,changed)),"owner expected state");
        check(!DecorativeOwnershipFence.sameOwner(captured,owner(badKey,4,5,0,OwnershipDisposition.DIRECTOR_ACTIVE,6,h)),"resource key");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(evidence,captured,captured,changed),"observed hash differs");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.empty(),evidence.ownerToken(),captured.epoch(),captured.playerOverrideEpoch(),false),
            captured,captured,h),"missing observed hash");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(badKey,EvidenceStatus.CONFIRMED,evidence.observedHash(),evidence.ownerToken(),captured.epoch(),captured.playerOverrideEpoch(),false),
            captured,captured,h),"foreign resource");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,evidence.observedHash(),Optional.of(new OwnerToken(uuid(99))),captured.epoch(),captured.playerOverrideEpoch(),false),
            captured,captured,h),"foreign owner");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,evidence.observedHash(),evidence.ownerToken(),new Epoch(3),captured.playerOverrideEpoch(),false),
            captured,captured,h),"foreign epoch");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,evidence.observedHash(),evidence.ownerToken(),captured.epoch(),new Epoch(9),false),
            captured,captured,h),"foreign override");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(
            new RecoveryEvidence(key,EvidenceStatus.UNKNOWN,evidence.observedHash(),evidence.ownerToken(),captured.epoch(),captured.playerOverrideEpoch(),false),
            captured,captured,h),"unconfirmed status");
        check(!DecorativeOwnershipFence.confirmedAtCurrentOwner(evidence,captured,null,h),"no owner on recovery");
        System.out.println("PASS decorative owner/CAS regression assertions="+count+"; game native NOT_RUN");
    }
}
