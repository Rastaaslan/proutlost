package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One world-local bridge per MinecraftServer. Always fails closed.
 * The optional laboratory plan is *not* a production Director activation switch;
 * it qualifies recovery and writer closure only and is absent by default.
 */
public final class DirectorServerLifecycle {
    public enum Phase { STARTING, RECOVERING, OBSERVING_DISABLED, PAUSED, RUNNING, FAILED_SAFE, STOPPING, STOPPED }
    public record View(Phase phase, long observedTicks, boolean admitsEvents,
                       int missingPortCount) {
        public View {
            Objects.requireNonNull(phase);
            if(observedTicks<0 || missingPortCount<0 || admitsEvents&&phase!=Phase.RUNNING)
                throw new IllegalArgumentException("unqualified Director admission");
        }
    }
    /** Explicitly named lab-only capability. Never accepted from player/network inputs.
     * The folder is constrained to a subdirectory of the disposable server world.
     */
    public record LabStorePlan(String folder, StoreOptions capacities) {
        public LabStorePlan {
            Objects.requireNonNull(folder);
            Objects.requireNonNull(capacities);
            if(!folder.matches("director-lab-[a-z0-9-]{1,32}"))
                throw new IllegalArgumentException("unsafe lab directory");
        }
    }
    private record LoadedProduction(DirectorOperatorBundle approved,DirectorProductionRuntimeQa declaredQa) {
        LoadedProduction{if((approved==null)==(declaredQa==null))throw new IllegalArgumentException("one explicit production/declared-QA profile required");}
        ConfigView configuration(){return approved!=null?approved.configuration():declaredQa.configuration();}
    }
    private static final class Session {
        Phase phase=Phase.STARTING;
        long ticks;
        int missingPorts=11;
        final DirectorNativeAutoSupervisor supervisor=new DirectorNativeAutoSupervisor();
        DirectorNativeObservationQualification labObservation;
        DirectorNativeMemoryProjectionQualification labMemory;
        DirectorLabObservationWalIngestion labIngestion;
        DirectorNativeAnchorWalIngestion labAnchors;
        boolean j01AnchorsFailedSafe;
        DirectorNativeWorldStateBootstrap labWorld;
        DirectorNativeDecisionScheduler labScheduler;
        boolean j03SchedulerFailedSafe;
        boolean j03WorldFailedSafe;
        DirectorNativeCandidateCatalog labCandidateCatalog;
        DirectorNativeLaboratoryPreview labPreview;
        boolean j02CandidateFailedSafe;
        boolean j01FailedSafe;
        boolean j04MemoryFailedSafe;
        DirectorNativeGameplay labGameplay;
        DirectorNativeStoreBootstrap labStore;
        DirectorNativeStoreBootstrap productionStore;
        DirectorOperatorBundle productionBundle;
        DirectorProductionRuntime production;
        CompletableFuture<LoadedProduction> pendingProduction;
        DirectorProductionRuntimeQa declaredQa;
        DirectorProductionCohortQa cohortQa;
        String productionDiagnostic="";
        CompletionStage<Void> close;
        View view(){return new View(phase,ticks,production!=null&&production.phase()==DirectorProductionRuntime.Phase.RUNNING,missingPorts);}
    }

