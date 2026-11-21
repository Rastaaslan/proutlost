package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Declared synthetic QA: actual staged/activated J04, pure native planning
 * bridge, no Minecraft reception, physical survey, Contra or RUN7 claim. */
public final class DenseMetadataProjectionTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303844454e53L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:p008_dense_anchors");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static UUID id(int namespace,int index){return new UUID(namespace,index);}
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static ConfigView config(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();
        var values=new HashMap<String,JsonValue.Number>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static LocationImport signed(ResourceId source,List<ImportedRegion> regions,List<ImportedLocation> locations,List<ImportedAnchor> anchors){
        var draft=new LocationImport(1,WORLD,source,new Revision(1),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        return new LocationImport(1,WORLD,source,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,locations,anchors);
    }
    private static LocationImport denseSource(){
        var region=new RegionId(id(51,1));var location=new LocationId(id(52,1));
        var bounds=new ImportBounds(0,0,0,600*128,256,32);var anchors=new ArrayList<ImportedAnchor>();
        for(int n=0;n<600;n++)anchors.add(new ImportedAnchor(new AnchorId(id(53,n)),location,DIM,new ImportPosition(n*128+1,64,1),
            Set.of(new TagId(n==599?"proutlost:structure":"proutlost:anchor_center"))));
        return signed(SOURCE,List.of(new ImportedRegion(region,DIM,bounds,Set.of())),
            List.of(new ImportedLocation(location,"dense_qa",Set.of(region),DIM,bounds,Set.of())),anchors);
    }
    private static CommitAck append(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs)throws Exception{
        return await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs)));
    }
    private static void durable(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception{
        if(append(store,List.of(record),blobs).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("actual synthetic J04 fixture refused");
    }
    private static void install(JournalStore store,LocationImport input)throws Exception{
        var transport=LocationTransfer.prepare(input);var pinned=store.metadataSnapshot().orElseThrow();
        var token=store.metadataHeadVersions(pinned,Set.of(AggregateKind.ANCHOR)).orElseThrow();
        for(var page:transport.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(page.bytesBase64()));
            durable(store,new DurableMetadataSourcePage(transport.manifest(),page.pageIndex(),blob.reference()),List.of(blob));
        }
        var plan=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(1),key->store.metadataOwner(pinned,key));
        for(int n=0;n<plan.pageCount();n++){
            var page=plan.planPage(plan.pageKeys(n),Map.of());
            var dto=new MetadataGenerationPage(LocationTransfer.identity(transport.manifest()),input.sourceNamespace(),input.exportRevision(),input.exportDigest(),n,
                plan.pageCount(),page.states(),page.beforeRevisions());
            var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,new ModelCodec<>(MetadataGenerationPage.class).encode(dto));
            durable(store,new DurableMetadataStage(transport.manifest(),n,plan.pageCount(),blob.reference()),List.of(blob));
        }
        check(store.metadataHeadVersions(store.metadataSnapshot().orElseThrow(),Set.of(AggregateKind.ANCHOR)).orElseThrow().equals(token),"hidden preparation preserves visible anchor token");
        durable(store,new DurableMetadataActivation(transport.manifest(),Optional.empty(),plan.pageCount()),List.of());
    }
    private static BoundedMetadataProjection.Demand demand(int anchor,Set<AnchorId> pinned){
        return new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(new BlockPosition(DIM,anchor*128+1,64,1),16)),16,Set.of(),pinned);
    }
    private static SnapshotBatch live(MemoryView memory){
        var scope=new ScopeRef(ScopeKind.REGION,id(51,1));
        return new SnapshotBatch(new RequestId(id(59,1)),new ScopeSet(WORLD,Set.of(scope)),new Tick(10),Map.of(),
            new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),
            Set.of(SensorKind.REGION_TRAITS,SensorKind.REGION_TAGS,SensorKind.ANCHORS,SensorKind.CAMP_ACTIVITY),Set.of(),HealthMode.NORMAL);
    }
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3)throw new IllegalArgumentException("profile and NEW isolated result directory [before] required");
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh synthetic QA directory required");Files.createDirectories(root);
        var profile=config(Path.of(args[0]));var settings=new MemorySettings(profile,false);boolean before=args.length==3&&args[2].equals("before");boolean nativeBridge=args.length==3&&args[2].equals("native");
        dense(root.resolve("600anchors"),settings,profile,before,nativeBridge);
        if(!before){locations4096(root.resolve("4096locations"),settings);if(nativeBridge)anchorOwner();}
        System.out.println("PASS DenseMetadataProjectionTests assertions="+checks+" scope=declared_synthetic_actual_J04_no_Minecraft_no_Contra_no_RUN7 native_bridge="+nativeBridge+" before="+before);
    }
    private static void dense(Path path,MemorySettings settings,ConfigView profile,boolean before,boolean nativeBridge)throws Exception{
        var source=denseSource();var transport=LocationTransfer.prepare(source);
        Files.write(path.getParent().resolve("dense-source.json"),new ModelCodec<>(LocationImport.class).encode(source));
        check(source.anchors().size()==600&&transport.manifest().entryCount()==602&&transport.manifest().sourceBytes()<=LocationTransfer.SOURCE_BYTES,
            "600 anchors in one region is legal declared multi-page G0, combined count remains602");
        JournalStore store=await(JournalStore.open(path,WORLD,OPTIONS));
        try{
            install(store,source);var cut=store.metadataSnapshot().orElseThrow();
            if(before){
                try{await(BoundedMetadataProjection.load(store,cut,demand(0,Set.of()),settings));throw new AssertionError("before dense defect did not reproduce");}
                catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException&&expected.getCause().getMessage().contains("exceeds512"),"BEFORE: legal dispersed catalogue refused by all-region-anchor working set");System.out.println("BEFORE_NATIVE_GAME_FAILURE "+expected.getCause().getMessage());}
                return;
            }
            var selected=await(BoundedMetadataProjection.load(store,cut,demand(0,Set.of()),settings));var memory=selected.memory();
            check(memory.regionRecords().get(source.regions().getFirst().regionId()).anchorIds().size()==600,"immutable region retains all600 anchor IDs");
            check(memory.anchors().size()==1&&memory.anchors().containsKey(new AnchorId(id(53,0)))&&selected.states().size()==4,"spatial game working set contains nearby bodies only");
            check(selected.peakPageRows()<=96&&store.metadataCachedEntries()<=512&&store.metadataHydrationPageLoads()>0,"real cold generation hydration/page/cache bounds hold");
            check(selected.currentHeads(store,cut),"all selected and summary heads current at pinned cut");
            var provider=new MemorySnapshotProvider(settings);
            check(provider.regions(memory,Map.of(),new Tick(10)).isEmpty(),"unattested partial bodies stay unknown");
            var enriched=provider.enrich(live(memory),memory,selected.anchorCoverage());var scope=new ScopeRef(ScopeKind.REGION,id(51,1));
            check(enriched.context().regions().size()==1&&enriched.context().regions().get(new RegionId(id(51,1))).tags().contains(new TagId("proutlost:structure")),"complete tags include cold distant anchor without loading its body");
            check(enriched.context().scopes().get(scope).availableSensors().contains(SensorKind.REGION_TAGS)&&!enriched.missingData().contains(SensorKind.REGION_TAGS),"complete pinned tag census is usable by actual region predicate input");
            check(enriched.context().scopes().get(scope).missingSensors().contains(SensorKind.ANCHORS)&&enriched.missingData().contains(SensorKind.ANCHORS),"spatial anchor subset never claims exhaustive body coverage");
            if(nativeBridge){
                var bridgeClass=Class.forName("fr.proutlost.director.bootstrap.DirectorNativeRegionalPlanningBridge");
                var bridge=bridgeClass.getConstructor(ConfigView.class).newInstance(profile);
                var projected=bridgeClass.getMethod("project",SnapshotBatch.class,MemoryView.class,long.class,Map.class,Optional.class)
                    .invoke(bridge,live(memory),memory,cut.cut().value(),Map.of(),selected.anchorCoverage());
                check(Boolean.FALSE.equals(projected.getClass().getMethod("deferred").invoke(projected))
                    &&Long.valueOf(1).equals(projected.getClass().getMethod("regionCount").invoke(projected)),"real pure native planning bridge accepts dense600 with bounded context");
            }
            System.out.println("DENSE_REGION_READ source_entries="+transport.manifest().entryCount()+" source_bytes="+transport.manifest().sourceBytes()+" declared_anchor_ids="+memory.regionRecords().get(new RegionId(id(51,1))).anchorIds().size()+" selected_states="+selected.states().size()+" resident_anchors="+memory.anchors().size()+" full_anchor_sensor=false verified_tags=true");
            var far=await(BoundedMetadataProjection.load(store,cut,demand(599,Set.of()),settings));
            check(far.snapshot().cut().equals(selected.snapshot().cut())&&far.memory().anchors().keySet().equals(Set.of(new AnchorId(id(53,599)))),"same-cut movement selects distant actual body atomically");
            var pinned=await(BoundedMetadataProjection.load(store,cut,demand(599,Set.of(new AnchorId(id(53,0)))),settings));
            check(pinned.memory().anchors().size()==2,"unacknowledged far anchor pin survives spatial rescope");
            var operational=new PlayerOperationalMemory(new AggregateHeader(1,id(57,1),new Revision(1),new Sequence(0),new Tick(1)),new PlayerId(id(57,1)),new DurationTicks(0),Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0),Map.of(),Optional.empty(),new Epoch(0));
            durable(store,new DurableAggregate(new PlayerOperationalAggregate(operational)),List.of());
            check(selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"unrelated player append does not invalidate complete cold tag census");
            var coldId=new AnchorId(id(53,598));var original=source.anchors().get(598);
            var update=new AnchorRecord(new AggregateHeader(1,coldId.value(),new Revision(2),new Sequence(0),new Tick(2)),coldId,Optional.of(original.locationId()),Set.of(source.regions().getFirst().regionId()),DIM,
                new BlockPosition(DIM,original.position().x(),original.position().y(),original.position().z()),"region.camp_radius_blocks",Set.of(new TagId("proutlost:dungeon")),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true);
            durable(store,new DurableAggregate(new AnchorAggregate(update)),List.of());
            check(!selected.currentHeads(store,store.metadataSnapshot().orElseThrow()),"cold unresident anchor update invalidates summary despite unchanged selected heads");
            var refreshed=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand(0,Set.of()),settings));
            check(refreshed.anchorCoverage().orElseThrow().tags(memory.regionRecords().get(new RegionId(id(51,1)))).orElseThrow().contains(new TagId("proutlost:dungeon")),"refreshed summary reads real changed cold tag");
            var added=new AnchorRecord(new AggregateHeader(1,id(53,600),new Revision(1),new Sequence(0),new Tick(3)),new AnchorId(id(53,600)),Optional.empty(),Set.of(new RegionId(id(51,1))),DIM,
                new BlockPosition(DIM,70000,64,1),"region.camp_radius_blocks",Set.of(new TagId("proutlost:abandoned_camp")),AnchorOrigin.CAMP,EvidenceStatus.CONFIRMED,true);
            durable(store,new DurableAggregate(new AnchorAggregate(added)),List.of());
            check(!refreshed.currentHeads(store,store.metadataSnapshot().orElseThrow()),"new unlisted cold runtime anchor invalidates summary without fake region mutation");
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        store=await(JournalStore.open(path,WORLD,OPTIONS));try{
            var selected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),demand(599,Set.of()),settings));
            check(selected.currentHeads(store,store.metadataSnapshot().orElseThrow())&&selected.memory().anchors().size()==1,"checkpoint/new J04 session restores dense scoped bodies and writer tokens");
            check(selected.anchorCoverage().orElseThrow().tags(selected.memory().regionRecords().get(new RegionId(id(51,1)))).orElseThrow().contains(new TagId("proutlost:abandoned_camp")),"cold restart preserves unlisted runtime tag summary");
        }finally{await(store.closeAsync());}
    }
    private static void locations4096(Path path,MemorySettings settings)throws Exception{
        var store=await(JournalStore.open(path,WORLD,OPTIONS));try{
            int nativePlaces=0,combined=0;
            for(int part=0;part<2;part++){
                var region=new RegionId(id(61,part));var imported=new ArrayList<ImportedLocation>();
                for(int n=0;n<2048;n++){int x=n*64;imported.add(new ImportedLocation(new LocationId(id(62+part,n)),"q"+n,Set.of(region),DIM,new ImportBounds(x,0,part*64,x+8,128,part*64+8),Set.of()));}
                var input=signed(new ResourceId("test:p008_4096_part"+part),List.of(new ImportedRegion(region,DIM,new ImportBounds(0,0,part*64,2048*64,128,part*64+8),Set.of())),imported,List.of());
                var transport=LocationTransfer.prepare(input);nativePlaces+=input.locations().size();combined+=transport.manifest().entryCount();
                Files.write(path.getParent().resolve("locations-source-part"+part+".json"),new ModelCodec<>(LocationImport.class).encode(input));install(store,input);
            }
            check(nativePlaces==4096&&combined==4098,"4096 native locations distinct from combined regions+locations count4098 across two legal sources");
            var point=new BlockPosition(DIM,2047*64+1,64,65);var wanted=new BoundedMetadataProjection.Demand(Set.of(),Set.of(BoundedMetadataProjection.Demand.cell(point,16)),16,Set.of());
            var selected=await(BoundedMetadataProjection.load(store,store.metadataSnapshot().orElseThrow(),wanted,settings));
            check(selected.memory().locations().keySet().equals(Set.of(new LocationId(id(63,2047))))&&selected.states().size()==3,"last of4096 locations found through bounded on-demand game cache");
            check(selected.peakPageRows()<=96&&store.metadataCachedEntries()<=512,"4096 native-location scan preserves96-page512-cache limits");
            System.out.println("DENSE_NATIVE_LOCATIONS native_locations="+nativePlaces+" combined_entries="+combined+" selected_states="+selected.states().size()+" peak_page="+selected.peakPageRows()+" cache="+store.metadataCachedEntries());
        }finally{await(store.closeAsync());}
    }
    private static void anchorOwner()throws Exception{
        var source=denseSource();var a=source.anchors().getFirst();var b=source.anchors().getLast();var region=source.regions().getFirst().regionId();
        java.util.function.Function<ImportedAnchor,AnchorRecord> row=x->new AnchorRecord(new AggregateHeader(1,x.anchorId().value(),new Revision(1),new Sequence(0),new Tick(1)),x.anchorId(),Optional.of(x.locationId()),Set.of(region),DIM,new BlockPosition(DIM,x.position().x(),x.position().y(),x.position().z()),"region.camp_radius_blocks",x.tags(),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true);
        var pending=new HashSet<AnchorId>();var type=Class.forName("fr.proutlost.director.bootstrap.DirectorNativeAnchorOwner");
        var owner=type.getConstructor(Runnable.class,Map.class,int.class,int.class,java.util.function.Consumer.class,java.util.function.Predicate.class,java.util.function.BooleanSupplier.class)
            .newInstance((Runnable)()->{},Map.of(a.anchorId(),row.apply(a)),2,2,(java.util.function.Consumer<AnchorRecord>)unused->{},(java.util.function.Predicate<AnchorId>)pending::contains,(java.util.function.BooleanSupplier)()->true);
        var merge=type.getMethod("mergeDurable",Map.class,long.class,boolean.class);var current=type.getMethod("current",AnchorId.class);
        merge.invoke(owner,Map.of(b.anchorId(),row.apply(b)),10,true);
        check(((Optional<?>)current.invoke(owner,a.anchorId())).isEmpty()&&((Optional<?>)current.invoke(owner,b.anchorId())).isPresent(),"spatial owner evicts read cache only without durable tombstone");
        pending.add(b.anchorId());merge.invoke(owner,Map.of(a.anchorId(),row.apply(a)),10,true);
        check(((Optional<?>)current.invoke(owner,a.anchorId())).isPresent()&&((Optional<?>)current.invoke(owner,b.anchorId())).isPresent()&&((Set<?>)type.getMethod("retainedForWrite").invoke(owner)).contains(b.anchorId()),"same-cut rescope preserves unfinished owned anchor writes");
        pending.clear();merge.invoke(owner,Map.of(a.anchorId(),row.apply(a)),10,true);
        check(((Optional<?>)current.invoke(owner,b.anchorId())).isEmpty(),"only completed pure durable read body is evicted");type.getMethod("close").invoke(owner);
    }
}
