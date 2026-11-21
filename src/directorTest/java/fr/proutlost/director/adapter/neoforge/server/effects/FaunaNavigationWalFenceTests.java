package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Strict, executable J04 binding assertions; does not simulate a native animal. */
public final class FaunaNavigationWalFenceTests {
    private static int assertions;
    private static void check(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("navigation WAL fence #" + assertions);
    }
    private static void rejected(Runnable runnable) {
        boolean refused=false;
        try { runnable.run(); } catch(IllegalArgumentException expected) { refused=true; }
        check(refused);
    }
    private static final ResourceId ROUTE = new ResourceId("proutlost_director:animal_route");
    private static final CapabilityId CAP = new CapabilityId("entity.navigation_owned_v1");
    private static final DimensionId DIM = new DimensionId("minecraft:overworld");
    private static final ResourceKey KEY = new ResourceKey(
            OwnershipDomain.ENTITY, DIM, "00000000-0000-0000-0000-000000000123");
    private static final Epoch GENERATION = new Epoch(4);
    private static final BudgetVector COST =
            new BudgetVector(0,0,0,0,1,1,0,0,0,3);
    private static final BlobContent BLOB =
            BlobContent.capture(ROUTE,1,new byte[]{1,2,3,4});
    private static final BlobRef REF = BLOB.reference();

