package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/** Bounded read projection backed by the durable store; reductions never mutate this cache.
 * Bootstrap calls refresh on its projection worker, using J04 page cursors for cold data.
 * Missing scopes are absent from revisions, never interpreted as known empty safety data.
 */
public final class DurableMemoryPort implements MemoryPort,ProviderProvenance {
    private final DurableStore store;
    private final ProviderMode mode;
    private final StoreSession session;
    private final MemoryReducer reducer;
    private final int residentLimit,regionLimit,playerLimit;
    private record CachedProjection(ScopeSet scopes,MemoryView value) {}
    /** Authoritative fields are immutable. At most one derived view belongs to
     * this exact publication; replacing the publication releases its memo too.
     * It cannot refresh evidence, claim coverage, or survive a session fence. */
    private static final class Published {
        private final Map<AggregateKey,AggregateState> states;
        private final ScopeSet selectedCoverage;
        private volatile CachedProjection cached;
        Published(Map<AggregateKey,AggregateState> states,ScopeSet selectedCoverage){
            this.states=states;this.selectedCoverage=selectedCoverage;
        }
        Map<AggregateKey,AggregateState> states(){return states;}
        ScopeSet selectedCoverage(){return selectedCoverage;}
        MemoryView view(ScopeSet scopes){
            var memo=cached;
            if(memo!=null&&memo.scopes().equals(scopes))return memo.value();
            var value=MemoryProjection.of(scopes,states.values());
            cached=new CachedProjection(scopes,value);
            return value;
        }
    }
    private volatile Published published = new Published(Map.of(),null);
    private final LinkedHashMap<AggregateKey,AggregateState> resident = new LinkedHashMap<>();
    private Thread projectionWriter;
    private LinkedHashMap<AggregateKey,AggregateState> staging;
    private Optional<String> expectedCursor=Optional.empty();
    private Sequence stagingSequence;

    public DurableMemoryPort(DurableStore store, MemorySettings settings) {
        this.store = Objects.requireNonNull(store);
        mode=Objects.requireNonNull(settings).mode();
        if(mode==ProviderMode.PRODUCTION &&
           (!(store instanceof ProviderProvenance provenance) ||
            !Set.copyOf(provenance.supportedModes()).contains(mode)))
            throw new IllegalArgumentException("unprovenanced production memory store");
        session=store.session();
        reducer = new MemoryReducer(settings);
        regionLimit=settings.count("memory.resident_region_limit");playerLimit=settings.count("memory.resident_player_limit");
        residentLimit = Math.addExact(Math.multiplyExact(settings.count("memory.resident_region_limit"),4),
                Math.multiplyExact(settings.count("memory.resident_player_limit"),settings.count("memory.player_recent_event_entries")+1));
    }

    @Override public Set<ProviderMode> supportedModes(){return Set.of(mode);}

