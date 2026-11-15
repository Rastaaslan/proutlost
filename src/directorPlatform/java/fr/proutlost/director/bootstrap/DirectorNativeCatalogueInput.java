package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.memory.MemoryProjection;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Actual J01/J04 input for the private catalogue. All scope CAS revisions come
 * from the corresponding persisted aggregate, not a logical memory maximum.
 * Filesystem reads remain behind JournalStore's asynchronous reader. This
 * capture does not assign health, regions, participants, risk or physical data.
 */
public final class DirectorNativeCatalogueInput {
    public record Requests(StoreSession session,Sequence cut,List<RequestView> values) {
        public Requests {Objects.requireNonNull(session);Objects.requireNonNull(cut);values=List.copyOf(values);}
    }
    public record Capture(DecisionInput input,Sequence cut) {}
    private static final Set<String> FACT_TYPES=Set.of(
        "proutlost:safe_micro_observed","proutlost:local_mist_observed",
        "proutlost:parallel_steps_observed","proutlost:goat_guest_observed",
        "proutlost:camp_regrowth_observed","proutlost:animal_migration_observed",
        "proutlost:controlled_landslide_observed","proutlost:required_signal_observed",
        "proutlost:shared_choice_observed","proutlost:context_lost",
        "proutlost_director:admin_tension_override");
    private final MinecraftServer server;
    private final WorldId world;
    private final JournalStore store;
    private final StoreSession session;
    private final ConfigView config;
    private final ProviderMode mode;
    private final Map<ResourceId,ConfigView> profiles;
    private final DirectorNativeRegionalPlanningBridge regions;
    private final Map<FactId,DirectorFact> facts=new LinkedHashMap<>();
    private Sequence factCut=new Sequence(0);
    private final Map<FactId,DirectorFact> observedFacts=new LinkedHashMap<>();
    private Consumer<DurableRecord> observer=row->{};
    public void observer(Consumer<DurableRecord> callback){actor();observer=Objects.requireNonNull(callback);}
    public List<DirectorFact> knownFacts(){actor();return List.copyOf(observedFacts.values());}
    private StoreQuery scanning;
    private CompletableFuture<StoreReadResult> read;
    private Optional<String> cursor=Optional.empty();
    private boolean recovered,failed;
    private String diagnostic="RECOVERING";
    public DirectorNativeCatalogueInput(MinecraftServer server,WorldId world,JournalStore store,ConfigView config){
        this(server,world,store,config,ProviderMode.TEST_ONLY,Map.of());
    }
    public DirectorNativeCatalogueInput(MinecraftServer server,WorldId world,JournalStore store,ConfigView config,ProviderMode mode,Map<ResourceId,ConfigView> profiles){
        this.profiles=Map.copyOf(profiles);this.mode=Objects.requireNonNull(mode);
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);
        this.store=Objects.requireNonNull(store);this.config=Objects.requireNonNull(config);
        actor();session=store.session();regions=new DirectorNativeRegionalPlanningBridge(config,mode);
        if(!session.worldId().equals(world)||config.calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("native catalogue input identity");
    }
    private void actor(){if(!server.isSameThread())throw new IllegalStateException("catalogue input actor");}
    private void accept(List<DurableRecord> rows){
        for(var row:rows)if(row instanceof DurableFact fact
            &&fact.value().durable()&&fact.value().evidenceStatus()==EvidenceStatus.CONFIRMED
            &&!fact.value().factType().equals(new FactType("proutlost_director:recorded_decision_input"))
            &&!fact.value().factType().equals(DirectorNativeEventEnvelopes.TYPE)){
            var old=observedFacts.putIfAbsent(fact.value().factId(),fact.value());
            if(old!=null&&!old.equals(fact.value()))throw new IllegalStateException("conflicting observed catalogue fact");
            if(observedFacts.size()>4096)throw new IllegalStateException("catalogue observed fact capacity");
            if(old==null)observer.accept(row);
        }
        for(var row:rows)if(row instanceof DurableFact f&&FACT_TYPES.contains(f.value().factType().value())){
            var value=f.value();
            if(!value.durable()||value.evidenceStatus()!=EvidenceStatus.CONFIRMED)continue;
            var old=facts.putIfAbsent(value.factId(),value);
            if(old!=null&&!old.equals(value))throw new IllegalStateException("conflicting catalogue fact");
            if(facts.size()>2048)throw new IllegalStateException("catalogue fact window requires archival");
        }
    }
    public void resetRecoveryScan(){
        actor();facts.clear();observedFacts.clear();factCut=new Sequence(0);recovered=false;failed=false;
        cursor=Optional.empty();read=null;scanning=null;diagnostic="RECOVERING";
    }
    public void acceptRecoveryRows(List<DurableRecord> rows,boolean last,Sequence cut){
        actor();if(recovered)throw new IllegalStateException("input recovery already sealed");
        accept(rows);if(last){factCut=cut;recovered=true;diagnostic="WAIT_LIVE_CONTEXT";}
    }
    /** Advance the verified completion-fact watermark before exposing a planning input. */
    public void poll(){
        actor();if(!recovered||failed)return;
        try{
            if(!session.equals(store.session())||store.readOnlySafe())throw new IllegalStateException("input session lost");
            if(read==null){
                var index=store.residentAggregateKeys(Set.of(AggregateKind.WORLD),4096);
                if(index.isEmpty()||index.get().cut().compareTo(factCut)<=0)return;
                scanning=new StoreQuery(world,Set.of(),new Sequence(factCut.value()+1),cursor,256);
                var resident=store.view(scanning);
                read=resident.status()==ReadStatus.NOT_RESIDENT?store.read(scanning).toCompletableFuture():
                    CompletableFuture.completedFuture(resident);return;
            }
            if(!read.isDone())return;
            var result=read.getNow(null);read=null;
            ReadContracts.validate(result,scanning,session);
            if(result.status()==ReadStatus.STALE){cursor=Optional.empty();return;}
            if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("completion facts not hydrated");
            var page=result.page().orElseThrow();accept(page.records());cursor=page.nextCursor();
            if(!page.hasMore())factCut=page.lastDurableSequence();
        }catch(RuntimeException invalid){failed=true;diagnostic="FAILED "+invalid.getMessage();}
    }
    private Optional<Capture> waitFor(String reason){diagnostic=reason;return Optional.empty();}
    public Optional<Capture> capture(CatalogSnapshot catalog,BudgetView budget,
            Map<CapabilityId,CapabilityDescriptor> installedCapabilities,
            Optional<Requests> requests,Map<RegionId,Set<TagId>> authoredTags,long seed){
        actor();
        if(!recovered||failed||store.readOnlySafe()||!session.equals(store.session()))return waitFor("J04_NOT_READY");
        var life=DirectorStandaloneMod.directorLifecycle();
        var raw=life.observationSnapshot(server);var remembered=life.runtimeMemoryView(server);
        var projected=life.memoryProjectionView(server);var observationTick=life.runtimeObservationTick(server);
        if(raw.isEmpty()||remembered.isEmpty()||projected.isEmpty()||observationTick.isEmpty()||
            !projected.get().complete()||projected.get().failClosed())return waitFor("J01_MEMORY_INCOMPLETE");
        long age=observationTick.get().value()-raw.get().capturedTick().value();
        long maximumAge=config.values().get("scheduler.snapshot_max_age_ticks").value().longValueExact();
        if(age<0||age>maximumAge)return waitFor("J01_STALE");
        var index=mode==ProviderMode.PRODUCTION?store.residentPlanningAggregateKeys(EnumSet.allOf(AggregateKind.class),4096):
            store.residentAggregateKeys(EnumSet.allOf(AggregateKind.class),4096);
        if(index.isEmpty()||!index.get().session().equals(session)||index.get().keys().isEmpty()||
            index.get().keys().size()>1024)return waitFor("CURRENT_AGGREGATES_NOT_RESIDENT");
        var cut=index.get().cut();
        if(projected.get().lastDurableSequence()!=cut.value()||factCut.compareTo(cut)!=0)
            return waitFor("WAIT_COMMON_WAL_CUT");
        var query=new StoreQuery(world,index.get().keys(),new Sequence(0),Optional.empty(),1024);
        var result=store.view(query);ReadContracts.validate(result,query,session);
        if(result.status()!=ReadStatus.AVAILABLE||result.page().orElseThrow().hasMore()||
            !result.page().orElseThrow().lastDurableSequence().equals(cut))return waitFor("AGGREGATE_CAPTURE_INCOMPLETE");
        var states=new HashMap<AggregateKey,AggregateState>();
        for(var row:result.page().orElseThrow().aggregates())states.put(row.state().key(),row.state());
        if(!states.keySet().equals(index.get().keys()))return waitFor("AGGREGATE_INDEX_CHANGED");
        var stored=states.get(new AggregateKey(AggregateKind.WORLD,world.value()));
        if(!(stored instanceof WorldAggregate wa)||!wa.value().worldId().equals(world)||
            !wa.value().storeGeneration().equals(session.storeGeneration())||!wa.value().bootEpoch().equals(session.bootEpoch()))
            return waitFor("WORLD_IDENTITY_CHANGED");
        var actual=MemoryProjection.of(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),states.values());
        var memory=remembered.get();
        if(!actual.regionRecords().equals(memory.regionRecords())||!actual.locations().equals(memory.locations())||
            !actual.anchors().equals(memory.anchors())||!new HashSet<>(actual.regions()).equals(new HashSet<>(memory.regions()))||
            !new HashSet<>(actual.operationalPlayers()).equals(new HashSet<>(memory.operationalPlayers())))
            return waitFor("MEMORY_NOT_AT_CAPTURE");
        var current=wa.value();
        ConfigView selectedConfig=profiles.isEmpty()?config:fr.proutlost.director.runtime.AdminWorldControlHandler.configuration(current,profiles);
        if(!current.catalogHash().equals(catalog.hash())||!current.configHash().equals(selectedConfig.provenanceHash()))
            return waitFor("WORLD_CATALOGUE_CONFIG_NOT_ACTIVE");
        var regional=regions.project(raw.get(),memory,cut.value(),authoredTags);
        if(regional.deferred())return waitFor("REGIONAL_CONTEXT_DEFERRED");
        var observed=regional.snapshot();var context=observed.context();
        var cas=new HashMap<ScopeRef,Revision>();
        for(var state:states.values()){
            ScopeKind kind=switch(state.key().kind()){
                case WORLD->ScopeKind.WORLD;case REGION->ScopeKind.REGION;
                case LOCATION->ScopeKind.LOCATION;case PLAYER_OPERATIONAL->ScopeKind.PLAYER;
                default->null;
            };
            if(kind!=null)cas.put(new ScopeRef(kind,state.key().id()),state.header().revision());
        }
        // A complete resident index attests an absent operational player row at revision0.
        for(var id:context.players().keySet())cas.putIfAbsent(new ScopeRef(ScopeKind.PLAYER,id.value()),new Revision(0));
        var scopes=new HashMap<ScopeRef,ScopeSnapshot>();
        for(var entry:context.scopes().entrySet()){
            var revision=cas.get(entry.getKey());if(revision==null)continue;
            var s=entry.getValue();
            scopes.put(entry.getKey(),new ScopeSnapshot(entry.getKey(),revision,s.availableSensors(),s.missingSensors(),s.scoringFeatures()));
        }
        for(var entry:cas.entrySet())if(!scopes.containsKey(entry.getKey()))
            scopes.put(entry.getKey(),new ScopeSnapshot(entry.getKey(),entry.getValue(),Set.of(),EnumSet.allOf(SensorKind.class),Map.of()));
        var scoped=new ScopeSet(world,scopes.keySet());
        var sample=new SnapshotBatch(observed.requestId(),scoped,observed.capturedTick(),cas,
            new ObservationContext(scopes,context.players(),context.groups(),context.regions(),context.anchors(),
                context.locations(),context.terrain(),Map.copyOf(installedCapabilities)),observed.missingData(),
            observed.loadedChunks(),observed.serverHealth());
        var owners=new HashMap<ResourceKey,OwnershipRecord>();var cooldowns=new ArrayList<CooldownRecord>();
        var unique=new ArrayList<UniqueUseRecord>();var events=new ArrayList<EventInstance>();
        var chains=new ArrayList<ChainInstance>();var disasters=new ArrayList<DisasterInstance>();
        var requestKeys=new HashSet<RequestId>();
        for(var state:states.values())switch(state){
            case OwnershipAggregate o->owners.put(o.value().resourceKey(),o.value());
            case CooldownAggregate c->cooldowns.add(c.value());case UniqueAggregate u->unique.add(u.value());
            case EventAggregate e->events.add(e.value());case ChainAggregate c->chains.add(c.value());
            case DisasterAggregate d->disasters.add(d.value());case RequestAggregate r->requestKeys.add(r.value().requestId());
            default->{}
        }
        List<RequestView> actualRequests=List.of();
        if(!requestKeys.isEmpty()){
            if(requests.isEmpty()||!requests.get().session().equals(session)||!requests.get().cut().equals(cut))
                return waitFor("REQUESTS_NOT_AT_CAPTURE");
            actualRequests=requests.get().values();
            var supplied=new HashSet<RequestId>();actualRequests.forEach(r->supplied.add(r.state().requestId()));
            if(!supplied.equals(requestKeys))return waitFor("REQUEST_INDEX_INCOMPLETE");
            for(var r:actualRequests){
                var head=states.get(new AggregateKey(AggregateKind.REQUEST,r.state().requestId().value()));
                if(!(head instanceof RequestAggregate a)||!a.value().equals(r.state()))return waitFor("REQUEST_STATE_CHANGED");
            }
        }
        var now=new Tick(Math.max(current.simTick().value(),observationTick.get().value()));
        var worldAtTick=new DirectorWorldState(current.header(),world,current.storeGeneration(),now,current.runControl(),
            current.healthMode(),current.pacingState(),current.tension(),current.intensityProfileId(),current.catalogHash(),
            current.configHash(),current.bootEpoch(),current.lastCheckpointSeq(),current.engineControl());
        var decisionState=new DecisionState(worldAtTick,profiles.isEmpty()?budget:fr.proutlost.director.runtime.CatalogueBudgetProjection.from(selectedConfig,states.values()),new OwnershipView(owners),cooldowns,unique,List.copyOf(facts.values()),events,chains,disasters);
        diagnostic="READY cut="+cut.value()+" players="+context.players().size()+" regions="+context.regions().size();
        return Optional.of(new Capture(new DecisionInput(sample,memory,catalog,selectedConfig,actualRequests,chains,seed,decisionState,Optional.empty()),cut));
    }
    public Optional<List<DirectorFact>> factsAt(Sequence cut){actor();return recovered&&!failed&&factCut.equals(cut)?Optional.of(List.copyOf(observedFacts.values())):Optional.empty();}
    public String diagnostic(){actor();return diagnostic;}
}
