package fr.proutlost.director.memory;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.bootstrap.DirectorMetadataMemoryReader;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.admin.client.*;
import fr.proutlost.director.adapter.neoforge.network.WireCodec;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Synthetic600-region native generation through actual J04. Client state and
 * wire exercised; real Minecraft UI/command/handshake remain native reception. */
public final class GlobalAdminRegionDirectoryTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303844495252L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:p008_global_directory");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static UUID id(int namespace,int n){return new UUID(namespace,n);}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(revision));}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static DirectorWorldState world(JournalStore store,ConfigView config){return new DirectorWorldState(header(WORLD.value(),1),WORLD,store.session().storeGeneration(),new Tick(1),RunControl.PAUSED,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,
        new ResourceId("test:p008_global_directory_profile"),config.provenanceHash(),config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),new EngineControlState(1,new Revision(0),new Tick(0),new Tick(0),new UnitValue(0),new Tick(0),Optional.empty(),new UnitValue(.5)));}
    private static DirectorWorldState nextWorld(DirectorWorldState w,JournalStore store){return new DirectorWorldState(header(WORLD.value(),w.header().revision().value()+1),WORLD,store.session().storeGeneration(),w.simTick().plus(1),w.runControl(),w.healthMode(),w.pacingState(),w.tension(),w.intensityProfileId(),w.catalogHash(),w.configHash(),store.session().bootEpoch(),w.lastCheckpointSeq(),w.engineControl());}
    private static void durable(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(record),blobs);
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"actual declared J04 fixture append durable");
    }
    private static void install(JournalStore store)throws Exception{
        var regions=new ArrayList<ImportedRegion>();for(int n=0;n<600;n++)regions.add(new ImportedRegion(new RegionId(id(81,n)),DIM,new ImportBounds(n*64,0,0,n*64+(n==599?600*128:16),256,16),Set.of(new TagId(n==599?"proutlost:structure":"proutlost:clearing"))));
        var place=new LocationId(id(84,1));var anchors=new ArrayList<ImportedAnchor>();
        for(int n=0;n<600;n++)anchors.add(new ImportedAnchor(new AnchorId(id(85,n)),place,DIM,new ImportPosition(599*64+n*128+1,64,1),Set.of(new TagId("proutlost:anchor_center"))));
        var locations=List.of(new ImportedLocation(place,"qa_dense_directory",Set.of(new RegionId(id(81,599))),DIM,new ImportBounds(599*64,0,0,599*64+600*128,256,16),Set.of()));
        var draft=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        var source=new LocationImport(1,WORLD,SOURCE,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,locations,anchors);var prepared=LocationTransfer.prepare(source);var snapshot=store.metadataSnapshot().orElseThrow();
        check(prepared.manifest().entryCount()==1201&&prepared.pages().size()>1,"true600-region multi-page G0 fixture, not600resident metadata assumptions");
        for(var page:prepared.pages()){var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(page.bytesBase64()));durable(store,new DurableMetadataSourcePage(prepared.manifest(),page.pageIndex(),blob.reference()),List.of(blob));}
        var plan=new DirectorNativeLocationGenerationPlan(WORLD,source,null,new Tick(1),key->store.metadataOwner(snapshot,key));
        for(int n=0;n<plan.pageCount();n++){
            var page=plan.planPage(plan.pageKeys(n),Map.of());var dto=new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),SOURCE,source.exportRevision(),source.exportDigest(),n,plan.pageCount(),page.states(),page.beforeRevisions());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(dto));durable(store,new DurableMetadataStage(prepared.manifest(),n,plan.pageCount(),blob.reference()),List.of(blob));
        }
        durable(store,new DurableMetadataActivation(prepared.manifest(),Optional.empty(),plan.pageCount()),List.of());
    }
    private static AdminQuery query(SessionId actor,Set<TagId> tags,Optional<String> cursor,Optional<Revision> expected){return new AdminQuery(actor,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,new RegionFilter(Optional.empty(),tags),cursor,expected,23);}
    private static MemoryView scoped(JournalStore store,MemorySettings settings)throws Exception{
        var point=new BlockPosition(DIM,1,64,1);var demand=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(point,16)),16,Set.of());
        return await(BoundedMetadataProjection.load(store,store.metadataReads().snapshot().orElseThrow(),demand,settings)).memory();
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh isolated synthetic results required");Files.createDirectories(root);
        var config=profile(Path.of(args[0]));var settings=new MemorySettings(config,false);var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
        try{
            var current=world(store,config);durable(store,new DurableAggregate(new WorldAggregate(current)),List.of());install(store);
            var actorId=new SessionId(id(82,1));var actor=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);var initial=query(actorId,Set.of(),Optional.empty(),Optional.empty());
            var selected=scoped(store,settings);check(selected.regionRecords().size()==1&&!selected.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,WORLD.value())),"game cache is honestly scoped while real directory contains600regions");
            if(args.length>2&&args[2].equals("before")){
                var pages=new AdminProjectionPages(WORLD,512,32,22000);pages.publish(WORLD,current.header().revision(),current.simTick(),store.metadataSnapshot().orElseThrow().cut(),List.of(),Set.of(),selected);
                var result=pages.query(initial,actor,store.session().bootEpoch());System.out.println("BEFORE_GLOBAL_DIRECTORY expected=SUCCEEDED actual="+result.status()+" scoped_regions="+selected.regionRecords().size()+" actual_regions=600");
                check(result.status()==AdminStatus.SUCCEEDED,"default MEMORY global list must discover real persisted regions despite absent WORLD resident coverage");return;
            }
            var reader=new ColdAdminMemoryReader(store,new DirectorMetadataMemoryReader(store),WORLD,8,64,22000);var network=new SessionIdentity(WORLD,store.session().bootEpoch(),new Epoch(1),DIM,new PlayerId(id(83,1)));var client=new AdminPageState();client.reset(network);
            List<RegionId> ids=new ArrayList<>();int windows=0,loading=0,fragments=0;var request=initial;AdminSnapshotStamp pinned=null;boolean worldAdvanced=false;int regionActivity=0;boolean inserted=false;long pinnedRegionZero=-1;Optional<String> firstConsumed=Optional.empty();
            do{
                var result=reader.query(request,actor,current,store.metadataSnapshot().orElseThrow().cut(),settings);check(result.status()==AdminStatus.SUCCEEDED,"directory progress is a typed bounded window");if(pinned==null)pinned=result.snapshot().orElseThrow();
                check(result.snapshot().orElseThrow().equals(pinned),"directory windows retain original stamp across unrelated WORLD advance");
                var packet=AdminFacade.snapshot(request,result,NativeReadCommandFence.supported());check(new WireCodec<>(AdminSnapshot.class,24572).encode(packet).length<=22000,"directory actual encoded network packet within22k");
                client.begin(request);check(client.receive(network,packet),"client accepts directory references/loading windows for global filter");
                if(packet.rows().isEmpty()&&packet.nextCursor().isPresent()){loading++;check(client.loadingContinuation().equals(packet.nextCursor()),"empty loading window auto-continues and cannot masquerade as empty world");Thread.sleep(2);}
                for(int n=0;n<client.snapshot().orElseThrow().rows().size();n++){
                    var row=client.snapshot().orElseThrow().rows().get(n);if(row.data() instanceof MemoryDocumentPageData){fragments++;check(client.loadingContinuation().equals(packet.nextCursor()),"large directory region reference continues transport automatically");continue;}
                    var id=client.directorySelection(n).orElseThrow();ids.add(id);if(id.equals(new RegionId(id(81,0))))pinnedRegionZero=row.revision().value();check(row.data() instanceof RegionMemoryDetailData data&&data.region().orElseThrow().regionId().equals(id),"directory reference is an actual complete RegionRecord, navigable to exact ID");
                }
                windows++;if(!worldAdvanced&&!packet.rows().isEmpty()){current=nextWorld(current,store);durable(store,new DurableAggregate(new WorldAggregate(current)),List.of());worldAdvanced=true;}
                if(!packet.rows().isEmpty()&&!(packet.rows().getFirst().data() instanceof MemoryDocumentPageData)&&regionActivity<25){
                    regionActivity++;var revision=store.metadataRevision(store.metadataSnapshot().orElseThrow(),new AggregateKey(AggregateKind.REGION,id(81,0))).plus(1);
                    durable(store,new DurableAggregate(new RegionAggregate(regionZero(revision.value(),.5))),List.of());
                }
                if(!inserted&&ids.size()>150){
                    insertRegion(store);inserted=true;
                }
                if(result.nextCursor().isEmpty())break;
                var next=query(actorId,Set.of(),result.nextCursor(),Optional.of(pinned.snapshotRevision()));firstConsumed=result.nextCursor();
                if(request.cursor().isPresent())check(reader.query(request,actor,current,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"consumed continuation cannot replay");
                request=next;
            }while(windows<2000);
            check(ids.size()==600&&new HashSet<>(ids).size()==600&&ids.contains(new RegionId(id(81,599))),"all600regions including cold distant599 discovered without duplicate/omission");
            check(regionActivity==25&&pinnedRegionZero==1&&store.metadataRevision(store.metadataSnapshot().orElseThrow(),new AggregateKey(AggregateKind.REGION,id(81,0))).value()==26,"25 REGION updates do not relabel or interrupt original pinned headers");
            check(inserted&&!ids.contains(new RegionId(id(81,600))),"new head inserted midcursor is absent only from explicitly historical600-region cut");
            check(ids.equals(ids.stream().sorted(Comparator.comparing(r->r.value().toString())).toList()),"directory ordering stable over pinned aggregate keys");
            check(fragments>0,"directory with600anchorIDs uses unchanged hashed transport rather than truncating RegionRecord");
            check(loading>0&&store.metadataCachedEntries()<=512,"actual cold async windows preserve512cache and finite page bodies");
            var lastCursor=firstConsumed;check(reader.query(query(actorId,Set.of(),lastCursor,Optional.of(pinned.snapshotRevision())),actor,current,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"last one-use cursor remains consumed");
            check(AdminMemoryFilters.parse("").equals(new RegionFilter(Optional.empty(),Set.of())),"blank MEMORY search is actual global directory");
            check(AdminMemoryFilters.parse("tag proutlost:structure").equals(new RegionFilter(Optional.empty(),Set.of(new TagId("proutlost:structure")))),"typed tag form reaches indexed global search");
            check(AdminMemoryFilters.parse(id(81,599).toString()).equals(new RegionFilter(Optional.of(new RegionId(id(81,599))),Set.of())),"discovered UUID opens exact region filter");
            var tagged=traverse(reader,store,actor,current,settings,Set.of(new TagId("proutlost:structure")));
            check(tagged.equals(Set.of(new RegionId(id(81,599)))),"global source-authored tag search finds distant region through complete verified census");
            check(traverse(reader,store,actor,current,settings,Set.of(new TagId("test:absent"))).isEmpty(),"complete tag census can prove no match");
            var pending=reader.query(query(actorId,Set.of(),Optional.empty(),Optional.empty()),actor,current,store.metadataSnapshot().orElseThrow().cut(),settings);check(pending.nextCursor().isPresent(),"new global search yields bounded loading continuation");
            var newer=store.metadataRevision(store.metadataSnapshot().orElseThrow(),new AggregateKey(AggregateKind.REGION,id(81,0))).plus(1);
            durable(store,new DurableAggregate(new RegionAggregate(regionZero(newer.value(),.9))),List.of());
            var continued=reader.query(query(actorId,Set.of(),pending.nextCursor(),Optional.of(pending.snapshot().orElseThrow().snapshotRevision())),actor,current,store.metadataSnapshot().orElseThrow().cut(),settings);
            check(continued.status()==AdminStatus.SUCCEEDED&&continued.snapshot().equals(pending.snapshot()),"existing REGION update continues original historical cut without current relabelling");
            while(continued.nextCursor().isPresent()){
                var nextRequest=query(actorId,Set.of(),continued.nextCursor(),Optional.of(continued.snapshot().orElseThrow().snapshotRevision()));
                continued=reader.query(nextRequest,actor,current,store.metadataSnapshot().orElseThrow().cut(),settings);check(continued.status()==AdminStatus.SUCCEEDED,"fresh-start directory remains coherent during head changes");Thread.sleep(2);
            }
            var afterInsert=traverse(reader,store,actor,current,settings,Set.of());check(afterInsert.size()==601&&afterInsert.contains(new RegionId(id(81,600))),"new start reads current601-key index including midcursor insertion");
            sourceFences(reader,store,actor,current,settings);
            var denied=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),3,true);check(reader.query(initial,denied,current,store.metadataSnapshot().orElseThrow().cut(),settings).data().isEmpty(),"permission revoked on every directory poll");
            reader.disconnect(actorId);check(reader.query(query(actorId,Set.of(),pending.nextCursor(),Optional.empty()),actor,current,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"disconnect releases global cursors");
            var reconnected=new ActorIdentity(new SessionId(id(82,2)),ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
            check(reader.query(query(reconnected.session(),Set.of(),pending.nextCursor(),Optional.empty()),reconnected,current,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"reconnected actor cannot reuse former session cursor");
            client.reset(new SessionIdentity(WORLD,store.session().bootEpoch(),new Epoch(2),DIM,new PlayerId(id(83,1))));
            check(client.snapshot().isEmpty()&&client.loadingContinuation().isEmpty()&&client.directorySelection(0).isEmpty(),"reconnect drops directory rows, automatic continuation and navigable reference");reader.close();
            check(reader.query(initial,actor,current,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"closed directory exposes no cache");
            resourceBounds(store,current,settings);
            System.out.println("GLOBAL_DIRECTORY regions="+ids.size()+" windows="+windows+" loading_windows="+loading+" fragments="+fragments+" cache="+store.metadataCachedEntries()+" distant599=true stable_order=true");
        }finally{await(store.closeAsync());}
        System.out.println("PASS GlobalAdminRegionDirectoryTests assertions="+checks+" scope=synthetic_actual_J04_wire_client_state_no_Minecraft");
    }
    private static LocationImport emptySource(ResourceId name,long revision){
        var draft=new LocationImport(1,WORLD,name,new Revision(revision),new Fingerprint("0".repeat(64)),List.of(),List.of(),List.of());
        return new LocationImport(1,WORLD,name,draft.exportRevision(),StandaloneLocationImporter.digest(draft),List.of(),List.of(),List.of());
    }
    private static void sourceFences(ColdAdminMemoryReader reader,JournalStore store,ActorIdentity actor,DirectorWorldState world,MemorySettings settings)throws Exception{
        var before=store.metadataSnapshot().orElseThrow();var original=await(store.readMetadataSource(before,SOURCE)).orElseThrow();
        var started=reader.query(query(actor.session(),Set.of(),Optional.empty(),Optional.empty()),actor,world,before.cut(),settings);
        // A completed prior traversal makes this fresh initial start executable.
        check(started.status()==AdminStatus.SUCCEEDED&&started.nextCursor().isPresent(),"source-fence scenario owns a pinned directory continuation");
        var other=emptySource(new ResourceId("test:p008_directory_other"),1);var prepared=LocationTransfer.prepare(other);
        for(var fragment:prepared.pages()){var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(fragment.bytesBase64()));durable(store,new DurableMetadataSourcePage(prepared.manifest(),fragment.pageIndex(),blob.reference()),List.of(blob));}
        var page=new MetadataGenerationPage(LocationTransfer.identity(prepared.manifest()),other.sourceNamespace(),other.exportRevision(),other.exportDigest(),0,1,List.of(),Map.of());
        var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(page));durable(store,new DurableMetadataStage(prepared.manifest(),0,1,blob.reference()),List.of(blob));durable(store,new DurableMetadataActivation(prepared.manifest(),Optional.empty(),1),List.of());
        check(reader.query(query(actor.session(),Set.of(),started.nextCursor(),Optional.of(started.snapshot().orElseThrow().snapshotRevision())),actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"new active source generation refuses old cursor instead of mixing generations");
        check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(original),"other generation leaves original canonical source bytes unchanged");
        traverse(reader,store,actor,world,settings,Set.of());
        var legacyStart=reader.query(query(actor.session(),Set.of(),Optional.empty(),Optional.empty()),actor,world,store.metadataSnapshot().orElseThrow().cut(),settings);var legacyBefore=store.metadataSnapshot().orElseThrow();
        var legacy=emptySource(new ResourceId("test:p008_directory_legacy"),1);var identity=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+WORLD.value()+"/"+legacy.sourceNamespace().value()+"/1/"+legacy.exportDigest().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var fact=new DirectorFact(identity,new FactType("proutlost_director:location_import_applied"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("source_namespace",new JsonValue.Text(legacy.sourceNamespace().value()),"export_revision",new JsonValue.Text("1"),"export_digest",new JsonValue.Text(legacy.exportDigest().value()),"location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(LocationImport.class).encode(legacy)))),true,Optional.empty());
        durable(store,new DurableFact(fact),List.of());var legacyAfter=store.metadataSnapshot().orElseThrow();
        check(legacyBefore.generations().equals(legacyAfter.generations())&&!legacyBefore.sources().equals(legacyAfter.sources()),"actual legacy source descriptor changes independently of generation map");
        check(reader.query(query(actor.session(),Set.of(),legacyStart.nextCursor(),Optional.of(legacyStart.snapshot().orElseThrow().snapshotRevision())),actor,world,legacyAfter.cut(),settings).status()==AdminStatus.DEFERRED,"legacy source provenance change also refuses historical cursor");
    }
    private static RegionRecord regionZero(long revision,double importance){return new RegionRecord(header(id(81,0),revision),new RegionId(id(81,0)),DIM,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(DIM,0,0,0,16,256,16)),List.of(),List.of()),Set.of(),new UnitValue(importance),Set.of(),Set.of(),new Revision(revision),new RegionPersonality(Map.of()),Set.of(new MemoryId(id(81,0))));}
    private static void insertRegion(JournalStore store)throws Exception{
        var id=new RegionId(id(81,600));var record=new RegionRecord(header(id.value(),1),id,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(new Aabb(DIM,200000,0,0,200016,256,16)),List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(id.value())));
        var memory=new RegionMemory(header(id.value(),1),id,Map.of(),new Tick(1),new Tick(1),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(new RegionAggregate(record)),new DurableAggregate(new RegionMemoryAggregate(memory))),List.of()))).ackStatus()==AckStatus.DURABLE,"actual runtime-region pair inserted atomically during historical traversal");
    }
    /** Fault injection pauses only this new test-owned directory executor;
     * the real J04 writer and reader are neither stopped nor substituted. */
    private static void resourceBounds(JournalStore store,DirectorWorldState world,MemorySettings settings)throws Exception{
        var directory=new GlobalAdminRegionDirectory(store,4,8,22000);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var field=GlobalAdminRegionDirectory.class.getDeclaredField("worker");field.setAccessible(true);var executor=(ThreadPoolExecutor)field.get(directory);
        executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        check(entered.await(10,TimeUnit.SECONDS),"test-owned directory executor paused independently of actual J04");
        var firstActor=new ActorIdentity(new SessionId(id(82,10)),ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);AdminReadResult first=null;
        try{
            for(int n=0;n<4;n++){
                var request=query(firstActor.session(),Set.of(new TagId("test:queued_"+n)),Optional.empty(),Optional.empty());
                var result=directory.query(request,firstActor,world,store.metadataSnapshot().orElseThrow().cut());
                check(result.status()==AdminStatus.SUCCEEDED&&result.data().isEmpty()&&result.nextCursor().isPresent(),"each bounded pending read returns loading continuation, not empty census");if(n==0)first=result;
            }
            var fifth=query(firstActor.session(),Set.of(new TagId("test:queued_4")),Optional.empty(),Optional.empty());
            check(directory.query(fifth,firstActor,world,store.metadataSnapshot().orElseThrow().cut()).status()==AdminStatus.DEFERRED,"fifth in-flight read cannot evict pending work outside four-read bound");
            check(executor.getQueue().size()<=4,"directory worker queue remains bounded under paused IO scheduling");
            directory.disconnect(firstActor.session());
            var secondActor=new ActorIdentity(new SessionId(id(82,11)),ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
            check(directory.query(query(secondActor.session(),Set.of(new TagId("test:new_session")),Optional.empty(),Optional.empty()),secondActor,world,store.metadataSnapshot().orElseThrow().cut()).status()==AdminStatus.DEFERRED,"reconnect cannot bypass pending-read resource bound");
            check(directory.query(query(firstActor.session(),Set.of(new TagId("test:queued_0")),first.nextCursor(),Optional.empty()),firstActor,world,store.metadataSnapshot().orElseThrow().cut()).status()==AdminStatus.DEFERRED,"disconnected pending read cannot expose a retained private cursor");
            release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);AdminReadResult resumed;
            do{resumed=directory.query(query(secondActor.session(),Set.of(),Optional.empty(),Optional.empty()),secondActor,world,store.metadataSnapshot().orElseThrow().cut());if(resumed.status()==AdminStatus.SUCCEEDED)break;Thread.sleep(5);}while(System.nanoTime()<until);
            check(resumed.status()==AdminStatus.SUCCEEDED,"settled revoked work frees capacity without restarting or weakening J04");
        }finally{release.countDown();directory.close();}
    }
    private static Set<RegionId> traverse(ColdAdminMemoryReader reader,JournalStore store,ActorIdentity actor,DirectorWorldState world,MemorySettings settings,Set<TagId> tags)throws Exception{
        var result=new HashSet<RegionId>();var request=query(actor.session(),tags,Optional.empty(),Optional.empty());AdminSnapshotStamp stamp=null;var assembler=new MemoryDocumentAssembler();
        for(int n=0;n<2000;n++){
            var reply=reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings);
            if(reply.status()==AdminStatus.DEFERRED&&request.cursor().isEmpty()){Thread.sleep(5);continue;}
            check(reply.status()==AdminStatus.SUCCEEDED,"filtered directory remains available or explicitly loading");
            if(stamp==null)stamp=reply.snapshot().orElseThrow();var packet=AdminFacade.snapshot(request,reply,NativeReadCommandFence.supported());
            for(var row:reply.data()){var semantic=assembler.accept(packet,row);if(semantic.isPresent()&&semantic.get().data() instanceof RegionMemoryDetailData data)check(result.add(data.regionId()),"tagged directory result unique after verified whole-body reconstruction");}
            if(reply.nextCursor().isEmpty())return Set.copyOf(result);Thread.sleep(2);request=query(actor.session(),tags,reply.nextCursor(),Optional.of(stamp.snapshotRevision()));
        }throw new AssertionError("directory tag traversal did not terminate");
    }
}
