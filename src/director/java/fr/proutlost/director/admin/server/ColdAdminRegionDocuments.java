package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Oversize exact-region J07 reader. At most96 hydrated bodies are processed
 * per page. Retained results are immutable encoded section documents, bounded
 * to4MiB/read and4reads; no catalogue-sized aggregate cache is constructed. */
public final class ColdAdminRegionDocuments implements AutoCloseable {
    private static final int PAGE=96,MAX_READ_BYTES=4_194_304;
    private static final long TTL=60_000_000_000L;
    private static final Set<AggregateKind> KINDS=Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY,AggregateKind.ANCHOR,AggregateKind.LOCATION);
    private record Key(SessionId actor,RegionFilter filter){}
    private record Document(ScopeRef scope,Revision revision,Tick tick,byte[] bytes,Fingerprint hash){}
    private record Read(MetadataReadSnapshot snapshot,Map<AggregateKind,Sequence> versions,
            Map<AggregateKey,Revision> heads,List<Document> documents,Set<TagId> tags){
        boolean current(MetadataReadPort store,MetadataReadSnapshot now){
            if(!snapshot.session().equals(now.session())||!snapshot.generations().equals(now.generations()))return false;
            var current=store.metadataHeadVersions(now,KINDS);if(current.isEmpty())return false;
            if(versions.equals(current.get()))return true;
            if(!Objects.equals(versions.get(AggregateKind.ANCHOR),current.get().get(AggregateKind.ANCHOR)))return false;
            for(var entry:heads.entrySet())if(!store.metadataRevision(now,entry.getKey()).equals(entry.getValue()))return false;
            return true;
        }
    }
    private static final class Entry {CompletableFuture<Read> future;Read read;long touched;}
    private record Cursor(Key key,Read read,AdminSnapshotStamp stamp,int limit,int document,int offset,long expires){}
    private final MetadataReadPort store;private final MetadataMemoryReader reader;private final WorldId world;private final StoreSession session;
    private final int maximumReads,maximumCursors,wireBytes;private final Thread owner=Thread.currentThread();
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();private final Map<String,Cursor> cursors=new HashMap<>();private boolean closed;
    public ColdAdminRegionDocuments(MetadataReadPortProvider source,MetadataMemoryReader reader,int maximumReads,int maximumCursors,int wireBytes){
        this.store=Objects.requireNonNull(source).metadataReads();this.reader=Objects.requireNonNull(reader);session=store.session();world=session.worldId();
        if(maximumReads<1||maximumCursors<1||wireBytes<4096||wireBytes>32768)throw new IllegalArgumentException("dense admin bounds");
        this.maximumReads=Math.min(4,maximumReads);this.maximumCursors=maximumCursors;this.wireBytes=wireBytes;
    }
    private void actor(){if(Thread.currentThread()!=owner)throw new IllegalStateException("dense admin actor");}
    private void remove(Key key){entries.remove(key);cursors.values().removeIf(c->c.key().equals(key));}
    public AdminReadResult query(AdminQuery query,ActorIdentity actor,DirectorWorldState current,Sequence cut){
        actor();long now=System.nanoTime();cursors.values().removeIf(c->now-c.expires()>=0);
        for(var key:entries.entrySet().stream().filter(e->now-e.getValue().touched>=TTL).map(Map.Entry::getKey).toList())remove(key);
        if(closed||!(query.filter() instanceof RegionFilter filter)||filter.regionId().isEmpty()||query.panel()!=AdminPanel.MEMORY
            ||!actor.serverAuthenticated()||actor.permissionLevel()<4||!actor.session().equals(query.actorSession())
            ||!session.equals(store.session())||store.readOnlySafe()||!current.worldId().equals(world)
            ||!current.bootEpoch().equals(session.bootEpoch())||!current.storeGeneration().equals(session.storeGeneration()))return deferred(query);
        var snapshot=store.snapshot();if(snapshot.isEmpty()||!snapshot.get().cut().equals(cut))return deferred(query);
        var key=new Key(query.actorSession(),filter);var entry=entries.get(key);Cursor cursor=null;
        if(query.cursor().isPresent()){
            cursor=cursors.get(query.cursor().orElseThrow());
            if(cursor==null||!cursor.key().equals(key)||cursor.limit()!=query.limit()||entry==null||entry.read!=cursor.read()
                ||query.expectedSnapshotRevision().isPresent()&&!query.expectedSnapshotRevision().get().equals(cursor.stamp().snapshotRevision())
                ||!cursor.read().current(store,snapshot.get()))return deferred(query);
            entry.touched=now;
        }else{
            if(query.expectedSnapshotRevision().filter(r->!r.equals(current.header().revision())).isPresent())return deferred(query);
            if(entry==null){
                if(entries.size()>=maximumReads){var removable=entries.entrySet().stream().filter(e->e.getValue().future==null||e.getValue().future.isDone()).findFirst();
                    if(removable.isEmpty())return deferred(query);remove(removable.get().getKey());}
                entry=new Entry();entries.put(key,entry);
            }
            entry.touched=now;
            if(entry.future!=null&&entry.future.isDone()){
                try{entry.read=entry.future.join();entry.future=null;}
                catch(CompletionException failure){remove(key);return deferred(query);}
            }
            if(entry.read!=null&&!entry.read.current(store,snapshot.get())){entry.read=null;cursors.values().removeIf(c->c.key().equals(key));}
            if(entry.read==null){if(entry.future==null)entry.future=new Builder(store,reader,snapshot.get(),filter.regionId().orElseThrow()).load().toCompletableFuture();return deferred(query);}
        }
        var read=cursor==null?entry.read:cursor.read();var stamp=cursor==null?
            new AdminSnapshotStamp(world,session.bootEpoch(),current.header().revision(),current.simTick(),cut):cursor.stamp();
        int document=cursor==null?0:cursor.document(),offset=cursor==null?0:cursor.offset();
        if(!read.tags().containsAll(filter.tags()))document=read.documents().size();
        List<AdminRow> rows=new ArrayList<>();int nextDocument=document,nextOffset=offset;
        if(document<read.documents().size()){
            var value=read.documents().get(document);
            if(offset==0&&value.bytes().length<=wireBytes-2048){rows.add(new ModelCodec<>(AdminRow.class).decode(value.bytes()));nextDocument++;}
            else{
                int end=Math.min(value.bytes().length,offset+MemoryDocumentPageData.MAX_FRAGMENT_BYTES);
                var page=new MemoryDocumentPageData(value.hash(),document,read.documents().size(),value.bytes().length,offset,
                    Base64.getEncoder().encodeToString(Arrays.copyOfRange(value.bytes(),offset,end)));
                rows.add(new AdminRow(value.scope(),value.revision(),value.tick(),page));nextOffset=end;
                if(end==value.bytes().length){nextDocument++;nextOffset=0;}
            }
        }
        boolean more=nextDocument<read.documents().size();
        if(more&&cursors.size()-(query.cursor().isPresent()?1:0)>=maximumCursors)return deferred(query);
        var probe=new AdminSnapshot(query.requestId(),stamp.snapshotRevision(),stamp.capturedTick(),stamp.lastDurableSequence(),rows,
            more?Optional.of("x".repeat(36)):Optional.empty(),NativeReadCommandFence.supported());
        if(new ModelCodec<>(AdminSnapshot.class).encode(probe).length>wireBytes)return deferred(query);
        var fence=store.snapshot();if(fence.isEmpty()||!fence.get().cut().equals(cut)||!read.current(store,fence.get()))return deferred(query);
        query.cursor().ifPresent(cursors::remove);Optional<String> next=Optional.empty();
        if(more){String token=UUID.randomUUID().toString();cursors.put(token,new Cursor(key,read,stamp,query.limit(),nextDocument,nextOffset,now+TTL));next=Optional.of(token);}
        return new AdminReadResult(AdminStatus.SUCCEEDED,rows,Optional.empty(),next,Optional.of(stamp));
    }
    private static AdminReadResult deferred(AdminQuery query){return new AdminReadResult(AdminStatus.DEFERRED,List.of(),
        Optional.of(new SystemError(ReasonCode.STALE_CONTEXT,Severity.INFO,false,Optional.empty(),Set.of(),new CorrelationId(query.requestId().value()),SafeState.UNCHANGED)),Optional.empty(),Optional.empty());}
    public void disconnect(SessionId actor){actor();for(var key:entries.keySet().stream().filter(k->k.actor().equals(actor)).toList())remove(key);}
    @Override public void close(){actor();closed=true;entries.clear();cursors.clear();}

    private static final class Builder {
        final MetadataReadPort store;final MetadataMemoryReader reader;final MetadataReadSnapshot snapshot;final RegionId id;
        final Map<AggregateKey,Revision> heads=new HashMap<>();final Map<AggregateKind,Sequence> versions;
        final Set<AnchorId> missingAnchors=new HashSet<>();final Set<LocationId> locations=new HashSet<>(),missingLocations=new HashSet<>();
        final Set<TagId> tags=new HashSet<>();final Map<LocationId,ImportedLocation> authoredLocations=new HashMap<>();
        final List<Document> documents=new ArrayList<>();RegionRecord region;RegionMemory memory;ResourceId authoredSource;Revision authoredRevision;int anchorCount,locationCount,encodedBytes;
        Builder(MetadataReadPort store,MetadataMemoryReader reader,MetadataReadSnapshot snapshot,RegionId id){this.store=store;this.reader=reader;this.snapshot=snapshot;this.id=id;
            versions=store.metadataHeadVersions(snapshot,KINDS).orElseThrow(()->new IllegalStateException("metadata token unavailable"));}
        CompletionStage<Read> load(){
            var primary=new AggregateKey(AggregateKind.REGION,id.value());var memoryKey=new AggregateKey(AggregateKind.REGION_MEMORY,id.value());
            heads.put(primary,store.metadataRevision(snapshot,primary));
            if(heads.get(primary).value()==0)return CompletableFuture.completedFuture(finish());
            if(store.metadataRevision(snapshot,memoryKey).value()==0)throw new IllegalStateException("region memory unknown");
            return reader.capture(snapshot,Set.of(primary,memoryKey),2).thenCompose(initial->{
                region=((RegionAggregate)initial.states().get(primary)).value();memory=((RegionMemoryAggregate)initial.states().get(memoryKey)).value();
                initial.states().forEach((k,v)->heads.put(k,v.header().revision()));missingAnchors.addAll(region.anchorIds());
                var owner=store.metadataOwner(snapshot,primary);
                return owner.isEmpty()?CompletableFuture.<Optional<LocationImport>>completedFuture(Optional.empty()):store.readMetadataSource(snapshot,owner.get()).thenApply(source->{
                    if(source.isEmpty())throw new IllegalStateException("exact authored source unavailable");return source;});
            }).thenCompose(source->{
                source.ifPresent(input->{
                    authoredSource=input.sourceNamespace();authoredRevision=input.exportRevision();
                    input.regions().stream().filter(r->r.regionId().equals(id)).findFirst().ifPresent(r->{
                        if(!r.dimension().equals(region.dimension()))throw new IllegalStateException("authored region identity");tags.addAll(r.tags());});
                    for(var location:input.locations())if(location.regionIds().contains(id)){authoredLocations.put(location.locationId(),location);locations.add(location.locationId());}
                });
                return pass(AggregateKind.ANCHOR,states->{for(var state:states){var anchor=((AnchorAggregate)state).value();
                    if(region.anchorIds().contains(anchor.anchorId())||anchor.regionIds().contains(id)){
                        missingAnchors.remove(anchor.anchorId());heads.put(state.key(),state.header().revision());anchorCount++;
                        anchor.locationId().ifPresent(locations::add);if(anchor.active())tags.addAll(anchor.tags());}}});
            }).thenCompose(v->{
                if(!missingAnchors.isEmpty())throw new IllegalStateException("declared region anchor unknown");missingLocations.addAll(locations);
                return pass(AggregateKind.LOCATION,states->{for(var state:states){var location=((LocationAggregate)state).value();if(!locations.contains(location.locationId()))continue;
                    missingLocations.remove(location.locationId());heads.put(state.key(),state.header().revision());locationCount++;
                    var authored=authoredLocations.get(location.locationId());if(authored!=null){var bounds=authored.bounds();
                        if(!location.sourceNamespace().equals(authoredSource)||!location.importRevision().equals(authoredRevision)
                            ||!location.dimension().equals(authored.dimension())||!location.externalId().equals(authored.externalId())
                            ||!location.bounds().equals(new Aabb(authored.dimension(),bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxXExclusive(),bounds.maxYExclusive(),bounds.maxZExclusive()))
                            ||!location.tags().equals(authored.tags()))throw new IllegalStateException("authored location identity");}
                    if(location.active())tags.addAll(location.tags());}});
            }).thenCompose(v->{
                if(!missingLocations.isEmpty())throw new IllegalStateException("region location unknown");
                section(RegionMemoryDetailData.Section.REGION,0,1,Optional.of(region),Optional.empty(),Set.of(),List.of(),List.of());
                section(RegionMemoryDetailData.Section.MEMORY,0,1,Optional.empty(),Optional.of(memory),Set.of(),List.of(),List.of());
                section(RegionMemoryDetailData.Section.TAGS,0,tags.size(),Optional.empty(),Optional.empty(),tags,List.of(),List.of());
                int[] offset={0};return pass(AggregateKind.ANCHOR,states->{var page=new ArrayList<AnchorRecord>();for(var state:states){var a=((AnchorAggregate)state).value();
                    if(region.anchorIds().contains(a.anchorId())||a.regionIds().contains(id))page.add(a);}
                    if(!page.isEmpty()){anchorSections(offset[0],page);offset[0]+=page.size();}});
            }).thenCompose(v->{int[] offset={0};return pass(AggregateKind.LOCATION,states->{var page=new ArrayList<LocationRecord>();for(var state:states){var l=((LocationAggregate)state).value();if(locations.contains(l.locationId()))page.add(l);}
                if(!page.isEmpty()){locationSections(offset[0],page);offset[0]+=page.size();}});
            }).thenApply(v->finish());
        }
        Read finish(){return new Read(snapshot,versions,Map.copyOf(heads),List.copyOf(documents),Set.copyOf(tags));}
        void anchorSections(int offset,List<AnchorRecord> page){try{section(RegionMemoryDetailData.Section.ANCHORS,offset,anchorCount,Optional.empty(),Optional.empty(),Set.of(),page,List.of());}
            catch(IllegalArgumentException oversized){if(page.size()==1)throw oversized;int half=page.size()/2;anchorSections(offset,page.subList(0,half));anchorSections(offset+half,page.subList(half,page.size()));}}
        void locationSections(int offset,List<LocationRecord> page){try{section(RegionMemoryDetailData.Section.LOCATIONS,offset,locationCount,Optional.empty(),Optional.empty(),Set.of(),List.of(),page);}
            catch(IllegalArgumentException oversized){if(page.size()==1)throw oversized;int half=page.size()/2;locationSections(offset,page.subList(0,half));locationSections(offset+half,page.subList(half,page.size()));}}
        void section(RegionMemoryDetailData.Section section,int offset,int total,Optional<RegionRecord> regionBody,Optional<RegionMemory> memoryBody,
                Set<TagId> pageTags,List<AnchorRecord> anchors,List<LocationRecord> pageLocations){
            var scope=new ScopeRef(ScopeKind.REGION,id.value());
            var data=new RegionMemoryDetailData(id,section,offset,total,regionBody,memoryBody,pageTags,anchors,pageLocations);
            var row=new AdminRow(scope,region.header().revision(),region.header().updatedTick(),data);byte[] bytes=new ModelCodec<>(AdminRow.class).encode(row);
            if(bytes.length>MAX_READ_BYTES-encodedBytes||documents.size()>=4096)throw new IllegalStateException("dense admin encoded result budget");
            documents.add(new Document(scope,row.revision(),row.capturedTick(),bytes,BlobContent.hash(bytes)));encodedBytes+=bytes.length;
        }
        CompletionStage<Void> pass(AggregateKind kind,Consumer<List<AggregateState>> consume){return page(kind,Optional.empty(),consume);}
        CompletionStage<Void> page(AggregateKind kind,Optional<AggregateKey> after,Consumer<List<AggregateState>> consume){
            var keys=store.metadataKeysPage(snapshot,Set.of(kind),after,PAGE).orElseThrow(()->new IllegalStateException("metadata keys unavailable"));
            if(keys.keys().isEmpty()){if(keys.next().isPresent())throw new IllegalStateException("nonadvancing metadata keys");return CompletableFuture.completedFuture(null);}
            var query=new StoreQuery(snapshot.session().worldId(),Set.copyOf(keys.keys()),new Sequence(0),Optional.empty(),PAGE);
            return store.readMetadata(snapshot,query).thenCompose(result->{
                ReadContracts.validate(result,query,snapshot.session());if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("metadata document page unavailable");
                var page=result.page().orElseThrow();if(page.hasMore()||!page.records().isEmpty()||!page.lastDurableSequence().equals(snapshot.cut())||page.aggregates().size()!=keys.keys().size())throw new IllegalStateException("metadata document page cut/completeness");
                var expected=new HashSet<>(keys.keys());var states=new ArrayList<AggregateState>();for(var row:page.aggregates()){
                    if(!expected.remove(row.state().key()))throw new IllegalStateException("metadata document page duplicate/foreign key");states.add(row.state());}
                if(!expected.isEmpty())throw new IllegalStateException("metadata document page omitted key");consume.accept(List.copyOf(states));
                if(keys.next().isEmpty())return CompletableFuture.completedFuture(null);
                if(keys.next().equals(after))throw new IllegalStateException("nonadvancing metadata document cursor");return page(kind,keys.next(),consume);
            });
        }
    }
}
