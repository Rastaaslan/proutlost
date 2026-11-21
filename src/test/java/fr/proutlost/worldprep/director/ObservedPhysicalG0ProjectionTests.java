package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import fr.proutlost.contract.director.CatalogSnapshot;
import fr.proutlost.director.api.model.AckStatus;
import fr.proutlost.contract.director.Vocabulary.*;
import fr.proutlost.director.api.codec.LocationTransferContract;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeLocationGenerationPlan;
import fr.proutlost.director.persistence.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Declared synthetic producer witnesses; actual G0/J04 contracts and cold
 * process readback. No Minecraft/Anvil/native author reception is claimed. */
public final class ObservedPhysicalG0ProjectionTests {
    private static final UUID WORLD_UUID=new UUID(18112026,810);
    private static final WorldId WORLD=new WorldId(WORLD_UUID);
    private static final Id SOURCE_WORLD=new Id("fixture:observed_world");
    private static final Id BUILD=new Id("fixture:observed_build");
    private static final Id OVERWORLD=new Id("minecraft:overworld"),NETHER=new Id("minecraft:the_nether");
    private static final ModelCodec<LocationImport> G0=new ModelCodec<>(LocationImport.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    private static int assertions;
    private ObservedPhysicalG0ProjectionTests(){}
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static void rejects(Runnable code,String why){try{code.run();throw new AssertionError("accepted "+why);}catch(IllegalArgumentException expected){check(true,why);}}
    private static Bounds box(Id dimension,int x,int y,int z,int x2,int y2,int z2){return new Bounds(dimension,new fr.proutlost.contract.director.BlockPos(x,y,z),new fr.proutlost.contract.director.BlockPos(x2,y2,z2));}
    private static CatalogSnapshot catalog(Id world,Id build,String revision,List<List<Bounds>> geometry,boolean reverse){
        var regions=new ArrayList<SemanticRegion>();var sites=new ArrayList<PhysicalCatalogExporter.Site>();
        for(int i=0;i<geometry.size();i++){
            var id=new Id("fixture:observed_location_"+i);var region=new SemanticRegionId(new Id("fixture:observed_region_"+i));
            var rev=new fr.proutlost.contract.director.Revision(revision);regions.add(new SemanticRegion(region,rev,geometry.get(i),Set.of(),0));
            var evidence=new EvidenceRef(new Id("fixture:synthetic_loaded_"+i),new Digest("a".repeat(64)),new Id("proutlost:native_loaded_block_state_v1"));
            var disk=new EvidenceRef(new Id("fixture:synthetic_disk_"+i),evidence.digest(),new Id("proutlost:anvil_readback_v1"));
            sites.add(new PhysicalCatalogExporter.Site(id,rev,SourceType.worldprep_prepared_site,null,new Id("proutlost:observed_existing_cells"),
                geometry.get(i),Set.of(region),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(evidence),List.of(disk),Set.of(),Set.of()));
        }
        if(reverse){Collections.reverse(regions);Collections.reverse(sites);}
        var exported=PhysicalCatalogExporter.export(world,build,new fr.proutlost.contract.director.Revision(revision),Instant.parse("2026-11-18T00:00:00Z"),regions,sites,site->{});
        check(exported.json()!=null,"actual PhysicalCatalogExporter seals declared synthetic source");return StrictCodec.decode(exported.json(),CatalogSnapshot.class);
    }
    private static String sha(CatalogSnapshot value){return Digests.sha256(StrictCodec.encode(value)).value();}
    private static CatalogSnapshot reseal(CatalogSnapshot original,CatalogState state,List<StaticLocationCatalogEntry> entries){
        var raw=new CatalogSnapshot(original.world_id(),original.schema_version(),original.build_id(),original.catalog_revision(),new Digest("0".repeat(64)),state,original.validated_at(),original.validation_evidence(),original.regions(),entries);
        return new CatalogSnapshot(raw.world_id(),raw.schema_version(),raw.build_id(),raw.catalog_revision(),StrictCodec.contentDigest(raw),state,raw.validated_at(),raw.validation_evidence(),raw.regions(),entries);
    }
    private static void pure(){
        var geometry=List.of(List.of(box(OVERWORLD,0,64,0,2,66,2),box(OVERWORLD,100,64,100,102,66,102)),List.of(box(NETHER,-5,20,-5,-3,22,-3)));
        var d0=catalog(SOURCE_WORLD,BUILD,"r1",geometry,false);var projected=ObservedPhysicalG0Projection.project(WORLD_UUID,1,d0,sha(d0));var input=projected.input();
        check(input.regions().size()==3&&input.locations().size()==3&&input.anchors().isEmpty()&&projected.mappings().size()==3,"one exact region/location per box; no fabricated anchors");
        check(input.sourceNamespace().equals(ObservedPhysicalG0Projection.SOURCE_NAMESPACE)&&!input.sourceNamespace().value().equals("proutlost:worldprep_structures"),"separate complete observed-geometry source namespace");
        for(var mapping:projected.mappings()){
            var location=input.locations().stream().filter(x->x.locationId().equals(mapping.locationId())).findFirst().orElseThrow();
            var region=input.regions().stream().filter(x->x.regionId().equals(mapping.regionId())).findFirst().orElseThrow();var b=mapping.geometry();
            check(location.dimension().value().equals(b.dimension_id().value())&&region.dimension().equals(location.dimension()),"each dimension is preserved independently");
            check(location.bounds().minX()==b.min().x()&&location.bounds().maxXExclusive()==b.max().x()+1&&location.bounds().maxYExclusive()==b.max().y()+1&&location.bounds().maxZExclusive()==b.max().z()+1,"inclusive to exclusive conversion preserves exactly observed cells");
            check(location.bounds().equals(region.bounds())&&location.regionIds().equals(Set.of(region.regionId()))&&location.tags().isEmpty()&&region.tags().isEmpty(),"paired membership exact; all authored tags remain absent");
            check(mapping.sourceWorld().equals(d0.world_id())&&mapping.sourceBuild().equals(d0.build_id())&&mapping.catalogRevision().equals(d0.catalog_revision()),"source identity remains explicit provenance");
        }
        check(input.locations().stream().noneMatch(x->x.bounds().minX()<=50&&x.bounds().maxXExclusive()>50),"disconnected boxes never acquire their unwitnessed bounding union");
        check(G0.decode(G0.encode(input)).equals(input)&&input.exportDigest().equals(LocationTransferContract.digest(input)),"actual G0 codec and canonical digest round-trip");
        var changed=catalog(SOURCE_WORLD,new Id("fixture:next_build"),"r2",geometry,true);var next=ObservedPhysicalG0Projection.project(WORLD_UUID,2,changed,sha(changed));
        check(input.regions().stream().map(ImportedRegion::regionId).toList().equals(next.input().regions().stream().map(ImportedRegion::regionId).toList())&&input.locations().stream().map(ImportedLocation::locationId).toList().equals(next.input().locations().stream().map(ImportedLocation::locationId).toList()),"build/revision/discovery order do not change technical identities");
        check(input.locations().stream().map(ImportedLocation::externalId).toList().equals(next.input().locations().stream().map(ImportedLocation::externalId).toList()),"stable external identities across source revisions");
        var foreign=catalog(new Id("fixture:other_world"),BUILD,"r1",geometry,false);check(!input.locations().getFirst().locationId().equals(ObservedPhysicalG0Projection.project(WORLD_UUID,1,foreign,sha(foreign)).input().locations().getFirst().locationId()),"different physical source world cannot alias an identity");
        var sameCoordinates=catalog(SOURCE_WORLD,BUILD,"r1",List.of(List.of(box(OVERWORLD,0,64,0,0,64,0)),List.of(box(NETHER,0,64,0,0,64,0))),false);
        check(ObservedPhysicalG0Projection.project(WORLD_UUID,1,sameCoordinates,sha(sameCoordinates)).input().locations().size()==2,"same coordinates in distinct dimensions are distinct observed boxes");
        var movedGeometry=List.of(List.of(box(OVERWORLD,0,64,0,3,66,2),geometry.getFirst().get(1)),geometry.get(1));
        var moved=catalog(SOURCE_WORLD,BUILD,"r1",movedGeometry,false);var movedProjection=ObservedPhysicalG0Projection.project(WORLD_UUID,1,moved,sha(moved));
        var unchanged=projected.mappings().stream().filter(m->m.geometry().equals(geometry.getFirst().get(1))).findFirst().orElseThrow();
        check(movedProjection.mappings().stream().anyMatch(m->m.geometry().equals(unchanged.geometry())&&m.regionId().equals(unchanged.regionId())&&m.locationId().equals(unchanged.locationId())),"moving one support never renumbers unchanged supports");
        var priorChanged=projected.mappings().stream().filter(m->m.geometry().equals(geometry.getFirst().getFirst())).findFirst().orElseThrow();
        check(movedProjection.mappings().stream().noneMatch(m->m.locationId().equals(priorChanged.locationId())),"changed observed geometry cannot retain the old support identity");
        rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,1,d0,"f".repeat(64)),"wrong complete catalog SHA");
        rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,-1,d0,sha(d0)),"negative G0 revision");
        var unsealed=new CatalogSnapshot(d0.world_id(),d0.schema_version(),d0.build_id(),d0.catalog_revision(),new Digest("0".repeat(64)),d0.state(),d0.validated_at(),d0.validation_evidence(),d0.regions(),d0.entries());
        rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,1,unsealed,sha(unsealed)),"unsealed physical source");
        rejects(()->StrictCodec.decode(StrictCodec.encode(d0).replace("\"READY\"","\"NOT_READY\""),CatalogSnapshot.class),"closed source contract refuses non-READY state");
        var one=d0.entries().getFirst();var semanticRaw=new StaticLocationCatalogEntry(one.world_id(),one.schema_version(),one.build_id(),one.location_id(),one.revision(),one.source_type(),one.structure_instance_id(),one.semantic_region_ids(),one.geometry(),one.tags(),one.anchors(),one.surfaces(),one.capabilities(),one.protection(),Set.of(Channel.TEXT_UI),one.recovery_anchors(),one.provenance(),one.validation_evidence(),new Digest("0".repeat(64)));
        var semantic=new StaticLocationCatalogEntry(semanticRaw.world_id(),semanticRaw.schema_version(),semanticRaw.build_id(),semanticRaw.location_id(),semanticRaw.revision(),semanticRaw.source_type(),semanticRaw.structure_instance_id(),semanticRaw.semantic_region_ids(),semanticRaw.geometry(),semanticRaw.tags(),semanticRaw.anchors(),semanticRaw.surfaces(),semanticRaw.capabilities(),semanticRaw.protection(),semanticRaw.presentation_channels(),semanticRaw.recovery_anchors(),semanticRaw.provenance(),semanticRaw.validation_evidence(),StrictCodec.contentDigest(semanticRaw));
        var entries=new ArrayList<>(d0.entries());entries.set(0,semantic);var semanticCatalog=reseal(d0,d0.state(),entries);rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,1,semanticCatalog,sha(semanticCatalog)),"authored channels cannot be projected as geometry-only");
        var repeated=catalog(SOURCE_WORLD,BUILD,"r1",List.of(List.of(geometry.getFirst().getFirst()),List.of(geometry.getFirst().getFirst())),false);rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,1,repeated,sha(repeated)),"physical aliases are not inferred from duplicate boxes");
        var tooLarge=catalog(SOURCE_WORLD,BUILD,"r1",List.of(List.of(box(OVERWORLD,0,64,0,255,65,255))),false);rejects(()->ObservedPhysicalG0Projection.project(WORLD_UUID,1,tooLarge,sha(tooLarge)),"complete observed cell volume exceeds32768");
        System.out.println("PASS ObservedPhysicalG0ProjectionTests pureAssertions="+assertions+" scope=SYNTHETIC_D0_G0_CODEC_GEOMETRY_NO_NATIVE_WITNESS");
    }
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(60,TimeUnit.SECONDS);}
    private static void commit(JournalStore store,DurableRecord row,List<BlobContent> blobs)throws Exception{
        var batch=fr.proutlost.director.runtime.ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(row),blobs);
        check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"actual J04 transaction durable");
    }
    private static LocationImport many(){
        var geometry=new ArrayList<List<Bounds>>();for(int i=0;i<32;i++){var boxes=new ArrayList<Bounds>();for(int j=0;j<8;j++){int x=(i*8+j)*3;boxes.add(box(OVERWORLD,x,64,0,x,64,0));}geometry.add(boxes);}
        var source=catalog(SOURCE_WORLD,BUILD,"many-r1",geometry,false);return ObservedPhysicalG0Projection.project(WORLD_UUID,1,source,sha(source)).input();
    }
    private static void inspect(JournalStore store,LocationImport input)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();check(await(store.readMetadataSource(snapshot,input.sourceNamespace())).orElseThrow().equals(input),"J04 complete original projected G0 readback exact");
        Set<AggregateKind> kinds=Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY,AggregateKind.LOCATION,AggregateKind.ANCHOR);Optional<AggregateKey> after=Optional.empty();int rows=0;
        do{var page=store.metadataKeysPage(snapshot,kinds,after,96).orElseThrow();check(page.keys().size()<=96,"actual J04 head discovery bounded96");
            var query=new StoreQuery(WORLD,Set.copyOf(page.keys()),new Sequence(0),Optional.empty(),96);var read=await(store.readMetadata(snapshot,query));ReadContracts.validate(read,query,store.session());check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"all selected native metadata heads available at same pinned cut");
            for(var row:read.page().orElseThrow().aggregates()){
                check(row.state().header().updatedTick().value()==42,"actual metadata header uses frozen proposal time");rows++;
                if(row.state() instanceof LocationAggregate location){var expected=input.locations().stream().filter(x->x.locationId().equals(location.value().locationId())).findFirst().orElseThrow();check(location.value().bounds().minX()==expected.bounds().minX()&&location.value().bounds().maxXExclusive()==expected.bounds().maxXExclusive()&&location.value().tags().isEmpty()&&location.value().anchorIds().isEmpty(),"actual location geometry and empty semantics preserved");}
            }after=page.next();
        }while(after.isPresent());check(rows==768&&store.metadataCachedEntries()<=512,"all256boxes include paired region/memory/location without expanding cache512");
    }
    private static void produce(Path root)throws Exception{
        Files.createDirectory(root);pure();var input=many();Files.write(root.resolve("source.json"),G0.encode(input));var prepared=LocationTransferContract.prepare(input);check(prepared.pages().size()>1,"real projected source requires multipage transport");
        var store=await(JournalStore.open(root.resolve("j04"),WORLD,OPTIONS));try{
            for(var fragment:prepared.pages()){
                var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,LocationTransferContract.fragment(prepared.manifest(),fragment));
                commit(store,new DurableMetadataSourcePage(prepared.manifest(),fragment.pageIndex(),blob.reference()),List.of(blob));
            }
            var snapshot=store.metadataSnapshot().orElseThrow();var planner=new DirectorNativeLocationGenerationPlan(WORLD,input,null,new Tick(42),key->store.metadataOwner(snapshot,key));
            for(int i=0;i<planner.pageCount();i++){
                var proposed=planner.planPage(planner.pageKeys(i),Map.of());var page=new MetadataGenerationPage(LocationTransferContract.identity(prepared.manifest()),input.sourceNamespace(),input.exportRevision(),input.exportDigest(),i,planner.pageCount(),proposed.states(),proposed.beforeRevisions());
                var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(page));commit(store,new DurableMetadataStage(prepared.manifest(),i,planner.pageCount(),blob.reference()),List.of(blob));
            }
            check(store.metadataSnapshot().orElseThrow().generations().isEmpty(),"staged complete projection remains invisible before atomic pivot");commit(store,new DurableMetadataActivation(prepared.manifest(),Optional.empty(),planner.pageCount()),List.of());inspect(store,input);await(store.checkpoint());await(store.evictVerifiedCheckpointRows());
        }finally{await(store.closeAsync());}
        System.out.println("PASS ObservedPhysicalG0ProjectionTests produceAssertions="+assertions+" multipage=256BOXES_768HEADS scope=ACTUAL_J04_SYNTHETIC_NOT_MINECRAFT_ANVIL");
    }
    private static void reopen(Path root)throws Exception{
        var input=G0.decode(Files.readAllBytes(root.resolve("source.json")));var store=await(JournalStore.open(root.resolve("j04"),WORLD,OPTIONS));try{inspect(store,input);}finally{await(store.closeAsync());}
        System.out.println("PASS ObservedPhysicalG0ProjectionTests coldAssertions="+assertions+" pid="+ProcessHandle.current().pid()+" scope=NEW_JVM_ACTUAL_J04_NOT_NATIVE_MINECRAFT");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("produce|reopen NEW_PRIVATE_ROOT");var root=Path.of(args[1]);System.out.println("JVM_IDENTITY mode="+args[0]+" pid="+ProcessHandle.current().pid()+" startMillis="+java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());if(args[0].equals("produce"))produce(root);else if(args[0].equals("reopen"))reopen(root);else throw new IllegalArgumentException("mode");
    }
}