    /** One bounded page per call, no waiting/IO. Returns the original cursor and durable sequence. */
    public StoreView refresh(StoreQuery query) {
        if (projectionWriter == null) projectionWriter = Thread.currentThread();
        if (projectionWriter != Thread.currentThread()) throw new IllegalStateException("single projection writer required");
        if(!session.equals(store.session()))throw new IllegalStateException("store session changed; rebuild projection");
        if (!query.worldId().equals(session.worldId())) throw new IllegalArgumentException("wrong world");
        StoreReadResult result = store.view(query);
        ReadContracts.validate(result, query, session);
        if (result.status() != ReadStatus.AVAILABLE)
            throw new IllegalStateException("Memory projection requires hydrated page: " + result.status());
        StoreView page = result.page().orElseThrow();
        if (page.aggregates().size() > query.limit()) throw new IllegalStateException("store exceeded page bound");
        if(query.pageCursor().isPresent()&&(staging==null||!query.pageCursor().equals(expectedCursor)))throw new IllegalArgumentException("unexpected projection cursor");
        if(query.pageCursor().isPresent()&&!page.lastDurableSequence().equals(stagingSequence))throw new IllegalStateException("page snapshot changed; restart query");
        if(page.hasMore()&&(page.nextCursor().isEmpty()||page.nextCursor().equals(query.pageCursor())))throw new IllegalStateException("non-advancing store cursor");
        // Complete WAL chronology from sequence zero MUST be replayed from a
        // blank candidate on each scan; replaying older rev1 over the current
        // resident revN would misdiagnose a valid new snapshot as rollback.
        // Cursor pages keep the same staged candidate until the final page.
        boolean fullChronology=query.aggregateKeys().isEmpty() &&
            query.sequence().value()==0;
        var staged = new LinkedHashMap<AggregateKey,AggregateState>(
            query.pageCursor().isPresent()?staging:
                fullChronology?Map.of():resident);
        // StoreQuery with empty aggregateKeys is CHRONOLOGICAL and returns
        // records(), not aggregates(). Older J04 projection code ignored the
        // chronological WAL entirely, making an existing player revision look
        // like absent revision zero. This breaks strict CAS after reboot.
        // Reconstruct immutable aggregate records in source WAL order.
        List<AggregateState> recovered = new ArrayList<>();
        // Native J04 returns aggregates for key-specific pages and records
        // for chronological pages. The read contract also permits a mixed
        // verified page from a compatible projection provider. Consume both
        // in their supplied order; published state remains atomic per scan.
        for(RestoredAggregate restored:page.aggregates())
            recovered.add(restored.state());
        for(DurableRecord record:page.records()) {
            if(record instanceof DurableAggregate aggregate)
                recovered.add(aggregate.state());
            // Other durable records never fabricate memory aggregates.
            // Cold/missing histories already fail closed via ReadContracts.
        }
        for (AggregateState next : recovered) {
            if (!(next instanceof RegionAggregate || next instanceof RegionMemoryAggregate || next instanceof AnchorAggregate
                    || next instanceof LocationAggregate || next instanceof PlayerOperationalAggregate || next instanceof PlayerSignificantAggregate)) continue;
            AggregateState old = staged.get(next.key());
            if (old != null && next.header().revision().compareTo(old.header().revision()) < 0)
                throw new IllegalStateException("projection revision rollback");
            if (old != null && next.header().revision().equals(old.header().revision()) && !old.equals(next))
                throw new IllegalStateException("conflicting projection at same revision");
            staged.remove(next.key()); staged.put(next.key(),next);
            while (staged.size() > residentLimit) staged.remove(staged.keySet().iterator().next());
        }
        trimScopes(staged);
        if(page.hasMore()) {
            staging=staged;expectedCursor=page.nextCursor();stagingSequence=page.lastDurableSequence();
        } else {
            resident.clear();resident.putAll(staged);published = new Published(Map.copyOf(staged),null);
            staging=null;expectedCursor=Optional.empty();stagingSequence=null;
        }
        return page;
    }

