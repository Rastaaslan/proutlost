package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.WorldDirector;
import fr.proutlost.director.persistence.JournalStore;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Native J03 to J04 scheduler, confined to the disposable TEST_ONLY lab.
 * This lane computes a deterministic no-plan decision on real server data,
 * persists its audit with WORLD CAS and never offers any physical admission.
 */
public final class DirectorNativeDecisionScheduler implements AutoCloseable {
    public enum Phase { READY, WAITING_WAL, FAILED_SAFE, CLOSED }
    /** Evidence from the exact recovered J04 view used by a DURABLY
     * acknowledged J03 evaluation. Counts reveal no individual player data.
     * They grant no admission or execution capability. */
    public record Status(Phase phase,long evaluated,long committed,
                         long worldRevision,long simTick,boolean gameplayActive,
                         long evaluatedRegions,long evaluatedIndependentCampVisits,
                         long evaluatedJ04Sequence,
                         long evaluatedRegionContexts,long evaluatedCampSnapshots,
                         boolean regionalContextDeferred) {}
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final DirectorNativeObservationQualification observation;
    private final DirectorNativeMemoryProjectionQualification memory;
    private final DirectorNativeWorldStateBootstrap bootstrap;
    private final ConfigView config;
    private final DirectorDecisionJournal journal;
    private final WorldDirector engine=new WorldDirector(ProviderMode.TEST_ONLY);
    private final CatalogSnapshot emptyCatalog;
    private final DirectorNativeRegionalPlanningBridge regionalBridge;
    private Phase phase=Phase.READY;
    private long lastRun=-200,evaluated,committed,revision,simTick;
    private DecisionId pending;
    private WorldAggregate expected;
    private long pendingRegionCount,pendingIndependentCampVisits,pendingJ04Sequence;
    private long acknowledgedRegionCount,acknowledgedIndependentCampVisits,
                 acknowledgedJ04Sequence;
    private long pendingRegionContexts,pendingCampSnapshots;
    private long acknowledgedRegionContexts,acknowledgedCampSnapshots;
    private boolean pendingRegionalDeferred,acknowledgedRegionalDeferred;

