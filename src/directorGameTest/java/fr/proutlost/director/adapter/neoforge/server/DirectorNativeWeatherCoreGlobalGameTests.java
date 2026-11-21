package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.adapter.neoforge.server.effects.WeatherBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.WeatherCoreLeaseState;
import fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreIntegration;
import io.github.steamautomata.weathercore.WeatherCore;
import io.github.steamautomata.weathercore.api.*;
import io.github.steamautomata.weathercore.init.VanillaWeatherRegistry;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
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
 * real WeatherCore manager mutation -> J04 RESULT fsync and private WAL reopen.
 * This is a NEW provider qualification attempt, not a replay of old vanilla tests.
 *
 * Covers the Core adapter's real birth/early inverse, finite expiry and foreign
 * replacement refusal. Does NOT qualify full STORM/WARN, production admission,
 * human audio/graphics, multiplayer or a new-Minecraft-JVM cold recovery.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWeatherCoreGlobalGameTests {
    private DirectorNativeWeatherCoreGlobalGameTests() {}
    private static final StoreOptions OPTIONS=new StoreOptions(
        1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

    private enum Scenario { INVERSE, EXPIRY, FOREIGN }
    private record Before(WeatherType<?> type,UUID uuid,long deadline){}
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
        int stage;final Scenario scenario;final List<Before> beforeCore;
        long coreEnd=-1;UUID foreignGlobal;OwnershipRecord releasedOwner;

        Probe(GameTestHelper helper,Scenario scenario) {
            this.scenario=scenario;
            if(!Boolean.getBoolean("proutlost.director.nativeGameTests")||!Boolean.getBoolean("proutlost.director.nativeLab"))throw new IllegalStateException("explicit isolated native QA opt-ins required");
            if(!ModList.get().isLoaded("weathercore"))throw new IllegalStateException("real Core provider required");
            level=helper.getLevel();
            server=Objects.requireNonNull(level.getServer());
            if(!server.isSameThread())throw new IllegalStateException("server actor");
            if(!Boolean.getBoolean("proutlost.quest.neutralLab"))throw new IllegalStateException("neutral TEST_ONLY world required");
            try {
                var actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
                var configured=java.nio.file.Path.of(System.getProperty("proutlost.quest.neutralLabRoot","")).toRealPath();
                var marker=actual.resolve("TEST_ONLY");
                if(!actual.equals(configured)||java.nio.file.Files.isSymbolicLink(marker)
                    ||!java.nio.file.Files.isRegularFile(marker,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ||java.nio.file.Files.size(marker)>128)
                    throw new IllegalStateException("owned exact TEST_ONLY world required");
                try(var in=java.nio.file.Files.newInputStream(marker,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                    var bytes=in.readNBytes(129);
                    if(!new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim().equals("PROUTLOST_NEUTRAL_QA_V1"))
                        throw new IllegalStateException("owned neutral TEST_ONLY marker mismatch");
                }
            }catch(java.io.IOException invalid){throw new IllegalStateException("neutral TEST_ONLY world unreadable",invalid);}
            data=(ServerLevelData)level.getLevelData();
            clearBefore=data.getClearWeatherTime();
            rainBefore=data.getRainTime();
            thunderBefore=data.getThunderTime();
            rainingBefore=data.isRaining();
            thunderingBefore=data.isThundering();
            cycleBefore=level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
            if(!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Core global native test requires zero clients");
            var globals=WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global).toList();
            if(globals.isEmpty()||globals.size()>3)throw new IllegalStateException("initialized known Core baseline required");
            beforeCore=globals.stream().map(w->new Before(w.type(),w.uuid(),Math.addExact(level.getGameTime(),w.getTimeLeft()))).toList();
            for(var previous:beforeCore)if(!Set.of("minecraft:clear","minecraft:rain","minecraft:thunder").contains(WeatherCore.WEATHER_REGISTRY.getKey(previous.type()).toString()))
                throw new IllegalStateException("unknown original Core baseline; fixture refused before writes");
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
            // Actual provider setup, never a native /weather substitute. This
            // controlled fixture precedes all measured J04 effects and is retained.
            WeatherManager.get(level).overrideWeather(VanillaWeatherRegistry.CLEAR.get()).setTimeLeft(20000);
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
                    new DurationTicks(scenario==Scenario.EXPIRY?120:4000),new Revision(1)),
                footprint,new EventInstanceId(UUID.randomUUID()));
            binding=new WeatherBinding(dim->level,liveOwnership::get,dim->external.get(),8);
            root=server.getWorldPath(LevelResource.ROOT).resolve(
                "director-weathercore-global-test-only").resolve(UUID.randomUUID().toString());
            opening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
        }

        void restoreFixture() {
            // Real Core baseline, aged on the same native clock. A missing old
            // counterfactual is explicitly refused, not replaced with invented RNG.
            for(var previous:beforeCore)if(previous.deadline()<=level.getGameTime())throw new IllegalStateException("original Core fixture baseline expired; retained for diagnosis");
            boolean first=true;
            for(var previous:beforeCore){
                var weather=first?WeatherManager.get(level).overrideWeather(previous.type()):WeatherManager.get(level).addWeather(previous.type());first=false;
                weather.setSyncUuid(previous.uuid());weather.setTimeLeft(Math.toIntExact(previous.deadline()-level.getGameTime()));
            }
            WeatherManager.get(level).setDirty();
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(cycleBefore,server);
        }
        List<Weather> globals(){return WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global).toList();}
        void releaseCurrent(boolean yielded){
            var observed=binding.observedFingerprint(dimension);
            releasedOwner=new OwnershipRecord(new AggregateHeader(1,owner.header().id(),new Revision(2),owner.header().lastSequence(),new Tick(150)),
                key,owner.ownerTokens(),yielded?owner.epoch().plus(1):owner.epoch(),yielded?OwnershipDisposition.YIELDED_TO_PLAYER:OwnershipDisposition.RELEASED,
                owner.creationMutationId(),observed,yielded?owner.playerOverrideEpoch().plus(1):owner.playerOverrideEpoch(),owner.exceptionContractId());
            releasedAck=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(releasedOwner)),List.of())).toCompletableFuture();
            stage=8;
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
                        .filter(ref->ref.codecId().equals(new ResourceId("proutlost_director:weathercore_global_state_v2")))
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
                    System.out.println("DIRECTOR_NATIVE_CORE_J04_WEATHER_CAPTURED_OWNER_CAS_PASS");
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
                    System.out.println("DIRECTOR_NATIVE_CORE_J04_EXACT_PREPARE_BLOBS_GUARD_PASS");
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
                        "DIRECTOR_NATIVE_CORE_J04_WEATHER_AFTER_WAL_PREPARE_RESULT_PASS");
                    // A genuine durable owner token can prove SAME_POST, but
                    // fabricated evidence with another token must NOT.
                    var weatherPost=binding.observedFingerprint(dimension);
                    helper.assertTrue(weatherPost.equals(prepared.expectedPost().get(key)),"real Core readback differs from sealed J04 post");
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
                    System.out.println("DIRECTOR_NATIVE_CORE_J04_WEATHER_OWNER_RECOVERY_FENCE_PASS");
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
                        "DIRECTOR_NATIVE_CORE_J04_WEATHER_FOREIGN_OWNER_RECOVERY_REJECTED_PASS");
                    if(scenario==Scenario.INVERSE){startInverse(helper);stage=7;}
                    else{
                        helper.assertTrue(arbiter.release(reservation),"Core forward reservation leaked");
                        if(scenario==Scenario.EXPIRY){
                            var rows=WeatherManager.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()).getList("weathers",net.minecraft.nbt.Tag.TAG_COMPOUND);
                            for(int n=0;n<rows.size();n++){
                                var row=rows.getCompound(n);var metadata=row.getCompound("data");
                                if(row.getString("id").equals("proutlost_director:global_weather_lease_marker")&&metadata.hasUUID("operation")&&metadata.getUUID("operation").equals(intent.operationToken().value()))coreEnd=metadata.getLong("end");
                            }
                            helper.assertTrue(coreEnd>level.getGameTime(),"actual Core apply deadline missing");stage=9;
                        }else{
                            var beforeIdentity=WeatherCoreIntegration.globalIdentity(level);
                            var foreign=WeatherManager.get(level).overrideWeather(VanillaWeatherRegistry.RAIN.get());foreignGlobal=foreign.uuid();
                            helper.assertTrue(data.isRaining()&&!data.isThundering(),"same-flags foreign Core setup changed weather profile");
                            helper.assertTrue(!beforeIdentity.equals(WeatherCoreIntegration.globalIdentity(level)),"Core identity witness missed same-flags UUID replacement");stage=10;
                        }
                    }
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
                            .filter(x->x instanceof DurablePrepare).count()==(scenario==Scenario.INVERSE?2:1) &&
                        rows.page().orElseThrow().records().stream()
                            .filter(x->x instanceof DurableResult).count()==(scenario==Scenario.INVERSE?2:1) &&
                        rows.page().orElseThrow().records().stream()
                            .filter(x->x instanceof DurableOwnership own &&
                                Set.of(OwnershipDisposition.RELEASED,OwnershipDisposition.YIELDED_TO_PLAYER).contains(own.value().disposition())).count()==1,
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
                        "DIRECTOR_NATIVE_CORE_J04_WEATHER_J04_WAL_REOPEN_TEST_ONLY_PASS");
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
                    releasedOwner=new OwnershipRecord(new AggregateHeader(
                        1,owner.header().id(),new Revision(2),
                        owner.header().lastSequence(),new Tick(150)),
                        key,owner.ownerTokens(),owner.epoch(),
                        OwnershipDisposition.RELEASED,owner.creationMutationId(),
                        originalWeatherBlob.reference().hash(),
                        owner.playerOverrideEpoch(),owner.exceptionContractId());
                    releasedAck=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableOwnership(releasedOwner)),List.of()))
                        .toCompletableFuture();
                    stage=8;
                    System.out.println(
                        "DIRECTOR_NATIVE_CORE_J04_WEATHER_INVERSE_DURABLE_RESULT_PASS");
                }
                case 8 -> {
                    helper.assertTrue(releasedAck.isDone() &&
                        !releasedAck.isCompletedExceptionally() &&
                        releasedAck.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "owned weather inverse RELEASED not durable");
                    if(inverseReservation!=null)helper.assertTrue(arbiter.release(inverseReservation),"Core inverse reservation leaked");
                    liveOwnership.set(new OwnershipView(Map.of(key,releasedOwner)));
                    binding.releaseReceipt(prepared);
                    closing=store.closeAsync().toCompletableFuture();
                    stage=3;
                }
                case 9 -> {
                    helper.assertTrue(!data.isThundering(),"Core selected unpermitted thunder during/after finite Director lease");
                    if(level.getGameTime()<coreEnd+2)return;
                    helper.assertTrue(!data.isRaining()&&!data.isThundering(),"Core finite expiry did not clear physical rain");
                    helper.assertTrue(binding.expiredByBackend(prepared),"Core expiry lacks exact owner/UUID/actual-clear receipt");
                    var actual=globals();helper.assertTrue(actual.size()==1&&WeatherCore.WEATHER_REGISTRY.getKey(actual.getFirst().type()).toString().equals("minecraft:clear"),"Core expiry did not use actual finite clear fallback");
                    System.out.println("DIRECTOR_NATIVE_CORE_GLOBAL_FINITE_EXPIRY_NO_RANDOM_THUNDER_RECEIPT_PASS");releaseCurrent(false);
                }
                case 10 -> {
                    // At least one native tick has now retired the obsolete cursor.
                    var inverse=new EffectIntent(new OperationToken(UUID.randomUUID()),intent.primitive(),1,
                        new GlobalWeatherLease(dimension,new ResourceId("proutlost_director:restore"),new DurationTicks(4000),external.get()),footprint,intent.instanceId());
                    binding.registerRestore(inverse,originalWeatherBlob);
                    var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),new Tick(200),Map.of(),
                        new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
                    boolean refused=false;
                    try{binding.capture(inverse,new PlanningSnapshot(snapshot,ownership,new BudgetView(new BudgetVector(100,100,100,100,100,100,100,100,100,100),BudgetMath.zero(),Map.of()),new BlobBundle(List.of())));}
                    catch(IllegalStateException|IllegalArgumentException safe){refused=true;}
                    helper.assertTrue(refused,"Core inverse adopted and overwrote a foreign same-flags replacement");
                    helper.assertTrue(globals().size()==1&&globals().getFirst().uuid().equals(foreignGlobal)&&data.isRaining(),"foreign Core weather changed despite refused inverse");
                    System.out.println("DIRECTOR_NATIVE_CORE_FOREIGN_SAME_FLAGS_INVERSE_REFUSED_PASS");releaseCurrent(true);
                }
                default -> throw new IllegalStateException("unknown weather test state");
            }
        }
    }

    private static void run(GameTestHelper helper,Scenario scenario){
        var probe=new Probe(helper,scenario);
        helper.succeedWhen(()->{probe.advance(helper);helper.assertTrue(probe.stage==6,"waiting Core real J04/manager/result/reopen");});
    }
    @net.minecraft.gametest.framework.GameTestGenerator
    public static Collection<net.minecraft.gametest.framework.TestFunction> coreGlobalQualification() {
        if(!Boolean.getBoolean("proutlost.director.nativeWeatherCoreGlobalQualification"))return List.of();
        return List.of(
            new net.minecraft.gametest.framework.TestFunction("p002_weathercore_global",
                "proutlost_director.p002_core_global_birth_inverse_a05","proutlost_director:smoke_empty",
                net.minecraft.world.level.block.Rotation.NONE,2400,0,true,h->run(h,Scenario.INVERSE)),
            new net.minecraft.gametest.framework.TestFunction("p002_weathercore_global",
                "proutlost_director.p002_core_global_finite_expiry_a05","proutlost_director:smoke_empty",
                net.minecraft.world.level.block.Rotation.NONE,2400,0,true,h->run(h,Scenario.EXPIRY)),
            new net.minecraft.gametest.framework.TestFunction("p002_weathercore_global",
                "proutlost_director.p002_core_global_foreign_refusal_a05","proutlost_director:smoke_empty",
                net.minecraft.world.level.block.Rotation.NONE,2400,0,true,h->run(h,Scenario.FOREIGN)));
    }
}
