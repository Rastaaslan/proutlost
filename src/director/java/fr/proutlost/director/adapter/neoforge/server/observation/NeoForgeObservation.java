package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import java.util.*;
import java.util.function.*;

/** Real server hooks. No chunk loads, world mutations, async access, chat or persistent trajectories.
 * Bootstrap owns lifecycle/registration and injects immutable region membership and capability views.
 */
public final class NeoForgeObservation implements ObservationPort, ProviderProvenance, AutoCloseable {
    @FunctionalInterface public interface CampChangeSink {
        boolean observe(BlockPosition position,boolean active,Tick at,Set<RegionId> actualRegions);
    }
    private final CampChangeSink campChangeSink;
    private final MinecraftServer server;
    private final boolean production;
    private final WorldId world;
    private final Supplier<DirectorClock> clock;
    private final Supplier<HealthMode> health;
    private final Function<BlockPosition,Optional<RegionId>> primary;
    private final Function<PlayerId,ClientCapabilitySnapshot> capabilities;
    private final ObservationBuffer buffer;
    private final CampSampler camps;
    private final ObservationPipeline pipeline;
    private final Function<BlockPosition,List<AnchorRecord>> nearbyAnchors;
    private final ObservationContinuity continuity;
    private final double campRadius;
    private final Consumer<AnchorRecord> anchorObservations;
    private final Map<BlockPosition,AnchorRecord> observedCampfires=new HashMap<>();
    private final int anchorCapacity;
    private final long sampleTicks,campTicks,bucketTicks,transitionTicks,deathSafetyTicks;
    private final int playerCell;
    private int samplingCursor;
    private final ObservationCadence campCadence;
    private final int maxPlayers;
    private final Map<PlayerId,Tick> lastSample=new HashMap<>(), transitioning=new HashMap<>();
    private final Map<PlayerId,PlayerEligibility> previousEligibility=new HashMap<>();
    private final Map<PlayerId,Revision> revisions=new HashMap<>();
    private final Map<PlayerId,PlayerSnapshot> publishedSamples=new HashMap<>();
    private final Map<PlayerId,BlockPosition> previousPosition=new HashMap<>();
    private final Map<PlayerId,RegionId> lastRegion=new HashMap<>();
    private IEventBus bus;
    private final List<Object> listeners=new ArrayList<>();
    private long invalidationGeneration;
    private boolean incomplete;

