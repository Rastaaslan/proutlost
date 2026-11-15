package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.model.PhysicalCatalogFacts;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static fr.proutlost.contract.director.Vocabulary.*;
import static org.junit.jupiter.api.Assertions.*;

/** Proposed actual J04 + full sealed D0 projection tests, never Minecraft/effect qualification. */
class DurablePhysicalCatalogViewTest {
    private static final fr.proutlost.director.api.model.WorldId DIRECTOR_WORLD=new fr.proutlost.director.api.model.WorldId(new UUID(0,1));
    private static final Id WORLD=new Id("test:source"),BUILD=new Id("test:build"),DIM=new Id("minecraft:overworld");
    private static final SemanticRegionId REGION=new SemanticRegionId(new Id("test:region"));
    private static final Digest ZERO=new Digest("0".repeat(64));
    private static final List<EvidenceRef> EVIDENCE=List.of(new EvidenceRef(new Id("test:measured"),ZERO,new Id("test:validator")));
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
    @TempDir Path temporary;
    private static Bounds box(int minX,int maxX){return new Bounds(DIM,new BlockPos(minX,64,0),new BlockPos(maxX,70,7));}
    private static StaticLocationCatalogEntry entry(String name,String revision,int x,Digest digest){
        var id=new Id("test:"+name);var rev=new Revision(revision);var center=new Id("test:center");
        var surface=new InteractionSurface(new Id("test:surface"),new Id("test:vote"),List.of(center),
            List.of(new Id("test:slot")),Set.of(new Id("test:record")),Set.of(new Id("test:gesture")),new Id("test:action_policy"),Set.of(new Id("test:zone")));
        var capability=new LocationCapability(new Id("proutlost:safe_shore"),new Revision("1"),
            new TypedValue(new Id("proutlost:safe_shore"),new Revision("1"),Map.of("capacity",new TypedValue.Number(2))),
            Set.of(center),Set.of(surface.surface_id()),EVIDENCE);
        return new StaticLocationCatalogEntry(WORLD,SchemaVersion.BASE,BUILD,id,rev,SourceType.worldprep_prepared_site,null,
            Set.of(REGION),List.of(box(x,x+7),box(x+12,x+19)),Set.of(new Id("test:prepared")),
            List.of(new Anchor(center,new Id("test:recovery"),DIM,new Vec3(x+2,65,2),new Vec3(0,0,1),2,Set.of(new Id("test:safe")))),
            List.of(surface),List.of(capability),List.of(new ProtectedZone(new Id("test:zone"),box(x,x+7),
                new Id("test:access_policy"),new Id("test:presentation_policy"),new Id("test:action_policy"))),
            Set.of(Channel.SOUND,Channel.SYMBOL),List.of(new AnchorRef(new LocationRef(LocationStore.CATALOG,id,rev),center)),
            new Provenance(new Id("test:producer"),new Id("test:source_receipt"),null,EVIDENCE),EVIDENCE,digest);
    }
    private static CatalogSnapshot snapshot(String revision,int delta){
        List<StaticLocationCatalogEntry> entries=new ArrayList<>();
        for(String name:List.of("one","two")){
            int x=(name.equals("one")?0:40)+delta;var unsigned=entry(name,revision,x,ZERO);
            entries.add(entry(name,revision,x,StrictCodec.contentDigest(unsigned)));
        }
        var regions=List.of(new SemanticRegion(REGION,new Revision(revision),List.of(box(0,20),box(40,70)),Set.of(new Id("test:priority")),37));
        var unsigned=new CatalogSnapshot(WORLD,SchemaVersion.BASE,BUILD,new Revision(revision),ZERO,CatalogState.READY,
            Instant.parse("2026-11-13T00:00:00Z"),EVIDENCE,regions,entries);
        return new CatalogSnapshot(WORLD,SchemaVersion.BASE,BUILD,unsigned.catalog_revision(),StrictCodec.contentDigest(unsigned),
            unsigned.state(),unsigned.validated_at(),EVIDENCE,regions,entries);
    }
    private static Document document(CatalogSnapshot source){
        var bytes=StrictCodec.encode(source).getBytes(StandardCharsets.UTF_8);
        var blob=fr.proutlost.director.api.model.BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,bytes);
        Map<fr.proutlost.director.api.model.ResourceId,fr.proutlost.director.api.model.RegionId> regions=Map.of(
            new fr.proutlost.director.api.model.ResourceId(REGION.value().value()),new fr.proutlost.director.api.model.RegionId(new UUID(0,2)));
        Map<fr.proutlost.director.api.model.ResourceId,fr.proutlost.director.api.model.LocationId> locations=Map.of(
            new fr.proutlost.director.api.model.ResourceId("test:one"),new fr.proutlost.director.api.model.LocationId(new UUID(0,3)),
            new fr.proutlost.director.api.model.ResourceId("test:two"),new fr.proutlost.director.api.model.LocationId(new UUID(0,4)));
        var anchors=List.of(new AnchorBinding(new fr.proutlost.director.api.model.ResourceId("test:one"),new fr.proutlost.director.api.model.ResourceId("test:center"),new fr.proutlost.director.api.model.AnchorId(new UUID(0,5))),
            new AnchorBinding(new fr.proutlost.director.api.model.ResourceId("test:two"),new fr.proutlost.director.api.model.ResourceId("test:center"),new fr.proutlost.director.api.model.AnchorId(new UUID(0,6))));
        var blank=new Binding(DIRECTOR_WORLD,new fr.proutlost.director.api.model.ResourceId(WORLD.value()),new fr.proutlost.director.api.model.ResourceId(BUILD.value()),
            blob.reference().hash(),new fr.proutlost.director.api.model.Fingerprint("0".repeat(64)),regions,locations,anchors);
        var binding=new Binding(blank.directorWorld(),blank.sourceWorld(),blank.sourceBuild(),blank.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(blank),regions,locations,anchors);
        var key=new Key(DIRECTOR_WORLD,binding.sourceWorld(),binding.sourceBuild(),source.catalog_revision().value(),
            new fr.proutlost.director.api.model.Fingerprint(source.digest().value()),blob.reference().hash(),binding.bindingSha());
        return new Document(key,binding,blob);
    }
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(15,TimeUnit.SECONDS);}
    private static void world(JournalStore store)throws Exception{
        var header=new fr.proutlost.director.api.model.AggregateHeader(1,DIRECTOR_WORLD.value(),new fr.proutlost.director.api.model.Revision(1),new fr.proutlost.director.api.model.Sequence(0),new fr.proutlost.director.api.model.Tick(0));
        var tick=new fr.proutlost.director.api.model.Tick(0);var hash=fr.proutlost.director.api.model.BlobContent.hash(new byte[]{1});
        var engine=new fr.proutlost.director.api.model.EngineControlState(1,new fr.proutlost.director.api.model.Revision(0),tick,tick,new fr.proutlost.director.api.model.UnitValue(0),tick,Optional.empty(),new fr.proutlost.director.api.model.UnitValue(.5));
        var value=new fr.proutlost.director.api.model.DirectorWorldState(header,DIRECTOR_WORLD,store.session().storeGeneration(),tick,
            fr.proutlost.director.api.model.RunControl.PAUSED,fr.proutlost.director.api.model.HealthMode.NORMAL,fr.proutlost.director.api.model.PacingState.REST,
            fr.proutlost.director.api.model.GlobalTension.CALM,new fr.proutlost.director.api.model.ResourceId("test:metadata"),hash,hash,store.session().bootEpoch(),new fr.proutlost.director.api.model.Sequence(0),engine);
        var batch=ResultTransaction.batch(new fr.proutlost.director.api.model.TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
            List.of(new fr.proutlost.director.api.model.DurableAggregate(new fr.proutlost.director.api.model.WorldAggregate(value))),List.of());
        assertEquals(fr.proutlost.director.api.model.AckStatus.DURABLE,await(store.append(batch)).ackStatus());
    }
    private static DurablePhysicalCatalogView view(JournalStore store,DurablePhysicalCatalogView.InstalledSemantics semantics){
        return new DurablePhysicalCatalogView(WORLD,BUILD,store::session,Map.of(DIM,new Bounds(DIM,new BlockPos(-100,-64,-100),new BlockPos(100,319,100))),semantics);
    }
    private static PhysicalCatalogJournalAdmission runtime(JournalStore store,DurablePhysicalCatalogView view,Executor worker,fr.proutlost.director.api.model.SessionId actor){
        return runtime(store,view,worker,actor,Set.of(document(snapshot("revision_a",0)).binding(),document(snapshot("revision_b",1)).binding()));
    }
    private static PhysicalCatalogJournalAdmission runtime(JournalStore store,DurablePhysicalCatalogView view,Executor worker,fr.proutlost.director.api.model.SessionId actor,Set<Binding> issued){
        Thread owner=Thread.currentThread();AtomicReference<Object> lane=new AtomicReference<>();
        return new PhysicalCatalogJournalAdmission(store,()->assertSame(owner,Thread.currentThread()),actor::equals,(binding,use)->{
            if(use!=PhysicalCatalogJournalAdmission.BindingUse.RECOVERY&&!issued.contains(binding))throw new IllegalArgumentException("binding not frozen by TEST owner");
            var reserved=store.residentAggregateKeys(EnumSet.allOf(fr.proutlost.director.api.model.AggregateKind.class),4096).orElseThrow().keys()
                .stream().map(fr.proutlost.director.api.model.AggregateKey::id).collect(java.util.stream.Collectors.toSet());
            if(binding.regions().values().stream().anyMatch(id->reserved.contains(id.value()))
                ||binding.locations().values().stream().anyMatch(id->reserved.contains(id.value()))
                ||binding.anchors().stream().anyMatch(a->reserved.contains(a.directorAnchor().value())))throw new IllegalArgumentException("actual core/G0 UUID collision");
        },()->true,
            request->lane.get()==request||lane.compareAndSet(null,request),request->assertTrue(lane.compareAndSet(request,null)),
            ()->Map.of(new fr.proutlost.director.api.model.ScopeRef(fr.proutlost.director.api.model.ScopeKind.WORLD,DIRECTOR_WORLD.value()),new fr.proutlost.director.api.model.Revision(1)),worker,view);
    }
    private static void until(PhysicalCatalogJournalAdmission runtime,BooleanSupplier done)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){runtime.tick();if(done.getAsBoolean())return;Thread.sleep(2);}
        fail("physical view timeout: "+runtime.diagnostic());
    }
    /** Explicit synthetic adapter support, used only for these neutral unit fixtures. */
    private static void supportedTestSemantics(CatalogSnapshot source){
        for(var entry:source.entries()){
            if(!entry.capabilities().stream().allMatch(c->c.capability_id().equals(new Id("proutlost:safe_shore"))&&c.capability_version().equals(new Revision("1"))))throw new IllegalArgumentException("unsupported TEST capability");
            for(var zone:entry.protection())if(!zone.access_policy().equals(new Id("test:access_policy"))||!zone.presentation_policy().equals(new Id("test:presentation_policy"))||!zone.player_action_policy().equals(new Id("test:action_policy")))throw new IllegalArgumentException("unsupported TEST policy");
        }
    }
    @Test void actualJ04PreservesFullD0MetadataLocalAnchorsHistoryAndReopen()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();Path path=temporary.resolve("real-j04");
        var store=await(JournalStore.open(path,DIRECTOR_WORLD,OPTIONS));world(store);var view=view(store,DurablePhysicalCatalogViewTest::supportedTestSemantics);
        var actor=new fr.proutlost.director.api.model.SessionId(UUID.randomUUID());var runtime=runtime(store,view,worker,actor);
        try{
            until(runtime,runtime::ready);var original=snapshot("revision_a",0);var first=document(original);
            assertEquals(State.PENDING,runtime.submit(actor,first).state());assertThrows(IllegalStateException.class,view::snapshot);
            until(runtime,()->runtime.poll(actor,first.key()).state()==State.DURABLE);
            assertEquals(original,view.snapshot());assertEquals(37,view.snapshot().regions().getFirst().priority());
            assertEquals(2,view.query(new CatalogQuery(Set.of(new Id("proutlost:safe_shore")),Set.of(REGION),null,128)).size());
            assertEquals(2,view.snapshot().entries().getFirst().geometry().size());
            var oldRef=new LocationRef(LocationStore.CATALOG,new Id("test:one"),new Revision("revision_a"));
            assertTrue(view.current(oldRef));assertEquals(original.entries().getFirst(),view.lookup(oldRef).orElseThrow());
            var updated=snapshot("revision_b",1);var second=document(updated);assertEquals(State.PENDING,runtime.submit(actor,second).state());
            until(runtime,()->runtime.poll(actor,second.key()).state()==State.DURABLE);
            assertEquals(updated,view.snapshot());assertFalse(view.current(oldRef));assertTrue(view.lookup(oldRef).isPresent());
            runtime.close();await(store.closeAsync());
            var cold=await(JournalStore.open(path,DIRECTOR_WORLD,OPTIONS));var coldView=view(cold,DurablePhysicalCatalogViewTest::supportedTestSemantics);
            var coldActor=new fr.proutlost.director.api.model.SessionId(UUID.randomUUID());var recovered=runtime(cold,coldView,worker,coldActor);
            until(recovered,recovered::ready);assertEquals(updated,coldView.snapshot());assertTrue(coldView.lookup(oldRef).isPresent());
            var read=recovered.readSource(coldActor,recovered.poll(coldActor,second.key()).readback().orElseThrow()).toCompletableFuture();until(recovered,read::isDone);
            assertEquals(StrictCodec.encode(updated),new String(await(read).contents().getFirst().bytes(),StandardCharsets.UTF_8));
            recovered.close();await(cold.closeAsync());
        }finally{worker.shutdown();assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));}
    }
    @Test void unsupportedRequiredPolicyRefusesBeforeAnyPublication()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();var store=await(JournalStore.open(temporary.resolve("unsupported"),DIRECTOR_WORLD,OPTIONS));world(store);
        var view=view(store,snapshot->{throw new IllegalArgumentException("No actual installed handler for required policy");});
        var actor=new fr.proutlost.director.api.model.SessionId(UUID.randomUUID());var runtime=runtime(store,view,worker,actor);var document=document(snapshot("revision_a",0));
        try{until(runtime,runtime::ready);runtime.submit(actor,document);until(runtime,()->runtime.poll(actor,document.key()).state()==State.REFUSED);
            assertThrows(IllegalStateException.class,view::snapshot);assertFalse(runtime.metadataLaneHeld());
        }finally{runtime.close();await(store.closeAsync());worker.shutdown();assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));}
    }

    /** Large neutral metadata fixture. Real J04 writer/compiler/readback/cold recovery,
     * explicitly no Minecraft preparation, production grant or physical-site claim. */
    @Test void full256RichEntriesBeyondLegacy96And32KiBSurviveExactJ04ColdReadback()throws Exception{
        var entries=new ArrayList<StaticLocationCatalogEntry>();
        for(int i=0;i<256;i++){String name=String.format(java.util.Locale.ROOT,"large_%04d",i);var unsigned=entry(name,"large_a",0,ZERO);entries.add(entry(name,"large_a",0,StrictCodec.contentDigest(unsigned)));}
        var regions=List.of(new SemanticRegion(REGION,new Revision("large_a"),List.of(box(0,70)),Set.of(new Id("test:priority")),37));
        var unsigned=new CatalogSnapshot(WORLD,SchemaVersion.BASE,BUILD,new Revision("large_a"),ZERO,CatalogState.READY,Instant.parse("2026-11-13T00:00:00Z"),EVIDENCE,regions,entries);
        var source=new CatalogSnapshot(WORLD,SchemaVersion.BASE,BUILD,unsigned.catalog_revision(),StrictCodec.contentDigest(unsigned),unsigned.state(),unsigned.validated_at(),EVIDENCE,regions,entries);
        CatalogValidation.requireSealed(source);byte[] canonical=StrictCodec.encode(source).getBytes(StandardCharsets.UTF_8);
        assertTrue(canonical.length>32768&&canonical.length<=1048576,"real full rich source exceeds legacy packet cap but respects existing 1MiB ceiling");
        var blob=fr.proutlost.director.api.model.BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,canonical);
        Map<fr.proutlost.director.api.model.ResourceId,fr.proutlost.director.api.model.RegionId> boundRegions=Map.of(new fr.proutlost.director.api.model.ResourceId(REGION.value().value()),new fr.proutlost.director.api.model.RegionId(new UUID(0,2)));
        Map<fr.proutlost.director.api.model.ResourceId,fr.proutlost.director.api.model.LocationId> locations=new HashMap<>();var anchors=new ArrayList<AnchorBinding>();
        for(int i=0;i<entries.size();i++){var id=new fr.proutlost.director.api.model.ResourceId(entries.get(i).location_id().value());locations.put(id,new fr.proutlost.director.api.model.LocationId(new UUID(0,2000+i)));anchors.add(new AnchorBinding(id,new fr.proutlost.director.api.model.ResourceId("test:center"),new fr.proutlost.director.api.model.AnchorId(new UUID(0,5000+i))));}
        var blank=new Binding(DIRECTOR_WORLD,new fr.proutlost.director.api.model.ResourceId(WORLD.value()),new fr.proutlost.director.api.model.ResourceId(BUILD.value()),blob.reference().hash(),new fr.proutlost.director.api.model.Fingerprint("0".repeat(64)),boundRegions,locations,anchors);
        var binding=new Binding(blank.directorWorld(),blank.sourceWorld(),blank.sourceBuild(),blank.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(blank),boundRegions,locations,anchors);
        var key=new Key(DIRECTOR_WORLD,binding.sourceWorld(),binding.sourceBuild(),source.catalog_revision().value(),new fr.proutlost.director.api.model.Fingerprint(source.digest().value()),blob.reference().hash(),binding.bindingSha());
        var document=new Document(key,binding,blob);ExecutorService worker=Executors.newSingleThreadExecutor();Path path=temporary.resolve("large-real-j04");
        var store=await(JournalStore.open(path,DIRECTOR_WORLD,OPTIONS));world(store);var view=view(store,DurablePhysicalCatalogViewTest::supportedTestSemantics);var actor=new fr.proutlost.director.api.model.SessionId(UUID.randomUUID());var runtime=runtime(store,view,worker,actor,Set.of(binding));
        try{
            until(runtime,runtime::ready);assertEquals(State.PENDING,runtime.submit(actor,document).state());assertThrows(IllegalStateException.class,view::snapshot,"no partial catalogue publication");
            until(runtime,()->runtime.poll(actor,key).state()==State.DURABLE);var stamp=runtime.poll(actor,key).readback().orElseThrow();assertEquals(source,view.snapshot());
            assertEquals(stamp,runtime.submit(actor,document).readback().orElseThrow(),"same full source retry does not create a second publication");
            var seen=new HashSet<Id>();Id cursor=null;
            while(true){var page=view.query(new CatalogQuery(Set.of(new Id("proutlost:safe_shore")),Set.of(REGION),cursor,64));if(page.isEmpty())break;assertTrue(page.size()<=64);for(var ref:page){assertTrue(seen.add(ref.location_id()),"no duplicate after cursor");assertEquals(entries.stream().filter(e->e.location_id().equals(ref.location_id())).findFirst().orElseThrow(),view.lookup(ref).orElseThrow());}cursor=page.getLast().location_id();}
            assertEquals(256,seen.size(),"complete catalogue, not first 96 entries");
            var read=runtime.readSource(actor,stamp).toCompletableFuture();until(runtime,read::isDone);assertArrayEquals(canonical,await(read).contents().getFirst().bytes());
            var actorThread=Thread.currentThread();var actualRead=await(read);
            var inspected=fr.proutlost.director.bootstrap.PhysicalCatalogReadbackDecoder.read(CompletableFuture.completedFuture(actualRead),stamp,worker,bytes->{
                assertNotSame(actorThread,Thread.currentThread(),"complete real consumer decode stays off actor");return new ContraRealDirectorCatalogImportProbe().describe(bytes);
            });assertEquals(256,await(inspected).locations().size());
            var called=new java.util.concurrent.atomic.AtomicBoolean();var refused=fr.proutlost.director.bootstrap.PhysicalCatalogReadbackDecoder.read(CompletableFuture.completedFuture(new fr.proutlost.director.api.model.BlobReadResult(fr.proutlost.director.api.model.AckStatus.REJECTED,List.of(),List.of())),stamp,worker,bytes->{called.set(true);return bytes.length;});
            assertThrows(ExecutionException.class,()->await(refused));assertFalse(called.get(),"refused source cannot invoke the consumer decoder");
            runtime.close();await(store.closeAsync());var cold=await(JournalStore.open(path,DIRECTOR_WORLD,OPTIONS));var restored=view(cold,DurablePhysicalCatalogViewTest::supportedTestSemantics);var coldActor=new fr.proutlost.director.api.model.SessionId(UUID.randomUUID());var recovered=runtime(cold,restored,worker,coldActor,Set.of(binding));
            try{until(recovered,recovered::ready);assertEquals(source,restored.snapshot());var coldStamp=recovered.poll(coldActor,key).readback().orElseThrow();var reread=recovered.readSource(coldActor,coldStamp).toCompletableFuture();until(recovered,reread::isDone);assertArrayEquals(canonical,await(reread).contents().getFirst().bytes());assertEquals(State.DURABLE,recovered.submit(coldActor,document).state());}
            finally{recovered.close();await(cold.closeAsync());}
            System.out.println("D0_FULL_RICH_TRANSFER_J04_PASS locations=256 bytes="+canonical.length+" scope=SYNTHETIC_METADATA_REAL_J04_NOT_MINECRAFT_PRODUCTION");
        }finally{worker.shutdown();assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));}
    }

    @Test void completeCatalogueDecodeKeepsItemDepthComplexityAndClosedFieldGuards(){
        String canonical=StrictCodec.encode(snapshot("revision_a",0));
        assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(canonical.substring(0,canonical.length()-1)+",\"entries\":[]}",CatalogSnapshot.class),"duplicate root key");
        String badLast=canonical.replace("\"location_id\":\"test:two\"","\"unknown_late_field\":true,\"location_id\":\"test:two\"");
        assertNotEquals(canonical,badLast);assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(badLast,CatalogSnapshot.class),"bad final record cannot return an accepted partial snapshot");
        int start=canonical.indexOf("\"entries\":")+10,end=canonical.indexOf(",\"regions\":");assertTrue(start>9&&end>start);
        String many="["+String.join(",",Collections.nCopies(Limits.MAX_ITEMS+1,"{}"))+"]";
        var entriesCap=assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(canonical.substring(0,start)+many+canonical.substring(end),CatalogSnapshot.class));assertTrue(entriesCap.getMessage().contains("Array cap"));
        String row="["+String.join(",",Collections.nCopies(1024,"0"))+"]";String complex="[{\"noise\":["+String.join(",",Collections.nCopies(16,row))+"]}]";
        var complexity=assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(canonical.substring(0,start)+complex+canonical.substring(end),CatalogSnapshot.class));assertTrue(complexity.getMessage().contains("JSON complexity cap"),"per-item work ceiling unchanged");
        String deep="[{\"nested\":"+"[".repeat(Limits.MAX_DEPTH)+"0"+"]".repeat(Limits.MAX_DEPTH)+"}]";
        var depth=assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(canonical.substring(0,start)+deep+canonical.substring(end),CatalogSnapshot.class));assertTrue(depth.getMessage().contains("JSON complexity cap"));
        assertThrows(IllegalArgumentException.class,()->StrictCodec.decode(" ".repeat(Limits.MAX_JSON_BYTES+1),CatalogSnapshot.class),"whole document byte ceiling unchanged");
    }
}
