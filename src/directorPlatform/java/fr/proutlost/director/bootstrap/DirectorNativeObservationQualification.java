package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.observation.NeoForgeObservation;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.ObservationPort;
import java.util.function.LongSupplier;
import java.time.Instant;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;

/**
 * J01 proof lane: real native server observation of bounded player presence,
 * eligibility and J06 capability negotiation. No invented region membership,
 * no chunk loads, no game decisions, no source of commit authority.
 *
 * Production activation requires an explicitly approved profile and production
 * provider graph; the legacy constructor remains a strict TEST_ONLY lane.
 */
public final class DirectorNativeObservationQualification implements AutoCloseable {
    public record View(long sampledAtTick,long samples,int currentPlayers,
                       int negotiatedClients,int pendingCampAnchors,
                       Set<SensorKind> unavailable,boolean testProfile,
                       boolean admitsEvents) {
        public View {
            unavailable=Set.copyOf(unavailable);
            if(sampledAtTick<0 || samples<0 || currentPlayers<0 ||
               negotiatedClients<0 || pendingCampAnchors<0 ||
               admitsEvents)
                throw new IllegalArgumentException("unqualified observation admission");
        }
    }
    private final long sampleCadence;
    private final MinecraftServer server;
    private final NeoForgeObservation observation;
    private final DirectorNativeAnchorOwner anchorOwner;
    private final WorldId world;
    private final ConfigView configuration;
    private final ProviderMode mode;
    private final LongSupplier clock;
    private final DirectorLabRegionIndex regionIndex;
    private final LinkedHashMap<AnchorId,Deque<AnchorRecord>> pendingAnchors=
        new LinkedHashMap<>();
    private Map<AnchorId,AnchorRecord> knownAnchors=Map.of();
    private DirectorLabCampAnchorIndex campAnchorIndex;
    private long campAnchorIndexSequence=-1;
    private static final int MAX_ANCHOR_VERSIONS=128;
    private SnapshotBatch last;
    private long lastTick;
    private long samples;
    private boolean closed;
    private boolean samplingSuspended;
    private boolean incomplete;
    private int lastReportedPlayers=-1,lastReportedNegotiated=-1;