    public DirectorNativeDecisionScheduler(MinecraftServer server,WorldId world,
          JournalStore store,DirectorNativeObservationQualification observation,
          DirectorNativeMemoryProjectionQualification memory,
          DirectorNativeWorldStateBootstrap bootstrap) {
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);
        this.observation=Objects.requireNonNull(observation);
        this.memory=Objects.requireNonNull(memory);
        this.bootstrap=Objects.requireNonNull(bootstrap);
        actor();
        session=store.session();
        config=observation.testConfiguration();
        if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING
          || !session.worldId().equals(world) || store.readOnlySafe()
          || bootstrap.world().isEmpty())
            throw new IllegalArgumentException("J03 lab requires recovered store");
        journal=new DirectorDecisionJournal(store,2);
        regionalBridge=new DirectorNativeRegionalPlanningBridge(config);
        emptyCatalog=new CatalogSnapshot(new Epoch(0),
          bootstrap.world().orElseThrow().catalogHash(),Map.of(),Map.of(),Map.of());
    }
    private void actor() {
        if(!server.isSameThread())
            throw new IllegalStateException("J03 must execute on server actor");
    }
    private void fail(String code) {
        phase=Phase.FAILED_SAFE;
        System.out.println("DIRECTOR_NATIVE_J03_SCHEDULER_FAILED_SAFE "+code);
    }
    private WorldAggregate latest() {
        var query=new StoreQuery(world,Set.of(new AggregateKey(
           AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
        var response=store.view(query);
        ReadContracts.validate(response,query,session);
        if(response.status()!=ReadStatus.AVAILABLE
            || response.page().orElseThrow().aggregates().size()!=1
            || !(response.page().orElseThrow().aggregates().getFirst().state()
                instanceof WorldAggregate found))
            throw new IllegalStateException("J04 WORLD unavailable");
        if(!found.value().worldId().equals(world)
            || !found.value().storeGeneration().equals(session.storeGeneration())
            || !found.value().bootEpoch().equals(session.bootEpoch()))
            throw new IllegalStateException("stale J04 WORLD authority");
        return found;
    }
    /** Actor poll: ACK is inspected before any new calculation. */
    public void poll(){poll(true);}
    public void poll(boolean allowNewDecision) {
        actor();
        if(phase==Phase.CLOSED || phase==Phase.FAILED_SAFE)return;
        try {
            if(!session.equals(store.session())) {
                fail("J04_SESSION_CHANGED");return;
            }
            if(phase==Phase.WAITING_WAL) {
                var receipt=journal.poll(Objects.requireNonNull(pending));
                if(receipt.state()==DirectorDecisionJournal.State.WAITING)return;
                if(receipt.state()!=DirectorDecisionJournal.State.DURABLE) {
                    fail("J04_DECISION_AUDIT_NOT_DURABLE");return;
                }
                WorldAggregate actual=latest();
                if(!actual.equals(expected)) {
                    fail("J03_WORLD_CAS_READBACK_MISMATCH");return;
                }
                revision=actual.header().revision().value();
                simTick=actual.value().simTick().value();
                committed=Math.addExact(committed,1);
                // Publish region-input evidence ONLY after exact WORLD CAS
                // and journal fsync/readback; never advertise pending work.
                acknowledgedRegionCount=pendingRegionCount;
                acknowledgedIndependentCampVisits=pendingIndependentCampVisits;
                acknowledgedJ04Sequence=pendingJ04Sequence;
                acknowledgedRegionContexts=pendingRegionContexts;
                acknowledgedCampSnapshots=pendingCampSnapshots;
                acknowledgedRegionalDeferred=pendingRegionalDeferred;
                journal.forgetDurable(pending);
                pending=null;
                expected=null;
                phase=Phase.READY;
                System.out.println("DIRECTOR_NATIVE_J03_SCHEDULED_AUDIT_DURABLE "+
                    "committed="+committed+" world_revision="+revision+
                    " sim_tick="+simTick+" plans=0 gameplay=false");
                return;
            }
            if(!allowNewDecision)return;
            if(bootstrap.world().isEmpty() || !memory.status().complete() ||
                memory.status().failClosed() || observation.snapshot().isEmpty())
                return;
            long processTick=server.getTickCount();
            if(processTick-lastRun<200)return;
            var live=observation.snapshot().orElseThrow();
            var remembered=memory.view().orElseThrow();
            if(!live.scopes().worldId().equals(world) ||
               !remembered.scopes().worldId().equals(world))
                throw new IllegalStateException("mixed J01/J04 world");
            // Unlike the raw J01 player census, this planning-only DTO
            // includes real, J04-persisted regional snapshots. No physical
            // J05 admission is ever attached to these immutable hints.
            var projected=regionalBridge.project(live,remembered,
                memory.status().lastDurableSequence());
            var context=projected.snapshot();
            WorldAggregate durable=latest();
            var old=durable.value();
            if(old.runControl()!=RunControl.PAUSED ||
               old.healthMode()!=HealthMode.READ_ONLY_SAFE ||
               !old.catalogHash().equals(emptyCatalog.hash()) ||
               !old.configHash().equals(config.provenanceHash()))
                throw new IllegalStateException("unapproved J03 runtime");
            Tick tick=new Tick(Math.max(observation.now(),
                           Math.addExact(old.simTick().value(),1)));
            var current=new DirectorWorldState(old.header(),old.worldId(),
                old.storeGeneration(),tick,old.runControl(),old.healthMode(),
                old.pacingState(),old.tension(),old.intensityProfileId(),
                old.catalogHash(),old.configHash(),old.bootEpoch(),
                old.lastCheckpointSeq(),old.engineControl());
            var budget=new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of());
            var state=new DecisionState(current,budget,new OwnershipView(Map.of()),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
            var seed=UUID.nameUUIDFromBytes(
                (world.value()+"/"+session.bootEpoch().value()+"/"+
                 old.header().revision().value()+"/"+tick.value())
                 .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var input=new DecisionInput(context,remembered,emptyCatalog,
                config,List.of(),List.of(),
                seed.getMostSignificantBits()^seed.getLeastSignificantBits(),
                state,Optional.empty());
            var output=engine.evaluate(input);
            if(output.selectedPlan().isPresent() ||
               !output.candidates().isEmpty() ||
               output.stateChanges().size()!=1 ||
               !(output.stateChanges().getFirst().next() instanceof WorldAggregate next) ||
               next.value().runControl()!=RunControl.PAUSED ||
               next.value().healthMode()!=HealthMode.READ_ONLY_SAFE ||
               !engine.replay(input,output.audit()).equals(output))
                throw new IllegalStateException("unqualified J03 evaluation");
            var receipt=journal.submit(output);
            if(receipt.state()==DirectorDecisionJournal.State.UNAVAILABLE ||
               receipt.state()==DirectorDecisionJournal.State.REJECTED)
                throw new IllegalStateException("J04 audit unavailable");
            // The engine above consumed this J04 memory view. Capture an
            // anonymous monotone observation for its eventual durable ACK.
            long visits=0;
            for(var region:remembered.regions())
                for(var camp:region.activity().camps().values())
                    visits=Math.addExact(visits,camp.independentVisits());
            pendingRegionCount=remembered.regionRecords().values().stream()
                .filter(region->region.status()==RegionStatus.ACTIVE).count();
            pendingIndependentCampVisits=visits;
            pendingJ04Sequence=projected.j04Sequence();
            pendingRegionContexts=projected.regionCount();
            pendingCampSnapshots=projected.campCount();
            pendingRegionalDeferred=projected.deferred();
            pending=output.proposalId();
            expected=next;
            evaluated=Math.addExact(evaluated,1);
            lastRun=processTick;
            phase=Phase.WAITING_WAL;
        }catch(RuntimeException failure){
            fail(failure.getClass().getSimpleName());
        }
    }
    public Status status() {
        actor();
        return new Status(phase,evaluated,committed,revision,simTick,false,
            acknowledgedRegionCount,acknowledgedIndependentCampVisits,
            acknowledgedJ04Sequence,acknowledgedRegionContexts,
            acknowledgedCampSnapshots,acknowledgedRegionalDeferred);
    }
    @Override public void close() {
        actor();
        phase=Phase.CLOSED;
    }
}
