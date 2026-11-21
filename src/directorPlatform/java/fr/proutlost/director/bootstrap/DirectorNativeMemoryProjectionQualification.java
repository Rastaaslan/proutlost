package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.memory.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import fr.proutlost.director.persistence.JournalStore;
import net.minecraft.server.MinecraftServer;

/**
 * Actor-only J04 WAL -> J01/J03 immutable memory read projection. The
 * available memory data is genuinely read from recovered J04. No writes,
 * no synthetic memory, no authoritative "no memory" if page hydration fails.
 * This lab does not qualify event admission or observation ingestion.
 */
public final class DirectorNativeMemoryProjectionQualification implements AutoCloseable {
    public record View(boolean j04Bound,boolean complete,boolean failClosed,
                       long completedScans,long lastDurableSequence,
                       int observedRegionMemories,int observedPlayers,
                       boolean permittedToWrite,boolean approvedForProduction) {
        public View {
            if(completedScans<0||lastDurableSequence<0||observedRegionMemories<0||
               observedPlayers<0||permittedToWrite)
                throw new IllegalArgumentException("unqualified J04 memory projection");
            if(failClosed && complete)
                throw new IllegalArgumentException("failed memory scan cannot be complete");
        }
    }
    // Bounded lab read from immutable in-memory J04 pages. Large histories
    // must not delay actual camp/region publication for many minutes.
    private static final long CADENCE_TICKS=20;
    private static final int PAGE_LIMIT=512;
    private final MinecraftServer server;
    private final DurableStore viewOnlyStore;
    private final StoreSession frozenSession;
    private final DurableMemoryPort memory;
    private final WorldId world;
    private final ProviderMode mode;
    private final ScopeSet scopes;
    private final MemorySettings settings;
    private final int demandCellBlocks;
    private CompletableFuture<BoundedMetadataProjection.View> hydration;
    private BoundedMetadataProjection.View selected;
    private BoundedMetadataProjection.Demand capacityBlockedDemand;
    private boolean generationMode;
    private String selectionDiagnostic="LEGACY_CHRONOLOGY";
    private Supplier<Set<ScopeRef>> pendingScopes=Set::of;
    private Supplier<Set<AnchorId>> pendingAnchors=Set::of;
    private long scans,lastDurableSequence,lastPoll=-CADENCE_TICKS;
    private Optional<String> cursor=Optional.empty();
    private Sequence scanFrom=new Sequence(0);
    private boolean complete,failed,closed;
    private int regionMemories,operationalPlayers;
    private int lastReportedPlayers=-1,lastReportedRegions=-1;

