package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.FaunaSpawnBinding;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real NeoForge server-actor recovery gate: replay of an owned animal spawn
 * MUST use the immutable J04 PRE/POST UUID pair. This test deliberately does
 * not spawn an animal or qualify physical production admission.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeFaunaSpawnRecoveryGameTests {
    private DirectorNativeFaunaSpawnRecoveryGameTests() {}

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=200)
    public static void replayUsesDurableSpawnSlotNotLiveCallback(GameTestHelper helper) {
        var level=helper.getLevel();
        var dimension=new DimensionId(level.dimension().location().toString());
        var pos=new BlockPosition(dimension,1,70,1);
        var chunk=new ChunkRef(dimension,0,0);
        var world=new WorldId(UUID.randomUUID());
        var scope=new ScopeRef(ScopeKind.WORLD,world.value());
        var instance=new EventInstanceId(UUID.randomUUID());
        var owner=new OwnerToken(UUID.randomUUID());
        var entityId=UUID.randomUUID();
        var cohort=new CohortId(UUID.randomUUID());
        var epoch=new Epoch(1);
        var headerId=UUID.randomUUID();
        var before=new EntityOwnershipRecord(
            new AggregateHeader(1,headerId,new Revision(1),new Sequence(0),new Tick(100)),
            owner,entityId,instance,cohort,"spawn-slot-1",EntityPhase.RESERVED,
            OwnershipDisposition.DIRECTOR_ACTIVE,chunk,new Tick(100),
            ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),epoch);
        var after=new EntityOwnershipRecord(
            new AggregateHeader(1,headerId,new Revision(2),new Sequence(0),new Tick(110)),
            owner,entityId,instance,cohort,"spawn-slot-1",EntityPhase.PRESENT,
            OwnershipDisposition.DIRECTOR_ACTIVE,chunk,new Tick(110),
            ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),epoch);
        var codec=new ModelCodec<>(EntityOwnershipRecord.class);
        var codecId=new ResourceId("proutlost_director:entity_slot");
        var pre=BlobContent.capture(codecId,1,codec.encode(before));
        var post=BlobContent.capture(codecId,1,codec.encode(after));
        var resource=new ResourceKey(OwnershipDomain.ENTITY,dimension,entityId.toString());
        var footprint=new Footprint(dimension,Set.of(chunk),Set.of(pos),List.of());
        var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),
            new PrimitiveId("proutlost:fauna_spawn_owned"),1,
            new FaunaMigrate(new ResourceId("minecraft:cow"),1,
                new TagId("proutlost:safe_corridor"),ArrivalPolicy.SETTLE_WORLD),
            footprint,instance);
        var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),
            new ScopeSet(world,Set.of(scope)),new Tick(110),
            Map.of(scope,new Revision(1)),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),
                Map.of(),Map.of(),List.of(),Map.of()),
            Set.of(),Set.of(chunk),HealthMode.NORMAL);
        var read=new ReadSet(Map.of(scope,new Revision(1)),
            Map.of(resource,epoch),Map.of(resource,pre.reference().hash()));
        var durable=new EffectBlobs(Set.of(pre.reference()),post.reference(),
            pre.reference(),Map.of(resource,pre.reference()),
            Map.of(resource,post.reference()));
        // Recovery must exercise a complete J04 PREPARE, including its
        // exclusive entity claim. An empty claim list is intentionally invalid.
        var claim=new ResourceClaim(resource,ClaimPolicy.EXCLUSIVE,
            BudgetMath.zero(),Optional.empty(),epoch);
        var plan=new PreparedEffect(intent,snapshot,List.of(claim),read,
            Map.of(resource,post.reference().hash()),BudgetMath.zero(),
            List.of(),Set.of(new CapabilityId("entity.spawn_owned_v1")),durable);
        var binding=new FaunaSpawnBinding(d->level,
            ignored->{throw new AssertionError("LIVE_SLOT_PROVIDER_CALLED_ON_J04_REPLAY");},
            ignored->{throw new AssertionError("PHYSICAL_SPAWN_CALLBACK");},
            ()->false,Set.of(new ResourceId("minecraft:cow")));

        var verified=new RecoveryProbe(plan,List.of(),new BlobBundle(List.of(pre,post)));
        helper.assertTrue(binding.reconcile(verified).status()==ReconcileStatus.UNAVAILABLE,
            "original UUID absent must be UNAVAILABLE, not replacement spawn");
        var missingPost=new RecoveryProbe(plan,List.of(),new BlobBundle(List.of(pre)));
        helper.assertTrue(binding.reconcile(missingPost).status()==ReconcileStatus.AMBIGUOUS,
            "missing durable POST blob must be AMBIGUOUS");
        var forgedPlan=new PreparedEffect(intent,snapshot,List.of(claim),read,
            Map.of(resource,pre.reference().hash()),BudgetMath.zero(),
            List.of(),Set.of(new CapabilityId("entity.spawn_owned_v1")),durable);
        helper.assertTrue(binding.reconcile(new RecoveryProbe(forgedPlan,List.of(),
            new BlobBundle(List.of(pre,post)))).status()==ReconcileStatus.AMBIGUOUS,
            "forged POST hash must not reconcile");
        System.out.println("DIRECTOR_NATIVE_J05_FAUNA_SPAWN_J04_CAPTURED_UUID_REPLAY_PASS");
        helper.succeed();
    }
}
