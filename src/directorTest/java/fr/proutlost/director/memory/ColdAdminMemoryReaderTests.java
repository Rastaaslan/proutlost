package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.bootstrap.DirectorMetadataMemoryReader;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic exact J07 reads over real J04, independent of Minecraft/J01 or Contra acceptance. */
public final class ColdAdminMemoryReaderTests {
    private static final WorldId WORLD=new WorldId(new UUID(0x5030303841444d49L,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("test:cold_admin_original");
    private static final StoreOptions OPTIONS=new StoreOptions(4_194_400,4_194_304,16_777_216,33_554_432,32,0,128);
    private static int checks;
    private static boolean beforeCursor,spatialProbe;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static UUID id(int domain,int n){return new UUID(domain,n);}
    private static AggregateHeader header(UUID id,long revision){return new AggregateHeader(1,id,new Revision(revision),new Sequence(0),new Tick(0));}
    private static ConfigView profile(Path file)throws Exception{
        var raw=((JsonValue.ObjectValue)((JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(file))).values().get("values")).values();
        Map<String,JsonValue.Number> values=new HashMap<>();raw.forEach((k,v)->values.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),BlobContent.hash(Files.readAllBytes(file)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    private static void append(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs)throws Exception{
        check(await(store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs))).ackStatus()==AckStatus.DURABLE,"actual J04 fixture transaction");
    }
    private static DirectorWorldState world(JournalStore store,ConfigView config)throws Exception{
        var engine=new EngineControlState(1,new Revision(0),new Tick(0),new Tick(0),new UnitValue(0),new Tick(0),Optional.empty(),new UnitValue(.5));
        var value=new DirectorWorldState(header(WORLD.value(),1),WORLD,store.session().storeGeneration(),new Tick(0),RunControl.PAUSED,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,
            new ResourceId("test:cold_admin_profile"),config.provenanceHash(),config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),engine);
        append(store,List.of(new DurableAggregate(new WorldAggregate(value))),List.of());return value;
    }
    private static void catalogue(JournalStore store)throws Exception{
        var region=new RegionId(id(1,1));var anchor=new AnchorId(id(2,1));var a=new LocationId(id(3,1));var b=new LocationId(id(3,2));
        var regionBounds=new ImportBounds(0,0,0,16,256,16);var first=new ImportBounds(0,0,0,8,256,16);var second=new ImportBounds(8,0,0,16,256,16);
        var blank=new LocationImport(1,WORLD,SOURCE,new Revision(1),new Fingerprint("0".repeat(64)),
            List.of(new ImportedRegion(region,DIM,regionBounds,Set.of(new TagId("proutlost:clearing")))),
            List.of(new ImportedLocation(a,"first",Set.of(region),DIM,first,Set.of(new TagId("proutlost:structure"))),new ImportedLocation(b,"unanchored",Set.of(region),DIM,second,Set.of(new TagId("proutlost:minor_poi")))),
            List.of(new ImportedAnchor(anchor,a,DIM,new ImportPosition(1,64,1),Set.of(new TagId("proutlost:anchor_center")))));
        var input=new LocationImport(1,WORLD,SOURCE,blank.exportRevision(),StandaloneLocationImporter.digest(blank),blank.regions(),blank.locations(),blank.anchors());
        var factId=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+WORLD.value()+"/"+SOURCE.value()+"/1/"+input.exportDigest().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var fact=new DirectorFact(factId,new FactType("proutlost_director:location_import_applied"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("source_namespace",new JsonValue.Text(SOURCE.value()),"export_revision",new JsonValue.Text("1"),"export_digest",new JsonValue.Text(input.exportDigest().value()),
                "location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(LocationImport.class).encode(input)))),true,Optional.empty());
        var records=new ArrayList<DurableRecord>();
        records.add(new DurableAggregate(new RegionAggregate(new RegionRecord(new AggregateHeader(1,region.value(),new Revision(1),new Sequence(0),new Tick(250)),region,DIM,RegionStatus.ACTIVE,
            new SupportShape(List.of(new Aabb(DIM,0,0,0,16,256,16)),List.of(),List.of()),Set.of(anchor),new UnitValue(.7),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(region.value()))))));
        records.add(new DurableAggregate(new RegionMemoryAggregate(new RegionMemory(header(region.value(),1),region,Map.of(),new Tick(0),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of())))));
        records.add(new DurableAggregate(new AnchorAggregate(new AnchorRecord(header(anchor.value(),1),anchor,Optional.of(a),Set.of(region),DIM,new BlockPosition(DIM,1,64,1),"region.camp_radius_blocks",Set.of(new TagId("proutlost:anchor_center")),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true))));
        for(var l:input.locations()){var bounds=l.bounds();records.add(new DurableAggregate(new LocationAggregate(new LocationRecord(header(l.locationId().value(),1),l.locationId(),SOURCE,l.externalId(),DIM,
            new Aabb(DIM,bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxXExclusive(),bounds.maxYExclusive(),bounds.maxZExclusive()),l.tags(),l.locationId().equals(a)?Set.of(anchor):Set.of(),new Revision(1),true))));}
        records.add(new DurableFact(fact));append(store,records,List.of());
    }
    private static AdminQuery query(SessionId actor,AdminFilter filter,Optional<String> cursor){return new AdminQuery(actor,new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,filter,cursor,Optional.empty(),1);}
    private static AdminReadResult until(ColdAdminMemoryReader reader,JournalStore store,AdminQuery query,ActorIdentity actor,DirectorWorldState world,MemorySettings settings)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);AdminReadResult result;
        do{result=reader.query(query,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings,world.simTick().plus(1000));if(result.status()==AdminStatus.SUCCEEDED){
            if(query.cursor().isEmpty())check(result.snapshot().orElseThrow().capturedTick().equals(world.simTick().plus(1000)),"actual actor capture time differs from last durable WORLD control");
            return result;}Thread.sleep(2);}while(System.nanoTime()<end);
        throw new AssertionError("exact cold read never became available: "+result);
    }
    public static void main(String[] args)throws Exception{
        beforeCursor=args.length>2&&args[2].equals("beforeCursor");spatialProbe=args.length>2&&args[2].equals("spatialProbe");
        Path root=Path.of(args[1]);if(Files.exists(root))throw new IllegalArgumentException("fresh declared QA folder required");Files.createDirectories(root);
        var config=profile(Path.of(args[0]));var settings=new MemorySettings(config,false);var store=await(JournalStore.open(root.resolve("store"),WORLD,OPTIONS));
        try{
            var world=world(store,config);catalogue(store);var actorId=new SessionId(id(5,1));var actor=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
            var reader=new ColdAdminMemoryReader(store,new DirectorMetadataMemoryReader(store),WORLD,8,16,22000);
            var filter=new RegionFilter(Optional.of(new RegionId(id(1,1))),Set.of());var request=query(actorId,filter,Optional.empty());
            check(reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"first exact request queues bounded IO without claiming empty success");
            var result=until(reader,store,request,actor,world,settings);var data=(RegionAdminData)result.data().getFirst().data();
            check(data.region().header().updatedTick().value()==250&&world.simTick().value()==0&&result.snapshot().orElseThrow().capturedTick().value()==1000,"newer durable region is not stamped with old WORLD tick");
            check(data.region().importance().value()==.7&&data.anchors().size()==1,"cold region and real anchor returned from authoritative J04");
            check(data.locations().size()==2,"source-bound unanchored location hydrated independently of J01 cache");
            check(data.tags().containsAll(Set.of(new TagId("proutlost:clearing"),new TagId("proutlost:minor_poi"))),"authored region and location tags recovered through exact retained source");
            check(result.snapshot().orElseThrow().lastDurableSequence().equals(store.metadataSnapshot().orElseThrow().cut()),"reply stamped with revalidated current common cut");
            var denied=new ActorIdentity(actorId,ActorKind.SERVER,Optional.empty(),Optional.empty(),3,true);
            check(reader.query(request,denied,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"permission rechecked after IO completion");
            var missing=query(actorId,new RegionFilter(Optional.of(new RegionId(id(1,999))),Set.of()),Optional.empty());
            var absent=until(reader,store,missing,actor,world,settings);
            check(absent.data().isEmpty()&&absent.snapshot().isPresent(),"authoritative absent exact primary can return proven empty success");
            var target=new RegionId(id(1,1));append(store,List.of(new DurableAggregate(new RegionMemoryAggregate(new RegionMemory(header(target.value(),2),target,Map.of(),new Tick(9),new Tick(0),Set.of(new ResourceId("test:actual_revision_two")),Set.of(),new RegionActivityState(Map.of(),Map.of()))))),List.of());
            check(reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"changed selected head invalidates old async body");
            var refreshed=until(reader,store,request,actor,world,settings);
            check(((RegionAdminData)refreshed.data().getFirst().data()).memory().header().revision().value()==2,"retry returns actual changed region memory");
            playerPagination(reader,store,world,settings,actor);
            reader.disconnect(actorId);check(reader.query(request,denied,world,store.metadataSnapshot().orElseThrow().cut(),settings).data().isEmpty(),"revoked actor receives no retained cached data");
            reader.close();check(reader.query(request,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"closed reader exposes no cache");
        }finally{await(store.closeAsync());}
        System.out.println("PASS ColdAdminMemoryReaderTests assertions="+checks+" scope=actual_J04_synthetic_no_Minecraft_no_Contra_no_RUN7 before_cursor="+beforeCursor+" spatial_probe="+spatialProbe);
    }
    private static void playerPagination(ColdAdminMemoryReader reader,JournalStore store,DirectorWorldState world,MemorySettings settings,ActorIdentity actor)throws Exception{
        var player=new PlayerId(id(7,1));var operational=new PlayerOperationalMemory(header(player.value(),1),player,new DurationTicks(500),Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(.2),Map.of(),Optional.empty(),new Epoch(0));
        var blob=BlobContent.capture(new ResourceId("test:synthetic_player_outcome"),1,new byte[]{1});List<DurableRecord> records=new ArrayList<>();records.add(new DurableAggregate(new PlayerOperationalAggregate(operational)));
        for(int n=0;n<64;n++){var memory=new MemoryId(id(8,n));records.add(new DurableAggregate(new PlayerSignificantAggregate(new PlayerSignificantMemory(header(memory.value(),1),memory,player,new EventInstanceId(id(9,n)),new DefinitionId("test:significant_"+n),"witness",PerceptionStatus.RECEIVED,blob.reference(),Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(n)))));}
        append(store,records,List.of(blob));var filter=new PlayerFilter(player);var result=until(reader,store,query(actor.session(),filter,Optional.empty()),actor,world,settings);
        check(result.nextCursor().isPresent(),"64 significant memories are paged under the unchanged22k wire bound");
        var frozen=result.snapshot().orElseThrow();
        world=new DirectorWorldState(header(world.worldId().value(),world.header().revision().value()+1),world.worldId(),world.storeGeneration(),world.simTick().plus(1),world.runControl(),world.healthMode(),world.pacingState(),world.tension(),world.intensityProfileId(),world.catalogHash(),world.configHash(),world.bootEpoch(),world.lastCheckpointSeq(),world.engineControl());
        append(store,List.of(new DurableAggregate(new WorldAggregate(world))),List.of());
        if(!beforeCursor){
            var unrelated=new RegionId(id(1,1));var key=new AggregateKey(AggregateKind.REGION_MEMORY,unrelated.value());
            var revision=store.metadataRevision(store.metadataSnapshot().orElseThrow(),key).plus(1);
            append(store,List.of(new DurableAggregate(new RegionMemoryAggregate(new RegionMemory(header(unrelated.value(),revision.value()),unrelated,Map.of(),new Tick(11),new Tick(0),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))))),List.of());
        }
        if(spatialProbe){
            var next=new AdminQuery(actor.session(),new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,filter,result.nextCursor(),Optional.of(frozen.snapshotRevision()),1);
            var observed=reader.query(next,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings);
            System.out.println("EXACT_PLAYER_SPATIAL_CENSUS_PROBE expected=SUCCEEDED actual="+observed.status()+" unrelated_region_memory_changed=true");
            check(observed.status()==AdminStatus.SUCCEEDED&&observed.snapshot().orElseThrow().equals(frozen),"exact PLAYER cursor must ignore unrelated no-camp RegionMemory change and keep original stamp");return;
        }
        if(beforeCursor){
            var next=new AdminQuery(actor.session(),new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,filter,result.nextCursor(),Optional.of(frozen.snapshotRevision()),1);
            check(reader.query(next,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"BEFORE normal64-memory cursor is rejected solely by unrelated WORLD revision");
            System.out.println("BEFORE_NORMAL_CURSOR_FAILURE old_snapshot_revision="+frozen.snapshotRevision().value()+" current_world_revision="+world.header().revision().value()+" status=DEFERRED");return;
        }
        Set<MemoryId> seen=new HashSet<>();int pages=0;
        do{
            pages++;var packet=new AdminSnapshot(new RequestId(UUID.randomUUID()),result.snapshot().orElseThrow().snapshotRevision(),result.snapshot().orElseThrow().capturedTick(),result.snapshot().orElseThrow().lastDurableSequence(),result.data(),result.nextCursor(),NativeReadCommandFence.supported());
            check(result.snapshot().orElseThrow().equals(frozen),"normal64-memory cursor keeps its original stamp across WORLD advance");
            check(new ModelCodec<>(AdminSnapshot.class).encode(packet).length<=22000,"actual serialized reply obeys wire cap");
            for(var row:result.data())for(var memory:((PlayerAdminData)row.data()).significant())check(seen.add(memory.memoryId()),"significant memory appears exactly once across pages");
            if(result.nextCursor().isEmpty())break;
            var foreign=new SessionId(id(5,2));var foreignActor=new ActorIdentity(foreign,ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
            check(reader.query(query(foreign,filter,result.nextCursor()),foreignActor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()!=AdminStatus.SUCCEEDED,"cursor is private to authenticated actor session");
            var next=new AdminQuery(actor.session(),new RequestId(UUID.randomUUID()),AdminPanel.MEMORY,filter,result.nextCursor(),Optional.of(frozen.snapshotRevision()),1);
            result=until(reader,store,next,actor,world,settings);
        }while(pages<20);
        check(seen.size()==64&&result.nextCursor().isEmpty(),"all64 significant records delivered without truncation or missing continuation");
        var added=new MemoryId(id(8,64));
        append(store,List.of(new DurableAggregate(new PlayerSignificantAggregate(new PlayerSignificantMemory(header(added.value(),1),added,player,new EventInstanceId(id(9,64)),new DefinitionId("test:new_significant"),"witness",PerceptionStatus.RECEIVED,blob.reference(),Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(1000))))),List.of());
        var freshQuery=query(actor.session(),filter,Optional.empty());
        check(reader.query(freshQuery,actor,world,store.metadataSnapshot().orElseThrow().cut(),settings).status()==AdminStatus.DEFERRED,"new significant head invalidates normal recent-memory census despite unchanged captured heads");
        var latest=until(reader,store,freshQuery,actor,world,settings);
        check(((PlayerAdminData)latest.data().getFirst().data()).significant().stream().anyMatch(m->m.memoryId().equals(added)),"fresh normal read returns newly appended significant memory");
    }
}