    /** Atomically publishes an explicitly selected, complete current-head read.
     * Native generation hydration is not chronological replay: staged records
     * never enter this cache, and omitted cold scopes remain unknown.
     */
    public void replaceProjection(Collection<AggregateState> states) {
        var coverage=new HashSet<ScopeRef>();
        for(var state:states)switch(state){
            case RegionAggregate r->coverage.add(new ScopeRef(ScopeKind.REGION,r.value().regionId().value()));
            case RegionMemoryAggregate r->coverage.add(new ScopeRef(ScopeKind.REGION,r.value().regionId().value()));
            case LocationAggregate l->coverage.add(new ScopeRef(ScopeKind.LOCATION,l.value().locationId().value()));
            case PlayerOperationalAggregate p->coverage.add(new ScopeRef(ScopeKind.PLAYER,p.value().playerId().value()));
            case PlayerSignificantAggregate p->coverage.add(new ScopeRef(ScopeKind.PLAYER,p.value().playerId().value()));
            case AnchorAggregate a->{a.value().regionIds().forEach(id->coverage.add(new ScopeRef(ScopeKind.REGION,id.value())));
                if(a.value().origin()==AnchorOrigin.CAMP&&a.value().regionIds().isEmpty())coverage.add(new ScopeRef(ScopeKind.REGION,a.value().anchorId().value()));}
            default->{}
        }
        replaceProjection(new ScopeSet(session.worldId(),coverage),states);
    }
    public void replaceProjection(ScopeSet coverage,Collection<AggregateState> states) {
        Objects.requireNonNull(coverage);
        if(!coverage.worldId().equals(session.worldId())||coverage.scopes().contains(new ScopeRef(ScopeKind.WORLD,session.worldId().value())))
            throw new IllegalArgumentException("selected memory cannot claim global coverage");
        if(projectionWriter==null)projectionWriter=Thread.currentThread();
        if(projectionWriter!=Thread.currentThread())throw new IllegalStateException("single projection writer required");
        if(!session.equals(store.session()))throw new IllegalStateException("store session changed; rebuild projection");
        var next=new LinkedHashMap<AggregateKey,AggregateState>();
        Set<RegionId> regions=new HashSet<>();Set<PlayerId> players=new HashSet<>();int metadata=0;
        for(var state:states) {
            if(!(state instanceof RegionAggregate||state instanceof RegionMemoryAggregate||state instanceof AnchorAggregate
                ||state instanceof LocationAggregate||state instanceof PlayerOperationalAggregate||state instanceof PlayerSignificantAggregate))
                throw new IllegalArgumentException("non-memory selected aggregate");
            if(next.putIfAbsent(state.key(),state)!=null)throw new IllegalArgumentException("duplicate selected memory key");
            var region=regionOf(state);if(region!=null)regions.add(region);
            var player=playerOf(state);if(player!=null)players.add(player);
            if(state instanceof RegionAggregate||state instanceof RegionMemoryAggregate||state instanceof AnchorAggregate
                ||state instanceof LocationAggregate)metadata++;
        }
        if(metadata>BoundedMetadataProjection.MAX_METADATA||next.size()>residentLimit
            ||regions.size()>regionLimit||players.size()>playerLimit)
            throw new IllegalArgumentException("selected memory working-set capacity");
        // Check the complete candidate before changing any published reference.
        resident.clear();resident.putAll(next);published=new Published(Map.copyOf(next),coverage);
        staging=null;expectedCursor=Optional.empty();stagingSequence=null;
    }

    private void trimScopes(LinkedHashMap<AggregateKey,AggregateState> states) {
        LinkedHashSet<RegionId> regions=new LinkedHashSet<>();LinkedHashSet<PlayerId> players=new LinkedHashSet<>();
        for(var state:states.values()) {
            RegionId region=regionOf(state);PlayerId player=playerOf(state);
            if(region!=null){regions.remove(region);regions.add(region);}
            if(player!=null){players.remove(player);players.add(player);}
        }
        Set<RegionId> evictedRegions=new HashSet<>();Set<PlayerId> evictedPlayers=new HashSet<>();
        while(regions.size()>regionLimit){RegionId id=regions.iterator().next();regions.remove(id);evictedRegions.add(id);}
        while(players.size()>playerLimit){PlayerId id=players.iterator().next();players.remove(id);evictedPlayers.add(id);}
        states.entrySet().removeIf(e->evictedRegions.contains(regionOf(e.getValue()))||evictedPlayers.contains(playerOf(e.getValue())));
    }
    private static RegionId regionOf(AggregateState state){return switch(state){case RegionAggregate a->a.value().regionId();case RegionMemoryAggregate a->a.value().regionId();default->null;};}
    private static PlayerId playerOf(AggregateState state){return switch(state){case PlayerOperationalAggregate a->a.value().playerId();case PlayerSignificantAggregate a->a.value().playerId();default->null;};}
    @Override public MemoryView view(ScopeSet scopes) {
        if(!session.equals(store.session()))throw new IllegalStateException("store session changed; rebuild projection");
        if (!scopes.worldId().equals(session.worldId())) throw new IllegalArgumentException("wrong world");
        var snapshot=published;var effective=scopes;
        if(snapshot.selectedCoverage()!=null){
            if(scopes.scopes().contains(new ScopeRef(ScopeKind.WORLD,scopes.worldId().value())))effective=snapshot.selectedCoverage();
            else{var selected=new HashSet<>(scopes.scopes());selected.retainAll(snapshot.selectedCoverage().scopes());
                effective=new ScopeSet(scopes.worldId(),selected);}
        }
        return snapshot.view(effective);
    }
    @Override public Reduction reduce(MemoryView before, ObservationBatch input, DirectorClock clock) {
        return reducer.reduce(before,input,clock);
    }
}
