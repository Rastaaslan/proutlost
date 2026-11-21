package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.adapter.neoforge.server.effects.WeatherBinding;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;

/**
 * Standalone NeoForge native J05 weather primitive qualification:
 * Test-only WorldId/ownership -> J04 WAL PREPARE fsync -> permit ->
 * actual vanilla weather mutation -> J04 RESULT fsync and WAL reopen.
 *
 * Does NOT qualify production weather leasing, WARN, physical catastrophe
 * admission or ownership-safe inverse. Those require separate G4/G5 gates.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWeatherLeaseGameTests {
    private DirectorNativeWeatherLeaseGameTests() {}
    private static final StoreOptions OPTIONS=new StoreOptions(
        1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

    private static final class Probe {
        final ServerLevel level;
        final ServerLevelData data;
        final net.minecraft.server.MinecraftServer server;
        final int clearBefore,rainBefore,thunderBefore;
        final boolean rainingBefore,thunderingBefore,cycleBefore;
        final WorldId world=new WorldId(UUID.randomUUID());
        final DimensionId dimension;
        final ResourceKey key;
        final AtomicReference<Revision> external=new AtomicReference<>(new Revision(1));
        final OwnershipRecord owner;
        final OwnershipView ownership;
        final AtomicReference<OwnershipView> liveOwnership=new AtomicReference<>();
        final Footprint footprint;
        final EffectIntent intent;
        final WeatherBinding binding;
        final java.nio.file.Path root;
        final CompletableFuture<JournalStore> opening;
        JournalStore store;
        CompletableFuture<CommitAck> initial;
        CompletableFuture<Void> closing;
        CompletableFuture<JournalStore> reopened;
        EffectPreparation captured;
        PreparedEffect prepared;
        PrepareRecord prepare;
        ReservationToken reservation,inverseReservation;
        ResourceArbiter arbiter;
        CommitPermit.Authority authority;
        ServerEffectPort port;
        EventRunner runner;
        LiveValidationSnapshot live;
        PrepareRecord inversePrepare;
        BlobContent originalWeatherBlob;
        CompletableFuture<CommitAck> releasedAck;
        int stage;

        Probe(GameTestHelper helper) {
            level=helper.getLevel();
            server=Objects.requireNonNull(level.getServer());
            if(!server.isSameThread())throw new IllegalStateException("server actor");
            data=(ServerLevelData)level.getLevelData();
            clearBefore=data.getClearWeatherTime();
            rainBefore=data.getRainTime();
            thunderBefore=data.getThunderTime();
            rainingBefore=data.isRaining();
            thunderingBefore=data.isThundering();
            cycleBefore=level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
            // Only the disposable native GameTest world's weather rule is
            // frozen. No J05 effect is committed by this fixture setup.
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE)
                .set(false,server);
            level.setWeatherParameters(0,0,false,false);
            data.setThunderTime(0);
            dimension=new DimensionId(level.dimension().location().toString());
            key=new ResourceKey(OwnershipDomain.WEATHER,dimension,"weather");
            var ownerHash=BlobContent.capture(
                new ResourceId("proutlost_director:weather_fixture_owner"),
                1,new byte[]{1}).reference().hash();
            owner=new OwnershipRecord(new AggregateHeader(
                1,UUID.randomUUID(),new Revision(1),new Sequence(0),
                new Tick(1)),key,Set.of(new OwnerToken(UUID.randomUUID())),
                new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
                new MutationId(UUID.randomUUID()),ownerHash,new Epoch(0),
                Optional.empty());
            ownership=new OwnershipView(Map.of(key,owner));
            liveOwnership.set(ownership);
            footprint=new Footprint(dimension,Set.of(),Set.of(),List.of());
            intent=new EffectIntent(new OperationToken(UUID.randomUUID()),
                new PrimitiveId("proutlost:global_weather_lease"),1,
                new GlobalWeatherLease(dimension,new ResourceId("minecraft:rain"),
                    new DurationTicks(4000),new Revision(1)),
                footprint,new EventInstanceId(UUID.randomUUID()));
            binding=new WeatherBinding(dim->level,liveOwnership::get,dim->external.get(),8);
            root=server.getWorldPath(LevelResource.ROOT).resolve(
                "director-j05-weather-test-only").resolve(UUID.randomUUID().toString());
            opening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
        }

        void restoreFixture() {
            level.setWeatherParameters(clearBefore,rainBefore,
                rainingBefore,thunderingBefore);
            data.setThunderTime(thunderBefore);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE)
                .set(cycleBefore,server);
        }

        /** Test-only independent inverse with its own J04 WAL PREPARE. */
        void startInverse(GameTestHelper helper) {
            helper.assertTrue(arbiter.release(reservation),
                "forward weather claim still held after durable RESULT");
            var inverse=new EffectIntent(new OperationToken(UUID.randomUUID()),
                intent.primitive(),1,new GlobalWeatherLease(dimension,
                    new ResourceId("proutlost_director:restore"),
                    new DurationTicks(4000),external.get()),
                footprint,intent.instanceId());
            var wrongVersion=BlobContent.capture(
                originalWeatherBlob.reference().codecId(),2,originalWeatherBlob.bytes());
            boolean refusedVersion=false;
            try {
                binding.registerRestore(inverse,wrongVersion);
            } catch(IllegalArgumentException expected) {
                refusedVersion=true;
            }
            helper.assertTrue(refusedVersion,
                "J05 inverse accepted weather blob with unsupported codec version");
            binding.registerRestore(inverse,originalWeatherBlob);
            var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
            var scopes=new ScopeSet(world,Set.of(worldScope));
            var now=new Tick(130);
            var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),
                scopes,now,Map.of(worldScope,new Revision(1)),
                new ObservationContext(Map.of(worldScope,new ScopeSnapshot(
                    worldScope,new Revision(1),Set.of(),Set.of(),Map.of())),
                    Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),
                    Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
            var planning=new PlanningSnapshot(snapshot,ownership,
                new BudgetView(new BudgetVector(
                    100,100,100,100,100,100,100,100,100,100),
                    BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
            var capturedInverse=binding.capture(inverse,planning);
            helper.assertTrue(capturedInverse.validation().valid() &&
                capturedInverse.prepared().isPresent(),
                "J05 weather inverse not capturable");
            var expected=capturedInverse.prepared().orElseThrow();
            helper.assertTrue(expected.expectedPost().get(key).equals(
                originalWeatherBlob.reference().hash()),
                "J05 inverse did not restore exact captured prestate");
            var candidate=new ResourceArbiter.Candidate(
                new ReservationId(UUID.randomUUID()),inverse.instanceId(),
                new ResourceArbiter.Rank(false,false,false,
                    Priority.AMBIENT,0,1.0,44),
                new Tick(900),expected.claims(),
                Map.of(worldScope,expected.budgetEstimate()));
            var decisions=arbiter.arbitrate(List.of(candidate),new Tick(26),
                Map.of(key,owner.epoch()),Map.of(worldScope,
                    new BudgetVector(100,100,100,100,100,100,100,100,100,100)));
            helper.assertTrue(decisions.size()==1 &&
                decisions.getFirst().accepted().isPresent(),
                "J05 inverse claim not reserved");
            inverseReservation=decisions.getFirst().accepted().orElseThrow();
            var inverseSlice=new PreparedSlice(new SliceId(UUID.randomUUID()),
                inverse.operationToken(),footprint,expected.preconditions(),
                List.of(inverse),expected.budgetEstimate());
            inversePrepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),
                inverse.operationToken(),inverseReservation.generation(),
                expected.preconditions(),new Tick(900),inverseSlice,
                inverseReservation,capturedInverse.validation(),
                List.of(),Set.of(),List.of(),expected.durableBlobs());
            live=new LiveValidationSnapshot(expected.preconditions(),
                Set.of(),HealthMode.NORMAL,new Tick(26),
                inverseReservation.generation());
            port.hydrate(expected,capturedInverse.blobs());
            var durable=new DurableBatch(inversePrepare.transactionId(),
                store.session().storeGeneration(),
                inversePrepare.readSet().revisions(),
                List.of(new DurablePrepare(inversePrepare)),
                capturedInverse.blobs().contents(),Optional.empty(),
                CommitPermit.Authority.prepareHash(inversePrepare));
            runner.submit(durable,expected.expectedPost(),live,actual->
                ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                    store.session().storeGeneration(),Map.of(),
                    List.of(new DurableResult(new ResultRecord(
                        inversePrepare.transactionId(),
                        inversePrepare.operationToken(),actual,Set.of(),
                        List.of(),List.of()))),List.of()));
            helper.assertTrue(data.isRaining(),
                "weather inverse preceded its durable PREPARE");
        }

        void advance(GameTestHelper helper) {
            if(!server.isSameThread())throw new IllegalStateException("J05 actor");
            switch(stage) {
                case 0 -> {
                    helper.assertTrue(opening.isDone()&&!opening.isCompletedExceptionally(),
                        "J05 weather WAL open pending");
                    store=opening.getNow(null);
                    helper.assertTrue(store!=null&&!store.readOnlySafe(),
                        "J04 lab writer failed");
                    var now=new Tick(100);
                    var state=new DirectorWorldState(
                        new AggregateHeader(1,world.value(),new Revision(1),
                            new Sequence(0),now),
                        world,store.session().storeGeneration(),now,
                        RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
                        PacingState.REST,GlobalTension.CALM,
                        new ResourceId("proutlost:lab_weather"),
                        BlobContent.capture(new ResourceId("proutlost:hash_catalog"),
                            1,new byte[]{3}).reference().hash(),
                        BlobContent.capture(new ResourceId("proutlost:hash_config"),
                            1,new byte[]{4}).reference().hash(),
                        store.session().bootEpoch(),new Sequence(0),
                        new EngineControlState(1,new Revision(0),now,now,
                            new UnitValue(0),now,Optional.empty(),new UnitValue(.5)));
                    initial=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableAggregate(new WorldAggregate(state)),
                            new DurableOwnership(owner)),List.of())).toCompletableFuture();
                    stage=1;
                }
                case 1 -> {
                    helper.assertTrue(initial.isDone()&&!initial.isCompletedExceptionally() &&
                        initial.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "J05 weather ownership/WORLD seed not durable");
                    var now=new Tick(110);
                    var worldScope=new ScopeRef(ScopeKind.WORLD,world.value());
                    var scopes=new ScopeSet(world,Set.of(worldScope));
                    var snapshot=new SnapshotBatch(
                        new RequestId(UUID.randomUUID()),scopes,now,
                        Map.of(worldScope,new Revision(1)),
                        new ObservationContext(Map.of(worldScope,new ScopeSnapshot(
                            worldScope,new Revision(1),Set.of(),Set.of(),Map.of())),
                            Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
                            List.of(),Map.of()),
                        Set.of(),Set.of(),HealthMode.NORMAL);
                    var planning=new PlanningSnapshot(snapshot,ownership,
                        new BudgetView(new BudgetVector(
                            100,100,100,100,100,100,100,100,100,100),
                            BudgetMath.zero(),Map.of()),
                        new BlobBundle(List.of()));
                    captured=binding.capture(intent,planning);
                    helper.assertTrue(captured.validation().valid()&&
                        captured.prepared().isPresent(),
                        "J05 vanilla global weather not safely capturable");
                    prepared=captured.prepared().orElseThrow();
                    var preBlobRef=prepared.durableBlobs().snapshots().stream()
                        .filter(ref->ref.codecId().equals(new ResourceId("proutlost_director:weather_state")))
                        .findFirst().orElseThrow();
                    originalWeatherBlob=captured.blobs().contents().stream()
                        .filter(b->b.reference().equals(preBlobRef))
                        .findFirst().orElseThrow();
                    helper.assertTrue(binding.revalidate(prepared,captured.blobs()) &&
                        !data.isRaining(),
                        "prestate not stable before PREPARE");
                    // A token rotated without an epoch increment must still
                    // invalidate the exact sealed J04 owner before any effect.
                    var rotated=new OwnershipRecord(owner.header(),key,
                        Set.of(new OwnerToken(UUID.randomUUID())),owner.epoch(),
                        owner.disposition(),owner.creationMutationId(),
                        owner.expectedPostHash(),owner.playerOverrideEpoch(),
                        owner.exceptionContractId());
                    liveOwnership.set(new OwnershipView(Map.of(key,rotated)));
                    helper.assertTrue(!binding.revalidate(prepared,captured.blobs()),
                        "weather accepted a rotated owner under the same epoch");
                    liveOwnership.set(ownership);
                    helper.assertTrue(binding.revalidate(prepared,captured.blobs()),
                        "weather failed to recover exact original owner");
                    System.out.println("DIRECTOR_NATIVE_J05_WEATHER_CAPTURED_OWNER_CAS_PASS");
                    external.set(new Revision(2));
                    helper.assertTrue(!binding.revalidate(prepared,captured.blobs()),
                        "external weather revision drift not rejected");
                    external.set(new Revision(1));
                    arbiter=new ResourceArbiter(world,new Epoch(0),1000,4,
                        new ResourceArbiter.Compatibility(){
                            public boolean share(ResourceClaim a,ResourceClaim b){
                                return false;
                            }
                            public boolean merge(ResourceId id,ResourceClaim a,
                                                 ResourceClaim b){return false;}
                        });
                    var selection=new ResourceArbiter.Candidate(
                        new ReservationId(UUID.randomUUID()),intent.instanceId(),
                        new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,
                            0,1.0,42),new Tick(800),prepared.claims(),
                        Map.of(worldScope,prepared.budgetEstimate()));
                    var decisions=arbiter.arbitrate(List.of(selection),new Tick(25),
                        Map.of(key,owner.epoch()),
                        Map.of(worldScope,new BudgetVector(
                            100,100,100,100,100,100,100,100,100,100)));
                    helper.assertTrue(decisions.size()==1 &&
                        decisions.getFirst().accepted().isPresent(),
                        "J05 weather claim refused");
                    reservation=decisions.getFirst().accepted().orElseThrow();
                    var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),
                        intent.operationToken(),footprint,prepared.preconditions(),
                        List.of(intent),prepared.budgetEstimate());
                    prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),
                        intent.operationToken(),reservation.generation(),
                        prepared.preconditions(),new Tick(800),slice,reservation,
                        captured.validation(),List.of(),Set.of(),List.of(),
                        prepared.durableBlobs());
                    live=new LiveValidationSnapshot(prepared.preconditions(),
                        Set.of(),HealthMode.NORMAL,new Tick(25),
                        reservation.generation());
                    authority=new CommitPermit.Authority(store,4);
                    port=new ServerEffectPort(authority,
                        i->{throw new IllegalStateException("already captured");},
                        sliceArg->live,Map.of(intent.primitive(),binding),
                        binding.capabilities(),4,
                        sliceArg->new StabilizationLiveSnapshot(
                            store.session(),live,ownership),
                        marker->{},(marker,result)->{});
                    // A valid extra blob has a real hash, but no J04 PREPARE
                    // reference: native dispatch must reject it BEFORE any
                    // physical permit or vanilla weather mutation.
                    var extra=BlobContent.capture(
                        new ResourceId("proutlost_director:unjournaled_weather"),
                        1,new byte[]{42,21});
                    var unsealed=new ArrayList<BlobContent>(captured.blobs().contents());
                    unsealed.add(extra);
                    boolean rejectedExtra=false;
                    try {
                        port.hydrate(prepared,new BlobBundle(unsealed));
                    } catch(IllegalArgumentException expected) {
                        rejectedExtra=true;
                    }
                    helper.assertTrue(rejectedExtra && !data.isRaining(),
                        "J05 unjournaled physical blob reached weather adapter");
                    System.out.println("DIRECTOR_NATIVE_J05_EXACT_PREPARE_BLOBS_GUARD_PASS");
                    port.hydrate(prepared,captured.blobs());
                    runner=new EventRunner(authority,store,port,arbiter,4);
                    var durable=new DurableBatch(prepare.transactionId(),
                        store.session().storeGeneration(),
                        prepare.readSet().revisions(),
                        List.of(new DurablePrepare(prepare)),
                        captured.blobs().contents(),Optional.empty(),
                        CommitPermit.Authority.prepareHash(prepare));
                    runner.submit(durable,prepared.expectedPost(),live,actual->
                        ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                            store.session().storeGeneration(),Map.of(),
                            List.of(new DurableResult(new ResultRecord(
                                prepare.transactionId(),prepare.operationToken(),
                                actual,Set.of(),List.of(),List.of()))),List.of()));
                    helper.assertTrue(!data.isRaining(),
                        "weather changed before WAL PREPARE fsync");
                    stage=2;
                }
                case 2 -> {
                    var result=runner.poll(prepare.transactionId(),live);
                    helper.assertTrue(result.stage()!=EventRunner.Stage.REJECTED &&
                        result.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,
                        "J05 WAL-before-weather failed: "+result.failure());
                    if(result.stage()!=EventRunner.Stage.COMMITTED)return;
                    helper.assertTrue(data.isRaining() &&
                        !data.isThundering() &&
                        result.result().orElseThrow().outcome()==Outcome.SUCCEEDED,
                        "actual vanilla rain not committed after WAL permit");
                    System.out.println(
                        "DIRECTOR_NATIVE_J05_WEATHER_AFTER_WAL_PREPARE_RESULT_PASS");
                    // A genuine durable owner token can prove SAME_POST, but
                    // fabricated evidence with another token must NOT.
                    var weatherPost=prepared.expectedPost().get(key);
                    var realOwner=owner.ownerTokens().iterator().next();
                    var genuineProof=new RecoveryEvidence(key,
                        EvidenceStatus.CONFIRMED,Optional.of(weatherPost),
                        Optional.of(realOwner),owner.epoch(),
                        owner.playerOverrideEpoch(),false);
                    var fakeOwner=new OwnerToken(UUID.randomUUID());
                    var forgedProof=new RecoveryEvidence(key,
                        EvidenceStatus.CONFIRMED,Optional.of(weatherPost),
                        Optional.of(fakeOwner),owner.epoch(),
                        owner.playerOverrideEpoch(),false);
                    helper.assertTrue(binding.reconcile(new RecoveryProbe(
                        prepared,List.of(genuineProof),captured.blobs())).status()
                        ==ReconcileStatus.SAME_POST,
                        "J05 genuine owner proof failed to reconcile actual rain");
                    var rotatedDuringRecovery=new OwnershipRecord(owner.header(),key,
                        Set.of(new OwnerToken(UUID.randomUUID())),owner.epoch(),
                        owner.disposition(),owner.creationMutationId(),
                        owner.expectedPostHash(),owner.playerOverrideEpoch(),
                        owner.exceptionContractId());
                    liveOwnership.set(new OwnershipView(Map.of(key,rotatedDuringRecovery)));
                    try {
                        helper.assertTrue(binding.reconcile(new RecoveryProbe(
                            prepared,List.of(genuineProof),captured.blobs())).status()
                            ==ReconcileStatus.AMBIGUOUS,
                            "J05 recovery used a stale sealed owner after token rotation");
                    } finally { liveOwnership.set(ownership); }
                    System.out.println("DIRECTOR_NATIVE_J05_WEATHER_OWNER_RECOVERY_FENCE_PASS");
                    helper.assertTrue(binding.reconcile(new RecoveryProbe(
                        prepared,List.of(forgedProof),captured.blobs())).status()
                        ==ReconcileStatus.AMBIGUOUS,
                        "J05 weather accepted fabricated owner proof");
                    // An authentic proof first must never conceal a conflicting
                    // second proof (or become authoritative based on ordering).
                    for(var proofs:List.of(List.of(genuineProof,forgedProof),
                            List.of(forgedProof,genuineProof))) {
                        var multiple=binding.reconcile(new RecoveryProbe(
                            prepared,proofs,captured.blobs()));
                        helper.assertTrue(multiple.status()==ReconcileStatus.AMBIGUOUS
                                && multiple.proofs().size()==2,
                            "J05 conflicting weather proofs selected by list order");
                    }
                    System.out.println(
                        "DIRECTOR_NATIVE_J05_WEATHER_FOREIGN_OWNER_RECOVERY_REJECTED_PASS");
                    startInverse(helper);
                    stage=7;
                }
                case 3 -> {
                    helper.assertTrue(closing.isDone()&&!closing.isCompletedExceptionally(),
                        "J05 weather J04 close pending");
                    reopened=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
                    stage=4;
                }
                case 4 -> {
                    helper.assertTrue(reopened.isDone()&&!reopened.isCompletedExceptionally(),
                        "J05 weather WAL reopen pending");
                    store=reopened.getNow(null);
                    var rows=store.view(new StoreQuery(world,Set.of(),
                        new Sequence(0),Optional.empty(),32));
                    helper.assertTrue(rows.status()==ReadStatus.AVAILABLE &&
                        rows.page().orElseThrow().records().stream()
                            .filter(x->x instanceof DurablePrepare).count()==2 &&
                        rows.page().orElseThrow().records().stream()
                            .filter(x->x instanceof DurableResult).count()==2 &&
                        rows.page().orElseThrow().records().stream()
                            .filter(x->x instanceof DurableOwnership own &&
                                own.value().disposition()==OwnershipDisposition.RELEASED).count()==1,
                        "J05 weather effect/inverse PREPARE RESULT RELEASED not recovered");
                    closing=store.closeAsync().toCompletableFuture();
                    stage=5;
                }
                case 5 -> {
                    helper.assertTrue(closing.isDone()&&!closing.isCompletedExceptionally(),
                        "J05 weather J04 final close pending");
                    restoreFixture();
                    stage=6;
                    System.out.println(
                        "DIRECTOR_NATIVE_J05_WEATHER_J04_WAL_REOPEN_TEST_ONLY_PASS");
                }
                case 6 -> {}
                case 7 -> {
                    var result=runner.poll(inversePrepare.transactionId(),live);
                    helper.assertTrue(result.stage()!=EventRunner.Stage.REJECTED &&
                        result.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,
                        "J05 weather inverse WAL rejected: "+result.failure());
                    if(result.stage()!=EventRunner.Stage.COMMITTED)return;
                    helper.assertTrue(!data.isRaining() && !data.isThundering() &&
                        result.result().orElseThrow().outcome()==Outcome.SUCCEEDED,
                        "J05 weather inverse did not restore original captured clear state");
                    var released=new OwnershipRecord(new AggregateHeader(
                        1,owner.header().id(),new Revision(2),
                        owner.header().lastSequence(),new Tick(150)),
                        key,owner.ownerTokens(),owner.epoch(),
                        OwnershipDisposition.RELEASED,owner.creationMutationId(),
                        originalWeatherBlob.reference().hash(),
                        owner.playerOverrideEpoch(),owner.exceptionContractId());
                    releasedAck=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableOwnership(released)),List.of()))
                        .toCompletableFuture();
                    stage=8;
                    System.out.println(
                        "DIRECTOR_NATIVE_J05_WEATHER_INVERSE_DURABLE_RESULT_PASS");
                }
                case 8 -> {
                    helper.assertTrue(releasedAck.isDone() &&
                        !releasedAck.isCompletedExceptionally() &&
                        releasedAck.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "owned weather inverse RELEASED not durable");
                    helper.assertTrue(arbiter.release(inverseReservation),
                        "J05 weather inverse reservation leaked");
                    closing=store.closeAsync().toCompletableFuture();
                    stage=3;
                }
                default -> throw new IllegalStateException("unknown weather test state");
            }
        }
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=16000)
    public static void realVanillaWeatherOnlyAfterJ04DurablePermit(
            GameTestHelper helper) {
        var probe=new Probe(helper);
        helper.succeedWhen(()->{
            probe.advance(helper);
            helper.assertTrue(probe.stage==6,
                "waiting J05 weather prepare/result/recovery");
        });
    }
}
