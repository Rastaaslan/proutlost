package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.memory.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;

/** Genuine J01 camp absence/return -> derived J04 metadata only.
 * No world block writes, synthetic observation, event completion or clock
 * advance occurs here. A fresh complete native roster and loaded physical
 * camp are required to enter abandonment; only owned tags are cleaned later.
 */
public final class DirectorNativeCampStateProjection implements AutoCloseable {
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final ConfigView config;
    private final MemorySettings settings;
    private final Supplier<Optional<SnapshotBatch>> snapshots;
    private final Supplier<Optional<MemoryView>> memory;
    private final LongSupplier clock;
    private final BooleanSupplier quiescent;
    private final Predicate<Object> acquireLane;
    private final Consumer<Object> releaseLane;
    private final Object owner=new Object();
    private boolean closed,failed,laneHeld;
    private long nextPoll;
    private DurableBatch batch;
    private CompletableFuture<CommitAck> ack;
    private CompletableFuture<StoreReadResult> hydration;
    private Map<AggregateKey,AggregateState> expected=Map.of();
    private Sequence sequence=new Sequence(0);
    private String phase="WAIT_J01";
    public DirectorNativeCampStateProjection(MinecraftServer server,WorldId world,JournalStore store,
            ConfigView config,Supplier<Optional<SnapshotBatch>> snapshots,
            Supplier<Optional<MemoryView>> memory,LongSupplier clock,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane){
        this(server,world,store,config,snapshots,memory,clock,quiescent,acquireLane,releaseLane,ProviderMode.TEST_ONLY);
    }
    public DirectorNativeCampStateProjection(MinecraftServer server,WorldId world,JournalStore store,
            ConfigView config,Supplier<Optional<SnapshotBatch>> snapshots,
            Supplier<Optional<MemoryView>> memory,LongSupplier clock,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane,ProviderMode mode){
        this(server,world,store,config,snapshots,memory,clock,quiescent,acquireLane,releaseLane,mode,null);
    }
    /** Closed factory token from the same actual server; no LAB flag or approval is inferred. */
    static DirectorNativeCampStateProjection declaredSynthetic(MinecraftServer server,WorldId world,JournalStore store,
            DirectorProductionRuntimeQa.Inputs inputs,Supplier<Optional<SnapshotBatch>> snapshots,
            Supplier<Optional<MemoryView>> memory,LongSupplier clock,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane){
        Objects.requireNonNull(inputs).require(server);
        return new DirectorNativeCampStateProjection(server,world,store,inputs.configuration(),snapshots,memory,
            clock,quiescent,acquireLane,releaseLane,ProviderMode.TEST_ONLY,inputs);
    }
    private DirectorNativeCampStateProjection(MinecraftServer server,WorldId world,JournalStore store,
            ConfigView config,Supplier<Optional<SnapshotBatch>> snapshots,
            Supplier<Optional<MemoryView>> memory,LongSupplier clock,BooleanSupplier quiescent,
            Predicate<Object> acquireLane,Consumer<Object> releaseLane,ProviderMode mode,
            DirectorProductionRuntimeQa.Inputs declaredQa){
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);this.config=Objects.requireNonNull(config);
        this.snapshots=Objects.requireNonNull(snapshots);this.memory=Objects.requireNonNull(memory);
        this.clock=Objects.requireNonNull(clock);this.quiescent=Objects.requireNonNull(quiescent);
        this.acquireLane=Objects.requireNonNull(acquireLane);this.releaseLane=Objects.requireNonNull(releaseLane);
        actor();session=store.session();
        if(!world.equals(session.worldId()))
            throw new IllegalArgumentException("verified private LAB profile required");
        if(declaredQa!=null){
            declaredQa.require(server);
            if(!config.equals(declaredQa.configuration()))
                throw new IllegalArgumentException("closed camp QA profile differs from factory inputs");
        }
        DirectorNativeCampProfileAuthority.verify(mode,config.calibration(),Boolean.getBoolean("proutlost.director.nativeLab"),
            mode==ProviderMode.TEST_ONLY&&declaredQa==null&&Boolean.getBoolean("proutlost.director.nativeLab")
                &&DirectorLabObservationProfile.isVerifiedRuntime(config),declaredQa!=null);
        settings=new MemorySettings(config,mode==ProviderMode.PRODUCTION);
    }
    private void actor(){
        if(closed||!server.isSameThread())throw new IllegalStateException("camp state actor");
    }
    private static void require(boolean ok,String reason){
        if(!ok)throw new IllegalStateException(reason);
    }
    public boolean metadataLaneHeld(){actor();return laneHeld;}
    public String status(){
        actor();return "CAMP_STATE "+phase+" lane="+laneHeld+" sequence="+sequence.value()
            +" abandonment_ticks="+settings.ticks("pattern.abandonment_enter_ticks")
            +" config="+config.provenanceHash().value();
    }
    private void release(){if(laneHeld){releaseLane.accept(owner);laneHeld=false;}}
    private void fail(RuntimeException error){
        failed=true;phase="FAILED_SAFE "+error.getClass().getSimpleName();
        if(ack==null)release();
        System.out.println("DIRECTOR_CAMP_STATE_FAILED_SAFE "+error);
    }
    private record Context(Sequence cut,Tick now,MemoryView memory,SnapshotBatch snapshot,
                           Map<AggregateKey,AggregateState> states){}
    private Optional<Context> context(){
        var live=snapshots.get();var projected=memory.get();
        if(live.isEmpty()||projected.isEmpty())return Optional.empty();
        long now=clock.getAsLong();var sample=live.orElseThrow();var supplied=projected.orElseThrow();
        if(!sample.scopes().worldId().equals(world)||!supplied.scopes().worldId().equals(world)
            ||!sample.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,world.value())))return Optional.empty();
        long age=now-sample.capturedTick().value();
        if(age<0||age>settings.ticks("scheduler.snapshot_max_age_ticks")
            ||sample.missingData().contains(SensorKind.PLAYER_PRESENCE)
            ||sample.missingData().contains(SensorKind.PLAYER_ELIGIBILITY)
            ||sample.missingData().contains(SensorKind.REGION_MEMBERSHIP))return Optional.empty();
        // Match the fresh immutable census to the actual actor-owned roster.
        // A late connection, move or dimension change defers a negative claim.
        Set<PlayerId> actualIds=new HashSet<>();
        for(var player:server.getPlayerList().getPlayers()){
            var id=new PlayerId(player.getUUID());actualIds.add(id);
            var known=sample.context().players().get(id);if(known==null)return Optional.empty();
            var p=player.blockPosition();var position=known.position().position();
            if(!position.dimension().value().equals(player.serverLevel().dimension().location().toString())
                ||p.getX()!=position.x()||p.getY()!=position.y()||p.getZ()!=position.z())return Optional.empty();
        }
        if(!actualIds.equals(sample.context().players().keySet()))return Optional.empty();
        var generation=store.metadataSnapshot();boolean scoped=generation.isPresent()&&!generation.get().generations().isEmpty();
        var keys=store.residentAggregateKeys(scoped?Set.of(AggregateKind.WORLD):Set.of(AggregateKind.WORLD,AggregateKind.REGION,
            AggregateKind.REGION_MEMORY,AggregateKind.ANCHOR,AggregateKind.LOCATION),4096);
        if(keys.isEmpty()||!keys.orElseThrow().session().equals(session))return Optional.empty();
        var wanted=new HashSet<>(keys.orElseThrow().keys());
        if(scoped)for(var key:DirectorNativeCatalogueInput.memoryKeys(supplied))
            if(Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY,AggregateKind.ANCHOR,AggregateKind.LOCATION).contains(key.kind()))wanted.add(key);
        if(wanted.size()>513)return Optional.empty(); //512 metadata plus the actual WORLD head.
        var query=new StoreQuery(world,wanted,new Sequence(0),Optional.empty(),513);
        var read=store.view(query);ReadContracts.validate(read,query,session);
        if(scoped&&read.status()==ReadStatus.NOT_RESIDENT) {
            if(hydration!=null&&hydration.isDone()) {
                var result=hydration.join();hydration=null;
                if(result.status()==ReadStatus.CORRUPT||result.status()==ReadStatus.UNAVAILABLE)return Optional.empty();
                read=store.view(query);ReadContracts.validate(read,query,session);
            }
            if(read.status()==ReadStatus.NOT_RESIDENT) {
                if(hydration==null)hydration=store.readMetadata(generation.orElseThrow(),query).toCompletableFuture();
                return Optional.empty();
            }
        }
        if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().hasMore()
            ||!read.page().orElseThrow().lastDurableSequence().equals(keys.orElseThrow().cut()))
            return Optional.empty();
        Map<AggregateKey,AggregateState> states=new HashMap<>();DirectorWorldState current=null;
        for(var row:read.page().orElseThrow().aggregates()){
            var state=row.state();if(state.header().updatedTick().value()>now)return Optional.empty();
            states.put(state.key(),state);if(state instanceof WorldAggregate w)current=w.value();
        }
        if(current==null||!current.worldId().equals(world)||!current.bootEpoch().equals(session.bootEpoch())
            ||!current.storeGeneration().equals(session.storeGeneration())
            ||!current.configHash().equals(config.provenanceHash())
            ||!states.keySet().equals(wanted))return Optional.empty();
        var actual=MemoryProjection.of(scoped?supplied.scopes():new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),
            states.values());
        if(!actual.regionRecords().equals(supplied.regionRecords())
            ||!actual.anchors().equals(supplied.anchors())||!actual.locations().equals(supplied.locations())
            ||!new HashSet<>(actual.regions()).equals(new HashSet<>(supplied.regions())))return Optional.empty();
        return Optional.of(new Context(keys.orElseThrow().cut(),new Tick(now),actual,sample,Map.copyOf(states)));
    }
    private boolean physicalCamp(AnchorRecord anchor){
        if(!anchor.active()||!(anchor.origin()==AnchorOrigin.CAMP
            ||anchor.tags().contains(new TagId("proutlost:camp"))))return false;
        var key=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(anchor.dimension().value()));
        var level=server.getLevel(key);if(level==null)return false;
        var p=new BlockPos(anchor.position().x(),anchor.position().y(),anchor.position().z());
        return level.hasChunkAt(p)&&level.getBlockState(p).is(BlockTags.CAMPFIRES);
    }
    private boolean anyoneAtCamp(AnchorRecord anchor){
        double radius=settings.number("region.camp_radius_blocks");
        for(var player:server.getPlayerList().getPlayers()){
            if(!anchor.dimension().value().equals(player.serverLevel().dimension().location().toString()))continue;
            var p=player.blockPosition();
            if(Math.hypot((double)p.getX()-anchor.position().x(),(double)p.getZ()-anchor.position().z())<=radius)
                return true;
        }
        return false;
    }
    private static AggregateHeader next(AggregateHeader old,Tick now){
        require(now.compareTo(old.updatedTick())>=0,"camp state clock rollback");
        return new AggregateHeader(1,old.id(),old.revision().plus(1),old.lastSequence(),now);
    }
    private record Plan(Map<AggregateKey,AggregateState> states,Map<ScopeRef,Revision> cas){}
    private Plan plan(Context c){
        Map<AggregateKey,AggregateState> changes=new LinkedHashMap<>();
        Map<ScopeRef,Revision> cas=new HashMap<>();
        // One regional transition per poll keeps physical checks bounded and
        // permits unrelated queued metadata lanes to make progress.
        for(var prior:c.memory.regions().stream().sorted(Comparator.comparing(RegionMemory::regionId)).toList()){
            var region=c.memory.regionRecords().get(prior.regionId());
            if(region==null||region.status()!=RegionStatus.ACTIVE)continue;
            var refs=CampAbandonmentPolicy.reduceMarked(prior.persistentStateRefs(),
                prior.activity().camps().values(),c.now,settings);
            var entry=CampAbandonmentPolicy.entered(refs);var returned=CampAbandonmentPolicy.resumed(refs);
            Set<AnchorId> owned=CampAbandonmentPolicy.ownedTags(refs);
            if(returned.isPresent()){
                Set<AnchorId> cleaned=new HashSet<>();
                for(var id:owned){
                    var a=c.memory.anchors().get(id);
                    if(a==null||!a.regionIds().contains(prior.regionId()))continue;
                    if(a.active()&&!physicalCamp(a))continue;
                    Set<TagId> tags=new HashSet<>(a.tags());tags.remove(CampAbandonmentPolicy.ANCHOR_TAG);
                    if(!tags.equals(a.tags())){
                        var updated=new AnchorRecord(next(a.header(),c.now),a.anchorId(),a.locationId(),
                            a.regionIds(),a.dimension(),a.position(),a.radiusProfile(),tags,a.origin(),a.evidence(),a.active());
                        var state=new AnchorAggregate(updated);changes.put(state.key(),state);
                    }
                    cleaned.add(id);
                }
                refs=CampAbandonmentPolicy.cleaned(refs,cleaned);
            }else if(entry.isEmpty()&&!prior.activity().camps().isEmpty()
                &&c.now.value()-prior.lastVisitTick().value()>=settings.ticks("pattern.abandonment_enter_ticks")){
                List<AnchorRecord> recognized=new ArrayList<>();boolean allKnown=true,present=false;
                for(var camp:prior.activity().camps().values()){
                    var anchor=c.memory.anchors().get(camp.anchorId());
                    if(anchor==null||!anchor.regionIds().contains(prior.regionId())||!physicalCamp(anchor)){
                        allKnown=false;break;
                    }
                    present|=anyoneAtCamp(anchor);
                    if(CampAbandonmentPolicy.recognized(camp,c.now,settings))recognized.add(anchor);
                }
                if(!allKnown||present||recognized.isEmpty())continue;
                Set<AnchorId> newlyOwned=new HashSet<>();
                for(var anchor:recognized){
                    if(anchor.tags().contains(CampAbandonmentPolicy.ANCHOR_TAG))continue;
                    Set<TagId> tags=new HashSet<>(anchor.tags());tags.add(CampAbandonmentPolicy.ANCHOR_TAG);
                    var updated=new AnchorRecord(next(anchor.header(),c.now),anchor.anchorId(),anchor.locationId(),
                        anchor.regionIds(),anchor.dimension(),anchor.position(),anchor.radiusProfile(),
                        tags,anchor.origin(),anchor.evidence(),anchor.active());
                    var state=new AnchorAggregate(updated);changes.put(state.key(),state);newlyOwned.add(anchor.anchorId());
                }
                refs=CampAbandonmentPolicy.enter(refs,c.now,newlyOwned);
            }
            if(!refs.equals(prior.persistentStateRefs())){
                var updated=new RegionMemory(next(prior.header(),c.now),prior.regionId(),prior.components(),
                    prior.lastVisitTick(),prior.lastEventTick(),refs,prior.durableFactRefs(),prior.activity());
                var state=new RegionMemoryAggregate(updated);changes.put(state.key(),state);
            }
            if(!changes.isEmpty()){
                cas.put(new ScopeRef(ScopeKind.REGION,region.regionId().value()),region.header().revision());
                break;
            }
        }
        return new Plan(Map.copyOf(changes),Map.copyOf(cas));
    }
    public void poll(){
        actor();if(failed)return;
        try{
            require(session.equals(store.session())&&!store.readOnlySafe(),"camp state J04 session");
            if(ack!=null){
                if(!ack.isDone())return;
                var result=ack.getNow(null);
                require(result!=null&&result.ackStatus()==AckStatus.DURABLE
                    &&result.transactionId().equals(batch.transactionId())
                    &&result.transactionHash().equals(batch.transactionHash())
                    &&result.storeGeneration().equals(session.storeGeneration())
                    &&result.durableSequence().value()>0&&result.permitAttempt().isEmpty()
                    &&result.forcedBlobs().isEmpty(),"camp state ACK uncertain/rejected");
                sequence=result.durableSequence();
                var projected=context();if(projected.isEmpty()||projected.orElseThrow().cut.compareTo(sequence)<0)return;
                for(var entry:expected.entrySet())require(entry.getValue().equals(projected.orElseThrow().states.get(entry.getKey())),
                    "camp state exact memory readback mismatch");
                System.out.println("DIRECTOR_CAMP_STATE_DURABLE sequence="+sequence.value()
                    +" changed="+expected.size()+" threshold="+settings.ticks("pattern.abandonment_enter_ticks")
                    +" config="+config.provenanceHash().value()+" physical_writes=0");
                ack=null;batch=null;expected=Map.of();release();phase="IDLE";return;
            }
            if(snapshots.get().isEmpty()||memory.get().isEmpty())return;
            long now=clock.getAsLong();if(now<nextPoll)return;
            if(!laneHeld&&!quiescent.getAsBoolean())return;
            var current=context();if(current.isEmpty()){phase="WAIT_COMPLETE_J01_J04_CONTEXT";return;}
            var planned=plan(current.orElseThrow());
            if(planned.states.isEmpty()){nextPoll=now+200;phase="IDLE";return;}
            if(!laneHeld){if(!acquireLane.test(owner))return;laneHeld=true;}
            current=context();if(current.isEmpty())return;
            planned=plan(current.orElseThrow());
            if(planned.states.isEmpty()){release();nextPoll=now+200;phase="IDLE";return;}
            expected=planned.states;
            List<DurableRecord> records=new ArrayList<>();expected.values().forEach(state->records.add(new DurableAggregate(state)));
            batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),session.storeGeneration(),
                planned.cas,records,List.of());ack=store.append(batch).toCompletableFuture();phase="WAIT_EXACT_J04_ACK";
        }catch(RuntimeException error){fail(error);}
    }
    @Override public void close(){actor();closed=true;}
}