    public DirectorNativeObservationQualification(MinecraftServer server,
                                                   WorldId world,Epoch bootEpoch,
                                                   MemoryView recoveredMemory) {
        this(server,world,bootEpoch,recoveredMemory,
            DirectorLabObservationProfile.loadRuntime(),ProviderMode.TEST_ONLY);
    }
    public DirectorNativeObservationQualification(MinecraftServer server,
            WorldId world,Epoch bootEpoch,MemoryView recoveredMemory,
            ConfigView configuration,ProviderMode mode) {
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        this.configuration=Objects.requireNonNull(configuration);
        this.mode=Objects.requireNonNull(mode);
        actor();
        if(Objects.requireNonNull(bootEpoch).value()<1)
            throw new IllegalArgumentException("J01 requires J04 verified boot epoch");
        if(configuration.calibration()!=(mode==ProviderMode.PRODUCTION
                ?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("J01 calibration/provider mode mismatch");
        if(!Objects.requireNonNull(recoveredMemory).scopes().worldId().equals(world))
            throw new IllegalArgumentException("J01 recovered memory world mismatch");
        if(mode==ProviderMode.PRODUCTION) {
            clock=()->fr.proutlost.director.neoforge.DirectorStandaloneMod
                .directorLifecycle().runtimeClock(server);
        } else {
            var recoveredClock=new DirectorLabRecoveredClock(server,recoveredMemory);
            clock=recoveredClock::now;
        }
        sampleCadence=configuration.values().get("sensor.player_ticks").value().longValueExact();
        long maximumAge=configuration.values().get("scheduler.snapshot_max_age_ticks").value().longValueExact();
        if(sampleCadence<1||sampleCadence>maximumAge)
            throw new IllegalArgumentException("J01 player sampling must remain within snapshot freshness");
        lastTick=-sampleCadence;
        regionIndex=new DirectorLabRegionIndex(server,world,configuration,
            recoveredMemory,0,mode);
        campAnchorIndex=new DirectorLabCampAnchorIndex(Map.of(),
            configuration.values().get("region.camp_radius_blocks")
                .value().doubleValue(),
            configuration.values().get("spatial.region_cell_blocks")
                .value().intValueExact(),8192,128);
        anchorOwner=new DirectorNativeAnchorOwner(this::actor,recoveredMemory.anchors(),8192,128,
            this::offerAnchor,id->pendingAnchors.containsKey(id),
            ()->fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeAnchorWritesQuiescent(server));
        observation=new NeoForgeObservation(
            server,world,()->new DirectorClock(
                new Tick(clock.getAsLong()),System.nanoTime(),Instant.now()),
            regionIndex::primary, // Only indexed, durable J04 regions; never chunk guesses.
            id->DirectorNativeNetworkBootstrap.capability(server,id),
            ()->fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeGameplay(server)
                .map(DirectorNativeGameplay::catalogueObservationHealth).orElse(HealthMode.READ_ONLY_SAFE),
            this::knownNearbyAnchors,
            this::offerAnchor,configuration,mode==ProviderMode.PRODUCTION,bootEpoch,anchorOwner::observeCamp);
    }
    private void actor(){
        if(!server.isSameThread())throw new IllegalStateException("J01 server actor");
    }
    public void register(IEventBus bus){
        actor();
        if(closed||samplingSuspended)throw new IllegalStateException("J01 closed or sensing revoked");
        observation.register(Objects.requireNonNull(bus));
    }
    public void tick(){
        actor();
        if(closed||samplingSuspended)return;
        long now=clock.getAsLong();
        if(now-lastTick<sampleCadence)return;
        lastTick=now;
        var scopes=new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value())));
        var request=new SampleRequest(
            new RequestId(UUID.nameUUIDFromBytes(
                (world.value()+"/j01/"+now).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            scopes,Set.of(SensorKind.PLAYER_PRESENCE,SensorKind.PLAYER_ELIGIBILITY,
                SensorKind.CLIENT_CAPABILITIES,SensorKind.GROUP_MEMBERSHIP,SensorKind.REGION_MEMBERSHIP),
            new BudgetVector(10,0,0,0,0,0,0,0,0,0));
        last=observation.sample(request);
        samples=Math.addExact(samples,1);
        int players=last.context().players().size();
        int negotiated=(int)last.context().players().values().stream()
            .filter(p->p.clientCapabilities().connected()).count();
        if(players!=lastReportedPlayers || negotiated!=lastReportedNegotiated){
            System.out.println("DIRECTOR_NATIVE_J01_LIVE_SAMPLE players="+players+
                " negotiated_clients="+negotiated+" profile="+configuration.calibration());
            lastReportedPlayers=players;lastReportedNegotiated=negotiated;
        }
        // Production sensors report measured health; admission owns the
        // corresponding event fence. Keep the historical LAB invariant.
        if(mode==ProviderMode.TEST_ONLY && last.serverHealth()!=HealthMode.READ_ONLY_SAFE &&
           last.serverHealth()!=HealthMode.NORMAL)
            throw new IllegalStateException("J01 lab observation health unavailable");
    }
    public View view(){
        actor();
        int players=last==null?0:last.context().players().size();
        int negotiated=last==null?0:(int)last.context().players().values()
            .stream().filter(p->p.clientCapabilities().connected()).count();
        Set<SensorKind> unavailable=last==null?Set.of():
            Set.copyOf(last.missingData());
        if(incomplete||anchorOwner.failed()){
            var missing=new HashSet<>(unavailable);
            missing.add(SensorKind.CAMP_ACTIVITY);
            unavailable=Set.copyOf(missing);
        }
        return new View(lastTick<0?0:lastTick,samples,players,negotiated,
            pendingAnchorCount(),unavailable,mode==ProviderMode.TEST_ONLY,false);
    }
    public Optional<SnapshotBatch> snapshot() {
        actor();
        return Optional.ofNullable(last);
    }
    /** Actor-only seal; ownership stays with J01 until a matching J04 durable ACK. */
    public ObservationBatch seal(MemoryView before){
        actor();
        if(closed || last==null)throw new IllegalStateException("J01 snapshot absent");
        if(!before.scopes().worldId().equals(world))
            throw new IllegalArgumentException("J01 WAL foreign memory");
        return observation.seal(last,before);
    }
    public void acknowledge(List<ObservationId> committed){
        actor();
        if(closed)throw new IllegalStateException("J01 closed");
        observation.acknowledge(committed);
    }
    public long now(){actor();return clock.getAsLong();}
    public void refreshRegionIndex(MemoryView memory,long durableSequence){
        actor();
        if(closed)throw new IllegalStateException("J01 region index closed");
        regionIndex.refresh(memory,durableSequence);
        if(durableSequence<campAnchorIndexSequence)
            throw new IllegalStateException("J01 stale anchor projection");
        if(durableSequence==campAnchorIndexSequence)return;
        anchorOwner.mergeDurable(memory.anchors(),durableSequence);
        // Candidate validation and cell assembly occur BEFORE publication.
        // Only J04-confirmed anchors are ever visible to camp sampling.
        var indexed=new DirectorLabCampAnchorIndex(memory.anchors(),
            configuration.values().get("region.camp_radius_blocks")
                .value().doubleValue(),
            configuration.values().get("spatial.region_cell_blocks")
                .value().intValueExact(),8192,128);
        knownAnchors=Map.copyOf(memory.anchors());
        campAnchorIndex=indexed;
        campAnchorIndexSequence=durableSequence;
    }
    public DirectorLabRegionIndex.Status regionIndexStatus(){
        actor();return regionIndex.status();
    }
    public Optional<RegionId> indexedPrimary(BlockPosition position){
        actor();return regionIndex.primary(position);
    }
    private List<AnchorRecord> knownNearbyAnchors(BlockPosition position){
        actor();
        if(closed)throw new IllegalStateException("J01 spatial index closed");
        return campAnchorIndex.nearby(position);
    }
    private int pendingAnchorCount(){
        return pendingAnchors.values().stream().mapToInt(Deque::size).sum();
    }
    private void offerAnchor(AnchorRecord record){
        actor();
        Objects.requireNonNull(record);
        int waiting=pendingAnchorCount();
        if(waiting>=MAX_ANCHOR_VERSIONS){
            incomplete=true;
            throw new IllegalStateException("J01 anchor buffer saturated");
        }
        var queue=pendingAnchors.computeIfAbsent(record.anchorId(),
            unused->new ArrayDeque<>());
        var previous=queue.peekLast();
        if(previous!=null){
            if(record.header().revision().equals(previous.header().revision())
                && record.equals(previous))return;
            if(!record.header().revision().equals(previous.header().revision().plus(1)))
                throw new IllegalStateException("J01 anchor revision jump");
        }
        queue.addLast(record);
    }
    /** Read actor-held evidence; no WAL acknowledgement implied. */
    public Optional<AnchorRecord> nextUncommittedAnchor(){
        actor();
        return pendingAnchors.values().stream().filter(q->!q.isEmpty())
            .findFirst().map(Deque::getFirst);
    }
    /** Called only after the exact J04 revision is DURABLE and projected. */
    public void acknowledgeAnchor(AnchorId id,Revision revision){
        actor();
        var queue=pendingAnchors.get(id);
        if(queue==null || queue.isEmpty() ||
           !queue.getFirst().header().revision().equals(revision))
            throw new IllegalArgumentException("unknown anchor acknowledgement");
        queue.removeFirst();
        if(queue.isEmpty())pendingAnchors.remove(id);
        observation.acknowledgeAnchor(id,revision);
    }
    /** Native GameTest stimulates the SAME callback as a real block hook.
     * No standalone production actor can call this without lab-specific flag. */
    public void labTestStageAnchor(AnchorRecord record){
        actor();
        if(mode!=ProviderMode.TEST_ONLY ||
           !Boolean.getBoolean("proutlost.director.nativeGameTests"))
            throw new IllegalStateException("test-only anchor injection disabled");
        offerAnchor(record);
    }
    /** Inspect only J04-confirmed nearby camp anchors. */
    public List<AnchorRecord> durableNearbyAnchors(BlockPosition position){
        return knownNearbyAnchors(position);
    }
    /** Gated native GameTest uses J04-confirmed anchor, not pending evidence. */
    public void labTestCampWindow(AnchorId id){
        actor();
        if(mode!=ProviderMode.TEST_ONLY ||
           !Boolean.getBoolean("proutlost.director.nativeGameTests"))
            throw new IllegalStateException("J01 camp fixture disabled");
        var anchor=knownAnchors.get(id);
        if(anchor==null || !anchor.active())
            throw new IllegalArgumentException("unverified camp anchor");
        observation.labTestCampWindow(anchor);
    }
    public DirectorNativeAnchorOwner anchorOwner(){actor();if(closed)throw new IllegalStateException("J01 closed");return anchorOwner;}
    public ObservationPort observationPort(){actor();if(closed)throw new IllegalStateException("J01 closed");return observation;}
    public ConfigView configuration(){return configuration;}
    public ProviderMode mode(){return mode;}
    /** Legacy LAB accessor must never launder an approved production profile. */
    public ConfigView testConfiguration(){
        if(mode!=ProviderMode.TEST_ONLY)throw new IllegalStateException("J01 is not TEST_ONLY");
        return configuration;
    }
    /** Actor-only revocation of native sensing; retained snapshots/anchor
     * mailboxes remain available to drain already submitted durable work. */
    public void suspendSampling(){
        actor();if(closed||samplingSuspended)return;
        samplingSuspended=true;observation.close();
    }
    @Override public void close(){
        actor();
        if(closed)return;
        observation.close();anchorOwner.close();
        pendingAnchors.clear();
        knownAnchors=Map.of();
        campAnchorIndex=new DirectorLabCampAnchorIndex(Map.of(),
            configuration.values().get("region.camp_radius_blocks")
                .value().doubleValue(),
            configuration.values().get("spatial.region_cell_blocks")
                .value().intValueExact(),8192,128);
        last=null;
        closed=true;
    }
}
