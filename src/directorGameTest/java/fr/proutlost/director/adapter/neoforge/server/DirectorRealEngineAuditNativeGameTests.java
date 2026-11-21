package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.engine.WorldDirector;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/**
 * Real J03 engine + actual J01/J04 immutable actor inputs -> J04 WAL audit.
 *
 * Explicit TEST_ONLY fixtures: initial PAUSED WorldAggregate (persisted in a
 * new disposable test store) and no qualified J02 event catalogue. The J03
 * decision is genuinely computed by WorldDirector, not a mock DecisionPort.
 * None of these fixtures may serve as a PRODUCTION catalogue, runtime world
 * authority or executable selection. There is no J05 admission call.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorRealEngineAuditNativeGameTests {
    private DirectorRealEngineAuditNativeGameTests(){}
    private static final StoreOptions OPTIONS=new StoreOptions(
        1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);

    private static class Probe {
        final MinecraftServer server;
        final WorldId world;
        final Path directory;
        final CompletableFuture<JournalStore> opened;
        CompletableFuture<CommitAck> first;
        CompletableFuture<Void> closed;
        CompletableFuture<JournalStore> reopened;
        JournalStore store;
        DirectorDecisionJournal journal;
        DecisionProposal proposal;
        DecisionId id;
        int step;
        Probe(GameTestHelper helper) {
            server=Objects.requireNonNull(helper.getLevel().getServer());
            if(!server.isSameThread())throw new IllegalStateException("actor");
            world=server.overworld().getDataStorage().computeIfAbsent(
                DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
            directory=server.getWorldPath(LevelResource.ROOT)
                .resolve("director-j03-test-only").resolve(UUID.randomUUID().toString());
            opened=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();
        }
        void advance(GameTestHelper helper) {
            if(!server.isSameThread())throw new IllegalStateException("actor");
            var life=DirectorStandaloneMod.directorLifecycle();
            switch(step) {
                case 0 -> {
                    helper.assertTrue(opened.isDone(),"J03 lab J04 opening");
                    helper.assertFalse(opened.isCompletedExceptionally(),"lab WAL open");
                    store=opened.getNow(null);
                    var snapshot=life.observationSnapshot(server);
                    var memory=life.labMemoryView(server);
                    helper.assertTrue(snapshot.isPresent() && memory.isPresent(),
                        "J01/J04 real actor snapshot not available yet");
                    var observed=snapshot.orElseThrow();
                    var recovered=memory.orElseThrow();
                    helper.assertTrue(observed.scopes().worldId().equals(world) &&
                        recovered.scopes().worldId().equals(world),
                        "real actor inputs from wrong world");
                    var config=DirectorLabObservationProfile.load();
                    var tick=observed.capturedTick();
                    var engineControl=new EngineControlState(1,new Revision(0),
                        tick,tick,new UnitValue(0),tick,Optional.empty(),new UnitValue(.5));
                    // UNAPPROVED laboratory-only initial state, never runtime
                    // production initialization or calibrated pacing.
                    var worldState=new DirectorWorldState(
                        new AggregateHeader(1,world.value(),new Revision(1),
                            new Sequence(0),tick),
                        world,store.session().storeGeneration(),tick,
                        RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
                        PacingState.REST,GlobalTension.CALM,
                        new ResourceId("proutlost_director:lab_only"),
                        new Fingerprint("0".repeat(64)),config.provenanceHash(),
                        store.session().bootEpoch(),new Sequence(0),engineControl);
                    first=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableAggregate(new WorldAggregate(worldState))),
                        List.of())).toCompletableFuture();
                    step=1;
                }
                case 1 -> {
                    helper.assertTrue(first.isDone(),"J03 fixture J04 fsync pending");
                    helper.assertTrue(!first.isCompletedExceptionally() &&
                        first.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "test-only persisted world state lacks J04 proof");
                    var observedContext=life.observationSnapshot(server);
                    var observedMemory=life.labMemoryView(server);
                    helper.assertTrue(observedContext.isPresent() && observedMemory.isPresent(),
                        "real observation and durable memory projection still synchronizing");
                    var context=observedContext.orElseThrow();
                    var memory=observedMemory.orElseThrow();
                    var config=DirectorLabObservationProfile.load();
                    var key=new AggregateKey(AggregateKind.WORLD,world.value());
                    var page=store.view(new StoreQuery(world,Set.of(key),
                        new Sequence(0),Optional.empty(),1));
                    helper.assertTrue(page.status()==ReadStatus.AVAILABLE,
                        "cannot read actual test WAL world aggregate");
                    var persisted=(WorldAggregate)page.page().orElseThrow()
                        .aggregates().getFirst().state();
                    // J02 cannot expose its CANDIDATE definitions to J03 as
                    // production content. Empty catalogue explicitly disables
                    // all physical plans in this TEST_ONLY computation.
                    var noProductionCatalogue=new CatalogSnapshot(new Epoch(0),
                        persisted.value().catalogHash(),Map.of(),Map.of(),Map.of());
                    var input=new DecisionInput(context,memory,
                        noProductionCatalogue,config,List.of(),List.of(),42,
                        new DecisionState(persisted.value(),
                            new BudgetView(new BudgetVector(10,10,10,0,0,0,0,0,0,0),
                                BudgetMath.zero(),Map.of()),
                            new OwnershipView(Map.of()),List.of(),List.of(),
                            List.of(),List.of(),List.of(),List.of()),
                        Optional.empty());
                    var engine=new WorldDirector(ProviderMode.TEST_ONLY);
                    proposal=engine.evaluate(input);
                    helper.assertTrue(proposal.selectedPlan().isEmpty() &&
                        proposal.candidates().isEmpty() &&
                        engine.replay(input,proposal.audit()).equals(proposal),
                        "real J03 engine did not replay deterministic no-plan");
                    journal=new DirectorDecisionJournal(store,4);
                    var receipt=journal.submit(proposal);
                    helper.assertTrue(!receipt.mayProceedToAdmission(),
                        "cannot admit before J04 audit ACK");
                    id=proposal.proposalId();
                    step=2;
                }
                case 2 -> {
                    var receipt=journal.poll(id);
                    helper.assertTrue(receipt.state()!=DirectorDecisionJournal.State.WAITING,
                        "pending J03 audit WAL durable ACK");
                    helper.assertTrue(receipt.state()==DirectorDecisionJournal.State.DURABLE,
                        "real J03 calculation audit not durably committed");
                    var result=store.view(new StoreQuery(world,Set.of(),
                        new Sequence(0),Optional.empty(),32));
                    helper.assertTrue(result.status()==ReadStatus.AVAILABLE &&
                        result.page().orElseThrow().records().stream()
                            .filter(r->r instanceof DurableAudit).count()==1,
                        "real J03 audit missing from native J04 chronological WAL");
                    var state=store.view(new StoreQuery(world,
                        Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
                        new Sequence(0),Optional.empty(),1));
                    helper.assertTrue(state.page().orElseThrow().aggregates().getFirst()
                        .state().header().revision().value()==2,
                        "J03 world-state CAS not committed with audit");
                    helper.assertTrue(!life.snapshot(server).admitsEvents() &&
                        proposal.selectedPlan().isEmpty(),
                        "J03 replay wrongly admitted gameplay");
                    closed=store.closeAsync().toCompletableFuture();
                    step=3;
                }
                case 3 -> {
                    helper.assertTrue(closed.isDone() && !closed.isCompletedExceptionally(),
                        "J03 lab writer did not close safely");
                    reopened=JournalStore.open(directory,world,OPTIONS).toCompletableFuture();
                    step=4;
                }
                case 4 -> {
                    helper.assertTrue(reopened.isDone() &&
                        !reopened.isCompletedExceptionally(),
                        "J03 real audit WAL failed recovery");
                    store=reopened.getNow(null);
                    var page=store.view(new StoreQuery(world,Set.of(),
                        new Sequence(0),Optional.empty(),32));
                    helper.assertTrue(page.status()==ReadStatus.AVAILABLE &&
                        page.page().orElseThrow().records().stream()
                            .filter(r->r instanceof DurableAudit).count()==1,
                        "real J03 audit lost on replay");
                    closed=store.closeAsync().toCompletableFuture();
                    step=5;
                }
                case 5 -> {
                    helper.assertTrue(closed.isDone() && !closed.isCompletedExceptionally(),
                        "J03 audit recovery store not closed");
                    System.out.println(
                        "DIRECTOR_NATIVE_J03_REAL_ENGINE_J01_J04_AUDIT_REBOOT_TEST_ONLY_PASS");
                    step=6;
                }
                case 6 -> { /* complete; no work */ }
                default -> throw new IllegalStateException("native J03 phase");
            }
        }
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void nativeRealJ03DecisionAuditSurvivesJournalReboot(
            GameTestHelper helper) {
        var probe=new Probe(helper);
        helper.succeedWhen(()->{
            probe.advance(helper);
            helper.assertTrue(probe.step==6,
                "J03 + J04 audit and reboot recovery not complete yet");
        });
    }
}
