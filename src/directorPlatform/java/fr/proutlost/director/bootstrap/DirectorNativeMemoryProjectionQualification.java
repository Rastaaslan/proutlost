package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.memory.*;
import java.util.*;
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
        memory=new DurableMemoryPort(viewOnlyStore,new MemorySettings(testConfig,mode==ProviderMode.PRODUCTION));
        scopes=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));
    }
    private void requireActor(){
        if(!server.isSameThread())
            throw new IllegalStateException("J04 memory projection requires Minecraft actor");
    }
    public void poll(){
        requireActor();
        if(closed||failed)return;
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
        return Optional.of(memory.view(scopes));
    }
    public ProviderMode mode(){return mode;}
    public fr.proutlost.director.api.port.MemoryPort memoryPort(){requireActor();return memory;}
    public View status(){
        requireActor();
        return new View(!closed,complete&&!failed,failed,scans,lastDurableSequence,
            regionMemories,operationalPlayers,false,mode==ProviderMode.PRODUCTION);
    }
    @Override public void close(){
        requireActor();
        closed=true;complete=false;cursor=Optional.empty();
    }
}
