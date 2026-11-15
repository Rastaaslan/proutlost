package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** J04 recovery admission tests: these never spawn an animal or simulate a client. */
public final class FaunaSpawnWalFenceTests {
    private static int assertions;
    private static void check(boolean ok) {
        assertions++;
        if (!ok) throw new AssertionError("spawn WAL fence #" + assertions);
    }
    private static final ResourceId SLOT =
            new ResourceId("proutlost_director:entity_slot");
    private static final CapabilityId CAP = new CapabilityId("entity.spawn_owned_v1");
    private static final DimensionId DIM = new DimensionId("minecraft:overworld");
    private static final ResourceKey KEY = new ResourceKey(OwnershipDomain.ENTITY,DIM,
            "00000000-0000-0000-0000-000000000123");
    private static final Epoch GEN = new Epoch(8);
    private static final BudgetVector COST =
            new BudgetVector(0,0,0,1,1,1,0,0,0,0);
    private static final BlobContent BEFORE=BlobContent.capture(SLOT,1,new byte[]{1,2,3});
    private static final BlobContent AFTER=BlobContent.capture(SLOT,1,new byte[]{4,5,6});
    private static final BlobRef PRE=BEFORE.reference(),POST=AFTER.reference();
    private static final BlobBundle BLOBS=new BlobBundle(List.of(BEFORE,AFTER));
    private static EffectBlobs refs(){
        return new EffectBlobs(Set.of(PRE),POST,PRE,Map.of(KEY,PRE),Map.of(KEY,POST));
    }
    private static ReadSet read(){
        return new ReadSet(Map.of(),Map.of(KEY,GEN),Map.of(KEY,PRE.hash()));
    }
    private static ResourceClaim claim(){
        return new ResourceClaim(KEY,ClaimPolicy.EXCLUSIVE,COST,Optional.empty(),GEN);
    }
    private static boolean accept(EffectBlobs refs,ReadSet read,
                                  Map<ResourceKey,Fingerprint> post,
                                  List<ResourceClaim> claims,Set<CapabilityId> caps,
                                  ResourceKey key,Epoch epoch,BlobBundle blobs,
                                  BlobContent before,BlobContent after) {
        return FaunaSpawnWalFence.authenticated(refs,read,post,claims,caps,
                key,epoch,blobs,before,after);
    }
    private static boolean accept(EffectBlobs refs){
        return accept(refs,read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER);
    }
    private static final Tick CAPTURE_TICK = new Tick(90);
    private static final EntityOwnershipRecord RESERVED = new EntityOwnershipRecord(
            new AggregateHeader(1,UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    new Revision(4),new Sequence(30),new Tick(80)),
            new OwnerToken(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            UUID.fromString("00000000-0000-0000-0000-000000000123"),
            new EventInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
            new CohortId(UUID.fromString("00000000-0000-0000-0000-000000000004")),
            "slot",EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,
            new ChunkRef(DIM,2,3),new Tick(80),ArrivalPolicy.SETTLE_WORLD,
            CleanupReason.COMPLETED,Optional.empty(),GEN);
    private static EntityOwnershipRecord changed(String part) {
        var b=RESERVED;
        var h=new AggregateHeader(1,b.header().id(),
                part.equals("revision")?new Revision(4):new Revision(5),
                part.equals("sequence")?new Sequence(31):b.header().lastSequence(),
                part.equals("headerTick")?new Tick(91):CAPTURE_TICK);
        return new EntityOwnershipRecord(h,
                part.equals("owner")?new OwnerToken(UUID.fromString(
                        "00000000-0000-0000-0000-000000000005")):b.ownerToken(),
                b.entityUuid(),b.instanceId(),b.cohortId(),
                part.equals("slot")?"other":b.slotId(),
                part.equals("phase")?EntityPhase.MOVING:EntityPhase.PRESENT,
                b.disposition(),
                part.equals("chunk")?new ChunkRef(DIM,7,7):b.lastKnownChunk(),
                part.equals("lastTick")?new Tick(91):CAPTURE_TICK,
                part.equals("persist")?ArrivalPolicy.CLEANUP_OWNED:b.persistMode(),
                part.equals("cleanup")?CleanupReason.EXPIRED:b.cleanupPolicy(),
                part.equals("adopted")?Optional.of(new PlayerId(
                        UUID.fromString("00000000-0000-0000-0000-000000000006"))):Optional.empty(),
                part.equals("generation")?new Epoch(9):GEN);
    }
    public static void main(String[] args) {
        check(FaunaSpawnWalFence.validTransition(RESERVED,changed("ok"),CAPTURE_TICK));
        for (var field:List.of("revision","sequence","headerTick","owner","slot",
                "phase","chunk","lastTick","persist","cleanup","adopted","generation"))
            check(!FaunaSpawnWalFence.validTransition(RESERVED,changed(field),CAPTURE_TICK));
        check(!FaunaSpawnWalFence.validTransition(RESERVED,changed("ok"),new Tick(91)));
        check(!FaunaSpawnWalFence.validTransition(null,changed("ok"),CAPTURE_TICK));
        check(accept(refs()));
        check(!accept(new EffectBlobs(Set.of(),POST,PRE,Map.of(KEY,PRE),Map.of(KEY,POST))));
        check(!accept(new EffectBlobs(Set.of(PRE),PRE,PRE,Map.of(KEY,PRE),Map.of(KEY,POST))));
        check(!accept(new EffectBlobs(Set.of(PRE),POST,POST,Map.of(KEY,PRE),Map.of(KEY,POST))));
        check(!accept(new EffectBlobs(Set.of(PRE),POST,PRE,Map.of(),Map.of(KEY,POST))));
        check(!accept(new EffectBlobs(Set.of(PRE),POST,PRE,Map.of(KEY,PRE),Map.of())));
        check(!accept(new EffectBlobs(Set.of(PRE,POST),POST,PRE,Map.of(KEY,PRE),Map.of(KEY,POST))));
        check(!accept(refs(),new ReadSet(Map.of(),Map.of(),Map.of(KEY,PRE.hash())),
                Map.of(KEY,POST.hash()),List.of(claim()),Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),new ReadSet(Map.of(),Map.of(KEY,GEN),Map.of()),
                Map.of(KEY,POST.hash()),List.of(claim()),Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(),List.of(claim()),Set.of(CAP),
                KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,PRE.hash()),List.of(claim()),Set.of(CAP),
                KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(),Set.of(CAP),
                KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim(),claim()),
                Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),
                List.of(new ResourceClaim(KEY,ClaimPolicy.SHARE,COST,Optional.empty(),GEN)),
                Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),
                List.of(new ResourceClaim(KEY,ClaimPolicy.EXCLUSIVE,COST,Optional.empty(),new Epoch(7))),
                Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(),KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP,new CapabilityId("entity.navigation_owned_v1")),
                KEY,GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),new ResourceKey(OwnershipDomain.BLOCK,DIM,KEY.key()),
                GEN,BLOBS,BEFORE,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,new Epoch(9),BLOBS,BEFORE,AFTER));
        var extra=BlobContent.capture(SLOT,1,new byte[]{9});
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,GEN,new BlobBundle(List.of(BEFORE,AFTER,extra)),BEFORE,AFTER));
        var v2=BlobContent.capture(SLOT,2,BEFORE.bytes());
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,GEN,BLOBS,v2,AFTER));
        check(!accept(refs(),read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,GEN,BLOBS,AFTER,AFTER));
        check(!accept(null,read(),Map.of(KEY,POST.hash()),List.of(claim()),
                Set.of(CAP),KEY,GEN,BLOBS,BEFORE,AFTER));
        System.out.println("DIRECTOR_FAUNA_SPAWN_WAL_FENCE_PASS assertions="+assertions);
    }
}
