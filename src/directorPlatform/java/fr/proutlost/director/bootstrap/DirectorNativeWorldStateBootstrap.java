package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;

/**
 * Qualification-only durable WORLD aggregate at server bootstrap.
 *
 * A recovered world is resumed with its previous revision, pacing and simTick;
 * only the session generation / boot fence is rebound. The world stays PAUSED
 * and READ_ONLY_SAFE with TEST calibration. This is NOT the production
 * configuration/health activation path; no event admission or mutation.
 */
public final class DirectorNativeWorldStateBootstrap implements AutoCloseable {
    public enum Phase { NEW, WRITE_PENDING, VERIFIED_PAUSED, FAILED_SAFE, CLOSED }
    public record Status(Phase phase, boolean created, boolean resumed,
                         long worldRevision,long simTick,boolean admittedGameplay) {
        public Status {
            if(worldRevision<0||simTick<0||admittedGameplay)
                throw new IllegalArgumentException("unqualified WORLD bootstrap");
        }
    }
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final ConfigView testConfig;
    private final ProviderMode mode;
    private final boolean declaredSynthetic;
    private final Fingerprint catalogHash;
    private final ResourceId profile;
    private Phase phase=Phase.NEW;
    private boolean created, resumed;
    private DirectorWorldState pending;
    private CompletableFuture<CommitAck> commit;
    private TransactionId transaction;
    private DirectorWorldState verified;
    private String reason="";