    private static EffectBlobs refs(BlobRef r) {
        return new EffectBlobs(Set.of(r),r,r,Map.of(KEY,r),Map.of());
    }
    private static ReadSet read() {
        return new ReadSet(Map.of(),Map.of(KEY,GENERATION),
                Map.of(KEY,REF.hash()));
    }
    private static ResourceClaim claim() {
        return new ResourceClaim(KEY,ClaimPolicy.EXCLUSIVE,COST,Optional.empty(),GENERATION);
    }
    private static boolean authentic(EffectBlobs refs,ReadSet read,
                                     Map<ResourceKey,Fingerprint> post,
                                     List<ResourceClaim> claims,
                                     Set<CapabilityId> caps,
                                     ResourceKey key,Epoch generation,BlobContent content) {
        return FaunaNavigationWalFence.authenticated(
                refs,read,post,claims,caps,key,generation,content);
    }
    public static void main(String[] args) {
        check(authentic(refs(REF),read(),Map.of(),List.of(claim()),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim()),Set.of(CAP),
                new ResourceKey(OwnershipDomain.ENTITY,DIM,"other-uuid"),GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim()),Set.of(CAP),
                new ResourceKey(OwnershipDomain.BLOCK,DIM,KEY.key()),GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim()),Set.of(CAP),
                KEY,new Epoch(5),BLOB));
        check(!authentic(refs(REF),new ReadSet(Map.of(),Map.of(KEY,GENERATION),
                Map.of(KEY,new Fingerprint("f".repeat(64)))),Map.of(),
                List.of(claim()),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),new ReadSet(Map.of(),Map.of(),Map.of(KEY,REF.hash())),
                Map.of(),List.of(claim()),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(new EffectBlobs(Set.of(REF),REF,REF,Map.of(),Map.of()),read(),
                Map.of(),List.of(claim()),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(new EffectBlobs(Set.of(),REF,REF,Map.of(KEY,REF),Map.of()),read(),
                Map.of(),List.of(claim()),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(new EffectBlobs(Set.of(REF),REF,REF,Map.of(KEY,REF),
                Map.of(KEY,REF)),read(),Map.of(),List.of(claim()),Set.of(CAP),
                KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(KEY,REF.hash()),List.of(claim()),
                Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(),Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim(),claim()),
                Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(new ResourceClaim(
                KEY,ClaimPolicy.SHARE,COST,Optional.empty(),GENERATION)),
                Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(new ResourceClaim(
                KEY,ClaimPolicy.EXCLUSIVE,COST,Optional.empty(),new Epoch(9))),
                Set.of(CAP),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim()),
                Set.of(),KEY,GENERATION,BLOB));
        check(!authentic(refs(REF),read(),Map.of(),List.of(claim()),
                Set.of(CAP,new CapabilityId("entity.spawn_owned_v1")),
                KEY,GENERATION,BLOB));
        var v2=BlobContent.capture(ROUTE,2,new byte[]{1,2,3,4});
        check(!authentic(refs(v2.reference()),read(),Map.of(),List.of(claim()),
                Set.of(CAP),KEY,GENERATION,v2));
        var foreign=BlobContent.capture(new ResourceId("proutlost_director:other"),1,
                new byte[]{1,2,3,4});
        check(!authentic(refs(foreign.reference()),read(),Map.of(),List.of(claim()),
                Set.of(CAP),KEY,GENERATION,foreign));
        check(!authentic(null,read(),Map.of(),List.of(claim()),Set.of(CAP),
                KEY,GENERATION,BLOB));
        // Physical route must move, not teleport, loop or settle at its start.
        var origin=new BlockPosition(DIM,32,64,48);
        var next=new BlockPosition(DIM,33,64,48);
        var destination=new BlockPosition(DIM,34,65,48);
        var ownerChunk=new ChunkRef(DIM,2,3);
        check(FaunaRouteGeometryFence.valid(List.of(origin,next,destination),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,next,origin),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,destination),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,new BlockPosition(DIM,32,65,48)),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,new BlockPosition(DIM,33,66,48)),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,next),DIM,new ChunkRef(DIM,9,9)));
        check(!FaunaRouteGeometryFence.valid(List.of(origin,new BlockPosition(new DimensionId("minecraft:the_nether"),33,64,48)),DIM,ownerChunk));
        check(!FaunaRouteGeometryFence.valid(List.of(new BlockPosition(DIM,Integer.MIN_VALUE,64,48),
                new BlockPosition(DIM,Integer.MAX_VALUE,64,48)),DIM,new ChunkRef(DIM,Integer.MIN_VALUE>>4,3)));
        var owner=new EntityOwnershipRecord(new AggregateHeader(1,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new Revision(1),new Sequence(1),new Tick(1)),
                new OwnerToken(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                UUID.fromString("00000000-0000-0000-0000-000000000123"),
                new EventInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                new CohortId(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                "slot",EntityPhase.PRESENT,OwnershipDisposition.DIRECTOR_ACTIVE,
                ownerChunk,new Tick(1),ArrivalPolicy.SETTLE_WORLD,
                CleanupReason.COMPLETED,Optional.empty(),GENERATION);
        check(new FaunaNavigationBinding.Route(owner,List.of(origin,next),0.4).cells().size()==2);
        // The native binding must use a CLOSED api.model record for the J04
        // codec: deserializing a binding-nested record is forbidden by ModelCodec.
        var codec=new fr.proutlost.director.api.codec.ModelCodec<>(FaunaNavigationRoute.class);
        var wire=new FaunaNavigationRoute(owner,List.of(origin,next),0.4);
        var bytes=codec.encode(wire);
        check(codec.decode(bytes).equals(wire));
        check(Arrays.equals(codec.encode(codec.decode(bytes)),bytes));
        rejected(()->new FaunaNavigationRoute(owner,List.of(origin),0.4));
        rejected(()->new FaunaNavigationBinding.Route(owner,List.of(origin),0.4));
        rejected(()->new FaunaNavigationBinding.Route(owner,List.of(origin,next,origin),0.4));
        rejected(()->new FaunaNavigationBinding.Route(owner,List.of(origin,next),Double.NaN));
        // A foreign, dead or wrong-species entity cannot turn a recovered
        // movement into a falsely attributed player override.
        var adopted=new EntityOwnershipRecord(new AggregateHeader(1,
                owner.header().id(),owner.header().revision().plus(1),
                owner.header().lastSequence(),new Tick(2)),
                owner.ownerToken(),owner.entityUuid(),owner.instanceId(),
                owner.cohortId(),owner.slotId(),EntityPhase.ADOPTED,
                OwnershipDisposition.YIELDED_TO_PLAYER,owner.lastKnownChunk(),
                new Tick(2),owner.persistMode(),owner.cleanupPolicy(),
                Optional.of(new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000005"))),
                owner.generation().plus(1));
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.of(adopted),true)
                ==ReconcileStatus.PLAYER_OVERRIDE);
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.of(adopted),false)
                ==ReconcileStatus.AMBIGUOUS);
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.empty(),true)
                ==ReconcileStatus.AMBIGUOUS);
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.of(owner),true)
                ==ReconcileStatus.AMBIGUOUS);
        var foreignOwner=new EntityOwnershipRecord(adopted.header(),new OwnerToken(
                UUID.fromString("00000000-0000-0000-0000-000000000099")),
                adopted.entityUuid(),adopted.instanceId(),adopted.cohortId(),
                adopted.slotId(),adopted.phase(),adopted.disposition(),
                adopted.lastKnownChunk(),adopted.lastSeenTick(),
                adopted.persistMode(),adopted.cleanupPolicy(),
                adopted.adoptedBy(),adopted.generation());
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.of(foreignOwner),true)
                ==ReconcileStatus.AMBIGUOUS);
        var stale=new EntityOwnershipRecord(adopted.header(),adopted.ownerToken(),
                adopted.entityUuid(),adopted.instanceId(),adopted.cohortId(),
                adopted.slotId(),adopted.phase(),adopted.disposition(),
                adopted.lastKnownChunk(),adopted.lastSeenTick(),
                adopted.persistMode(),adopted.cleanupPolicy(),
                adopted.adoptedBy(),owner.generation());
        check(FaunaNavigationBinding.classifyRecovery(owner,Optional.of(stale),true)
                ==ReconcileStatus.AMBIGUOUS);
        System.out.println("DIRECTOR_FAUNA_NAVIGATION_WAL_FENCE_PASS assertions="+assertions);
    }
}