    private final Map<MinecraftServer,Session> sessions=new IdentityHashMap<>();
    private final Optional<LabStorePlan> labPlan;
    private final IEventBus gameBus;
    private final Optional<Fingerprint> productionManifest;
    private final boolean declaredQaConfigured;
    private final ExecutorService bootstrapWorker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"director-production-bootstrap");t.setDaemon(true);return t;});
    private View lastStopped=new View(Phase.STOPPED,0,false,11);

    public DirectorServerLifecycle(IEventBus gameBus) {
        this(gameBus,Optional.empty(),Optional.empty());
    }
    public DirectorServerLifecycle(IEventBus gameBus, Optional<LabStorePlan> labPlan) {
        this(gameBus,labPlan,Optional.empty());
    }
    public DirectorServerLifecycle(IEventBus gameBus,Optional<LabStorePlan> labPlan,Optional<Fingerprint> productionManifest) {
        this.productionManifest=Objects.requireNonNull(productionManifest);
        this.declaredQaConfigured=DirectorProductionRuntimeQa.requested();
        if((declaredQaConfigured||DirectorProductionCohortQa.requested())&&(labPlan.isPresent()||productionManifest.isPresent()))throw new IllegalArgumentException("declared QA must be distinct from LAB/operator production");
        if(labPlan.isPresent()&&productionManifest.isPresent())throw new IllegalArgumentException("separate LAB and production server configurations required");
        this.gameBus=Objects.requireNonNull(gameBus);
        this.labPlan=Objects.requireNonNull(labPlan);
        gameBus.addListener(this::starting);
        gameBus.addListener(this::started);
        gameBus.addListener(this::tick);
        gameBus.addListener(this::stopping);
        gameBus.addListener(this::stopped);
    }

    private static void actor(MinecraftServer server) {
        if(server==null || !server.isSameThread())
            throw new IllegalStateException("Director world actor required");
    }
    private void starting(ServerStartingEvent event) {
        var server=event.getServer();
        actor(server);
        if(sessions.containsKey(server))
            throw new IllegalStateException("duplicate Director startup");
        sessions.put(server,new Session());
    }
    private void started(ServerStartedEvent event) {
        var server=event.getServer();
        actor(server);
        var session=sessions.get(server);
        if(session==null || session.phase!=Phase.STARTING)
            throw new IllegalStateException("invalid Director startup transition");
        var empty=DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of());
        if(!empty.capabilities().isEmpty() || empty.ports().worldEffects().isPresent()
            || empty.ports().durableStore().isPresent())
            throw new IllegalStateException("unexpected Director provider on boot");
        session.missingPorts=empty.missingPorts().size();
        session.phase=Phase.OBSERVING_DISABLED;

        if(DirectorProductionCohortQa.requested()){
            try{session.cohortQa=DirectorProductionCohortQa.open(server);}
            catch(RuntimeException failure){session.phase=Phase.FAILED_SAFE;session.productionDiagnostic=failure.toString();System.out.println("DIRECTOR_COHORT_QA_BOOT_REFUSED "+failure);return;}
        }
        if(productionManifest.isPresent()){
            session.phase=Phase.RECOVERING;
            var worldDirectory=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            var pin=productionManifest.orElseThrow();
            session.pendingProduction=CompletableFuture.supplyAsync(()->{
                try{
                    var bundle=DirectorOperatorBundle.load(worldDirectory.resolve("proutlost/director-profile"),pin);
                    // Persist every new parent entry up to the existing world root.
                    var parent=worldDirectory.resolve("proutlost");
                    if(Files.isSymbolicLink(parent))throw new java.io.IOException("symlink production store parent");
                    Files.createDirectories(parent);
                    try(var channel=FileChannel.open(parent,StandardOpenOption.READ)){channel.force(true);}
                    try(var channel=FileChannel.open(worldDirectory,StandardOpenOption.READ)){channel.force(true);}
                    return new LoadedProduction(bundle,null);
                }catch(java.io.IOException failure){throw new CompletionException(failure);}
            },bootstrapWorker);
        }
        if(declaredQaConfigured){
            session.phase=Phase.RECOVERING;
            try{
                var token=DirectorProductionRuntimeQa.open(server);
                var worldDirectory=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                var parent=worldDirectory.resolve("proutlost");
                if(Files.isSymbolicLink(parent))throw new java.io.IOException("symlink QA store parent");
                Files.createDirectories(parent);
                try(var channel=FileChannel.open(parent,StandardOpenOption.READ)){channel.force(true);}
                try(var channel=FileChannel.open(worldDirectory,StandardOpenOption.READ)){channel.force(true);}
                // One-time bounded QA bootstrap IO, before any measurement window.
                // Zero clients are allowed; no catalogue survey or event admission yet.
                session.pendingProduction=CompletableFuture.completedFuture(new LoadedProduction(null,token));
            }catch(java.io.IOException|RuntimeException failure){session.phase=Phase.FAILED_SAFE;session.productionDiagnostic=failure.toString();System.out.println("DIRECTOR_DECLARED_SYNTHETIC_QA_BOOT_REFUSED "+failure);}
        }
        labPlan.ifPresent(plan->{
            try {
                var identity=server.overworld().getDataStorage()
                    .computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY);
                var owner=new DirectorNativeStoreBootstrap.MinecraftServerActor() {
                    public void assertActor(){actor(server);}
                    public MinecraftServer server(){return server;}
                };
                var bridge=new DirectorNativeStoreBootstrap(owner,identity);
                session.labStore=bridge;
                Path directory=server.getWorldPath(LevelResource.ROOT)
                    .resolve(plan.folder());
                bridge.open(directory,plan.capacities());
                System.out.println("DIRECTOR_NATIVE_AUTOSTORE_OPEN_REQUESTED");
            } catch(RuntimeException failure) {
                // Do not crash Minecraft on failed J04 startup. Never admit gameplay.
                session.missingPorts=11;
                System.out.println("DIRECTOR_NATIVE_AUTOSTORE_OPEN_FAILED_SAFE "
                    +failure.getClass().getSimpleName());
            }
        });
        System.out.println("DIRECTOR_NATIVE_BOOTSTRAP_START_PASS missingPorts="+session.missingPorts);
    }
    /** A recovered store can lose fsync authority later. Stop every consumer
     * on the actor; no lab observer or scheduler may tick on revoked WAL state.
     * Do not discard the WAL or attempt a second writer open.
     */
    private static void revokeLabConsumers(Session session) {
        if(session.labGameplay!=null){session.labGameplay.close();session.labGameplay=null;}
        if(session.labObservation!=null){session.labObservation.close();session.labObservation=null;}
        if(session.labMemory!=null){session.labMemory.close();session.labMemory=null;}
        if(session.labIngestion!=null){session.labIngestion.close();session.labIngestion=null;}
        if(session.labAnchors!=null){session.labAnchors.close();session.labAnchors=null;}
        if(session.labWorld!=null){session.labWorld.close();session.labWorld=null;}
        if(session.labScheduler!=null){session.labScheduler.close();session.labScheduler=null;}
        session.labCandidateCatalog=null;
        session.labPreview=null;
        session.j01FailedSafe=true;
        session.j04MemoryFailedSafe=true;
        session.j03SchedulerFailedSafe=true;
        session.j03WorldFailedSafe=true;
        session.j01AnchorsFailedSafe=true;
        session.j02CandidateFailedSafe=true;
        session.missingPorts=11;
    }

    private void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        var session=sessions.get(server);
        if(session==null || session.phase==Phase.STOPPING||session.phase==Phase.STOPPED)return;
        if(session.cohortQa!=null&&session.cohortQa.branch()==DeclaredCohortQaPlan.Branch.BASELINE){
            actor(server);session.ticks=Math.addExact(session.ticks,1);
            try{session.cohortQa.requireWorld();if(session.production!=null||session.productionStore!=null||session.labStore!=null)throw new IllegalStateException("baseline cannot own a Director runtime/store");}
            catch(RuntimeException invalid){session.phase=Phase.FAILED_SAFE;session.productionDiagnostic=invalid.toString();}
            return;
        }
        if(productionConfigured()){
            actor(server);session.ticks=Math.addExact(session.ticks,1);pollProduction(server,session);return;
        }
        if(session.phase!=Phase.OBSERVING_DISABLED)return;
        actor(server);
        session.ticks=Math.addExact(session.ticks,1);
        if(session.labStore!=null) {
            session.labStore.poll();
            var view=session.labStore.view();
            session.missingPorts=view.hasDurableStore()?9:11;
            if(view.phase()==DirectorNativeStoreBootstrap.Phase.FAILED_SAFE ||
                    view.phase()==DirectorNativeStoreBootstrap.Phase.CLOSED ||
                    view.phase()==DirectorNativeStoreBootstrap.Phase.CLOSING ||
                    (view.phase()==DirectorNativeStoreBootstrap.Phase.RECOVERED_DISABLED &&
                     !view.hasDurableStore())) {
                revokeLabConsumers(session);
                session.supervisor.sample(server,session.view());
                return;
            }
        }
        if(labPlan.isPresent() && session.labStore!=null && session.labStore.view().hasDurableStore()) {
            // Restore the real WORLD boot fence before any selected-effect recovery.
            // Cold cleanup needs current J04 identity, but never depends on fresh J01.
            if(session.labWorld==null && !session.j03WorldFailedSafe) {
                try {session.labWorld=session.labStore.labWorldBootstrap(DirectorLabObservationProfile.loadRuntime());}
                catch(RuntimeException failure) {
                    session.j03WorldFailedSafe=true;
                    System.out.println("DIRECTOR_NATIVE_J03_EARLY_WORLD_BOOT_FAILED_SAFE "+failure);
                }
            }
            if(session.labWorld==null)return;
            session.labWorld.poll();
            if(session.labWorld.status().phase()==DirectorNativeWorldStateBootstrap.Phase.FAILED_SAFE) {
                session.j03WorldFailedSafe=true;return;
            }
            if(session.labWorld.status().phase()!=DirectorNativeWorldStateBootstrap.Phase.VERIFIED_PAUSED)return;
            if(session.labGameplay==null) {
                session.labGameplay=session.labStore.labGameplay();
                session.labGameplay.install(gameBus);
            }
            session.labGameplay.poll();
            // Bootstrap reads one stable WAL cut before observation/scheduler writers start.
            if(session.labGameplay.recovering())return;
        }
        // Recover J04 memory BEFORE opening native J01 sampling. This
        // provides a durable time lower bound for subsequent observations.
        if(labPlan.isPresent() && session.labStore!=null &&
           session.labStore.view().phase()==DirectorNativeStoreBootstrap.Phase.RECOVERED_DISABLED &&
           session.labMemory==null && !session.j04MemoryFailedSafe){
            try {
                var identity=server.overworld().getDataStorage().computeIfAbsent(
                    DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY);
                session.labMemory=new DirectorNativeMemoryProjectionQualification(
                    server,identity.worldId(),
                    session.labStore.labReadOnlyMemoryStore().orElseThrow(),
                    DirectorLabObservationProfile.loadRuntime());
                System.out.println("DIRECTOR_NATIVE_J04_MEMORY_READ_ONLY_PROJECTION_BOUND");
            }catch(RuntimeException invalid){
                session.j04MemoryFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J04_MEMORY_BIND_FAILED_SAFE "+
                    invalid.getClass().getSimpleName());
            }
        }
        if(session.labMemory!=null){
            session.labMemory.poll();
            if(session.labMemory.status().failClosed())session.j04MemoryFailedSafe=true;
        }
        if(session.labMemory!=null && session.labMemory.status().complete() &&
           session.labStore!=null && session.labObservation==null &&
           !session.j01FailedSafe && !session.j04MemoryFailedSafe) {
            DirectorNativeObservationQualification candidate=null;
            try {
                var identity=server.overworld().getDataStorage().computeIfAbsent(
                    DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY);
                candidate=new DirectorNativeObservationQualification(server,
                    identity.worldId(),session.labStore.recoveredBootEpoch().orElseThrow(),
                    session.labMemory.view().orElseThrow());
                candidate.register(gameBus);
                session.labObservation=candidate;
                session.labMemory.pendingDemand(session.labObservation::pendingMemoryScopes,session.labObservation::pendingMemoryAnchors);
                System.out.println("DIRECTOR_NATIVE_J01_REAL_SERVER_OBSERVER_REGISTERED_TEST_ONLY");
            }catch(RuntimeException failure){
                session.j01FailedSafe=true;
                if(candidate!=null)candidate.close();
                System.out.println("DIRECTOR_NATIVE_J01_OBSERVATION_FAILED_SAFE "+
                    failure.getClass().getSimpleName());
            }
        }
        if(session.labObservation!=null && session.labCandidateCatalog==null &&
           !session.j02CandidateFailedSafe) {
            try {
                var candidate=new DirectorNativeCandidateCatalog(
                    session.labObservation.testConfiguration());
                if(!candidate.rejectsUnqualifiedProviders(
                    session.labObservation.testConfiguration()))
                    throw new IllegalStateException("unqualified J02 catalog unexpectedly installed");
                session.labCandidateCatalog=candidate;
                // Compile exactly two immutable score-only fixtures; missing
                // providers never become J05 physical permissions.
                session.labPreview=new DirectorNativeLaboratoryPreview(
                    session.labObservation.testConfiguration());
                System.out.println("DIRECTOR_NATIVE_J07_SCORE_ONLY_FIXTURES_BOUND_TEST_ONLY");
                System.out.println("DIRECTOR_NATIVE_J02_PACKAGED_CANDIDATE_INTEGRITY_VERIFIED "+
                    "events="+candidate.status().verifiedEvents()+
                    " chains="+candidate.status().verifiedChains()+" production=false");
            }catch(RuntimeException failure){
                session.j02CandidateFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J02_CANDIDATE_FAILED_SAFE "+
                    failure.getClass().getSimpleName());
            }
        }
        if(session.labMemory!=null && session.labMemory.status().complete() &&
           session.labWorld==null && !session.j03WorldFailedSafe &&
           session.labStore!=null && session.labStore.view().hasDurableStore()) {
            try {
                session.labWorld=session.labStore.labWorldBootstrap(
                    DirectorLabObservationProfile.loadRuntime());
                System.out.println("DIRECTOR_NATIVE_J03_WORLD_BOOT_REQUESTED_TEST_ONLY");
            }catch(RuntimeException issue){
                session.j03WorldFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J03_WORLD_BOOT_BIND_FAILED_SAFE "+
                    issue.getClass().getSimpleName());
            }
        }
        if(session.labWorld!=null) {
            session.labWorld.poll();
            if(session.labWorld.status().phase()==
               DirectorNativeWorldStateBootstrap.Phase.FAILED_SAFE)
                session.j03WorldFailedSafe=true;
        }
        if(session.labMemory!=null && session.labObservation!=null &&
           !session.j04MemoryFailedSafe && session.labIngestion==null &&
           session.labStore!=null && session.labStore.view().hasDurableStore()) {
            try{
                session.labIngestion=session.labStore.labObservationIngestion(
                    session.labObservation,session.labMemory);
                System.out.println("DIRECTOR_NATIVE_J01_J04_INGESTION_BOUND_TEST_ONLY");
            }catch(RuntimeException failure){
                session.j04MemoryFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J01_J04_INGESTION_BIND_FAILED_SAFE "+
                    failure.getClass().getSimpleName());
            }
        }
        if(session.labMemory!=null && session.labObservation!=null &&
           session.labAnchors==null && !session.j01AnchorsFailedSafe &&
           session.labStore!=null && session.labStore.view().hasDurableStore()) {
            try {
                session.labAnchors=session.labStore.labAnchorIngestion(
                    session.labObservation,session.labMemory);
                System.out.println("DIRECTOR_NATIVE_J01_ANCHOR_INGRESS_BOUND_TEST_ONLY");
            }catch(RuntimeException issue){
                session.j01AnchorsFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J01_ANCHOR_INGRESS_BIND_FAILED "+
                    issue.getClass().getSimpleName());
            }
        }
        if(session.labObservation!=null) {
            try{
                if(session.labMemory!=null && session.labMemory.view().isPresent())
                    session.labObservation.refreshRegionIndex(
                        session.labMemory.view().orElseThrow(),
                        session.labMemory.status().lastDurableSequence());
                session.labObservation.tick();
            }
            catch(RuntimeException invalid){
                session.labObservation.close();session.labObservation=null;
                session.j01FailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J01_OBSERVATION_TICK_FAILED_SAFE "
                    +invalid.getClass().getSimpleName());
            }
        }
        // Process explicit LAB-only chain requests after actual J01 sampling and complete
        // memory publication, before ingress/scheduler writes advance that exact WAL cut.
        if(session.labGameplay!=null&&session.labObservation!=null&&session.labMemory!=null
           &&session.labMemory.status().complete()&&!session.j04MemoryFailedSafe&&!session.j01FailedSafe)
            session.labGameplay.pollLabChainsAtFreshContext();
        // Pump only after native J01's sample and native J04 projection:
        // J04 IO is strictly asynchronous. No gameplay admission is possible.
        if(session.labAnchors!=null) {
            session.labAnchors.poll(session.labGameplay==null||!session.labGameplay.metadataLaneBusy());
            if(session.labAnchors.status().phase()==
               DirectorNativeAnchorWalIngestion.Phase.FAILED_SAFE)
                session.j01AnchorsFailedSafe=true;
        }
        if(session.labIngestion!=null)session.labIngestion.poll(session.labGameplay==null||!session.labGameplay.metadataLaneBusy());
        if(session.labGameplay!=null&&session.labObservation!=null&&session.labAnchors!=null&&session.labIngestion!=null)
            session.labGameplay.bindAnchorOwner(session.labObservation.anchorOwner());
        if(session.labWorld!=null && session.labWorld.world().isPresent() &&
           session.labObservation!=null && session.labMemory!=null &&
           session.labScheduler==null && !session.j03SchedulerFailedSafe) {
            try {
                session.labScheduler=session.labStore.labDecisionScheduler(
                    session.labObservation,session.labMemory,session.labWorld);
                System.out.println("DIRECTOR_NATIVE_J03_SCHEDULER_BOUND_TEST_ONLY");
            }catch(RuntimeException invalid){
                session.j03SchedulerFailedSafe=true;
                System.out.println("DIRECTOR_NATIVE_J03_SCHEDULER_BIND_FAILED_SAFE "+
                    invalid.getClass().getSimpleName());
            }
        }
        if(session.labScheduler!=null)session.labScheduler.poll(session.labGameplay==null||!session.labGameplay.catalogueOwnsWorldLane());
        // J01 is a DEFAULT_FOR_TESTING observer only: it does not satisfy
        // the mandatory PRODUCTION ObservationPort gate, which stays missing.
        if(session.labStore!=null && session.labStore.view().hasDurableStore())
            session.missingPorts=9;
        session.supervisor.sample(server,session.view());
        // Real native J01 sensing, but no memory CAS or gameplay simTick.
    }
    private void pollProduction(MinecraftServer server,Session session){
        if(session.phase==Phase.FAILED_SAFE)return;
        try{
            if(session.pendingProduction!=null){
                if(!session.pendingProduction.isDone())return;
                var loaded=session.pendingProduction.getNow(null);var configuration=loaded.configuration();
                var identity=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY);
                var owner=new DirectorNativeStoreBootstrap.MinecraftServerActor(){public void assertActor(){actor(server);}public MinecraftServer server(){return server;}};
                session.productionStore=new DirectorNativeStoreBootstrap(owner,identity,loaded.declaredQa()==null?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY,configuration);
                var limits=StoreOptions.fromConfig(configuration,32,128);
                session.productionStore.open(server.getWorldPath(LevelResource.ROOT).resolve("proutlost/director"),limits);
                // Keep the immutable loaded bundle until the asynchronous WAL bind completes.
                session.pendingProduction=null;session.productionBundle=loaded.approved();session.declaredQa=loaded.declaredQa();
            }
            if(session.productionStore==null)return;
            session.productionStore.poll();var storeView=session.productionStore.view();
            if(storeView.phase()==DirectorNativeStoreBootstrap.Phase.FAILED_SAFE)throw new IllegalStateException("production storage recovery failed");
            var recovered=session.declaredQa==null?session.productionStore.productionRecoveredStore():session.productionStore.perceptionStore();if(recovered.isEmpty())return;
            if(session.production==null){
                if(session.declaredQa==null)session.production=new DirectorProductionRuntime(server,recovered.orElseThrow(),session.productionBundle,gameBus);
                else{
                    var inputs=session.declaredQa.complete(recovered.orElseThrow());
                    if(inputs.isEmpty()){session.productionDiagnostic="DECLARED_SYNTHETIC_WAIT_RETAINED_J04_ROUTES_OR_TWO_CURRENT_J06_CLIENTS_AND_REAL_SURVEYED_EXITS";return;}
                    session.production=DirectorProductionRuntime.declaredSynthetic(server,recovered.orElseThrow(),inputs.orElseThrow(),gameBus);
                }
            }
            session.production.poll();session.missingPorts=session.production.missingPorts();session.productionDiagnostic=session.production.diagnostic();
            session.phase=switch(session.production.phase()){
                case RUNNING->Phase.RUNNING;case PAUSED->Phase.PAUSED;case FAILED_SAFE->Phase.FAILED_SAFE;
                case STOPPING->Phase.STOPPING;case CLOSED->Phase.STOPPED;default->Phase.RECOVERING;};
        }catch(RuntimeException failure){
            session.phase=Phase.FAILED_SAFE;session.productionDiagnostic=failure.getClass().getSimpleName()+":"+failure.getMessage();
            if(session.production!=null)session.production.close();
            System.out.println("DIRECTOR_PRODUCTION_BOOT_FAILED_SAFE "+session.productionDiagnostic);
        }
    }
    public boolean productionConfigured(){return productionManifest.isPresent()||declaredQaConfigured;}
    public Optional<DirectorProductionRuntime> productionRuntime(MinecraftServer server){
        actor(server);var s=sessions.get(server);return s==null||s.production==null||s.phase==Phase.FAILED_SAFE||s.phase==Phase.STOPPING||s.phase==Phase.STOPPED?Optional.empty():Optional.of(s.production);
    }
    public Optional<JournalStore> runtimePerceptionStore(MinecraftServer server){
        actor(server);var s=sessions.get(server);if(s==null||verifiedBootEpoch(server).isEmpty())return Optional.empty();
        var writer=productionRuntime(server).isPresent()?s.productionStore:s.labStore;
        return writer==null?Optional.empty():writer.perceptionStore();
    }
    public long runtimeClock(MinecraftServer server){
        actor(server);
        if(productionConfigured()){
            var session=sessions.get(server);
            if(session==null||session.production==null)throw new IllegalStateException("production clock unavailable");
            // Admission may be revoked while native cleanup still needs the
            // actual monotonic world clock. Clock data grants no authority.
            return session.production.clock();
        }
        return labAnchorClock(server);
    }
    public Optional<MemoryView> runtimeMemoryView(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().memory():labMemoryView(server);}
    public Optional<fr.proutlost.director.memory.RegionAnchorCoverage> runtimeRegionAnchorCoverage(MinecraftServer server){
        var p=productionRuntime(server);if(p.isPresent())return p.get().regionAnchorCoverage();
        actor(server);var s=sessions.get(server);return s==null||s.labMemory==null?Optional.empty():s.labMemory.anchorCoverage();
    }
    public Optional<Tick> runtimeObservationTick(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().observedTick():labObservationTick(server);}
    public Optional<RegionId> runtimePrimaryRegion(MinecraftServer server,BlockPosition point){var p=productionRuntime(server);return p.isPresent()?p.get().primary(point):labPrimaryRegion(server,point);}
    public Optional<fr.proutlost.director.api.port.PhysicalCatalogRuntimePort> physicalCatalogues(MinecraftServer server){return productionRuntime(server).map(DirectorProductionRuntime::physicalCatalogues);}
    public Optional<DirectorNativeGameplay> runtimeGameplay(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().gameplay():labGameplay(server);}
    public boolean runtimeMetadataIngressQuiescent(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().metadataQuiescent():labMetadataIngressQuiescent(server);}
    public boolean runtimeAnchorWritesQuiescent(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().anchorWritesQuiescent():labAnchorWritesQuiescent(server);}
    public Optional<Map<RegionId,Set<TagId>>> runtimeImportedRegionTags(MinecraftServer server){var p=productionRuntime(server);return p.isPresent()?p.get().regionTags():labImportedRegionTags(server);}
    public long labAnchorClock(MinecraftServer server){
        actor(server);var s=sessions.get(server);
        if(s==null||s.labObservation==null)throw new IllegalStateException("J01 clock unavailable");
        return s.labObservation.now();
    }
    public boolean labAnchorWritesQuiescent(MinecraftServer server){
        actor(server);var s=sessions.get(server);
        return labMetadataIngressQuiescent(server)&&(s.labGameplay==null||!s.labGameplay.metadataLaneBusy());
    }
    public boolean labMetadataIngressQuiescent(MinecraftServer server){
        actor(server);var s=sessions.get(server);
        if(s==null||s.labObservation==null||s.labAnchors==null||s.labIngestion==null||s.labMemory==null||
           s.j01FailedSafe||s.j01AnchorsFailedSafe||s.j04MemoryFailedSafe||
           !s.labMemory.status().complete()||s.labMemory.status().failClosed())return false;
        var phase=s.labIngestion.view().phase();
        return !s.labObservation.anchorOwner().traceBusy()&&s.labObservation.nextUncommittedAnchor().isEmpty()&&
            s.labAnchors.status().phase()==DirectorNativeAnchorWalIngestion.Phase.IDLE&&
            (phase==DirectorLabObservationWalIngestion.Phase.IDLE||
             phase==DirectorLabObservationWalIngestion.Phase.WAITING_FOR_MEMORY);
    }
    public Optional<java.util.Map<fr.proutlost.director.api.model.RegionId,java.util.Set<fr.proutlost.director.api.model.TagId>>> labImportedRegionTags(MinecraftServer server){
        actor(server);var s=sessions.get(server);return s==null||s.labGameplay==null?Optional.empty():s.labGameplay.importedRegionTags();
    }
    public Optional<DirectorNativeGameplay> labGameplay(MinecraftServer server) {
        actor(server);var session=sessions.get(server);
        return session==null?Optional.empty():Optional.ofNullable(session.labGameplay);
    }
    private void stopping(ServerStoppingEvent event) {
        var server=event.getServer();
        actor(server);
        var session=sessions.get(server);
        if(session==null)return;
        // Native stop probes still need this world's actor data and J06 receipts.
        // The runtime revokes admin admission while its existing cleanup drains.
        if(session.production!=null)session.production.close();
        DirectorNativeAdminReadBridge.close(server);
        session.phase=Phase.STOPPING;
        if(session.productionStore!=null){
            session.close=session.productionStore.closeAsync();
            var completion=session.close.toCompletableFuture();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            server.managedBlock(()->completion.isDone()||System.nanoTime()>=deadline);
            if(completion.isDone())session.productionStore.finishClose();
            else System.out.println("DIRECTOR_PRODUCTION_CHECKPOINT_CLOSE_UNCONFIRMED");
        }
        bootstrapWorker.shutdown();
        if(session.labGameplay!=null){session.labGameplay.close();session.labGameplay=null;}
        if(session.labObservation!=null){session.labObservation.close();session.labObservation=null;}
        if(session.labMemory!=null){session.labMemory.close();session.labMemory=null;}
        session.labPreview=null;
        if(session.labIngestion!=null){session.labIngestion.close();session.labIngestion=null;}
        if(session.labAnchors!=null){session.labAnchors.close();session.labAnchors=null;}
        if(session.labWorld!=null){session.labWorld.close();session.labWorld=null;}
        if(session.labScheduler!=null){session.labScheduler.close();session.labScheduler=null;}
        if(session.labStore!=null){
            session.close=session.labStore.closeAsync();
            // Shutdown is the sole bounded drain point; normal ticks never wait.
            // Both J04's writer and its close waiter are daemon threads. A callback
            // alone cannot keep the JVM alive until checkpoint/close is observed.
            if(DirectorShutdownDrain.await(session.close,TimeUnit.SECONDS.toNanos(10),server::managedBlock))
                session.labStore.finishClose();
            else System.out.println("DIRECTOR_NATIVE_CHECKPOINT_CLOSE_UNCONFIRMED");
        }
        if(session.ticks>0)
            System.out.println("DIRECTOR_NATIVE_TICKS_OBSERVED count="+session.ticks);
    }
    private void stopped(ServerStoppedEvent event) {
        var server=event.getServer();
        DirectorProductionCohortQa.release(server);
        actor(server);
        var session=sessions.remove(server);
        if(session!=null) {
            session.phase=Phase.STOPPED;
            lastStopped=session.view();
            if(lastStopped.admitsEvents())
                throw new IllegalStateException("Director retained admission on shutdown");
            // Completion may follow Minecraft shutdown: writer drains off the server actor.
            if(session.close!=null)
                session.close.whenComplete((ok,failure)->System.out.println(
                    failure==null ? "DIRECTOR_NATIVE_AUTOSTORE_CLOSE_PASS"
                                  : "DIRECTOR_NATIVE_AUTOSTORE_CLOSE_FAILED_SAFE"));
            System.out.println(session.production==null?"DIRECTOR_NATIVE_BOOTSTRAP_SHUTDOWN_PASS":
                "DIRECTOR_PRODUCTION_STOPPED cleanup="+(session.production.shutdownDrained()?"DURABLE":"RECOVERY_REQUIRED"));
        }
    }
    public View snapshot(MinecraftServer server) {
        actor(server);
        Session session=sessions.get(server);
        return session==null?lastStopped:session.view();
    }
    public Optional<DirectorNativeStoreBootstrap.View> labStoreSnapshot(MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        return session==null || session.labStore==null ? Optional.empty()
            :Optional.of(session.labStore.view());
    }
    public Optional<DirectorNativeWorldStateBootstrap.Status> worldBootstrapStatus(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labWorld==null?Optional.empty():
            Optional.of(session.labWorld.status());
    }
    public Optional<fr.proutlost.director.api.model.DirectorWorldState> recoveredWorld(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labWorld==null?Optional.empty():
            session.labWorld.world();
    }
    public Optional<DirectorNativeDecisionScheduler.Status> decisionSchedulerStatus(
            MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labScheduler==null?Optional.empty():
            Optional.of(session.labScheduler.status());
    }
    /** Actor-bound sparse primary region from the published J04 index.
     * An unknown position remains unknown; this is not event admission. */
    public Optional<fr.proutlost.director.api.model.RegionId> labPrimaryRegion(
            MinecraftServer server,
            fr.proutlost.director.api.model.BlockPosition position){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labObservation==null?Optional.empty():
            session.labObservation.indexedPrimary(position);
    }
    public Optional<DirectorLabRegionIndex.Status> regionIndexView(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labObservation==null?Optional.empty():
            Optional.of(session.labObservation.regionIndexStatus());
    }
    public Optional<DirectorNativeCandidateCatalog.View> candidateCatalogView(
            MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labCandidateCatalog==null?Optional.empty():
            Optional.of(session.labCandidateCatalog.status());
    }
    /** J02 hash-verified references only, never a provider permission. */
    public Optional<DirectorNativeCandidateCatalog.DependencyIndex> candidateDependencies(
            MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labCandidateCatalog==null?Optional.empty():
            Optional.of(session.labCandidateCatalog.dependencies());
    }
    /** A candidate is a known J07 score fixture only after the lab preview
     * has actually bound; catalog metadata alone cannot authorize simulation.
     * Actor-only, read-only, never a physical or production capability. */
    public boolean labPreviewFixtureRegistered(MinecraftServer server,
            fr.proutlost.director.api.model.ResourceId fixture) {
        actor(server);
        Objects.requireNonNull(fixture);
        var session=sessions.get(server);
        return session!=null && session.labPreview!=null &&
            session.labPreview.supportedFixtures().contains(fixture);
    }
    /** Only authenticated J07 may request a score-only laboratory preview.
     * Any unknown, cold, or unbound reference stays absent. */
    public Optional<fr.proutlost.director.api.model.AdminRow> labPreview(
            MinecraftServer server,fr.proutlost.director.api.model.ResourceId fixture,
            fr.proutlost.director.api.model.DirectorWorldState actualWorld) {
        actor(server);
        var session=sessions.get(server);
        if(session==null || session.labPreview==null ||
           session.labObservation==null || session.labMemory==null ||
           session.labMemory.status().failClosed() ||
           !session.labMemory.status().complete() ||
           session.labObservation.snapshot().isEmpty() ||
           session.labMemory.view().isEmpty())
            return Optional.empty();
        return session.labPreview.calculate(fixture,actualWorld,
            session.labObservation.snapshot().orElseThrow(),
            session.labMemory.view().orElseThrow(),
            session.labMemory.status().lastDurableSequence());
    }
    public Optional<DirectorLabObservationWalIngestion.View> labIngestionView(
            MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labIngestion==null?Optional.empty():
            Optional.of(session.labIngestion.view());
    }
    public Optional<DirectorNativeMemoryProjectionQualification.View> memoryProjectionView(
            MinecraftServer server){
        var p=productionRuntime(server);if(p.isPresent())return p.get().memoryStatus();
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labMemory==null?Optional.empty():
            Optional.of(session.labMemory.status());
    }
    /** Native GameTest only: stage the same anchor callback real place/break uses. */
    public void labTestStageAnchor(MinecraftServer server,
            fr.proutlost.director.api.model.AnchorRecord anchor){
        actor(server);
        if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))
            throw new IllegalStateException("anchor injection requires GameTests");
        var session=sessions.get(server);
        if(session==null||session.labObservation==null||session.labAnchors==null)
            throw new IllegalStateException("J01/J04 native test not ready");
        session.labObservation.labTestStageAnchor(anchor);
    }
    public List<fr.proutlost.director.api.model.AnchorRecord> labDurableAnchorsNear(
            MinecraftServer server,fr.proutlost.director.api.model.BlockPosition position){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labObservation==null?List.of():
            session.labObservation.durableNearbyAnchors(position);
    }
    public void labTestCampWindow(MinecraftServer server,
            fr.proutlost.director.api.model.AnchorId anchor){
        actor(server);
        if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))
            throw new IllegalStateException("camp fixture requires GameTests");
        var session=sessions.get(server);
        if(session==null || session.labObservation==null)
            throw new IllegalStateException("J01 server actor unavailable");
        session.labObservation.labTestCampWindow(anchor);
    }
    public Optional<DirectorNativeAnchorWalIngestion.Status> labAnchorStatus(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labAnchors==null?Optional.empty():
            Optional.of(session.labAnchors.status());
    }
    public Optional<fr.proutlost.director.api.model.MemoryView> labMemoryView(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labMemory==null?Optional.empty():
            session.labMemory.view();
    }
    public Optional<fr.proutlost.director.api.port.DurableStore> labReadOnlyStoreSnapshot(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labStore==null?Optional.empty():
            session.labStore.labReadOnlyMemoryStore();
    }
    public Optional<DirectorNativeObservationQualification.View> observationView(
            MinecraftServer server){
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labObservation==null?Optional.empty():
            Optional.of(session.labObservation.view());
    }
    public Optional<fr.proutlost.director.api.model.Tick> labObservationTick(MinecraftServer server){
        actor(server);var session=sessions.get(server);
        return session==null||session.labObservation==null||session.j01FailedSafe?Optional.empty():
            Optional.of(new fr.proutlost.director.api.model.Tick(session.labObservation.now()));
    }
    public Optional<fr.proutlost.director.api.model.SnapshotBatch> observationSnapshot(
            MinecraftServer server){
        var p=productionRuntime(server);if(p.isPresent())return p.get().snapshot();
        actor(server);
        var session=sessions.get(server);
        return session==null||session.labObservation==null?Optional.empty():
            session.labObservation.snapshot();
    }
    public DirectorNativeAutoSupervisor.Snapshot autoSnapshot(MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        if(session==null)throw new IllegalStateException("Director session unavailable");
        return session.supervisor.snapshot();
    }
    /** Network sessions may start only after verified J04 identity binding. */
    public java.util.Optional<fr.proutlost.director.api.model.Epoch> verifiedBootEpoch(
            MinecraftServer server) {
        actor(server);
        var session=sessions.get(server);
        if(session!=null&&session.productionStore!=null&&session.phase!=Phase.STOPPING&&session.phase!=Phase.STOPPED&&session.phase!=Phase.FAILED_SAFE)return session.productionStore.recoveredBootEpoch();
        return session==null || session.phase!=Phase.OBSERVING_DISABLED || session.labStore==null
            ?java.util.Optional.empty():session.labStore.recoveredBootEpoch();
    }
    public int activeServerCount(){return sessions.size();}
}