    public DirectorNativeMemoryProjectionQualification(MinecraftServer server,
            WorldId world,DurableStore viewOnlyStore,ConfigView testConfig) {
        this(server,world,viewOnlyStore,testConfig,ProviderMode.TEST_ONLY);
    }
    public DirectorNativeMemoryProjectionQualification(MinecraftServer server,
            WorldId world,DurableStore viewOnlyStore,ConfigView testConfig,ProviderMode mode) {
        this.mode=Objects.requireNonNull(mode);
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        this.viewOnlyStore=Objects.requireNonNull(viewOnlyStore);
        requireActor();
        if((mode==ProviderMode.TEST_ONLY&&!(viewOnlyStore instanceof DirectorLabReadOnlyStore)) ||
           (mode==ProviderMode.PRODUCTION&&!(viewOnlyStore instanceof fr.proutlost.director.persistence.JournalStore)) ||
           testConfig.calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING) ||
           !viewOnlyStore.session().worldId().equals(world))
            throw new IllegalArgumentException("unqualified memory store/profile");
        frozenSession=viewOnlyStore.session();
        settings=new MemorySettings(testConfig,mode==ProviderMode.PRODUCTION);
        demandCellBlocks=testConfig.values().get("spatial.region_cell_blocks").value().intValueExact();
        memory=new DurableMemoryPort(viewOnlyStore,settings);
        scopes=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));
    }
    private void requireActor(){
        if(!server.isSameThread())
            throw new IllegalStateException("J04 memory projection requires Minecraft actor");
    }
    public void poll(){
        requireActor();
        if(closed||failed)return;
        var active=metadataSnapshot();
        if(active.isPresent()&&(!active.get().generations().isEmpty()||generationMode)) {
            generationMode=true;pollSelected(active.get());return;
        }
        long now=server.overworld().getGameTime();
        boolean starting=cursor.isEmpty();
        if(starting&&now-lastPoll<CADENCE_TICKS)return;
        if(starting)lastPoll=now;
        try{
            if(!frozenSession.equals(viewOnlyStore.session()))
                throw new IllegalStateException("J04 boot/session changed");
            if(starting){
                if(scans>0){
                    // A future N+1 query is NOT_RESIDENT, never proven empty.
                    // Keep the last fully published memory while no new cut exists.
                    var latest=mode==ProviderMode.TEST_ONLY?
                        ((DirectorLabReadOnlyStore)viewOnlyStore).residentAggregateKeys(Set.of(AggregateKind.WORLD),4096):
                        ((fr.proutlost.director.persistence.JournalStore)viewOnlyStore).residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);
                    if(latest.isEmpty())return;
                    if(!latest.get().session().equals(frozenSession))
                        throw new IllegalStateException("memory discovery session changed");
                    if(latest.get().cut().value()<=lastDurableSequence)return;
                }
                scanFrom=new Sequence(scans==0?0:Math.incrementExact(lastDurableSequence));
            }
            // A staged page always retains its original from-sequence.
            // Only the final verified page atomically publishes the projection.
            var page=memory.refresh(new StoreQuery(world,Set.of(),
                scanFrom,cursor,PAGE_LIMIT));
            cursor=page.nextCursor();
            if(!page.hasMore()){
                var actual=memory.view(scopes);
                regionMemories=actual.regions().size();
                operationalPlayers=actual.operationalPlayers().size();
                lastDurableSequence=page.lastDurableSequence().value();
                scans=Math.addExact(scans,1);
                complete=true;
                cursor=Optional.empty();
                if(operationalPlayers!=lastReportedPlayers ||
                   regionMemories!=lastReportedRegions){
                    System.out.println("DIRECTOR_NATIVE_J04_MEMORY_READBACK players="+
                        operationalPlayers+" regions="+regionMemories+
                        " durable_sequence="+lastDurableSequence);
                    lastReportedPlayers=operationalPlayers;
                    lastReportedRegions=regionMemories;
                }
            }else complete=false;
        }catch(RuntimeException invalid){
            // A concurrent J03/J05 durable append invalidates the previous
            // WAL page cursor. That is a normal STALE snapshot, not corrupt
            // memory. Restart from the last fully published cut, not staging.
            // Never publish the partial staging that lost its stamp.
            if(cursor.isPresent() && invalid instanceof IllegalStateException &&
               "Memory projection requires hydrated page: STALE"
                    .equals(invalid.getMessage())) {
                cursor=Optional.empty();
                scanFrom=new Sequence(scans==0?0:Math.incrementExact(lastDurableSequence));
                complete=false;
                System.out.println("DIRECTOR_NATIVE_J04_MEMORY_CURSOR_STALE_RESTART");
                return;
            }
            failed=true;complete=false;
            cursor=Optional.empty();
            System.out.println("DIRECTOR_NATIVE_J04_MEMORY_PROJECTION_FAILED_SAFE "+
                invalid.getClass().getSimpleName()+" reason="+invalid.getMessage());
        }
    }
    /** Even an AVAILABLE empty projection is not a calibrated safety fact. */
    public Optional<MemoryView> view(){
        requireActor();
        if(!complete||failed||closed)return Optional.empty();
        if(generationMode) {
            if(selected==null)return Optional.empty();
            return Optional.of(memory.view(selected.scopes()));
        }
        return Optional.of(memory.view(scopes));
    }
    public void pendingDemand(Supplier<Set<ScopeRef>> scopes,Supplier<Set<AnchorId>> anchors){
        requireActor();pendingScopes=Objects.requireNonNull(scopes);pendingAnchors=Objects.requireNonNull(anchors);
    }
    private BoundedMetadataProjection.Demand demand() {
        var players=new HashSet<PlayerId>();var cells=new HashSet<BoundedMetadataProjection.Cell>();
        for(var player:server.getPlayerList().getPlayers()) {
            players.add(new PlayerId(player.getUUID()));
            var position=player.blockPosition();
            var point=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),
                position.getX(),position.getY(),position.getZ());
            cells.add(BoundedMetadataProjection.Demand.cell(point,demandCellBlocks));
        }
        return new BoundedMetadataProjection.Demand(players,cells,demandCellBlocks,pendingScopes.get(),pendingAnchors.get());
    }
    private Optional<JournalStore.MetadataSnapshot> metadataSnapshot(){
        if(viewOnlyStore instanceof JournalStore store)return store.metadataSnapshot();
        return ((DirectorLabReadOnlyStore)viewOnlyStore).metadataSnapshot();
    }
    private static boolean covers(BoundedMetadataProjection.View selection,BoundedMetadataProjection.Demand demand){
        if(!selection.demand().players().equals(demand.players())||!selection.demand().cells().containsAll(demand.cells()))return false;
        var refs=new HashSet<>(selection.demand().pinned());refs.addAll(selection.scopes().scopes());
        if(!refs.containsAll(demand.pinned()))return false;
        var anchors=new HashSet<>(selection.demand().anchors());
        selection.states().values().forEach(s->{if(s instanceof AnchorAggregate a)anchors.add(a.value().anchorId());});
        return anchors.containsAll(demand.anchors());
    }
    private void pollSelected(JournalStore.MetadataSnapshot snapshot) {
        try {
            if(!frozenSession.equals(snapshot.session())||!frozenSession.equals(viewOnlyStore.session())||metadataSnapshot().isEmpty())
                throw new IllegalStateException("metadata selection session revoked");
            var wanted=demand();long now=server.overworld().getGameTime();
            if(capacityBlockedDemand!=null&&capacityBlockedDemand.equals(wanted)&&now-lastPoll<CADENCE_TICKS)return;
            capacityBlockedDemand=null;
            if(hydration!=null) {
                if(!hydration.isDone())return;
                var result=hydration.join();hydration=null;
                var current=metadataSnapshot().orElseThrow(()->new IllegalStateException("metadata snapshot unavailable"));
                // Every page used one immutable cut. A newer generation or a
                // changed demand invalidates the whole candidate, never one page.
                if(!result.snapshot().generations().equals(current.generations())||!covers(result,wanted)) {
                    complete=false;selectionDiagnostic="DEMAND_OR_GENERATION_CHANGED_RETRY";return;
                }
                if(!currentMetadataHeads(result)){
                    complete=false;selectionDiagnostic="REFRESHING_SELECTED_HEADS";
                    hydration=hydrate(current,wanted,result);return;
                }
                if(result.snapshot().cut().value()<lastDurableSequence)
                    throw new IllegalStateException("metadata selection cut regression");
                memory.replaceProjection(result.scopes(),result.states().values());selected=result;
                var actual=memory.view(result.scopes());regionMemories=actual.regions().size();
                operationalPlayers=actual.operationalPlayers().size();lastDurableSequence=result.snapshot().cut().value();
                scans=Math.incrementExact(scans);complete=true;lastPoll=now;
                selectionDiagnostic="SCOPED_READY metadata="+result.states().keySet().stream()
                    .filter(k->Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY,AggregateKind.ANCHOR,AggregateKind.LOCATION).contains(k.kind())).count()
                    +" examined="+result.examinedRows()+" cut="+lastDurableSequence;
                System.out.println("DIRECTOR_NATIVE_J04_SELECTED_MEMORY "+selectionDiagnostic);return;
            }
            boolean changed=selected==null||!covers(selected,wanted)
                ||!selected.snapshot().generations().equals(snapshot.generations());
            if(!changed&&(snapshot.cut().value()<=lastDurableSequence||now-lastPoll<CADENCE_TICKS))return;
            complete=false;selectionDiagnostic="HYDRATING_SCOPED_MEMORY";
            hydration=hydrate(snapshot,wanted,selected);
        }catch(RuntimeException invalid) {
            Throwable cause=invalid;while(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof BoundedMetadataProjection.DemandCapacityException){
                complete=false;hydration=null;capacityBlockedDemand=demand();lastPoll=server.overworld().getGameTime();
                selectionDiagnostic="LOCAL_DEMAND_CAPACITY_DEFERRED "+cause.getMessage();
                System.out.println("DIRECTOR_NATIVE_J04_SELECTED_MEMORY "+selectionDiagnostic);return;
            }
            failed=true;complete=false;hydration=null;
            selectionDiagnostic="FAILED "+invalid.getClass().getSimpleName()+":"+invalid.getMessage();
            System.out.println("DIRECTOR_NATIVE_J04_SELECTED_MEMORY_FAILED_SAFE "+selectionDiagnostic);
        }
    }
    private CompletableFuture<BoundedMetadataProjection.View> hydrate(JournalStore.MetadataSnapshot snapshot,
            BoundedMetadataProjection.Demand wanted,BoundedMetadataProjection.View previous){
        return (viewOnlyStore instanceof JournalStore store?BoundedMetadataProjection.refresh(store,previous,snapshot,wanted,settings):
            ((DirectorLabReadOnlyStore)viewOnlyStore).refreshMetadataProjection(snapshot,previous,wanted,settings)).toCompletableFuture();
    }
    private boolean currentMetadataHeads(BoundedMetadataProjection.View view){
        if(viewOnlyStore instanceof JournalStore store){var snapshot=store.metadataSnapshot();
            return snapshot.isPresent()&&view.currentHeads(store,snapshot.get());}
        return ((DirectorLabReadOnlyStore)viewOnlyStore).currentMetadataHeads(view);
    }
    /** Actor-only pre-write fence: a complete old cut is not current CAS authority. */
    public boolean currentHeads(){
        requireActor();if(!complete||failed||closed)return false;
        if(!generationMode)return true;
        if(selected!=null&&currentMetadataHeads(selected))return true;
        complete=false;lastPoll=-CADENCE_TICKS;selectionDiagnostic="SELECTED_HEAD_CHANGED_BEFORE_WRITE";return false;
    }
    public Optional<RegionAnchorCoverage> anchorCoverage(){
        requireActor();return currentHeads()&&generationMode&&selected!=null?selected.anchorCoverage():Optional.empty();
    }
    public Set<AggregateKey> selectedKeys() {
        requireActor();return generationMode&&selected!=null?selected.states().keySet():Set.of();
    }
    public String selectionDiagnostic(){requireActor();return selectionDiagnostic;}
    public ProviderMode mode(){return mode;}
    public fr.proutlost.director.api.port.MemoryPort memoryPort(){requireActor();return memory;}
    public View status(){
        requireActor();
        return new View(!closed,complete&&!failed,failed,scans,lastDurableSequence,
            regionMemories,operationalPlayers,false,mode==ProviderMode.PRODUCTION);
    }
    @Override public void close(){
        requireActor();
        closed=true;complete=false;cursor=Optional.empty();hydration=null;selected=null;
    }
}
