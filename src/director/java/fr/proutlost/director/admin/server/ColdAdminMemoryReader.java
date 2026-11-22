package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;

/** Actor-owned exact-scope J07 reads. IO uses pinned J04 pages and never alters
 * J01's selected working set. The native facade independently authenticates
 * the supplied actor on EVERY poll, including after asynchronous completion.
 */
public final class ColdAdminMemoryReader implements AutoCloseable {
    private record Key(SessionId actor,AdminFilter filter){}
    private record Loaded(MetadataMemoryReader.Selection view,List<AdminRow> rows){}
    private record Cursor(Key key,Loaded loaded,AdminSnapshotStamp stamp,int limit,int offset){}
    private static final class Entry {CompletableFuture<Loaded> future;Loaded loaded;long retryAfter;}
    private final MetadataReadPort store;private final MetadataMemoryReader reader;private final WorldId world;private final StoreSession session;
    private final int maximumReads,maximumCursors,wireBytes;
    private final Thread owner=Thread.currentThread();
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();
    private final Map<String,Cursor> cursors=new HashMap<>();private boolean closed;
    private final ColdAdminRegionDocuments documents;
    private final GlobalAdminRegionDirectory directory;
    private final LinkedHashSet<Key> documentMode=new LinkedHashSet<>();
    public ColdAdminMemoryReader(MetadataReadPortProvider source,MetadataMemoryReader reader,WorldId world,int maximumReads,int maximumCursors,int wireBytes){
        this.store=Objects.requireNonNull(source).metadataReads();this.reader=Objects.requireNonNull(reader);this.world=Objects.requireNonNull(world);session=store.session();
        if(!session.worldId().equals(world)||maximumReads<1||maximumCursors<1||wireBytes<4096||wireBytes>32768)
            throw new IllegalArgumentException("cold admin reader bounds/world");
        this.maximumReads=maximumReads;this.maximumCursors=maximumCursors;this.wireBytes=wireBytes;
        documents=new ColdAdminRegionDocuments(store,reader,maximumReads,maximumCursors,wireBytes);
        directory=new GlobalAdminRegionDirectory(store,maximumReads,maximumCursors,wireBytes);
    }
    private void actor(){if(owner!=Thread.currentThread())throw new IllegalStateException("cold admin reader actor");}
    public StoreSession session(){return session;}
    public static boolean handles(AdminQuery request){return request.panel()==AdminPanel.MEMORY&&
        (request.filter() instanceof RegionFilter||request.filter() instanceof PlayerFilter);}
    public AdminReadResult query(AdminQuery request,ActorIdentity actor,DirectorWorldState current,Sequence cut,MetadataMemoryReader.Configuration settings){
        return query(request,actor,current,cut,settings,current.simTick());
    }
    public AdminReadResult query(AdminQuery request,ActorIdentity actor,DirectorWorldState current,Sequence cut,MetadataMemoryReader.Configuration settings,Tick capturedTick){
        Objects.requireNonNull(capturedTick);
        actor();Objects.requireNonNull(request);Objects.requireNonNull(actor);Objects.requireNonNull(current);Objects.requireNonNull(cut);
        if(closed||!handles(request)||!session.equals(store.session())||store.readOnlySafe()
            ||!current.worldId().equals(world)||!current.bootEpoch().equals(session.bootEpoch())
            ||!current.storeGeneration().equals(session.storeGeneration())||!actor.serverAuthenticated()
            ||actor.permissionLevel()<4||!actor.session().equals(request.actorSession()))return refused(request,"AUTHORITY_GUARD","closed="+closed+" readonly="+store.readOnlySafe()+" actor_auth="+actor.serverAuthenticated()+" permission="+actor.permissionLevel());
        if(request.filter() instanceof RegionFilter filter&&filter.regionId().isEmpty())return directory.query(request,actor,current,cut,capturedTick);
        var documentKey=new Key(request.actorSession(),request.filter());
        if(documentMode.contains(documentKey)){var result=documents.query(request,actor,current,cut,capturedTick);if(result.status()!=AdminStatus.SUCCEEDED)trace(request,"DOCUMENT_MODE_RESULT","status="+result.status());return result;}
        if(request.cursor().isEmpty()&&request.expectedSnapshotRevision().filter(v->!v.equals(current.header().revision())).isPresent())return refused(request,"FRESH_EXPECTED_REVISION","world="+current.header().revision().value());
        var snapshot=store.snapshot();if(snapshot.isEmpty()||!snapshot.get().cut().equals(cut))return refused(request,"INITIAL_CUT","expected="+cut.value()+" observed="+snapshot.map(v->String.valueOf(v.cut().value())).orElse("EMPTY"));
        var key=new Key(request.actorSession(),request.filter());Entry entry=entries.get(key);Cursor cursor=null;
        if(request.cursor().isPresent()){
            cursor=cursors.get(request.cursor().orElseThrow());
            if(cursor==null||!cursor.key().equals(key)||request.expectedSnapshotRevision().isPresent()&&!request.expectedSnapshotRevision().get().equals(cursor.stamp().snapshotRevision())||cursor.limit()!=request.limit())return refused(request,"CURSOR_TUPLE","cursor_present="+(cursor!=null));
            if(!fresh(cursor.loaded(),snapshot.get()))return refused(request,"CURSOR_HEADS",details(cursor.loaded(),snapshot.get()));
        }else{
            if(entry==null){
                if(entries.size()>=maximumReads){
                    var completed=entries.entrySet().stream().filter(e->e.getValue().future==null||e.getValue().future.isDone()).findFirst();
                    if(completed.isEmpty())return refused(request,"ALL_READ_SLOTS_BUSY","count="+entries.size());entries.remove(completed.get().getKey());
                }
                entry=new Entry();entries.put(key,entry);
            }
            if(entry.future!=null&&entry.future.isDone()){
                try{entry.loaded=entry.future.join();entry.future=null;}
                catch(CompletionException failed){entry.future=null;entry.loaded=null;trace(request,"LOAD_FUTURE_FAILED",safeFailure(failed));
                    if(request.filter() instanceof RegionFilter&&capacityFailure(failed)){
                        if(documentMode.size()>=maximumReads)documentMode.removeFirst();documentMode.add(key);entries.remove(key);
                        return documents.query(request,actor,current,cut,capturedTick);
                    }
                    entry.retryAfter=System.nanoTime()+1_000_000_000L;return refused(request,"FAILED_LOAD_BACKOFF","");}
            }
            if(entry.loaded!=null&&!fresh(entry.loaded,snapshot.get())){trace(request,"LOADED_NOT_CURRENT",details(entry.loaded,snapshot.get()));entry.loaded=null;}
            if(entry.loaded==null){
                if(entry.future==null&&System.nanoTime()>=entry.retryAfter)entry.future=load(snapshot.get(),request.filter(),settings).toCompletableFuture();
                return refused(request,"LOAD_NOT_READY","future="+(entry.future!=null)+" done="+(entry.future!=null&&entry.future.isDone())+" current_cut="+snapshot.get().cut().value());
            }
        }
        var loaded=cursor==null?entry.loaded:cursor.loaded();var rows=loaded.rows();
        var stamp=cursor==null?new AdminSnapshotStamp(world,session.bootEpoch(),current.header().revision(),capturedTick,cut):cursor.stamp();
        if(request.filter() instanceof RegionFilter filter&&!filter.tags().isEmpty())rows=rows.stream().filter(r->r.data() instanceof RegionAdminData region&&region.tags().containsAll(filter.tags())).toList();
        int start=cursor==null?0:cursor.offset();
        if(start<0||start>rows.size())return refused(request,"ROW_OFFSET","offset="+start+" count="+rows.size());
        List<AdminRow> page=new ArrayList<>();int end=start;
        while(end<rows.size()&&page.size()<request.limit()){
            page.add(rows.get(end));
            var probe=new AdminSnapshot(request.requestId(),stamp.snapshotRevision(),stamp.capturedTick(),stamp.lastDurableSequence(),page,Optional.of("x".repeat(36)),NativeReadCommandFence.supported());
            if(new ModelCodec<>(AdminSnapshot.class).encode(probe).length>wireBytes){page.removeLast();break;}end++;
        }
        if(end<rows.size()&&page.isEmpty())return refused(request,"ROW_WIRE_CAPACITY","wire="+wireBytes);
        if(end<rows.size()&&cursors.size()-(request.cursor().isPresent()?1:0)>=maximumCursors)return refused(request,"CURSOR_CAPACITY","count="+cursors.size());
        // No stale read may be stamped with a later WORLD cut.
        var finalSnapshot=store.snapshot();
        if(finalSnapshot.isEmpty()||!finalSnapshot.get().cut().equals(cut)||!fresh(loaded,finalSnapshot.get()))return refused(request,"FINAL_CUT_OR_HEADS","expected="+cut.value()+" observed="+finalSnapshot.map(v->String.valueOf(v.cut().value())).orElse("EMPTY")+" "+(finalSnapshot.isPresent()?details(loaded,finalSnapshot.get()):""));
        request.cursor().ifPresent(cursors::remove);Optional<String> next=Optional.empty();
        if(end<rows.size()){String token=UUID.randomUUID().toString();cursors.put(token,new Cursor(key,loaded,stamp,request.limit(),end));next=Optional.of(token);}
        return new AdminReadResult(AdminStatus.SUCCEEDED,page,Optional.empty(),next,
            Optional.of(stamp));
    }
    private boolean fresh(Loaded loaded,MetadataReadSnapshot current){
        return loaded.view().currentHeads(current);
    }
    private CompletionStage<Loaded> load(MetadataReadSnapshot snapshot,AdminFilter filter,MetadataMemoryReader.Configuration settings){
        ScopeRef scope=filter instanceof RegionFilter r?new ScopeRef(ScopeKind.REGION,r.regionId().orElseThrow().value()):
            new ScopeRef(ScopeKind.PLAYER,((PlayerFilter)filter).playerId().value());
        var primary=new AggregateKey(scope.kind()==ScopeKind.REGION?AggregateKind.REGION:AggregateKind.PLAYER_OPERATIONAL,scope.id());
        if(store.metadataRevision(snapshot,primary).value()==0){
            var view=reader.empty(snapshot,new ScopeSet(world,Set.of(scope)));
            return CompletableFuture.completedFuture(new Loaded(view,List.of()));
        }
        return reader.capture(snapshot,Set.of(primary),1).thenCompose(initial->{
            var state=initial.states().get(primary);Set<AnchorId> anchors=state instanceof RegionAggregate r?r.value().anchorIds():Set.of();
            if(anchors.size()>512)throw new IllegalArgumentException("exact admin scope exceeds metadata working-set bound");
            return reader.exact(snapshot,new ScopeSet(world,Set.of(scope)),anchors,settings);
        }).thenCompose(view->{
            if(!(filter instanceof RegionFilter region))return CompletableFuture.completedFuture(new Loaded(view,playerRows(view.memory(),((PlayerFilter)filter).playerId())));
            var id=region.regionId().orElseThrow();var owner=store.metadataOwner(snapshot,new AggregateKey(AggregateKind.REGION,id.value()));
            if(owner.isEmpty())return CompletableFuture.completedFuture(new Loaded(view,regionRows(view.memory(),id,Set.of())));
            return store.readMetadataSource(snapshot,owner.get()).thenCompose(source->{
                if(source.isEmpty())throw new IllegalStateException("exact authored source unavailable");
                var input=source.get();var extra=new HashSet<AggregateKey>();
                for(var location:input.locations())if(location.regionIds().contains(id)&&!view.states().containsKey(new AggregateKey(AggregateKind.LOCATION,location.locationId().value())))
                    extra.add(new AggregateKey(AggregateKind.LOCATION,location.locationId().value()));
                if((long)view.states().size()+extra.size()>512)throw new IllegalArgumentException("exact admin source metadata working-set bound");
                return reader.capture(snapshot,extra,512).thenApply(additional->{
                    var scopes=new HashSet<>(view.scopes().scopes());
                    extra.forEach(k->scopes.add(new ScopeRef(ScopeKind.LOCATION,k.id())));
                    var expanded=reader.expand(view,additional,new ScopeSet(world,scopes));
                    var memory=expanded.memory();Set<TagId> tags=new HashSet<>();
                    input.regions().stream().filter(r->r.regionId().equals(id)).findFirst().ifPresent(r->{
                        var actual=memory.regionRecords().get(id);if(actual==null||!actual.dimension().equals(r.dimension()))throw new IllegalStateException("authored region mismatch");tags.addAll(r.tags());});
                    for(var location:input.locations())if(location.regionIds().contains(id)){
                        var actual=memory.locations().get(location.locationId());var b=location.bounds();
                        if(actual==null||!actual.sourceNamespace().equals(input.sourceNamespace())||!actual.importRevision().equals(input.exportRevision())
                            ||!actual.dimension().equals(location.dimension())||!actual.bounds().equals(new Aabb(location.dimension(),b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()))
                            ||!actual.tags().equals(location.tags()))throw new IllegalStateException("authored location mismatch");
                        if(actual.active())tags.addAll(location.tags());
                    }
                    return new Loaded(expanded,regionRows(memory,id,tags));
                });
            });
        });
    }
    private List<AdminRow> regionRows(MemoryView memory,RegionId id,Set<TagId> authored){
        var record=memory.regionRecords().get(id);var state=memory.regions().stream().filter(r->r.regionId().equals(id)).findFirst();
        if(record==null||state.isEmpty()||!AdminMemoryCoverage.available(new RegionFilter(Optional.of(id),Set.of()),memory))throw new IllegalStateException("exact region incomplete");
        var anchors=memory.anchors().values().stream().filter(a->record.anchorIds().contains(a.anchorId())||a.regionIds().contains(id)).sorted(Comparator.comparing(AnchorRecord::anchorId)).toList();
        var locations=memory.locations().values().stream().sorted(Comparator.comparing(LocationRecord::locationId)).toList();
        Set<TagId> tags=new HashSet<>(authored);anchors.stream().filter(AnchorRecord::active).forEach(a->tags.addAll(a.tags()));locations.stream().filter(LocationRecord::active).forEach(l->tags.addAll(l.tags()));
        List<AdminRow> rows=new ArrayList<>();List<AnchorRecord> anchorPage=new ArrayList<>();List<LocationRecord> locationPage=new ArrayList<>();
        for(var anchor:anchors){anchorPage.add(anchor);var candidate=row(record,state.get(),tags,anchorPage,locationPage);
            if(bytes(candidate)>wireBytes-2048){anchorPage.removeLast();if(anchorPage.isEmpty()&&locationPage.isEmpty())throw new IllegalArgumentException("region row exceeds admin wire budget");
                rows.add(row(record,state.get(),tags,anchorPage,locationPage));anchorPage.clear();locationPage.clear();anchorPage.add(anchor);}}
        for(var location:locations){locationPage.add(location);var candidate=row(record,state.get(),tags,anchorPage,locationPage);
            if(bytes(candidate)>wireBytes-2048){locationPage.removeLast();if(anchorPage.isEmpty()&&locationPage.isEmpty())throw new IllegalArgumentException("region row exceeds admin wire budget");
                rows.add(row(record,state.get(),tags,anchorPage,locationPage));anchorPage.clear();locationPage.clear();locationPage.add(location);}}
        var finalRow=row(record,state.get(),tags,anchorPage,locationPage);if(bytes(finalRow)>wireBytes-2048)throw new IllegalArgumentException("region row exceeds admin wire budget");rows.add(finalRow);return List.copyOf(rows);
    }
    private static AdminRow row(RegionRecord region,RegionMemory memory,Set<TagId> tags,List<AnchorRecord> anchors,List<LocationRecord> locations){
        return new AdminRow(new ScopeRef(ScopeKind.REGION,region.regionId().value()),region.header().revision(),region.header().updatedTick(),new RegionAdminData(region,memory,tags,anchors,locations));
    }
    private List<AdminRow> playerRows(MemoryView memory,PlayerId id){
        var operational=memory.operationalPlayers().stream().filter(p->p.playerId().equals(id)).findFirst().orElseThrow();
        var significant=memory.significantPlayers().stream().filter(p->p.playerId().equals(id)).sorted(Comparator.comparing(PlayerSignificantMemory::recordedTick).reversed()).toList();
        List<AdminRow> rows=new ArrayList<>();List<PlayerSignificantMemory> page=new ArrayList<>();
        for(var value:significant){page.add(value);var row=new AdminRow(new ScopeRef(ScopeKind.PLAYER,id.value()),operational.header().revision(),operational.header().updatedTick(),new PlayerAdminData(operational,page));
            if(bytes(row)>wireBytes-2048){page.removeLast();if(page.isEmpty())throw new IllegalArgumentException("player row exceeds admin wire budget");
                rows.add(new AdminRow(row.scope(),row.revision(),row.capturedTick(),new PlayerAdminData(operational,page)));page.clear();page.add(value);}}
        var finalRow=new AdminRow(new ScopeRef(ScopeKind.PLAYER,id.value()),operational.header().revision(),operational.header().updatedTick(),new PlayerAdminData(operational,page));
        if(bytes(finalRow)>wireBytes-2048)throw new IllegalArgumentException("player row exceeds admin wire budget");rows.add(finalRow);return List.copyOf(rows);
    }
    private static int bytes(AdminRow row){return new ModelCodec<>(AdminRow.class).encode(row).length;}
    private String details(Loaded loaded,MetadataReadSnapshot current){
        if(!Boolean.getBoolean("proutlost.director.diagnostics.admin_reads"))return "";
        try{
        var original=loaded.view().snapshot();
        String heads=loaded.view().states().keySet().stream()
            .sorted(Comparator.comparing(k->k.kind().name()+":"+k.id())).limit(8)
            .map(k->k.kind().name()+":"+k.id()+":"+store.metadataRevision(original,k).value()+"->"+store.metadataRevision(current,k).value())
            .collect(java.util.stream.Collectors.joining(","));
        return "loaded_cut="+original.cut().value()+" current_cut="+current.cut().value()
            +" session="+original.session().equals(current.session())
            +" generations="+original.generations().equals(current.generations())
            +" sources="+original.sources().equals(current.sources())
            +" states="+loaded.view().states().size()+" heads_sample="+heads;
        }catch(RuntimeException diagnosticUnavailable){return "diagnostic_unavailable="+diagnosticUnavailable.getClass().getSimpleName();}
    }
    private static String safeFailure(Throwable failure){
        Throwable cause=failure;for(int n=0;n<8&&cause.getCause()!=null;n++)cause=cause.getCause();
        String message=cause.getMessage();
        boolean safe=message!=null&&(message.startsWith("exact ")||message.startsWith("metadata ")
            ||message.startsWith("region ")||message.equals("JSON byte bound"));
        return cause.getClass().getSimpleName()+":"+(safe?message.substring(0,Math.min(message.length(),180)):"UNEXPOSED");
    }
    private static void trace(AdminQuery request,String stage,String details){
        if(Boolean.getBoolean("proutlost.director.diagnostics.admin_reads"))
            System.out.println("DIRECTOR_NATIVE_J07_EXACT_MEMORY_REFUSAL B09R27_DIAG request="+request.requestId().value()+" stage="+stage+" "+details);
    }
    private static AdminReadResult refused(AdminQuery request,String stage,String details){
        trace(request,stage,details);return deferred(request);
    }
    private static AdminReadResult deferred(AdminQuery query){return new AdminReadResult(AdminStatus.DEFERRED,List.of(),
        Optional.of(new SystemError(ReasonCode.STALE_CONTEXT,Severity.INFO,false,Optional.empty(),Set.of(),new CorrelationId(query.requestId().value()),SafeState.UNCHANGED)),Optional.empty(),Optional.empty());}
    private static boolean capacityFailure(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            String reason=failure.getMessage();if(reason==null)continue;
            if(reason.contains("exact admin scope exceeds metadata working-set bound")||reason.contains("exact admin source metadata working-set bound")
                ||reason.contains("metadata demand exceeds512")||reason.contains("region row exceeds admin wire budget")||reason.equals("JSON byte bound"))return true;
        }
        return false;
    }
    /** Completion signal only: no content, new authority or relaxed freshness. */
    public Optional<CompletionStage<?>> pendingPlayerRead(AdminQuery query){
        actor();
        if(closed||store.readOnlySafe()||!session.equals(store.session())||!handles(query)
            ||!(query.filter() instanceof PlayerFilter)||query.cursor().isPresent())return Optional.empty();
        var entry=entries.get(new Key(query.actorSession(),query.filter()));
        return entry==null||entry.future==null?Optional.empty():Optional.of(entry.future.minimalCompletionStage());
    }
    public void disconnect(SessionId actor){actor();entries.keySet().removeIf(k->k.actor().equals(actor));cursors.values().removeIf(c->c.key().actor().equals(actor));documentMode.removeIf(k->k.actor().equals(actor));documents.disconnect(actor);directory.disconnect(actor);}
    @Override public void close(){actor();closed=true;entries.clear();cursors.clear();documentMode.clear();documents.close();directory.close();}
}