    public NeoForgeObservation(MinecraftServer server,WorldId world,Supplier<DirectorClock> clock,
            Function<BlockPosition,Optional<RegionId>> primary,Function<PlayerId,ClientCapabilitySnapshot> capabilities,Supplier<HealthMode> health,
            Function<BlockPosition,List<AnchorRecord>> nearbyAnchors,Consumer<AnchorRecord> anchorObservations,
            ConfigView config,boolean production) {
        this(server,world,clock,primary,capabilities,health,nearbyAnchors,
            anchorObservations,config,production,new Epoch(0));
    }
    /** Native ingress must use J04's recovered boot epoch so a reboot
     * cannot reuse a previous observation window's transaction identity. */
    public NeoForgeObservation(MinecraftServer server,WorldId world,Supplier<DirectorClock> clock,
            Function<BlockPosition,Optional<RegionId>> primary,Function<PlayerId,ClientCapabilitySnapshot> capabilities,Supplier<HealthMode> health,
            Function<BlockPosition,List<AnchorRecord>> nearbyAnchors,Consumer<AnchorRecord> anchorObservations,
            ConfigView config,boolean production,Epoch bootEpoch) {
        this(server,world,clock,primary,capabilities,health,nearbyAnchors,anchorObservations,config,production,bootEpoch,null);
    }
    public NeoForgeObservation(MinecraftServer server,WorldId world,Supplier<DirectorClock> clock,
            Function<BlockPosition,Optional<RegionId>> primary,Function<PlayerId,ClientCapabilitySnapshot> capabilities,Supplier<HealthMode> health,
            Function<BlockPosition,List<AnchorRecord>> nearbyAnchors,Consumer<AnchorRecord> anchorObservations,
            ConfigView config,boolean production,Epoch bootEpoch,CampChangeSink campChangeSink) {
        this.campChangeSink=campChangeSink;
        this.production=production;
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.clock=Objects.requireNonNull(clock);
        this.anchorObservations=Objects.requireNonNull(anchorObservations);this.nearbyAnchors=Objects.requireNonNull(nearbyAnchors);this.health=Objects.requireNonNull(health);this.primary=Objects.requireNonNull(primary);this.capabilities=Objects.requireNonNull(capabilities);
        if(production&&config.calibration()!=Calibration.APPROVED)throw new IllegalArgumentException("production requires approved calibration");
        playerCell=Math.toIntExact(parameter(config,"spatial.player_cell_blocks"));deathSafetyTicks=parameter(config,"fairness.death_safety_ticks");
        sampleTicks=parameter(config,"sensor.player_ticks");campTicks=parameter(config,"sensor.camp_ticks");bucketTicks=parameter(config,"observation.bucket_ticks");campCadence=new ObservationCadence(campTicks);continuity=new ObservationContinuity(sampleTicks,campTicks);transitionTicks=parameter(config,"fairness.transition_safety_ticks");
        maxPlayers=Math.toIntExact(parameter(config,"budget.snapshot_ops"));
        campRadius=parameter(config,"region.camp_radius_blocks");anchorCapacity=Math.toIntExact(parameter(config,"observation.max_pending_scopes"));
        camps=new CampSampler(Math.toIntExact(parameter(config,"observation.max_pending_scopes")),Math.toIntExact(parameter(config,"pattern.activity_time_bins")),parameter(config,"observation.bucket_ticks"),parameter(config,"pattern.independent_visit_gap_ticks"));
        requireActor();buffer=new ObservationBuffer(world,parameter(config,"observation.bucket_ticks"),Math.toIntExact(parameter(config,"observation.max_pending_scopes")),bootEpoch);pipeline=new ObservationPipeline(buffer,camps,campTicks);
    }
    @Override public Set<ProviderMode> supportedModes(){return Set.of(production?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY);}
    private static long parameter(ConfigView config,String key){long v=Objects.requireNonNull(config.values().get(key),key).value().longValueExact();if(v<=0)throw new IllegalArgumentException(key);return v;}
    private void requireActor(){if(!server.isSameThread())throw new IllegalStateException("Minecraft server actor required");}
    public void register(IEventBus bus) {
        requireActor();if(this.bus!=null)throw new IllegalStateException("already registered");this.bus=Objects.requireNonNull(bus);
        listen((Consumer<ServerTickEvent.Post>)this::tick);
        listen((Consumer<BlockEvent.BreakEvent>)this::broken);
        listen((Consumer<BlockEvent.EntityPlaceEvent>)this::placed);
        listen((Consumer<PlayerInteractEvent.RightClickBlock>)this::interact);
        listen((Consumer<LivingDeathEvent>)this::death);
        listen((Consumer<PlayerEvent.PlayerLoggedInEvent>)e->transition(e.getEntity()));
        listen((Consumer<PlayerEvent.PlayerLoggedOutEvent>)e->disconnect(e.getEntity()));
        listen((Consumer<PlayerEvent.PlayerChangedDimensionEvent>)e->transition(e.getEntity()));
        listen((Consumer<PlayerEvent.PlayerRespawnEvent>)e->transition(e.getEntity()));
        listen((Consumer<ChunkEvent.Load>)e->invalidate(e.getLevel().isClientSide()));
        listen((Consumer<ChunkEvent.Unload>)e->invalidate(e.getLevel().isClientSide()));
    }
    private <T extends net.neoforged.bus.api.Event> void listen(Consumer<T> listener){bus.addListener(listener);listeners.add(listener);}
    private void invalidate(boolean client){if(client)return;requireActor();invalidationGeneration=Math.addExact(invalidationGeneration,1);}
    private BlockPosition position(ServerPlayer p){var b=p.blockPosition();return new BlockPosition(new DimensionId(p.serverLevel().dimension().location().toString()),b.getX(),b.getY(),b.getZ());}
    private ScopeRef playerScope(ServerPlayer p){return new ScopeRef(ScopeKind.PLAYER,p.getUUID());}
    private void transition(net.minecraft.world.entity.player.Player entity) {
        if(!(entity instanceof ServerPlayer p)||p.getServer()!=server)return;requireActor();PlayerId id=new PlayerId(p.getUUID());Tick now=clock.get().simTick();
        transitioning.merge(id,now.plus(transitionTicks),(a,b)->a.compareTo(b)>0?a:b);lastSample.remove(id);previousEligibility.remove(id);lastRegion.remove(id);previousPosition.remove(id);continuity.reset(id);revisions.merge(id,new Revision(1),(a,b)->a.plus(1));
        if(!buffer.add(playerScope(p),new ResourceId("proutlost_director:transition"),now,"count",1))incomplete=true;
    }
    private void disconnect(net.minecraft.world.entity.player.Player entity) {
        if(!(entity instanceof ServerPlayer p)||p.getServer()!=server)return;requireActor();PlayerId id=new PlayerId(p.getUUID());
        transition(entity);lastSample.remove(id);previousEligibility.remove(id);lastRegion.remove(id);previousPosition.remove(id);continuity.reset(id);transitioning.remove(id);revisions.remove(id);publishedSamples.remove(id);
    }
    private void death(LivingDeathEvent event){if(event.getEntity() instanceof ServerPlayer p&&p.getServer()==server){requireActor();Tick now=clock.get().simTick();
        if(!buffer.add(playerScope(p),new ResourceId("proutlost_director:death"),now,"count",1))incomplete=true;continuity.reset(new PlayerId(p.getUUID()));previousEligibility.put(new PlayerId(p.getUUID()),PlayerEligibility.DEAD);transitioning.put(new PlayerId(p.getUUID()),now.plus(deathSafetyTicks));}}
    private void broken(BlockEvent.BreakEvent event){if(event.getPlayer() instanceof ServerPlayer p&&p.getServer()==server&&!event.isCanceled()){activity(p);if(event.getState().is(BlockTags.CAMPFIRES))campBlock(p,event.getPos(),false);}}
    private void placed(BlockEvent.EntityPlaceEvent event){if(event.getEntity() instanceof ServerPlayer p&&p.getServer()==server&&!event.isCanceled()){activity(p);if(event.getPlacedBlock().is(BlockTags.CAMPFIRES))campBlock(p,event.getPos(),true);}}
    private void interact(PlayerInteractEvent.RightClickBlock event){if(event.getEntity() instanceof ServerPlayer p&&p.getServer()==server&&!event.isCanceled())activity(p);}
    /** Anchor changes are observations for the durable actor mailbox, not direct store/world writes. */
    private void campBlock(ServerPlayer player,net.minecraft.core.BlockPos block,boolean active) {
        requireActor();Tick now=clock.get().simTick();BlockPosition position=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),block.getX(),block.getY(),block.getZ());
        if(campChangeSink!=null){
            if(!campChangeSink.observe(position,active,now,primary.apply(position).map(Set::of).orElse(Set.of())))incomplete=true;
            return;
        }
        AnchorRecord old=observedCampfires.get(position);
        if(old==null) {
            try {
                old=nearbyAnchors.apply(position).stream()
                    .filter(a->a.position().equals(position)
                        && a.origin()==AnchorOrigin.CAMP)
                    .findFirst().orElse(null);
            } catch(CampAnchorLookupSaturatedException saturated) {
                incomplete=true;
                return; // No partial camp placement/deactivation evidence.
            }
        }
        if(old==null&&!active)return;
        if(old==null&&observedCampfires.size()>=anchorCapacity){incomplete=true;return;}
        AnchorId id=old==null?new AnchorId(UUID.randomUUID()):old.anchorId();
        var record=new AnchorRecord(new AggregateHeader(1,id.value(),old==null?new Revision(1):old.header().revision().plus(1),old==null?new Sequence(0):old.header().lastSequence(),now),id,
                old==null?Optional.empty():old.locationId(),old==null?primary.apply(position).map(Set::of).orElse(Set.of()):old.regionIds(),position.dimension(),position,"region.camp_radius_blocks",Set.of(new TagId("proutlost:camp")),AnchorOrigin.CAMP,old==null?EvidenceStatus.POSSIBLE:old.evidence(),active);
        observedCampfires.put(position,record);anchorObservations.accept(record);
    }
    private void activity(ServerPlayer p){requireActor();Tick now=clock.get().simTick();
        if(!buffer.add(playerScope(p),new ResourceId("proutlost_director:player_activity"),now,"activity",1))incomplete=true;
        primary.apply(position(p)).ifPresent(id->{
        if(!buffer.add(new ScopeRef(ScopeKind.REGION,id.value()),new ResourceId("proutlost_director:activity"),now,"activity",1))incomplete=true;
    });}
    private PlayerEligibility eligibility(ServerPlayer p,Tick now){
        if(!p.isAlive())return PlayerEligibility.DEAD;if(p.isSpectator())return PlayerEligibility.SPECTATOR;
        Tick until=transitioning.get(new PlayerId(p.getUUID()));if(until!=null&&now.compareTo(until)<0)return PlayerEligibility.TRANSITIONING;
        return PlayerEligibility.ELIGIBLE;
    }
    private void tick(ServerTickEvent.Post event) {
        // EventBus may already have captured this listener before revocation.
        if(bus==null||event.getServer()!=server)return;requireActor();Tick now=clock.get().simTick();
        if(campCadence.due(now)||now.value()%sampleTicks==0)incomplete=buffer.saturated();
        if(campCadence.due(now))sampleCamps(now);
        if(now.value()%sampleTicks!=0)return;
        int tickWork=0,processedPlayers=0;
        Map<PlayerId,PlayerSnapshot> socialPlayers=new HashMap<>();Set<PlayerId> continuousEligible=new HashSet<>();
        var players=server.getPlayerList().getPlayers();incomplete|=players.size()>maxPlayers||buffer.saturated();
        for(int index=0;index<Math.min(maxPlayers,players.size());index++) {
            if(++tickWork>maxPlayers){incomplete=true;break;}processedPlayers++;
            ServerPlayer p=players.get(Math.floorMod(samplingCursor+index,players.size()));
            PlayerId id=new PlayerId(p.getUUID());PlayerEligibility eligible=eligibility(p,now);Tick previous=lastSample.put(id,now);
            if(previous!=null&&previousEligibility.get(id)==PlayerEligibility.ELIGIBLE&&eligible==PlayerEligibility.ELIGIBLE&&now.value()-previous.value()==sampleTicks)
                {continuousEligible.add(id);if(!buffer.add(playerScope(p),new ResourceId("proutlost_director:presence"),now,"eligible_ticks",sampleTicks))incomplete=true;}
            previousEligibility.put(id,eligible);
            if(eligible!=PlayerEligibility.ELIGIBLE)continuity.reset(id);
            if(previous==null||now.value()-previous.value()!=sampleTicks||eligible!=PlayerEligibility.ELIGIBLE) {
                lastRegion.remove(id);previousPosition.remove(id);
            }
            Optional<RegionId> region=eligible==PlayerEligibility.ELIGIBLE?primary.apply(position(p)):Optional.empty();
            socialPlayers.put(id,new PlayerSnapshot(id,revisions.getOrDefault(id,new Revision(0)),now,eligible,new PositionSnapshot(position(p),now),region.map(Set::of).orElse(Set.of()),Set.of(),new DurationTicks(0),Set.of(),Objects.requireNonNull(capabilities.apply(id)),Set.of()));
            if(region.isPresent()) {
                RegionId old=lastRegion.put(id,region.get());
                BlockPosition previousPos=previousPosition.put(id,position(p));
                if(old!=null&&!old.equals(region.get())&&previousPos!=null&&previousPos.dimension().equals(position(p).dimension())) {
                    double dx=(double)position(p).x()-previousPos.x(),dz=(double)position(p).z()-previousPos.z();
                    Sector direction=Sector.values()[Math.floorMod((int)Math.round(Math.atan2(dx,-dz)/(Math.PI/4)),8)];
                    if(!buffer.transition(new RouteKey(old,region.get()),direction,now))incomplete=true;
                }
                // Per-player previous region is ephemeral, removed on disconnect; only counts reach persistence.
                if(!region.get().equals(old))if(!buffer.add(new ScopeRef(ScopeKind.REGION,region.get().value()),new ResourceId("proutlost_director:activity"),now,"visit",1))incomplete=true;
            } else {lastRegion.remove(id);previousPosition.remove(id);}
        }
        var social=ProximityGroups.build(socialPlayers,playerCell,Math.max(0,maxPlayers-tickWork),now);
        continuity.proximity(socialPlayers,social,!incomplete&&processedPlayers==players.size(),now);
        if(!social.complete())incomplete=true;
        else for(var group:social.groups().values()) {
            Map<RegionId,Integer> regionalMembers=new HashMap<>();
            for(PlayerId member:group.members()) {
                if(!continuousEligible.contains(member))continue;
                socialPlayers.get(member).regionIds().forEach(id->regionalMembers.merge(id,1,Integer::sum));
                if(!buffer.add(new ScopeRef(ScopeKind.PLAYER,member.value()),new ResourceId("proutlost_director:player_activity"),now,"social",sampleTicks))incomplete=true;
            }
            for(var members:regionalMembers.entrySet())
                if(!buffer.add(new ScopeRef(ScopeKind.REGION,members.getKey().value()),new ResourceId("proutlost_director:activity"),now,"social",(double)sampleTicks*members.getValue()/group.members().size()))incomplete=true;
        }
        samplingCursor=players.isEmpty()?0:Math.floorMod(samplingCursor+processedPlayers,players.size());
    }
    /** Only loaded, physically present campfires can accrue visits.
     * J04 remains the authority for anchor identity: checking the world here
     * only rejects stale/unloaded blocks, never creates or revokes an anchor.
     * In particular an out-of-band block edit must not create phantom camps.
     */
    private boolean loadedPhysicalCampfire(AnchorRecord anchor) {
        requireActor();
        var key=net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(anchor.dimension().value()));
        var level=server.getLevel(key);
        if(level==null)return false;
        var at=new net.minecraft.core.BlockPos(anchor.position().x(),
            anchor.position().y(),anchor.position().z());
        return level.hasChunkAt(at) && level.getBlockState(at).is(BlockTags.CAMPFIRES);
    }
    private void sampleCamps(Tick now) {
        int work=0,processed=0;var players=server.getPlayerList().getPlayers();
        Map<AnchorId,List<PositionSnapshot>> observed=new HashMap<>();Map<AnchorId,AnchorRecord> anchors=new HashMap<>();Map<AnchorId,Long> dayTimes=new HashMap<>();
        for(int index=0;index<players.size()&&work<maxPlayers;index++) {
            work++;processed++;var p=players.get(campCadence.index(index,players.size()));
            var id=new PlayerId(p.getUUID());var pos=position(p);var eligible=eligibility(p,now);
            if(eligible!=PlayerEligibility.ELIGIBLE){continuity.camp(id,Optional.empty(),eligible,now);continue;}
            AnchorRecord selected=null;double distance=Double.POSITIVE_INFINITY;boolean complete=true;
            List<AnchorRecord> nearby;
            try {
                nearby=nearbyAnchors.apply(pos);
            } catch(CampAnchorLookupSaturatedException saturated) {
                incomplete=true;
                continuity.reset(id);
                continue; // Drop this census, never invent an eligible stay.
            }
            for(var a:nearby) {
                if(work>=maxPlayers){complete=false;break;}work++;
                if(!a.active()||!(a.origin()==AnchorOrigin.CAMP||a.tags().contains(new TagId("proutlost:camp")))||!a.dimension().equals(pos.dimension()))continue;
                double d=Math.hypot((double)a.position().x()-pos.x(),(double)a.position().z()-pos.z());
                if(d>campRadius)continue;
                // An active J04 record alone is insufficient evidence of an
                // existing block. A stale or unloaded camp invalidates the
                // entire census rather than fabricating a complete absence.
                if(!loadedPhysicalCampfire(a)){complete=false;break;}
                if(d<distance||(d==distance&&selected!=null&&a.anchorId().compareTo(selected.anchorId())<0)){selected=a;distance=d;}
            }
            if(!complete){incomplete=true;continuity.reset(id);continue;}
            if(continuity.camp(id,Optional.ofNullable(selected).map(AnchorRecord::anchorId),eligible,now)) {
                observed.computeIfAbsent(selected.anchorId(),k->new ArrayList<>()).add(new PositionSnapshot(pos,now));anchors.put(selected.anchorId(),selected);dayTimes.put(selected.anchorId(),p.serverLevel().getDayTime());
            }
        }
        if(processed<players.size())incomplete=true;
        campCadence.advance(processed,players.size());
        for(var entry:observed.entrySet()) {
            var anchor=anchors.get(entry.getKey());RegionId region=primary.apply(anchor.position()).filter(anchor.regionIds()::contains).orElseGet(()->anchor.regionIds().stream().sorted().findFirst().orElse(new RegionId(anchor.anchorId().value())));
            if(!camps.observeInterval(anchor,region,entry.getValue(),now,dayTimes.get(entry.getKey()),campTicks,campRadius))incomplete=true;
        }
    }
    @Override public SnapshotBatch sample(SampleRequest request) {return sample(request,()->true);}
    public SnapshotBatch sample(SampleRequest request,java.util.function.BooleanSupplier sharedTake) {
        requireActor();if(!request.scopes().worldId().equals(world))throw new IllegalArgumentException("wrong world");Tick now=clock.get().simTick();
        Map<PlayerId,PlayerSnapshot> players=new HashMap<>();Map<ScopeRef,Revision> versions=new HashMap<>();Map<ScopeRef,ScopeSnapshot> scopes=new HashMap<>();Set<ChunkRef> chunks=new HashSet<>();
        ObservationBudget budget=new ObservationBudget(maxPlayers,request.maxWork().tickMillis()*1_000_000,System::nanoTime,sharedTake);
        boolean exhausted=false;
        boolean all=request.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,world.value()));
        Set<SensorKind> supported=Set.of(SensorKind.PLAYER_ELIGIBILITY,SensorKind.PLAYER_PRESENCE,SensorKind.REGION_MEMBERSHIP,SensorKind.CLIENT_CAPABILITIES,SensorKind.GROUP_MEMBERSHIP);
        Set<SensorKind> missing=new HashSet<>(request.sensorSet());missing.removeAll(supported);
        for(ServerPlayer p:server.getPlayerList().getPlayers()) {
            // Even an unrequested player consumes work before position/scope lookup.
            if(!budget.take()){exhausted=true;missing.addAll(request.sensorSet());break;}
            ScopeRef scope=playerScope(p);BlockPosition pos=position(p);
            if(!budget.take()){exhausted=true;missing.addAll(request.sensorSet());break;}
            Optional<RegionId> membership=primary.apply(pos);
            if(!all&&!request.scopes().scopes().contains(scope)&&membership.stream().noneMatch(id->request.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION,id.value()))))continue;
            if(!budget.take()){exhausted=true;missing.addAll(request.sensorSet());break;}
            PlayerId id=new PlayerId(p.getUUID());
            if(p.serverLevel().hasChunkAt(p.blockPosition()))chunks.add(new ChunkRef(pos.dimension(),Math.floorDiv(pos.x(),16),Math.floorDiv(pos.z(),16)));
            Set<RegionId> regions=membership.map(Set::of).orElse(Set.of());Revision revision=revisions.getOrDefault(id,new Revision(0));
            var capability=Objects.requireNonNull(capabilities.apply(id),"capability snapshot required");
            players.put(id,new PlayerSnapshot(id,revision,now,incomplete?PlayerEligibility.UNAVAILABLE:eligibility(p,now),new PositionSnapshot(pos,now),regions,Set.of(),new DurationTicks(0),Set.of(),capability,Set.of()));
            versions.put(scope,revision);Set<SensorKind> available=new HashSet<>(request.sensorSet());available.retainAll(supported);
            scopes.put(scope,new ScopeSnapshot(scope,revision,available,missing,Map.of()));
        }
        ProximityGroups.Result groups=exhausted||!budget.available()?new ProximityGroups.Result(Map.of(),Map.of(),false,0):ProximityGroups.build(players,playerCell,budget.remaining(),now,budget);
        // Region/player-limited snapshots cannot establish absence of nearby unrequested players.
        if(!all||!groups.complete()||exhausted)missing.add(SensorKind.GROUP_MEMBERSHIP);
        Map<PlayerId,PlayerSnapshot> grouped=new HashMap<>();
        for(var entry:players.entrySet()) {
            PlayerId id=entry.getKey();var p=entry.getValue();Set<UUID> memberships=groups.memberships().getOrDefault(id,Set.of());
            if(p.eligibility()!=PlayerEligibility.ELIGIBLE||!memberships.isEmpty())continuity.resetIsolation(id);
            long isolated=missing.contains(SensorKind.GROUP_MEMBERSHIP)?0:continuity.isolatedTicks(id,now);
            grouped.put(id,new PlayerSnapshot(id,p.revision(),now,p.eligibility(),p.position(),p.regionIds(),memberships,new DurationTicks(isolated),p.observedReactions(),p.clientCapabilities(),p.contextTags()));
        }
        for(var entry:new ArrayList<>(grouped.entrySet())) {
            PlayerId id=entry.getKey();PlayerSnapshot p=entry.getValue(),old=publishedSamples.get(id);
            Revision revision=revisions.getOrDefault(id,new Revision(0));
            if(old==null||!old.position().position().equals(p.position().position())||old.eligibility()!=p.eligibility()
                    ||!old.regionIds().equals(p.regionIds())||!old.groupIds().equals(p.groupIds())||!old.clientCapabilities().equals(p.clientCapabilities()))revision=revision.plus(1);
            PlayerSnapshot updated=new PlayerSnapshot(id,revision,p.capturedTick(),p.eligibility(),p.position(),p.regionIds(),p.groupIds(),p.isolatedTicks(),p.observedReactions(),p.clientCapabilities(),p.contextTags());
            grouped.put(id,updated);publishedSamples.put(id,updated);revisions.put(id,revision);
            ScopeRef scope=new ScopeRef(ScopeKind.PLAYER,id.value());versions.put(scope,revision);ScopeSnapshot scopeSnapshot=scopes.get(scope);
            scopes.put(scope,new ScopeSnapshot(scope,revision,scopeSnapshot.availableSensors(),scopeSnapshot.missingSensors(),scopeSnapshot.scoringFeatures()));
        }
        players=grouped;
        if(incomplete)missing.addAll(request.sensorSet());
        if(!missing.isEmpty()) {
            Map<ScopeRef,ScopeSnapshot> corrected=new HashMap<>();
            scopes.forEach((key,value)->{Set<SensorKind> available=new HashSet<>(value.availableSensors());available.removeAll(missing);
                corrected.put(key,new ScopeSnapshot(key,value.revision(),available,missing,Map.of()));});scopes=corrected;
        }
        ObservationContext context=new ObservationContext(scopes,players,groups.groups(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
        HealthMode measuredHealth=Objects.requireNonNull(health.get());
        return new SnapshotBatch(request.requestId(),request.scopes(),now,versions,context,missing,chunks,incomplete&&measuredHealth==HealthMode.NORMAL?HealthMode.DEGRADED:measuredHealth);
    }
    /** GameTest-only synthetic witness, passed through the REAL CampSampler
     * and ObservationPipeline; cannot be enabled in gameplay sessions. */
    public void labTestCampWindow(AnchorRecord anchor){
        requireActor();
        if(production || !Boolean.getBoolean("proutlost.director.nativeGameTests"))
            throw new IllegalStateException("camp fixture is GameTest only");
        if(!anchor.active() || anchor.origin()!=AnchorOrigin.CAMP)
            throw new IllegalArgumentException("known active camp required");
        // Even the synthetic GameTest witness must use a physically present
        // block. This exercises the exact gate applied to real player censuses.
        if(!loadedPhysicalCampfire(anchor))
            throw new IllegalStateException("unloaded or missing physical campfire");
        Tick now=clock.get().simTick();
        if(now.value()<40)throw new IllegalStateException("simTick too young");
        RegionId region=anchor.regionIds().stream().sorted().findFirst()
            .orElse(new RegionId(anchor.anchorId().value()));
        var witness=List.of(new PositionSnapshot(anchor.position(),now));
        long duration=Math.min(40,Math.floorMod(now.value()-1,bucketTicks)+1);
        if(!camps.observeInterval(anchor,region,witness,now,
            Math.floorMod(now.value(),24000),duration,campRadius))
            throw new IllegalStateException("camp evidence saturated");
    }
    /** Diagnostic raw windows; durable delivery is owned by seal/acknowledge. */
    public Set<ScopeRef> pendingMemoryScopes(){
        requireActor();var refs=new HashSet<>(buffer.pendingMemoryScopes());
        camps.pendingRegions().forEach(id->refs.add(new ScopeRef(ScopeKind.REGION,id.value())));
        return Set.copyOf(refs);
    }
    public Set<AnchorId> pendingMemoryAnchors(){requireActor();return camps.pendingAnchors();}
    public List<CampSampler.RawWindow> sealedCampWindows(){requireActor();return camps.sealed(ObservationPipeline.campCutoff(clock.get().simTick(),campTicks));}
    /** Call only after the anchor CAS is durable and the sparse anchor read projection is published. */
    public void acknowledgeAnchor(AnchorId id,Revision revision){requireActor();observedCampfires.entrySet().removeIf(e->e.getValue().anchorId().equals(id)&&e.getValue().header().revision().equals(revision));}
    public ObservationBatch seal(SnapshotBatch liveContext,MemoryView before){requireActor();if(!before.scopes().worldId().equals(world))throw new IllegalArgumentException("wrong memory world");return pipeline.seal(liveContext,before);}
    public ObservationBatch rebaseAfterRejection(CommitAck ack,SnapshotBatch context,MemoryView before){requireActor();return pipeline.rebaseAfterRejection(ack,context,before);}
    public void acknowledge(List<ObservationId> inputs){requireActor();pipeline.acknowledge(inputs,clock.get().simTick());}
    /** After the actor has drained pre-topology observations and committed the topology transaction. */
    public void invalidateMembership(){requireActor();lastRegion.clear();previousPosition.clear();continuity.clear();invalidationGeneration=Math.addExact(invalidationGeneration,1);}
    public long invalidationGeneration(){requireActor();return invalidationGeneration;}
    @Override public void close(){requireActor();if(bus!=null){listeners.forEach(bus::unregister);listeners.clear();bus=null;}continuity.clear();lastSample.clear();previousEligibility.clear();lastRegion.clear();previousPosition.clear();transitioning.clear();revisions.clear();publishedSamples.clear();observedCampfires.clear();}
}
