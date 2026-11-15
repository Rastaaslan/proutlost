package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.admin.server.NativeReadCommandFence;
import fr.proutlost.director.admin.server.AdminActiveWalProjection;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/**
 * First real J07 native query from J06 authenticated session to J04 read-only
 * WORLD state. No synthetic budgets or runtime authority, no admin writes.
 * Five-tab GUI client may render other panels as currently unpopulated.
 */
public final class DirectorNativeAdminReadBridge {
    private DirectorNativeAdminReadBridge(){}
    private static final int MAX_WIRE_BYTES=22000;

    /** Correlation, not authentication: server independently checks operator
     * permission and the actual network session before calling query(). */
    public static SessionId actorSession(SessionIdentity session) {
        return DirectorAdminSessionKey.of(session);
    }

    public static Optional<AdminSnapshot> query(MinecraftServer server,
            SessionIdentity session,boolean authenticatedOperator,
            AdminQuery request) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(session);
        Objects.requireNonNull(request);
        if(!server.isSameThread())
            throw new IllegalStateException("J07 must read on server actor");
        if(!authenticatedOperator || !request.actorSession().equals(actorSession(session)) ||
            request.limit()>50)
            return Optional.empty();
        var lifecycle=fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle();
        var bootstrap=lifecycle.recoveredWorld(server);
        var store=lifecycle.labReadOnlyStoreSnapshot(server);
        var projection=lifecycle.memoryProjectionView(server);
        if(!session.worldId().equals(server.overworld().getDataStorage()
                .computeIfAbsent(
                    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.FACTORY,
                    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.KEY
                ).worldId()) || bootstrap.isEmpty() || store.isEmpty() ||
                !store.orElseThrow().session().worldId().equals(session.worldId()) ||
                !store.orElseThrow().session().bootEpoch().equals(session.bootEpoch()))
            return Optional.empty();
        // WORLD and the live WAL panels have their own exact durable read.
        // A multi-page memory refresh must not hide an independently verified WORLD.
        boolean needsMemory=request.panel()==AdminPanel.MEMORY ||
            request.panel()==AdminPanel.LABORATORY &&
            request.filter() instanceof LaboratoryFilter f && f.fixtureId().isPresent();
        if(needsMemory && (projection.isEmpty() || !projection.orElseThrow().complete() ||
            projection.orElseThrow().failClosed()))return Optional.empty();
        // The bootstrap's verified state is a BOOT snapshot. J03 advances
        // WORLD independently in J04, so serving that cached revision would
        // misreport live administrative state and accept stale UI cursors.
        // A real ROOT control ACK and full TEST_ONLY catalogue gate, not
        // NORMAL alone, permit read-only inspection while the LAB is running.
        var durable=latestWorld(store.orElseThrow(),session,runningCatalogueLab(server,lifecycle));
        if(durable.isEmpty())return Optional.empty();
        DirectorWorldState world=durable.orElseThrow().value();
        if(world.header().revision().compareTo(
                bootstrap.orElseThrow().header().revision())<0)
            return Optional.empty();
        var revision=world.header().revision();
        if(request.expectedSnapshotRevision().filter(r->!r.equals(revision)).isPresent())
            return Optional.empty();
        var memory=lifecycle.labMemoryView(server);
        // MEMORY combines J04 projection with the current WORLD. A newer
        // WAL revision must not be rendered beside a lagging partial scan.
        if(request.panel()==AdminPanel.MEMORY &&
            (memory.isEmpty() || projection.orElseThrow().lastDurableSequence()!=
                durable.orElseThrow().sequence().value()))
            return Optional.empty();
        if(request.panel()==AdminPanel.LABORATORY &&
           request.filter() instanceof LaboratoryFilter laboratory &&
           laboratory.fixtureId().isPresent()) {
            // This is a score-only diagnostic, not an authorized C2S
            // SIMULATE/TRIGGER command. Require exact fresh J04 projection.
            if(request.cursor().isPresent() || memory.isEmpty() ||
               projection.orElseThrow().lastDurableSequence()!=
                   durable.orElseThrow().sequence().value())
                return Optional.empty();
            var fixture=laboratory.fixtureId().orElseThrow();
            var row=lifecycle.labPreview(server,fixture,world);
            if(row.isEmpty() && lifecycle.labPreviewFixtureRegistered(server,fixture)) {
                // The registered TEST_ONLY recipe exists, but its cold J01/J04
                // context is insufficient for an honest score. Return an
                // explicit read-only diagnostic; never fabricate a proposal,
                // operation, permission, or a successful simulation.
                var stale=new AdminRow(
                    new ScopeRef(ScopeKind.WORLD,world.worldId().value()),
                    revision,world.simTick(),
                    new PublicStatusData(world.healthMode(),true,
                        Set.of(ReasonCode.STALE_CONTEXT,ReasonCode.CAPABILITY_MISSING)));
                return boundedPage(request,revision,world.simTick(),
                    durable.orElseThrow().sequence(),List.of(stale));
            }
            return boundedPage(request,revision,world.simTick(),
                durable.orElseThrow().sequence(),
                row.map(List::of).orElseGet(List::of));
        }
        if(request.panel()==AdminPanel.DECISIONS)
            return sameWalCut(decisionPage(store.orElseThrow(),request,world),
                durable.orElseThrow().sequence());
        if(request.panel()==AdminPanel.ACTIVE)
            return sameWalCut(activePage(store.orElseThrow(),request,world,
                    nativeRawEventDefinitions(server,lifecycle,world)),
                durable.orElseThrow().sequence());
        // BudgetView is the existing physical-ownership projection, never fabricated free capacity.
        var worldBudget=request.panel()==AdminPanel.WORLD?
            lifecycle.labGameplay(server).flatMap(gameplay->
                gameplay.catalogueAdminBudgetAt(durable.orElseThrow().sequence())):
            Optional.<BudgetView>empty();
        List<AdminRow> all= switch(request.panel()){
            case WORLD -> List.of(new AdminRow(
                new ScopeRef(ScopeKind.WORLD,session.worldId().value()),
                revision,world.simTick(),
                worldBudget.<AdminData>map(budget->new WorldAdminData(
                    world,budget,Map.of(),List.of())).orElseGet(()->
                    new PublicStatusData(world.healthMode(),
                        world.runControl()!=RunControl.RUNNING,
                        Set.of(ReasonCode.CAPABILITY_MISSING)))));
            case MEMORY -> memoryRows(request.filter(),memory.orElseThrow());
            case LABORATORY -> laboratoryReadiness(server,request,world);
            default -> List.of(); // No imaginary decisions or active events.
        };
        return boundedPage(request,revision,world.simTick(),
            durable.orElseThrow().sequence(),all);
    }

    /**
     * A second J04 read must be from the very same durable cut as WORLD.
     * Writer IO can publish a new WAL sequence between two actor-side reads.
     * Rather than mix decisions/active objects from a newer cut with a stale
     * WORLD revision, reject the response (a retry obtains a new WORLD cut).
     * This only filters read-only snapshots; it never grants a mutation permit.
     */
    public static Optional<AdminSnapshot> sameWalCut(
            Optional<AdminSnapshot> candidate,Sequence worldCut) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(worldCut);
        return candidate.filter(snapshot ->
            snapshot.lastDurableSequence().equals(worldCut));
    }

    /** Exact J04 resident WORLD at the time of a J07 request. The boot
     * snapshot is not refreshed by J03 and cannot be used as live authority.
     * A missing, foreign or unqualified world never becomes an empty world.
     */
    private record VerifiedWorld(DirectorWorldState value,Sequence sequence) {}
    private static boolean runningCatalogueLab(MinecraftServer server,DirectorServerLifecycle lifecycle) {
        if(!Boolean.getBoolean("proutlost.director.nativeLab"))return false;
        var candidate=lifecycle.candidateCatalogView(server);
        if(candidate.filter(c->c.verifiedEvents()==9 && c.verifiedChains()==2 &&
                !c.validatedForProduction()).isEmpty())return false;
        // enabled() is true only after ROOT control's exact J04 ACK and WORLD
        // readback, following the full nine-event/two-chain/provider-ready gate.
        // It also checks the live store session and excludes a failed writer.
        return lifecycle.labGameplay(server).map(DirectorNativeGameplay::catalogueEnabled).orElse(false);
    }
    private static Optional<VerifiedWorld> latestWorld(
            DurableStore store,SessionIdentity session,boolean allowRunningLab) {
        try {
            var query=new StoreQuery(session.worldId(),Set.of(new AggregateKey(
                AggregateKind.WORLD,session.worldId().value())),
                new Sequence(0),Optional.empty(),1);
            var result=store.view(query);
            ReadContracts.validate(result,query,store.session());
            if(result.status()!=ReadStatus.AVAILABLE ||
               result.page().orElseThrow().aggregates().size()!=1 ||
               !(result.page().orElseThrow().aggregates().getFirst().state()
                    instanceof WorldAggregate aggregate))
                return Optional.empty();
            var world=aggregate.value();
            if(!world.worldId().equals(session.worldId()) ||
               !world.bootEpoch().equals(session.bootEpoch()) ||
               !world.storeGeneration().equals(store.session().storeGeneration()) ||
               // The existing PAUSED branch also reads a configured catalogue
               // after ROOT pause, preserving its real full catalogue hash.
               // Legacy boot PAUSED hashes remain readable as before.
               !((world.runControl()==RunControl.PAUSED && world.healthMode()==HealthMode.READ_ONLY_SAFE) ||
                 (allowRunningLab && world.runControl()==RunControl.RUNNING && world.healthMode()==HealthMode.NORMAL)))
                return Optional.empty();
            return Optional.of(new VerifiedWorld(world,
                result.page().orElseThrow().lastDurableSequence()));
        }catch(RuntimeException invalid){
            // WAL unavailable/stale/corrupt: no stale cache fallback.
            return Optional.empty();
        }
    }

    /** Fifth J07 tab: a real, read-only qualification diagnostic, NOT a
     * simulated fixture or a successful dry run. The immutable J02 candidate
     * manifest cannot grant J05/J06 rights, and a requested unknown fixture
     * must never be invented. Physical and simulation commands stay absent.
     */
    private static List<AdminRow> laboratoryReadiness(MinecraftServer server,
            AdminQuery request,DirectorWorldState world) {
        if(!(request.filter() instanceof LaboratoryFilter filter) ||
            filter.fixtureId().isPresent())return List.of();
        var candidate=fr.proutlost.director.neoforge.DirectorStandaloneMod
            .directorLifecycle().candidateCatalogView(server);
        if(candidate.filter(c->c.validatedForProduction()).isPresent())
            return List.of(); // This diagnostic is only for missing providers.
        var reasons=EnumSet.of(ReasonCode.CAPABILITY_MISSING);
        if(candidate.isEmpty())reasons.add(ReasonCode.CATALOG_INVALID);
        return List.of(new AdminRow(
            new ScopeRef(ScopeKind.WORLD,world.worldId().value()),
            world.header().revision(),world.simTick(),
            new PublicStatusData(world.healthMode(),false,reasons)));
    }

    /** Extract only J04-recovered Region/Player aggregates.
     * Missing records, names and inferred region tags are never fabricated. */
    public static List<AdminRow> memoryRows(AdminFilter filter,MemoryView view) {
        Objects.requireNonNull(filter);
        Objects.requireNonNull(view);
        List<AdminRow> rows=new ArrayList<>();
        if(filter instanceof RegionFilter region) {
            if(!region.tags().isEmpty())return List.of(); // Tags not fully indexed.
            for(var pair:new TreeMap<>(view.regionRecords()).entrySet()) {
                var record=pair.getValue();
                if(region.regionId().filter(id->!id.equals(record.regionId())).isPresent())
                    continue;
                var memory=view.regions().stream()
                    .filter(m->m.regionId().equals(record.regionId())).findFirst();
                if(memory.isEmpty())continue;
                var anchors=view.anchors().values().stream()
                    .filter(a->record.anchorIds().contains(a.anchorId())
                        ||a.regionIds().contains(record.regionId()))
                    .sorted(Comparator.comparing(a->a.anchorId().value())).toList();
                var locations=anchors.stream().map(a->a.locationId().orElse(null))
                    .filter(Objects::nonNull).distinct()
                    .map(view.locations()::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(l->l.locationId().value())).toList();
                rows.add(new AdminRow(
                    new ScopeRef(ScopeKind.REGION,record.regionId().value()),
                    record.header().revision(),record.header().updatedTick(),
                    new RegionAdminData(record,memory.orElseThrow(),
                        Set.of(),anchors,locations)));
            }
        } else if(filter instanceof PlayerFilter player) {
            var recent=view.operationalPlayers().stream()
                .filter(m->m.playerId().equals(player.playerId())).findFirst();
            recent.ifPresent(m->rows.add(new AdminRow(
                new ScopeRef(ScopeKind.PLAYER,m.playerId().value()),
                m.header().revision(),m.header().updatedTick(),
                new PlayerAdminData(m,view.significantPlayers().stream()
                    .filter(x->x.playerId().equals(m.playerId())).toList()))));
        }
        return List.copyOf(rows);
    }

    /** Cursor strictly bound to the current WORLD revision and 22k-wire cap.
     * The caller rejects stale snapshotRevision before reaching this method. */
    private static Optional<AdminSnapshot> boundedPage(AdminQuery request,
            Revision revision,Tick tick,Sequence sequence,List<AdminRow> all) {
        int start;
        try {
            start=request.cursor().map(Integer::parseInt).orElse(0);
        }catch(NumberFormatException invalid){return Optional.empty();}
        if(start<0 || start>all.size() || start>16384)
            return Optional.empty();
        List<AdminRow> rows=new ArrayList<>();
        int index=start;
        while(index<all.size() && rows.size()<request.limit()) {
            rows.add(all.get(index));
            var preview=new AdminSnapshot(request.requestId(),revision,tick,
                sequence,rows,Optional.empty(),NativeReadCommandFence.supported());
            if(new ModelCodec<>(AdminSnapshot.class).encode(preview).length>
                    MAX_WIRE_BYTES){
                rows.removeLast();
                break;
            }
            index++;
        }
        if(index<all.size() && rows.isEmpty())return Optional.empty();
        return Optional.of(new AdminSnapshot(request.requestId(),revision,tick,
            sequence,rows,
            index<all.size()?Optional.of(Integer.toString(index)):Optional.empty(),
            NativeReadCommandFence.supported())); // Read-only commands only.
    }

    /** Exact J03 audit lookup from the actual J04 chronological WAL.
     * Each request consumes ONE bounded resident page; the next cursor is
     * issued by the WAL itself and is not a fabricated decision offset.
     * A missing audit with more pages is never reported as globally absent.
     */
    public static Optional<AdminSnapshot> decisionPage(DurableStore store,
            AdminQuery request,DirectorWorldState world) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(request);
        Objects.requireNonNull(world);
        if(request.panel()!=AdminPanel.DECISIONS ||
           !(request.filter() instanceof DecisionFilter filter))
            return Optional.empty();
        if(filter.decisionId().isEmpty()){
            // Paginate the exact immutable J04 audit chronology, not a
            // mutable in-memory decision list. Each page reads at most four
            // WAL records. Shrink the J04 page itself if a long decision
            // would exceed the client wire bound: never skip a record.
            int count=Math.min(request.limit(),4);
            while(count>=1){
                var scan=new StoreQuery(world.worldId(),Set.of(),
                    new Sequence(0),request.cursor(),count);
                StoreReadResult read;
                try{
                    read=store.view(scan);
                    ReadContracts.validate(read,scan,store.session());
                }catch(RuntimeException staleOrInvalid){
                    // Network cursor is untrusted even after permission 4;
                    // neither a forged cursor nor a stale WAL can abort
                    // the Minecraft actor.
                    return Optional.empty();
                }
                if(read.status()!=ReadStatus.AVAILABLE)return Optional.empty();
                var page=read.page().orElseThrow();
                List<AdminRow> rows=new ArrayList<>();
                for(var record:page.records())if(record instanceof DurableAudit stored){
                    var audit=stored.value();
                    rows.add(new AdminRow(
                        new ScopeRef(ScopeKind.WORLD,world.worldId().value()),
                        world.header().revision(),audit.context().capturedTick(),
                        new DecisionAdminData(audit.decisionId(),
                            audit.context().capturedTick(),audit.candidates(),
                            audit.chosen(),audit.advisor(),audit.validations(),
                            audit.outcome())));
                }
                if(page.nextCursor().filter(c->c.length()>512).isPresent())
                    return Optional.empty();
                var snapshot=new AdminSnapshot(request.requestId(),
                    world.header().revision(),world.simTick(),
                    page.lastDurableSequence(),rows,page.nextCursor(),NativeReadCommandFence.supported());
                try{
                    if(new ModelCodec<>(AdminSnapshot.class).encode(snapshot).length
                        <=MAX_WIRE_BYTES)return Optional.of(snapshot);
                }catch(RuntimeException oversize){
                    // Some immutable audits may exceed even the codec cap.
                    // Retry a *smaller J04 page* without discarding records.
                }
                count/=2;
            }
            return Optional.empty(); // One oversized audit is not "no data".
        }
        var query=new StoreQuery(world.worldId(),Set.of(),new Sequence(0),
            request.cursor(),64);
        StoreReadResult result;
        try{
            result=store.view(query);
            ReadContracts.validate(result,query,store.session());
        }catch(RuntimeException invalidCursorOrWal){
            return Optional.empty();
        }
        if(result.status()!=ReadStatus.AVAILABLE)return Optional.empty();
        var page=result.page().orElseThrow();
        var found=page.records().stream()
            .filter(DurableAudit.class::isInstance)
            .map(DurableAudit.class::cast)
            .map(DurableAudit::value)
            .filter(a->a.decisionId().equals(filter.decisionId().orElseThrow()))
            .findFirst();
        List<AdminRow> rows=found.map(a->List.of(new AdminRow(
            new ScopeRef(ScopeKind.WORLD,world.worldId().value()),
            world.header().revision(),a.context().capturedTick(),
            new DecisionAdminData(a.decisionId(),a.context().capturedTick(),
                a.candidates(),a.chosen(),a.advisor(),
                a.validations(),a.outcome())))).orElseGet(List::of);
        var continuation=found.isPresent()?Optional.<String>empty():
            page.nextCursor();
        if(continuation.filter(s->s.length()>512).isPresent())
            return Optional.empty();
        var response=new AdminSnapshot(request.requestId(),
            world.header().revision(),world.simTick(),
            page.lastDurableSequence(),rows,continuation,NativeReadCommandFence.supported());
        if(new ModelCodec<>(AdminSnapshot.class).encode(response).length>
            MAX_WIRE_BYTES)
            return Optional.empty();
        return Optional.of(response);
    }

    /** Exact EVENT/CHAIN lookup. A missing EVENT may continue through a bounded
     * DISASTER discovery window, matched by its actual eventInstanceId. */
    public static Optional<AdminSnapshot> activePage(DurableStore store,
            AdminQuery request,DirectorWorldState world) {
        return activePage(store,request,world,Set.of()); // Existing/default path remains strict.
    }
    private static Optional<AdminSnapshot> activePage(DurableStore store,
            AdminQuery request,DirectorWorldState world,Set<DefinitionId> rawNativeDefinitions) {
        Objects.requireNonNull(store);Objects.requireNonNull(request);Objects.requireNonNull(world);
        var session=store.session();
        if(request.filter() instanceof MutationFilter)
            return DirectorNativeMutationReadBridge.page(store,request,world);
        if(request.panel()!=AdminPanel.ACTIVE || !activeSession(store,session,world))
            return Optional.empty();
        if(request.filter() instanceof EventFilter events && events.instanceId().isEmpty() ||
           request.filter() instanceof ChainFilter chains && chains.chainId().isEmpty())
            return activeListPage(store,request,world,false,Optional.empty(),rawNativeDefinitions);
        if(request.cursor().isPresent()) {
            if(request.filter() instanceof EventFilter events && events.states().isEmpty())
                return activeListPage(store,request,world,true,Optional.empty(),rawNativeDefinitions);
            return Optional.empty();
        }
        AggregateKey key;
        if(request.filter() instanceof EventFilter events)
            key=new AggregateKey(AggregateKind.EVENT,events.instanceId().orElseThrow().value());
        else if(request.filter() instanceof ChainFilter chain)
            key=new AggregateKey(AggregateKind.CHAIN,chain.chainId().orElseThrow().value());
        else return Optional.empty();
        try {
            var query=new StoreQuery(world.worldId(),Set.of(key),new Sequence(0),Optional.empty(),1);
            var read=store.view(query);ReadContracts.validate(read,query,session);
            if(read.status()!=ReadStatus.AVAILABLE || !activeSession(store,session,world))
                return Optional.empty();
            var page=read.page().orElseThrow();
            if(page.hasMore() || page.nextCursor().isPresent() || !page.records().isEmpty())
                return Optional.empty();
            // The aggregate IDs are different domains. Do not query a made-up
            // DISASTER key using an event UUID; discover its genuine key instead.
            if(page.aggregates().isEmpty() && request.filter() instanceof EventFilter events &&
               events.states().isEmpty())
                return activeListPage(store,request,world,true,Optional.of(page.lastDurableSequence()),rawNativeDefinitions);
            List<AdminRow> rows=new ArrayList<>();
            for(var entry:page.aggregates()) {
                if(!entry.state().key().equals(key) || entry.committedSequence().compareTo(page.lastDurableSequence())>0)
                    return Optional.empty();
                var row=activeRow(store,entry.state(),request.filter(),world.worldId(),page.lastDurableSequence(),rawNativeDefinitions);
                if(row.isPresent())rows.add(row.orElseThrow());
                else if(incompleteActiveJoin(entry.state(),request.filter(),rawNativeDefinitions))return Optional.empty();
            }
            if(!activeSession(store,session,world))return Optional.empty();
            var response=new AdminSnapshot(request.requestId(),world.header().revision(),world.simTick(),
                page.lastDurableSequence(),rows,Optional.empty(),NativeReadCommandFence.supported());
            return new ModelCodec<>(AdminSnapshot.class).encode(response).length<=MAX_WIRE_BYTES?
                Optional.of(response):Optional.empty();
        }catch(RuntimeException unavailable) {
            return Optional.empty();
        }
    }
    /** Actual ROOT-configured mode/hash and the verified bundled nine IDs only.
     * No AdminQuery payload, chat parsing or inferred NORMAL/production approval. */
    private static Set<DefinitionId> nativeRawEventDefinitions(MinecraftServer server,
            DirectorServerLifecycle lifecycle,DirectorWorldState world) {
        if(!Boolean.getBoolean("proutlost.director.nativeLab") ||
           !((world.runControl()==RunControl.RUNNING&&world.healthMode()==HealthMode.NORMAL) ||
             (world.runControl()==RunControl.PAUSED&&world.healthMode()==HealthMode.READ_ONLY_SAFE)))return Set.of();
        var configured=lifecycle.labGameplay(server).flatMap(DirectorNativeGameplay::catalogueConfiguredHash);
        if(configured.filter(world.catalogHash()::equals).isEmpty())return Set.of();
        var candidate=lifecycle.candidateCatalogView(server);
        if(candidate.filter(c->c.verifiedEvents()==9&&c.verifiedChains()==2&&!c.validatedForProduction()).isEmpty())return Set.of();
        return lifecycle.candidateDependencies(server).map(DirectorNativeCandidateCatalog.DependencyIndex::eventPrimitives)
            .filter(events->events.size()==9).map(events->Set.copyOf(events.keySet())).orElseGet(Set::of);
    }
    private static Optional<AdminRow> activeRow(DurableStore store,AggregateState state,AdminFilter filter,
            WorldId world,Sequence cut,Set<DefinitionId> rawNativeDefinitions) {
        return rawNativeDefinitions.isEmpty()?AdminActiveWalProjection.row(store,state,filter,world,cut):
            AdminActiveWalProjection.nativeTestOnlyRow(store,state,filter,world,cut,ProviderMode.TEST_ONLY,rawNativeDefinitions);
    }
    private static boolean activeSession(DurableStore store,StoreSession pinned,DirectorWorldState world) {
        return pinned.equals(store.session())&&pinned.worldId().equals(world.worldId())&&
            pinned.bootEpoch().equals(world.bootEpoch())&&pinned.storeGeneration().equals(world.storeGeneration());
    }
    private static boolean incompleteActiveJoin(AggregateState state,AdminFilter filter,Set<DefinitionId> rawNativeDefinitions) {
        if(state instanceof EventAggregate e && filter instanceof EventFilter f)
            return f.instanceId().filter(id->!id.equals(e.value().instanceId())).isEmpty() &&
                (f.states().isEmpty() || f.states().contains(e.value().state())) &&
                (!e.value().mutationRefs().isEmpty() ||
                 !rawNativeDefinitions.contains(e.value().definition().id()) &&
                  (!e.value().reservationRefs().isEmpty() || !e.value().effectRefs().isEmpty()));
        if(state instanceof DisasterAggregate d && filter instanceof EventFilter f)
            return f.states().isEmpty() &&
                f.instanceId().filter(id->!id.equals(d.value().eventInstanceId())).isEmpty() &&
                !d.value().mutationRefs().isEmpty();
        return state instanceof ChainAggregate c && !c.value().consumedFactIds().isEmpty();
    }
    private static Optional<AdminSnapshot> residentActivePage(DurableStore store,AdminQuery request,
            DirectorWorldState world,boolean disastersOnly,Optional<Sequence> requiredCut,Set<DefinitionId> rawNativeDefinitions) {
        var session=store.session();Set<AggregateKind> kinds;
        if(request.filter() instanceof EventFilter f)kinds=disastersOnly?Set.of(AggregateKind.DISASTER):
            f.states().isEmpty()?Set.of(AggregateKind.EVENT,AggregateKind.DISASTER):Set.of(AggregateKind.EVENT);
        else if(request.filter() instanceof ChainFilter&&!disastersOnly)kinds=Set.of(AggregateKind.CHAIN);
        else return Optional.empty();
        Optional<fr.proutlost.director.persistence.JournalStore.ResidentAggregateKeys> discovered;
        if(store instanceof DirectorLabReadOnlyStore readOnly)discovered=readOnly.residentAggregateKeys(kinds,4096);
        else if(store instanceof fr.proutlost.director.persistence.JournalStore journal)discovered=journal.residentAggregateKeys(kinds,4096);
        else return Optional.empty();
        if(discovered.isEmpty()||!activeSession(store,session,world))return Optional.empty();
        var keys=discovered.orElseThrow();
        if(!keys.session().equals(session)||requiredCut.filter(c->!c.equals(keys.cut())).isPresent())return Optional.empty();
        // An empty key set would mean chronology to StoreQuery: never send that query here.
        if(keys.keys().isEmpty()){
            if(request.cursor().isPresent())return Optional.empty();
            return Optional.of(new AdminSnapshot(request.requestId(),world.header().revision(),world.simTick(),
                keys.cut(),List.of(),Optional.empty(),NativeReadCommandFence.supported()));
        }
        try{
            // Fixed one CURRENT aggregate per page preserves the actual keyed J04 cursor and wire cap.
            // DISASTER sorts before EVENT in the existing J04 key ordering. No stage is invented.
            var query=new StoreQuery(world.worldId(),keys.keys(),new Sequence(0),request.cursor(),1);
            var read=store.view(query);ReadContracts.validate(read,query,session);
            if(read.status()!=ReadStatus.AVAILABLE||!activeSession(store,session,world))return Optional.empty();
            var page=read.page().orElseThrow();
            if(!page.lastDurableSequence().equals(keys.cut())||!page.records().isEmpty()
                ||page.nextCursor().filter(c->c.length()>512).isPresent())return Optional.empty();
            List<AdminRow> rows=new ArrayList<>();
            for(var entry:page.aggregates()){
                if(!keys.keys().contains(entry.state().key())||entry.committedSequence().compareTo(keys.cut())>0)return Optional.empty();
                var row=activeRow(store,entry.state(),request.filter(),world.worldId(),keys.cut(),rawNativeDefinitions);
                if(row.isPresent())rows.add(row.orElseThrow());
                else if(incompleteActiveJoin(entry.state(),request.filter(),rawNativeDefinitions))return Optional.empty();
            }
            if(!activeSession(store,session,world))return Optional.empty();
            var response=new AdminSnapshot(request.requestId(),world.header().revision(),world.simTick(),
                keys.cut(),rows,page.nextCursor(),NativeReadCommandFence.supported());
            return new ModelCodec<>(AdminSnapshot.class).encode(response).length<=MAX_WIRE_BYTES?Optional.of(response):Optional.empty();
        }catch(RuntimeException staleUnavailableOrOversize){return Optional.empty();}
    }

    /** One chronological record/current-key read per page preserves J04 queryHash
     * across every cursor. Only the exact latest revision is emitted; empty scan
     * windows retain their real WAL continuation. */
    private static Optional<AdminSnapshot> activeListPage(DurableStore store,
            AdminQuery request,DirectorWorldState world,boolean disastersOnly,Optional<Sequence> requiredCut,Set<DefinitionId> rawNativeDefinitions) {
        if(store instanceof DirectorLabReadOnlyStore || store instanceof fr.proutlost.director.persistence.JournalStore)
            return residentActivePage(store,request,world,disastersOnly,requiredCut,rawNativeDefinitions);
        var session=store.session();
        boolean events=request.filter() instanceof EventFilter;
        if(!activeSession(store,session,world) || disastersOnly &&
            (!(request.filter() instanceof EventFilter f) || !f.states().isEmpty()))
            return Optional.empty();
        int count=1;
        while(count>=1) {
            try {
                var scan=new StoreQuery(world.worldId(),Set.of(),new Sequence(0),request.cursor(),count);
                var read=store.view(scan);ReadContracts.validate(read,scan,session);
                if(read.status()!=ReadStatus.AVAILABLE || !activeSession(store,session,world))
                    return Optional.empty();
                var page=read.page().orElseThrow();
                if(requiredCut.filter(cut->!cut.equals(page.lastDurableSequence())).isPresent())
                    return Optional.empty();
                var seen=new HashSet<AggregateKey>();List<AdminRow> rows=new ArrayList<>();
                for(var record:page.records()) {
                    if(!(record instanceof DurableAggregate aggregate))continue;
                    var key=aggregate.state().key();
                    boolean kind=events?
                        (!disastersOnly && key.kind()==AggregateKind.EVENT ||
                         key.kind()==AggregateKind.DISASTER && ((EventFilter)request.filter()).states().isEmpty()):
                        !disastersOnly && key.kind()==AggregateKind.CHAIN;
                    if(!kind || seen.contains(key))continue;
                    var currentQuery=new StoreQuery(world.worldId(),Set.of(key),new Sequence(0),Optional.empty(),1);
                    var current=store.view(currentQuery);ReadContracts.validate(current,currentQuery,session);
                    if(current.status()!=ReadStatus.AVAILABLE || !activeSession(store,session,world))
                        return Optional.empty();
                    var latest=current.page().orElseThrow();
                    if(!latest.lastDurableSequence().equals(page.lastDurableSequence()) || latest.hasMore() ||
                       latest.nextCursor().isPresent() || !latest.records().isEmpty())
                        return Optional.empty();
                    for(var candidate:latest.aggregates()) {
                        if(!candidate.state().key().equals(key) || candidate.committedSequence().compareTo(page.lastDurableSequence())>0)
                            return Optional.empty();
                        int version=candidate.state().header().revision().compareTo(aggregate.state().header().revision());
                        if(version<0)return Optional.empty();
                        if(version>0)continue;
                        if(!candidate.state().equals(aggregate.state()))return Optional.empty();
                        seen.add(key);
                        var joined=activeRow(store,candidate.state(),request.filter(),world.worldId(),page.lastDurableSequence(),rawNativeDefinitions);
                        if(joined.isPresent())rows.add(joined.orElseThrow());
                        else if(incompleteActiveJoin(candidate.state(),request.filter(),rawNativeDefinitions))return Optional.empty();
                    }
                }
                if(!activeSession(store,session,world) || page.nextCursor().filter(c->c.length()>512).isPresent())
                    return Optional.empty();
                var response=new AdminSnapshot(request.requestId(),world.header().revision(),world.simTick(),
                    page.lastDurableSequence(),rows,page.nextCursor(),NativeReadCommandFence.supported());
                if(new ModelCodec<>(AdminSnapshot.class).encode(response).length<=MAX_WIRE_BYTES)
                    return Optional.of(response);
            }catch(RuntimeException invalidCursorOrWal) {
                return Optional.empty();
            }
            count/=2;
        }
        return Optional.empty();
    }
}
