package fr.proutlost.integration.contra;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.runtime.PhysicalSiteExecutionGuards;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure DTO/dispatch-index tests. Unit doubles here do not qualify J04, J05 permits or Minecraft. */
class PhysicalSitePinTest {
    record Fixture(WorldId world,StoreSession session,ExecutionPlan plan,PhysicalSiteOffer offer) {}
    private static Fixture fixture(){
        var world=new WorldId(UUID.randomUUID());var session=new StoreSession(world,new Epoch(1),new Epoch(3),new SessionId(UUID.randomUUID()));
        var source=BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,"source".getBytes(StandardCharsets.UTF_8));
        var key=new PhysicalCatalogModels.Key(world,new ResourceId("test:source"),new ResourceId("test:build"),"revision_a",
            BlobContent.hash(new byte[]{1}),source.reference().hash(),BlobContent.hash(new byte[]{2}));
        var readback=new PhysicalCatalogModels.Readback(key,session,new Sequence(9),source.reference(),BlobContent.hash(new byte[]{3}),"PURE_UNIT_METADATA");
        var def=new DefinitionRef(new DefinitionId("test:event"),new Revision(1),BlobContent.hash(new byte[]{4}));
        var candidate=new CandidateId(UUID.randomUUID());var event=new EventInstanceId(UUID.randomUUID());var operation=new OperationToken(UUID.randomUUID());
        var dim=new DimensionId("minecraft:overworld");var footprint=new Footprint(dim,Set.of(),Set.of(new BlockPosition(dim,2,65,2)),List.of());
        var intent=new EffectIntent(operation,new PrimitiveId("test:mist"),1,new LocalWeather("witness",new ResourceId("test:mist")),footprint,event);
        var roles=new RoleAssignments(Map.of("witness",List.of(new PlayerId(UUID.randomUUID()))));
        var plan=new ExecutionPlan(candidate,def,List.of(intent),roles,new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),new ReadSet(Map.of(),Map.of(),Map.of()));
        var need=BlobContent.capture(PhysicalSitePin.NEED_CODEC,1,"{}".getBytes(StandardCharsets.UTF_8));
        var selection=BlobContent.capture(PhysicalSitePin.SELECTION_CODEC,1,"[]".getBytes(StandardCharsets.UTF_8));
        var draft=new PhysicalSiteSelection(readback,def,candidate,event,need,selection,
            Set.of(new LocationId(UUID.randomUUID())),Set.of(),Set.of(),Map.of(operation,footprint));
        return new Fixture(world,session,plan,draft.pin(plan));
    }
    @Test void changingArgumentsWithTheSameOperationAndFootprintChangesTheExactPinnedPlan(){
        var f=fixture();f.offer().pin().requirePlan(f.plan());var original=f.plan().effects().getFirst();
        var changed=new EffectIntent(original.operationToken(),original.primitive(),original.version(),
            new LocalWeather("witness",new ResourceId("test:another")),original.expectedFootprint(),original.instanceId());
        var plan=new ExecutionPlan(f.plan().candidateId(),f.plan().definition(),List.of(changed),f.plan().roles(),f.plan().scopes(),f.plan().readSet());
        assertThrows(IllegalArgumentException.class,()->f.offer().pin().requirePlan(plan));
    }
    @Test void ancillaryPinRejectsAnExtraForeignBlobAndCannotSupplyBytesForAnotherReference(){
        var f=fixture();var blobs=new ArrayList<>(f.offer().blobs());blobs.add(BlobContent.capture(new ResourceId("test:extra"),1,new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->new PhysicalSiteOffer(f.offer().pin(),blobs));
        assertThrows(IllegalArgumentException.class,()->new PhysicalSiteOffer(f.offer().pin(),List.of(f.offer().blobs().getFirst(),f.offer().blobs().getFirst())));
    }
    private static final class Resolver implements PhysicalSiteResolverPort {
        final AtomicBoolean allowed=new AtomicBoolean(true);final AtomicInteger checks=new AtomicInteger();
        public boolean requires(DefinitionRef definition){return true;}
        public Optional<PhysicalSiteSelection> resolve(Candidate candidate,DecisionInput input,EventInstanceId event,List<PreparedEffect> preview){throw new UnsupportedOperationException("pure unit double");}
        public void discard(PhysicalSiteSelection selection){}
        public void restore(PhysicalSiteOffer offer){throw new UnsupportedOperationException("pure unit double");}
        public void requireCurrent(PhysicalSitePin pin,ExecutionPlan plan,Optional<EffectIntent> effect){checks.incrementAndGet();pin.requirePlan(plan);if(!allowed.get())throw new IllegalStateException("current policy revoked");}
        public void retire(PhysicalSitePin pin){}
    }
    private static final class Journal implements PhysicalSitePinJournalPort {
        final AtomicBoolean readable=new AtomicBoolean(true);
        public State ensurePinned(PhysicalSiteOffer offer){return readable.get()?State.DURABLE:State.FAILED_SAFE;}
        public boolean durable(PhysicalSitePin pin){return readable.get();}
        public void bindForward(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver){}
        public void retire(PhysicalSitePin pin){}
    }
    @Test void actualDispatchIndexRechecksRevokedPoliciesAndDurableReadbackAtEveryForwardCall(){
        var f=fixture();var resolver=new Resolver();var journal=new Journal();var owner=PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{});
        try{
            owner.bind(f.offer().pin(),f.plan(),resolver,journal);owner.recoveryComplete();var intent=f.plan().effects().getFirst();
            PhysicalSiteExecutionGuards.requireForward(f.world(),intent);assertTrue(resolver.checks.get()>=2);
            resolver.allowed.set(false);assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),intent));
            resolver.allowed.set(true);journal.readable.set(false);assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),intent));
        }finally{owner.close();}
    }
    @Test void unknownForwardOperationNeverMasqueradesAsCleanupAndManualInverseNeedsLiveOwnership(){
        var f=fixture();var resolver=new Resolver();var journal=new Journal();var owner=PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{});
        try{
            owner.bind(f.offer().pin(),f.plan(),resolver,journal);owner.recoveryComplete();var original=f.plan().effects().getFirst();
            var inverse=new EffectIntent(new OperationToken(UUID.randomUUID()),original.primitive(),1,new LocalWeather("witness",new ResourceId("test:clear")),original.expectedFootprint(),original.instanceId());
            assertThrows(IllegalArgumentException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),inverse));
            var owned=new AtomicBoolean(true);owner.registerInverse(inverse,owned::get);resolver.allowed.set(false);
            PhysicalSiteExecutionGuards.requireForward(f.world(),inverse);owned.set(false);
            assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),inverse));
        }finally{owner.close();}
    }
    @Test void historicalCleanupGuardDoesNotRequireTheCurrentSelectionToRemainEligibleButKeepsItsFootprint(){
        var f=fixture();var resolver=new Resolver();var journal=new Journal();var owner=PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{});
        try{
            owner.bind(f.offer().pin(),f.plan(),resolver,journal);owner.recoveryComplete();resolver.allowed.set(false);var original=f.plan().effects().getFirst();
            PhysicalSiteExecutionGuards.requireHistorical(f.world(),original); // No world method or permit is invoked by this metadata-only call.
            var dim=original.expectedFootprint().dimension();var outside=new Footprint(dim,Set.of(),Set.of(new BlockPosition(dim,999,65,999)),List.of());
            var escaped=new EffectIntent(original.operationToken(),original.primitive(),1,original.typedArgs(),outside,original.instanceId());
            assertThrows(IllegalArgumentException.class,()->PhysicalSiteExecutionGuards.requireHistorical(f.world(),escaped));
        }finally{owner.close();}
    }
    @Test void aLiveOwnerCannotBeReplacedAndCloseFencesFurtherDispatch(){
        var f=fixture();var owner=PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{});
        assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{}));owner.close();
        assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),f.plan().effects().getFirst()));
    }
    @Test void recoveryNeverTreatsAnUnknownEventAsAnUnpinnedLegacyEventBeforeTheIndexIsComplete(){
        var f=fixture();var owner=PhysicalSiteExecutionGuards.open(f.world(),f.session(),()->{});
        try{assertThrows(IllegalStateException.class,()->PhysicalSiteExecutionGuards.requireForward(f.world(),f.plan().effects().getFirst()));
            owner.recoveryComplete();PhysicalSiteExecutionGuards.requireForward(f.world(),f.plan().effects().getFirst());
        }finally{owner.close();}
    }
    @Test void forcedPlanBytesReconstructTheExactOriginalExecutionPlanWithoutGuessing(){
        var f=fixture();assertEquals(3,f.offer().blobs().size());assertEquals(f.plan(),f.offer().plan());
        assertEquals(f.offer().pin().planHash(),f.offer().planBlob().reference().hash());
    }
}