    public DirectorNativeWorldStateBootstrap(MinecraftServer server,WorldId world,
                                             JournalStore store,ConfigView config) {
        this(server,world,store,config,ProviderMode.TEST_ONLY,new Fingerprint("0".repeat(64)),new ResourceId("proutlost_director:lab_only"));
    }
    public DirectorNativeWorldStateBootstrap(MinecraftServer server,WorldId world,
            JournalStore store,ConfigView config,ProviderMode mode,Fingerprint catalogHash,ResourceId profile) {
        this(server,world,store,config,mode,catalogHash,profile,false);
    }
    static DirectorNativeWorldStateBootstrap declaredSynthetic(MinecraftServer server,WorldId world,JournalStore store,DirectorProductionRuntimeQa.Inputs inputs) {
        Objects.requireNonNull(inputs).require(server);
        return new DirectorNativeWorldStateBootstrap(server,world,store,inputs.configuration(),ProviderMode.TEST_ONLY,inputs.catalog().hash(),inputs.defaultProfile(),true);
    }
    private DirectorNativeWorldStateBootstrap(MinecraftServer server,WorldId world,JournalStore store,ConfigView config,ProviderMode mode,Fingerprint catalogHash,ResourceId profile,boolean declaredSynthetic) {
        this.declaredSynthetic=declaredSynthetic;
        this.mode=Objects.requireNonNull(mode);this.catalogHash=Objects.requireNonNull(catalogHash);this.profile=Objects.requireNonNull(profile);
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);
        this.testConfig=Objects.requireNonNull(config);
        actor();
        session=store.session();
        if(!session.worldId().equals(world) ||
           store.readOnlySafe() ||
           config.calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("WORLD boot needs recovered LAB J04");
    }
    private void actor(){
        if(!server.isSameThread())
            throw new IllegalStateException("WORLD initialization server actor");
    }
    private void failure(String code){
        phase=Phase.FAILED_SAFE;
        reason=code;
        System.out.println("DIRECTOR_NATIVE_J03_WORLD_BOOT_FAILED_SAFE "+code);
    }
    private StoreQuery query(){
        return new StoreQuery(world,
            Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
            new Sequence(0),Optional.empty(),1);
    }
    public void poll(){
        actor();
        if(phase==Phase.VERIFIED_PAUSED || phase==Phase.FAILED_SAFE ||
           phase==Phase.CLOSED)return;
        try{
            if(!session.equals(store.session())){
                failure("SESSION_CHANGED");return;
            }
            if(phase==Phase.NEW){
                var query=query();
                var read=store.view(query);
                ReadContracts.validate(read,query,session);
                if(read.status()!=ReadStatus.AVAILABLE){
                    failure("WORLD_READ_NOT_VERIFIED");return;
                }
                var rows=read.page().orElseThrow().aggregates();
                if(rows.size()>1){failure("WORLD_DUPLICATE");return;}
                DirectorWorldState previous=null;
                if(!rows.isEmpty()){
                    if(!(rows.getFirst().state() instanceof WorldAggregate w)){
                        failure("WORLD_WRONG_TYPE");return;
                    }
                    previous=w.value();
                    if(!previous.worldId().equals(world)){
                        failure("WORLD_ID_MISMATCH");return;
                    }
                }
                long current=server.getTickCount();
                long logicalTick=previous==null?current:
                    Math.addExact(previous.simTick().value(),1);
                var metadata=store.metadataSnapshot().flatMap(store::metadataClockFloor)
                    .orElseThrow(()->new IllegalStateException("verified metadata clock unavailable"));
                if(metadata.value()>0)logicalTick=Math.max(logicalTick,Math.incrementExact(metadata.value()));
                Tick tick=new Tick(logicalTick);
                AggregateHeader header=new AggregateHeader(1,world.value(),
                    previous==null?new Revision(1):previous.header().revision().plus(1),
                    previous==null?new Sequence(0):previous.header().lastSequence(),tick);
                EngineControlState control=previous==null?
                    new EngineControlState(1,new Revision(0),tick,tick,
                        new UnitValue(0),tick,Optional.empty(),new UnitValue(0)):
                    previous.engineControl();
                pending=new DirectorWorldState(header,world,
                    session.storeGeneration(),tick,RunControl.PAUSED,
                    HealthMode.READ_ONLY_SAFE,
                    previous==null?PacingState.REST:previous.pacingState(),
                    previous==null?GlobalTension.CALM:previous.tension(),
                    (mode==ProviderMode.PRODUCTION||declaredSynthetic)?profile:previous==null?new ResourceId("proutlost_director:lab_only"):previous.intensityProfileId(),
                    // No validated catalogue exists. Keep an inherited hash;
                    // do not claim a PRODUCTION catalog or approved config.
                    (mode==ProviderMode.PRODUCTION||declaredSynthetic)?catalogHash:previous==null?new Fingerprint("0".repeat(64)):previous.catalogHash(),
                    testConfig.provenanceHash(),session.bootEpoch(),
                    previous==null?new Sequence(0):previous.lastCheckpointSeq(),
                    control);
                var scope=new ScopeRef(ScopeKind.WORLD,world.value());
                var pre=previous==null?new Revision(0):previous.header().revision();
                transaction=new TransactionId(UUID.nameUUIDFromBytes(
                    ("world-boot-v1/"+world.value()+"/"+session.bootEpoch().value())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var batch=ResultTransaction.batch(transaction,
                    session.storeGeneration(),Map.of(scope,pre),
                    List.of(new DurableAggregate(new WorldAggregate(pending))),List.of());
                created=previous==null;resumed=previous!=null;
                commit=store.append(batch).toCompletableFuture();
                phase=Phase.WRITE_PENDING;
                return;
            }
            if(phase==Phase.WRITE_PENDING){
                if(commit==null||!commit.isDone())return;
                if(commit.isCompletedExceptionally()){
                    failure("WORLD_FSYNC_EXCEPTION");return;
                }
                var ack=commit.getNow(null);
                if(ack==null || ack.ackStatus()!=AckStatus.DURABLE ||
                   !ack.transactionId().equals(transaction)){
                    failure("WORLD_WAL_NOT_DURABLE");return;
                }
                var query=query();
                var read=store.view(query);
                ReadContracts.validate(read,query,session);
                if(read.status()!=ReadStatus.AVAILABLE ||
                   read.page().orElseThrow().aggregates().size()!=1){
                    failure("WORLD_READBACK_MISSING");return;
                }
                var state=read.page().orElseThrow().aggregates().getFirst().state();
                if(!(state instanceof WorldAggregate w) ||
                   !w.value().equals(pending) ||
                   w.value().runControl()!=RunControl.PAUSED ||
                   w.value().healthMode()!=HealthMode.READ_ONLY_SAFE){
                    failure("WORLD_READBACK_MISMATCH");return;
                }
                verified=pending;
                pending=null;commit=null;
                phase=Phase.VERIFIED_PAUSED;
                System.out.println("DIRECTOR_NATIVE_J03_WORLD_BOOT_DURABLE "+
                    (created?"created":"resumed")+" revision="+
                    verified.header().revision().value()+" tick="+verified.simTick().value()+
                    " gameplay=false");
            }
        }catch(RuntimeException failure){
            failure(failure.getClass().getSimpleName());
        }
    }
    public Status status(){
        actor();
        return new Status(phase,created,resumed,
            verified==null?0:verified.header().revision().value(),
            verified==null?0:verified.simTick().value(),false);
    }
    public Optional<DirectorWorldState> world(){
        actor();
        return phase==Phase.VERIFIED_PAUSED?Optional.of(verified):Optional.empty();
    }
    public String lastFailure(){actor();return reason;}
    @Override public void close(){actor();phase=Phase.CLOSED;}
}
