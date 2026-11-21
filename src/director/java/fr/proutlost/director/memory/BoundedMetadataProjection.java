package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Demand read over one immutable J04 cut. Pages and the selected metadata working
 * set are bounded independently of catalogue size. No stage record is a memory
 * aggregate; only the active J04 head index can supply these states.
 *
 * Cells are a broad phase only: actual membership is still decided by J01's
 * exact SupportGeometry test. Covering the complete cell prevents movement
 * inside it from exposing an unhydrated overlapping region as known absent.
 */
public final class BoundedMetadataProjection {
    private static final int PAGE_SIZE=96;
    /** Local demand can exceed a bounded cache without corrupting J04. */
    public static final class DemandCapacityException extends IllegalStateException {
        private static final long serialVersionUID=1L;
        public DemandCapacityException(String message){super(message);}
    }
    public static final int MAX_METADATA=512;
    private static final Set<AggregateKind> METADATA=Set.of(AggregateKind.REGION,
        AggregateKind.REGION_MEMORY,AggregateKind.LOCATION,AggregateKind.ANCHOR);
    public record Cell(DimensionId dimension,long x,long z) {
        public Cell {Objects.requireNonNull(dimension);}
    }
    public record Demand(Set<PlayerId> players,Set<Cell> cells,int cellBlocks,Set<ScopeRef> pinned,Set<AnchorId> anchors) {
        public Demand(Set<PlayerId> players,Set<Cell> cells,int cellBlocks,Set<ScopeRef> pinned){this(players,cells,cellBlocks,pinned,Set.of());}
        public Demand {
            players=Set.copyOf(players);cells=Set.copyOf(cells);pinned=Set.copyOf(pinned);anchors=Set.copyOf(anchors);
            if(cellBlocks<1||cellBlocks>1024||players.size()>128||cells.size()>256||pinned.size()>512||anchors.size()>512)
                throw new IllegalArgumentException("metadata demand bound");
        }
        public static Cell cell(BlockPosition point,int blocks) {
            if(blocks<1)throw new IllegalArgumentException("metadata cell size");
            return new Cell(point.dimension(),Math.floorDiv(point.x(),blocks),Math.floorDiv(point.z(),blocks));
        }
    }
    public record View(MetadataReadSnapshot snapshot,Demand demand,
            Map<AggregateKey,AggregateState> states,ScopeSet scopes,long examinedRows,int peakPageRows,Optional<RegionAnchorCoverage> anchorCoverage) {
        public View(MetadataReadSnapshot snapshot,Demand demand,Map<AggregateKey,AggregateState> states,ScopeSet scopes,long examinedRows,int peakPageRows){
            this(snapshot,demand,states,scopes,examinedRows,peakPageRows,Optional.empty());
        }
        public View {
            Objects.requireNonNull(snapshot);Objects.requireNonNull(demand);states=Map.copyOf(states);
            Objects.requireNonNull(scopes);Objects.requireNonNull(anchorCoverage);
            if(examinedRows<0||peakPageRows<0||peakPageRows>PAGE_SIZE
                ||states.keySet().stream().filter(k->METADATA.contains(k.kind())).count()>MAX_METADATA)
                throw new IllegalArgumentException("metadata projection bound");
        }
        public MemoryView memory(){return MemoryProjection.of(scopes,states.values());}
        public boolean currentHeads(MetadataReadPortProvider source,MetadataReadSnapshot current){
            var store=source.metadataReads();if(!store.owns(snapshot)||!store.owns(current))return false;
            return new ScopedAggregateCapture.View(snapshot,states).sameMemoryHeads(store,current,scopes,demand.anchors())
                &&anchorCoverage.map(value->value.current(store,current)).orElse(true);
        }
    }
    private BoundedMetadataProjection(){}
    public static CompletionStage<View> load(MetadataReadPortProvider source,MetadataReadSnapshot snapshot,
            Demand demand,MemorySettings settings) {return load(source.metadataReads(),snapshot,demand,settings,true);}
    /** Refresh only the already selected keys when spatial, timed/reference,
     * recent-memory and scoped absence proofs are unchanged. Every body comes
     * from the new pinned cut; the result still needs currentHeads at publication.
     * Any selection uncertainty falls back to the full bounded catalogue scan. */
    public static CompletionStage<View> refresh(MetadataReadPortProvider source,View previous,MetadataReadSnapshot current,
            Demand demand,MemorySettings settings) {
        var store=Objects.requireNonNull(source).metadataReads();Objects.requireNonNull(demand);Objects.requireNonNull(settings);
        if(previous==null||!previous.demand().equals(demand)||previous.states().isEmpty()
            ||previous.anchorCoverage().isEmpty()||!previous.anchorCoverage().orElseThrow().sameSettings(settings)
            ||!previous.anchorCoverage().orElseThrow().sameSelection(store,current)
            ||!new ScopedAggregateCapture.View(previous.snapshot(),previous.states()).sameMemoryAbsences(store,current,previous.scopes(),demand.anchors()))
            return load(store,current,demand,settings);
        return ScopedAggregateCapture.load(store,current,previous.states().keySet(),previous.states().size()).thenApply(captured->{
            var coverage=previous.anchorCoverage().orElseThrow().rebound(current,captured.states(),settings);
            return new View(current,demand,captured.states(),previous.scopes(),captured.states().size(),Math.min(PAGE_SIZE,captured.states().size()),Optional.of(coverage));
        });
    }
    /** Administrative exact reads have an independent working set; they never
     * pin unrelated online players, active events or timed camp reductions. */
    public static CompletionStage<View> loadExact(MetadataReadPortProvider source,MetadataReadSnapshot snapshot,
            Demand demand,MemorySettings settings) {return load(source.metadataReads(),snapshot,demand,settings,false);}
    private static CompletionStage<View> load(MetadataReadPort store,MetadataReadSnapshot snapshot,
            Demand demand,MemorySettings settings,boolean liveDependencies) {
        Objects.requireNonNull(store);Objects.requireNonNull(snapshot);Objects.requireNonNull(demand);
        if(!store.owns(snapshot))throw new IllegalStateException("foreign or revoked metadata snapshot");
        var scan=new Scan(store,snapshot,demand,settings,liveDependencies);
        // Exact nonspatial region/location reads already know their primary IDs.
        // Do not hydrate foreign region/memory/location bodies merely to discard them.
        // All anchor heads are still scanned: reverse membership need not be declared
        // by RegionRecord, and its complete census remains part of the freshness proof.
        if(!liveDependencies&&demand.players().isEmpty()&&demand.cells().isEmpty()
                &&demand.pinned().stream().allMatch(s->s.kind()==ScopeKind.REGION||s.kind()==ScopeKind.LOCATION)) {
            var primary=new HashSet<AggregateKey>();
            scan.pinnedRegions.forEach(id->primary.add(new AggregateKey(AggregateKind.REGION,id.value())));
            return scan.keyedPass(primary,scan::region)
                .thenCompose(v->scan.pass(Set.of(AggregateKind.ANCHOR),scan::anchor))
                .thenCompose(v->scan.exactRemaining())
                .thenApply(v->scan.finish());
        }
        CompletionStage<Void> initial=liveDependencies?
            scan.pass(Set.of(AggregateKind.EVENT,AggregateKind.CHAIN,AggregateKind.PLAYER_OPERATIONAL,AggregateKind.REGION_MEMORY),scan::references):
            demand.players().isEmpty()?CompletableFuture.completedFuture(null):scan.pass(Set.of(AggregateKind.PLAYER_OPERATIONAL),scan::references);
        return initial.thenCompose(v->scan.pass(Set.of(AggregateKind.REGION),scan::region))
            .thenCompose(v->scan.pass(Set.of(AggregateKind.ANCHOR),scan::anchor))
            .thenCompose(v->scan.pass(Set.of(AggregateKind.LOCATION,AggregateKind.REGION_MEMORY,
                AggregateKind.PLAYER_SIGNIFICANT),scan::remaining))
            .thenApply(v->scan.finish());
    }
    @FunctionalInterface private interface Row {void accept(AggregateState state);}
    private static final class Scan {
        final MetadataReadPort store;final MetadataReadSnapshot snapshot;final Demand demand;
        final int maximumStates,maximumRegions,maximumPlayers,maximumSignificant;final boolean liveDependencies;
        final MemorySettings settings;
        final Set<AnchorId> mandatoryAnchors=new HashSet<>();
        final Map<RegionId,Set<AnchorId>> missingAnchors=new HashMap<>();
        final Map<AnchorId,Set<RegionId>> declaredOwners=new HashMap<>();
        final Map<RegionId,Set<TagId>> anchorTags=new HashMap<>();
        final Map<RegionId,Set<AnchorId>> coveredAnchors=new HashMap<>();
        final LinkedHashMap<AggregateKey,AggregateState> selected=new LinkedHashMap<>();
        final Set<RegionId> regions=new HashSet<>(),pinnedRegions=new HashSet<>(),candidateRegions=new HashSet<>();
        final Set<LocationId> locations=new HashSet<>();
        final Set<PlayerId> selectedPlayers=new HashSet<>();
        final Map<PlayerId,PriorityQueue<PlayerSignificantAggregate>> significant=new HashMap<>();
        long examined;int peak,metadataCount;
        Scan(MetadataReadPort store,MetadataReadSnapshot snapshot,Demand demand,MemorySettings settings,boolean liveDependencies) {
            this.store=store;this.snapshot=snapshot;this.demand=demand;this.liveDependencies=liveDependencies;this.settings=settings;
            mandatoryAnchors.addAll(demand.anchors());
            maximumRegions=settings.count("memory.resident_region_limit");
            maximumPlayers=settings.count("memory.resident_player_limit");
            maximumSignificant=settings.count("memory.player_recent_event_entries");
            maximumStates=Math.addExact(Math.multiplyExact(maximumRegions,4),
                Math.multiplyExact(maximumPlayers,maximumSignificant+1));
            if(demand.players().size()>maximumPlayers)throw new IllegalArgumentException("metadata player demand capacity");
            for(var scope:demand.pinned())switch(scope.kind()) {
                case REGION->pinnedRegions.add(new RegionId(scope.id()));
                case LOCATION->locations.add(new LocationId(scope.id()));
                default->{}
            }
        }
        CompletionStage<Void> pass(Set<AggregateKind> kinds,Row consume) {
            return page(kinds,Optional.empty(),consume);
        }
        CompletionStage<Void> page(Set<AggregateKind> kinds,Optional<AggregateKey> after,Row consume) {
            var keys=store.metadataKeysPage(snapshot,kinds,after,PAGE_SIZE)
                .orElseThrow(()->new IllegalStateException("metadata snapshot unavailable"));
            if(keys.keys().isEmpty()) {
                if(keys.next().isPresent())throw new IllegalStateException("nonadvancing metadata key page");
                return CompletableFuture.completedFuture(null);
            }
            return readKeys(Set.copyOf(keys.keys()),consume).thenCompose(v->{
                if(keys.next().isEmpty())return CompletableFuture.completedFuture(null);
                if(keys.next().equals(after))throw new IllegalStateException("nonadvancing metadata key cursor");
                return page(kinds,keys.next(),consume);
            });
        }
        CompletionStage<Void> exactRemaining() {
            var keys=new HashSet<AggregateKey>();
            regions.forEach(id->keys.add(new AggregateKey(AggregateKind.REGION_MEMORY,id.value())));
            locations.forEach(id->keys.add(new AggregateKey(AggregateKind.LOCATION,id.value())));
            return keyedPass(keys,this::remaining);
        }
        CompletionStage<Void> keyedPass(Set<AggregateKey> candidates,Row consume) {
            var keys=candidates.stream().filter(k->store.metadataRevision(snapshot,k).value()!=0)
                .sorted(Comparator.comparing(k->k.kind().name()+":"+k.id())).toList();
            CompletionStage<Void> chain=CompletableFuture.completedFuture(null);
            for(int start=0;start<keys.size();start+=PAGE_SIZE) {
                var page=Set.copyOf(keys.subList(start,Math.min(keys.size(),start+PAGE_SIZE)));
                chain=chain.thenCompose(v->readKeys(page,consume));
            }
            return chain;
        }
        CompletionStage<Void> readKeys(Set<AggregateKey> keys,Row consume) {
            var query=new StoreQuery(snapshot.session().worldId(),keys,new Sequence(0),Optional.empty(),PAGE_SIZE);
            return store.readMetadata(snapshot,query).thenAccept(result->{
                ReadContracts.validate(result,query,snapshot.session());
                if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("metadata page unavailable: "+result.status());
                var page=result.page().orElseThrow();
                if(page.hasMore()||!page.lastDurableSequence().equals(snapshot.cut())
                    ||page.aggregates().size()!=keys.size()||!page.records().isEmpty())
                    throw new IllegalStateException("metadata page completeness/cut");
                peak=Math.max(peak,page.aggregates().size());
                Set<AggregateKey> expected=new HashSet<>(keys);
                for(var row:page.aggregates()) {
                    if(!expected.remove(row.state().key()))throw new IllegalStateException("metadata page duplicate/foreign key");
                    examined=Math.incrementExact(examined);consume.accept(row.state());
                }
                if(!expected.isEmpty())throw new IllegalStateException("metadata page key omitted");
            });
        }
        void add(AggregateState state) {
            if(selected.containsKey(state.key()))throw new IllegalStateException("metadata duplicate selected key");
            if(METADATA.contains(state.key().kind())&&++metadataCount>MAX_METADATA)
                throw new DemandCapacityException("metadata demand exceeds512; repartition demand");
            if(selected.size()>=maximumStates)throw new DemandCapacityException("memory demand capacity");
            selected.put(state.key(),state);
        }
        void references(AggregateState state) {
            if(!liveDependencies){
                if(state instanceof PlayerOperationalAggregate p&&demand.players().contains(p.value().playerId())){
                    selectedPlayers.add(p.value().playerId());add(p);
                }
                return;
            }
            if(state instanceof EventAggregate e) {
                if(switch(e.value().state()) {case SUCCEEDED,PARTIAL,ABORTED_SAFE,FAILED_SAFE->false;default->true;})
                    pinnedRegions.addAll(e.value().regionIds());
            }else if(state instanceof ChainAggregate c) {
                if(c.value().state()!=ChainState.COMPLETED&&c.value().state()!=ChainState.ABORTED_SAFE
                    &&(c.value().state()!=ChainState.SETTLED_AS_WORLD_STATE||c.value().regionalStateRef().isPresent()))
                    pinnedRegions.addAll(c.value().lineageRefs());
            }else if(state instanceof PlayerOperationalAggregate p) {
                // A disconnect does not acknowledge J01's sealed observations.
                // Retain the existing bounded player heads until their owner
                // can reduce pending input with the actual durable revision.
                if(selectedPlayers.size()>=maximumPlayers)throw new DemandCapacityException("metadata operational player cache capacity");
                selectedPlayers.add(p.value().playerId());add(p);
                if(demand.players().contains(p.value().playerId()))p.value().lastRegionId().ifPresent(pinnedRegions::add);
            }else if(state instanceof RegionMemoryAggregate r&&!r.value().activity().camps().isEmpty()) {
                // An occupied/recent durable camp still needs native timed
                // absence/return reduction after its last player has left.
                pinnedRegions.add(r.value().regionId());mandatoryAnchors.addAll(r.value().activity().camps().keySet());
            }
            if(pinnedRegions.size()>maximumRegions)throw new DemandCapacityException("metadata pinned region capacity");
        }
        void region(AggregateState state) {
            var region=((RegionAggregate)state).value();
            if(pinnedRegions.contains(region.regionId())||
                (region.status()==RegionStatus.ACTIVE||region.status()==RegionStatus.DORMANT)
                    &&intersects(region.supportShape(),region.dimension(),demand)) {
                if(regions.size()>=maximumRegions)throw new DemandCapacityException("metadata region demand capacity");
                regions.add(region.regionId());add(state);
                missingAnchors.put(region.regionId(),new HashSet<>(region.anchorIds()));anchorTags.put(region.regionId(),new HashSet<>());coveredAnchors.put(region.regionId(),new HashSet<>());
                for(var id:region.anchorIds())declaredOwners.computeIfAbsent(id,unused->new HashSet<>()).add(region.regionId());
            }
        }
        void anchor(AggregateState state) {
            var anchor=((AnchorAggregate)state).value();
            var owners=new HashSet<>(declaredOwners.getOrDefault(anchor.anchorId(),Set.of()));
            anchor.regionIds().stream().filter(regions::contains).forEach(owners::add);
            for(var owner:owners){missingAnchors.get(owner).remove(anchor.anchorId());coveredAnchors.get(owner).add(anchor.anchorId());if(anchor.active())anchorTags.get(owner).addAll(anchor.tags());}
            // Exact administration requests pin their declared references. Live
            // readers need only geometrically relevant bodies and unfinished
            // J01/camp writes, never all anchors of a large region.
            // A durable provisional CAMP exists before its first memory or
            // discovered RegionRecord. Nearby gameplay must keep that actual
            // anchor without requiring a still-pending append pin.
            boolean candidateCamp=liveDependencies&&anchor.origin()==AnchorOrigin.CAMP&&anchor.regionIds().isEmpty();
            boolean spatial=anchor.active()&&(!owners.isEmpty()||candidateCamp)&&intersects(anchor.dimension(),
                anchor.position().x()-Math.max(settings.number(anchor.radiusProfile()),settings.number("region.camp_radius_blocks")),
                anchor.position().z()-Math.max(settings.number(anchor.radiusProfile()),settings.number("region.camp_radius_blocks")),
                anchor.position().x()+Math.max(settings.number(anchor.radiusProfile()),settings.number("region.camp_radius_blocks")),
                anchor.position().z()+Math.max(settings.number(anchor.radiusProfile()),settings.number("region.camp_radius_blocks")),demand);
            if(mandatoryAnchors.contains(anchor.anchorId())||spatial||!liveDependencies&&anchor.active()&&!owners.isEmpty()) {
                add(state);anchor.locationId().ifPresent(locations::add);
                if(anchor.origin()==AnchorOrigin.CAMP&&anchor.regionIds().isEmpty()){
                    var candidate=new RegionId(anchor.anchorId().value());
                    if(!regions.contains(candidate)&&candidateRegions.add(candidate)&&regions.size()+candidateRegions.size()>maximumRegions)
                        throw new DemandCapacityException("metadata candidate memory capacity");
                }
            }
        }
        void remaining(AggregateState state) {
            if(state instanceof RegionMemoryAggregate r) {
                if(regions.contains(r.value().regionId())||candidateRegions.contains(r.value().regionId()))add(state);
            }else if(state instanceof LocationAggregate l) {
                if(locations.contains(l.value().locationId())||l.value().active()&&intersects(l.value().bounds(),demand))add(state);
            }else if(state instanceof PlayerSignificantAggregate p&&selectedPlayers.contains(p.value().playerId())) {
                var queue=significant.computeIfAbsent(p.value().playerId(),unused->new PriorityQueue<>(
                    Comparator.comparing((PlayerSignificantAggregate a)->a.value().recordedTick())
                        .thenComparing(a->a.value().memoryId())));
                queue.add(p);if(queue.size()>maximumSignificant)queue.remove();
            }
        }
        View finish() {
            for(var queue:significant.values())for(var state:queue)add(state);
            var scopes=new HashSet<>(demand.pinned());
            regions.forEach(id->scopes.add(new ScopeRef(ScopeKind.REGION,id.value())));
            // A provisional CAMP has durable memory before its discovery gate
            // creates RegionRecord. Its reserved identity is not a real region.
            candidateRegions.forEach(id->scopes.add(new ScopeRef(ScopeKind.REGION,id.value())));
            selectedPlayers.forEach(id->scopes.add(new ScopeRef(ScopeKind.PLAYER,id.value())));
            demand.players().forEach(id->scopes.add(new ScopeRef(ScopeKind.PLAYER,id.value())));
            for(var state:selected.values())if(state instanceof LocationAggregate l)
                scopes.add(new ScopeRef(ScopeKind.LOCATION,l.value().locationId().value()));
            else if(state instanceof AnchorAggregate a&&a.value().origin()==AnchorOrigin.CAMP&&a.value().regionIds().isEmpty()
                &&demand.anchors().contains(a.value().anchorId()))
                scopes.add(new ScopeRef(ScopeKind.REGION,a.value().anchorId().value()));
            // WORLD is deliberately absent: omitted cold regions are UNKNOWN,
            // not a claim that the selected cache is the complete world memory.
            var coverage=new HashMap<RegionId,RegionAnchorCoverage.Entry>();
            for(var state:selected.values())if(state instanceof RegionAggregate r)coverage.put(r.value().regionId(),
                new RegionAnchorCoverage.Entry(r.value(),anchorTags.get(r.value().regionId()),coveredAnchors.get(r.value().regionId()),missingAnchors.get(r.value().regionId()).isEmpty()));
            return new View(snapshot,demand,selected,new ScopeSet(snapshot.session().worldId(),scopes),examined,peak,
                Optional.of(new RegionAnchorCoverage(snapshot,coverage,(liveDependencies||metadataCount>0)?RegionAnchorCoverage.anchorHeads(store,snapshot):Map.of(),store.metadataSignificantVersions(snapshot,selectedPlayers)
                    .orElseThrow(()->new IllegalStateException("selected player census unavailable")),
                    (liveDependencies||metadataCount>0)?RegionAnchorCoverage.selectionHeads(store,snapshot):Map.of(),settings)));
        }
    }
    static boolean intersects(Aabb box,Demand demand) {
        for(var cell:demand.cells())if(cell.dimension().equals(box.dimension())
            &&box.maxXExclusive()>(double)cell.x()*demand.cellBlocks()
            &&box.minX()<(double)(cell.x()+1)*demand.cellBlocks()
            &&box.maxZExclusive()>(double)cell.z()*demand.cellBlocks()
            &&box.minZ()<(double)(cell.z()+1)*demand.cellBlocks())return true;
        return false;
    }
    static boolean intersects(SupportShape shape,DimensionId dimension,Demand demand) {
        for(var box:shape.boxes())if(intersects(box,demand))return true;
        for(var disc:shape.discs())if(intersects(dimension,disc.center().x()-disc.radius(),
                disc.center().z()-disc.radius(),disc.center().x()+disc.radius(),disc.center().z()+disc.radius(),demand))return true;
        for(var route:shape.routes())if(intersects(dimension,Math.min(route.from().x(),route.to().x())-route.radius(),
                Math.min(route.from().z(),route.to().z())-route.radius(),Math.max(route.from().x(),route.to().x())+route.radius(),
                Math.max(route.from().z(),route.to().z())+route.radius(),demand))return true;
        return false;
    }
    private static boolean intersects(DimensionId dimension,double minX,double minZ,double maxX,double maxZ,Demand demand) {
        for(var cell:demand.cells())if(cell.dimension().equals(dimension)
            &&maxX>=(double)cell.x()*demand.cellBlocks()&&minX<(double)(cell.x()+1)*demand.cellBlocks()
            &&maxZ>=(double)cell.z()*demand.cellBlocks()&&minZ<(double)(cell.z()+1)*demand.cellBlocks())return true;
        return false;
    }
}
