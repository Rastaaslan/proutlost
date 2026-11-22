package fr.proutlost.director.admin.server;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;

/** J07 discovery over the pinned J04 index. No WORLD memory coverage is forged.
 * Only two encoded pages per read are retained; catalogue bodies remain cold.
 * Empty windows with a continuation mean loading, never a proven empty census. */
public final class GlobalAdminRegionDirectory implements AutoCloseable {
    private static final int PAGE_KEYS=96,MAX_PAGE_BYTES=4_194_304;
    private static final long TTL=60_000_000_000L;
    private record Key(SessionId actor,RegionFilter filter){}
    private record Document(AggregateKey key,Revision revision,Tick tick,byte[] bytes,Fingerprint hash){}
    private record Page(List<Document> documents,Optional<AggregateKey> after,boolean more){}
    private record PageKey(Optional<AggregateKey> after,int limit){}
    private static final class Read {
        final MetadataReadSnapshot snapshot;
        final CompletableFuture<Map<RegionId,Set<TagId>>> tags;
        final LinkedHashMap<PageKey,CompletableFuture<Page>> pages=new LinkedHashMap<>();long touched;boolean failed;
        boolean settled(){return tags.isDone()&&pages.values().stream().allMatch(CompletableFuture::isDone);}
        Read(MetadataReadSnapshot snapshot,CompletableFuture<Map<RegionId,Set<TagId>>> tags){
            this.snapshot=snapshot;this.tags=tags;
        }
    }
    private record Cursor(Key key,Read read,PageKey page,int document,int offset,AdminSnapshotStamp stamp,long expires){}
    private final MetadataReadPort store;private final WorldId world;private final StoreSession session;private final Thread owner=Thread.currentThread();
    private final int maximumReads,maximumCursors,wireBytes;
    private final LinkedHashMap<Key,Read> reads=new LinkedHashMap<>();private final Map<String,Cursor> cursors=new HashMap<>();
    private final ThreadPoolExecutor worker;private boolean closed;
    public GlobalAdminRegionDirectory(MetadataReadPortProvider source,int maximumReads,int maximumCursors,int wireBytes){
        this.store=Objects.requireNonNull(source).metadataReads();session=store.session();world=session.worldId();
        if(maximumReads<1||maximumCursors<1||wireBytes<4096||wireBytes>32768)throw new IllegalArgumentException("region directory bounds");
        this.maximumReads=Math.min(4,maximumReads);this.maximumCursors=maximumCursors;this.wireBytes=wireBytes;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),task->{var thread=new Thread(task,"Director-J07-region-directory");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    private void actor(){if(owner!=Thread.currentThread())throw new IllegalStateException("directory actor");}
    private void remove(Key key){reads.remove(key);cursors.values().removeIf(c->c.key().equals(key));}
    private void discard(Key key,Read read){read.failed=true;cursors.values().removeIf(c->c.key().equals(key));if(read.settled())reads.remove(key);}
    private boolean sameSources(Read read,MetadataReadSnapshot now){
        // Bodies/key discovery remain on the original cut. A normal head
        // update must not substitute latest rows into this historical read.
        return !read.failed&&read.snapshot.session().equals(now.session())
            &&read.snapshot.generations().equals(now.generations())&&read.snapshot.sources().equals(now.sources());
    }
    public AdminReadResult query(AdminQuery query,ActorIdentity actor,DirectorWorldState current,Sequence cut){
        return query(query,actor,current,cut,current.simTick());
    }
    public AdminReadResult query(AdminQuery query,ActorIdentity actor,DirectorWorldState current,Sequence cut,Tick capturedTick){
        Objects.requireNonNull(capturedTick);
        actor();long now=System.nanoTime();
        if(Boolean.getBoolean("proutlost.director.diagnostics.admin_reads")&&query.cursor().isPresent()){
            var observed=cursors.get(query.cursor().orElseThrow());
            if(observed!=null&&now-observed.expires()>=0)trace(query,"CURSOR_EXPIRED","late_nanos="+(now-observed.expires()));
        }
        cursors.values().removeIf(c->now-c.expires()>=0);
        for(var key:reads.entrySet().stream().filter(e->now-e.getValue().touched>=TTL&&e.getValue().settled()).map(Map.Entry::getKey).toList())remove(key);
        if(closed||query.panel()!=AdminPanel.MEMORY||!(query.filter() instanceof RegionFilter filter)||filter.regionId().isPresent()
            ||!actor.serverAuthenticated()||actor.permissionLevel()<4||!actor.session().equals(query.actorSession())
            ||!session.equals(store.session())||store.readOnlySafe()||!current.worldId().equals(world)||!current.bootEpoch().equals(session.bootEpoch())
            ||!current.storeGeneration().equals(session.storeGeneration()))return refused(query,"AUTHORITY_GUARD","closed="+closed+" readonly="+store.readOnlySafe()+" store_session="+session.equals(store.session()));
        var snapshot=store.snapshot();if(snapshot.isEmpty()||!snapshot.get().cut().equals(cut))return refused(query,"INITIAL_CUT","expected="+cut.value()+" observed="+snapshot.map(v->String.valueOf(v.cut().value())).orElse("EMPTY"));
        var key=new Key(query.actorSession(),filter);Read read;PageKey pageKey;int document=0,offset=0;AdminSnapshotStamp stamp;
        if(query.cursor().isPresent()){
            var cursor=cursors.get(query.cursor().orElseThrow());
            if(cursor==null||!cursor.key().equals(key)||cursor.page().limit()!=query.limit()||reads.get(key)!=cursor.read()
                ||query.expectedSnapshotRevision().isPresent()&&!query.expectedSnapshotRevision().get().equals(cursor.stamp().snapshotRevision())
                ||!sameSources(cursor.read(),snapshot.get()))return refused(query,"CURSOR_GUARD",
                    cursor==null?"MISSING":!cursor.key().equals(key)?"KEY":cursor.page().limit()!=query.limit()?"LIMIT":
                    reads.get(key)!=cursor.read()?"READ_SLOT":
                    query.expectedSnapshotRevision().isPresent()&&!query.expectedSnapshotRevision().get().equals(cursor.stamp().snapshotRevision())?"REVISION":
                    "SOURCE_FENCE failed="+cursor.read().failed+" session="+cursor.read().snapshot.session().equals(snapshot.get().session())+
                    " generations="+cursor.read().snapshot.generations().equals(snapshot.get().generations())+" sources="+cursor.read().snapshot.sources().equals(snapshot.get().sources()));
            read=cursor.read();pageKey=cursor.page();document=cursor.document();offset=cursor.offset();stamp=cursor.stamp();
        }else{
            if(query.expectedSnapshotRevision().filter(r->!r.equals(current.header().revision())).isPresent())return refused(query,"FRESH_REVISION","");
            cursors.values().removeIf(c->c.key().equals(key));read=reads.get(key);
            // A new search is current; continuations alone retain the old cut.
            if(read!=null){if(!read.settled()){discard(key,read);return refused(query,"FRESH_REPLACED_PENDING_READ","");}remove(key);read=null;}
            if(read==null){
                if(reads.size()>=maximumReads){var candidate=reads.entrySet().stream().filter(e->e.getValue().settled()).findFirst();
                    if(candidate.isEmpty())return refused(query,"ALL_READ_SLOTS_BUSY","");remove(candidate.get().getKey());}
                var tags=filter.tags().isEmpty()?CompletableFuture.<Map<RegionId,Set<TagId>>>completedFuture(Map.of()):
                    CompletableFuture.supplyAsync(()->GlobalRegionTagCensus.load(store,snapshot.get()),worker).thenCompose(stage->stage).toCompletableFuture();
                read=new Read(snapshot.get(),tags);reads.put(key,read);
            }
            pageKey=new PageKey(Optional.empty(),query.limit());stamp=new AdminSnapshotStamp(world,session.bootEpoch(),current.header().revision(),capturedTick,cut);
        }
        read.touched=now;CompletableFuture<Page> future;
        try{future=page(read,pageKey,filter.tags());}catch(RejectedExecutionException unavailable){discard(key,read);return refused(query,"PAGE_WORKER_BUSY","");}
        List<AdminRow> rows=new ArrayList<>();boolean more=true;PageKey nextPage=pageKey;int nextDocument=document,nextOffset=offset;
        if(future.isDone()){
            final Page page;
            try{page=future.join();}catch(CompletionException failed){discard(key,read);var cause=failed.getCause();
                return refused(query,"PAGE_FUTURE_FAILED",cause==null?"NO_CAUSE":cause.getClass().getSimpleName()+":"+safeFailure(cause.getMessage()));}
            if(document<0||document>page.documents().size())return refused(query,"DOCUMENT_RANGE","document="+document+" count="+page.documents().size());
            int end=document;
            while(end<page.documents().size()&&rows.size()<query.limit()){
                var value=page.documents().get(end);
                if(offset>0||value.bytes().length>wireBytes-2048){
                    if(!rows.isEmpty())break;
                    int stop=Math.min(value.bytes().length,offset+MemoryDocumentPageData.MAX_FRAGMENT_BYTES);
                    var fragment=new MemoryDocumentPageData(value.hash(),end,page.documents().size(),value.bytes().length,offset,
                        Base64.getEncoder().encodeToString(Arrays.copyOfRange(value.bytes(),offset,stop)));
                    rows.add(new AdminRow(new ScopeRef(ScopeKind.REGION,value.key().id()),value.revision(),value.tick(),fragment));
                    nextOffset=stop;nextDocument=end;
                    if(stop==value.bytes().length){nextDocument++;nextOffset=0;}break;
                }
                rows.add(new ModelCodec<>(AdminRow.class).decode(value.bytes()));
                var probe=new AdminSnapshot(query.requestId(),stamp.snapshotRevision(),stamp.capturedTick(),stamp.lastDurableSequence(),rows,Optional.of("x".repeat(36)),NativeReadCommandFence.supported());
                if(new ModelCodec<>(AdminSnapshot.class).encode(probe).length>wireBytes){rows.removeLast();break;}
                end++;nextDocument=end;nextOffset=0;
            }
            if(nextDocument==page.documents().size()&&nextOffset==0){
                more=page.more();nextPage=new PageKey(page.after(),query.limit());nextDocument=0;
                if(more){try{page(read,nextPage,filter.tags());}catch(RejectedExecutionException unavailable){discard(key,read);return refused(query,"PREFETCH_WORKER_BUSY","");}}
            }
        }
        if(more&&cursors.size()-(query.cursor().isPresent()?1:0)>=maximumCursors)return refused(query,"CURSOR_CAPACITY","count="+cursors.size());
        var finalSnapshot=store.snapshot();if(finalSnapshot.isEmpty()||!finalSnapshot.get().cut().equals(cut)||!sameSources(read,finalSnapshot.get()))return refused(query,"FINAL_CUT_OR_SOURCES","expected="+cut.value()+" observed="+finalSnapshot.map(v->String.valueOf(v.cut().value())).orElse("EMPTY")+" sources="+(finalSnapshot.isPresent()&&sameSources(read,finalSnapshot.get())));
        query.cursor().ifPresent(cursors::remove);Optional<String> next=Optional.empty();
        if(more){String token=UUID.randomUUID().toString();cursors.put(token,new Cursor(key,read,nextPage,nextDocument,nextOffset,stamp,now+TTL));next=Optional.of(token);}
        return new AdminReadResult(AdminStatus.SUCCEEDED,rows,Optional.empty(),next,Optional.of(stamp));
    }
    private CompletableFuture<Page> page(Read read,PageKey key,Set<TagId> required){
        var existing=read.pages.get(key);if(existing!=null)return existing;
        while(read.pages.size()>=2){var completed=read.pages.entrySet().stream().filter(e->e.getValue().isDone()).findFirst();
            if(completed.isEmpty())throw new RejectedExecutionException("directory page reads busy");read.pages.remove(completed.get().getKey());}
        var future=read.tags.thenCompose(tags->CompletableFuture.supplyAsync(()->{
            var keys=store.metadataKeysPage(read.snapshot,Set.of(AggregateKind.REGION),key.after(),PAGE_KEYS).orElseThrow(()->new IllegalStateException("directory index unavailable"));
            var matching=keys.keys().stream().filter(k->required.isEmpty()||tags.getOrDefault(new RegionId(k.id()),Set.of()).containsAll(required)).limit(key.limit()).toList();
            return new AbstractMap.SimpleImmutableEntry<>(keys,matching);
        },worker)).thenCompose(selection->{
            var keys=selection.getKey();var matching=selection.getValue();
            if(matching.isEmpty())return CompletableFuture.completedFuture(new Page(List.of(),keys.keys().isEmpty()?key.after():Optional.of(keys.keys().getLast()),keys.next().isPresent()));
            var query=new StoreQuery(world,Set.copyOf(matching),new Sequence(0),Optional.empty(),PAGE_KEYS);
            return store.readMetadata(read.snapshot,query).thenApplyAsync(result->{
                ReadContracts.validate(result,query,read.snapshot.session());if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("directory body page unavailable:"+result.status());
                var page=result.page().orElseThrow();if(page.hasMore()||!page.records().isEmpty()||!page.lastDurableSequence().equals(read.snapshot.cut())||page.aggregates().size()!=matching.size())throw new IllegalStateException("directory body cut/completeness");
                Map<AggregateKey,RegionRecord> records=new HashMap<>();for(var row:page.aggregates()){
                    if(!(row.state() instanceof RegionAggregate region)||!matching.contains(row.state().key())||records.put(row.state().key(),region.value())!=null)throw new IllegalStateException("directory duplicate/foreign record");}
                List<Document> documents=new ArrayList<>();int bytes=0;
                for(var id:matching){var region=Objects.requireNonNull(records.get(id));
                    var data=new RegionMemoryDetailData(region.regionId(),RegionMemoryDetailData.Section.REGION,0,1,Optional.of(region),Optional.empty(),Set.of(),List.of(),List.of());
                    var row=new AdminRow(new ScopeRef(ScopeKind.REGION,id.id()),region.header().revision(),region.header().updatedTick(),data);
                    byte[] encoded=new ModelCodec<>(AdminRow.class).encode(row);
                    if(encoded.length>MAX_PAGE_BYTES-bytes){if(documents.isEmpty())throw new IllegalStateException("directory row byte bound");break;}
                    documents.add(new Document(id,row.revision(),row.capturedTick(),encoded,BlobContent.hash(encoded)));bytes+=encoded.length;
                }
                var last=documents.getLast().key();boolean consumedAll=documents.size()==matching.size()&&matching.size()<key.limit()
                    ||documents.size()==matching.size()&&matching.size()==keys.keys().size();
                Optional<AggregateKey> after=Optional.of(consumedAll?keys.keys().getLast():last);
                boolean more=!consumedAll||keys.next().isPresent();
                return new Page(List.copyOf(documents),after,more);
            },worker);
        }).toCompletableFuture();
        read.pages.put(key,future);return future;
    }
    private static String safeFailure(String value){
        if(value==null)return "NONE";
        return value.startsWith("directory ")?value.substring(0,Math.min(value.length(),180)):"UNEXPOSED";
    }
    private static void trace(AdminQuery query,String stage,String details){
        if(Boolean.getBoolean("proutlost.director.diagnostics.admin_reads"))
            System.out.println("DIRECTOR_NATIVE_J07_DIRECTORY_REFUSAL B09R25_DIAG request="+query.requestId().value()+" stage="+stage+" "+details);
    }
    private static AdminReadResult refused(AdminQuery query,String stage,String details){trace(query,stage,details);return deferred(query);}
    private static AdminReadResult deferred(AdminQuery query){return new AdminReadResult(AdminStatus.DEFERRED,List.of(),Optional.of(new SystemError(ReasonCode.STALE_CONTEXT,Severity.INFO,false,Optional.empty(),Set.of(),new CorrelationId(query.requestId().value()),SafeState.UNCHANGED)),Optional.empty(),Optional.empty());}
    public void disconnect(SessionId actor){actor();for(var key:reads.keySet().stream().filter(k->k.actor().equals(actor)).toList())discard(key,reads.get(key));}
    @Override public void close(){actor();closed=true;reads.clear();cursors.clear();worker.shutdownNow();}
}
