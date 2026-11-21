package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real worldless J04 cold data; no Minecraft/human/physical receipt is claimed. */
public final class MetadataClockRecoveryTests {
    private MetadataClockRecoveryTests(){}
    private static final WorldId WORLD=new WorldId(new UUID(18018,1));
    private static final ResourceId SOURCE=new ResourceId("fixture:metadata_clock");
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final Tick TIME=new Tick(4242);
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    private static final ModelCodec<DurableRecord> RECORD=new ModelCodec<>(DurableRecord.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static int assertions,transactions;
    private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> pending)throws Exception{return pending.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static JournalStore open(Path root)throws Exception{return await(JournalStore.open(root,WORLD,OPTIONS));}
    private static void close(JournalStore store)throws Exception{await(store.closeAsync());}
    private static CommitAck commit(JournalStore store,DurableRecord record,List<BlobContent> blobs)throws Exception {
        return await(store.append(new DurableBatch(new TransactionId(new UUID(18019,++transactions)),store.session().storeGeneration(),
            Map.of(),List.of(record),blobs,Optional.empty(),BlobContent.hash(RECORD.encode(record)))));
    }
    private static void durable(JournalStore store,DurableRecord record,List<BlobContent> blobs,String label)throws Exception {
        check(commit(store,record,blobs).ackStatus()==AckStatus.DURABLE,label);
    }
    private static LocationImport source(long revision) {
        var location=new ImportedLocation(new LocationId(new UUID(18020,1)),"synthetic-clock-site",Set.of(),DIM,
            new ImportBounds(0,0,0,1,2,1),Set.of());
        var unsigned=new LocationImport(1,WORLD,SOURCE,new Revision(revision),new Fingerprint("0".repeat(64)),List.of(),List.of(location),List.of());
        return new LocationImport(1,WORLD,SOURCE,unsigned.exportRevision(),StandaloneLocationImporter.digest(unsigned),unsigned.regions(),unsigned.locations(),unsigned.anchors());
    }
    private static MetadataGenerationPage page(LocationTransferManifest transfer,Tick clock){
        var input=source(transfer.exportRevision().value());var location=input.locations().getFirst();
        var state=new LocationAggregate(new LocationRecord(new AggregateHeader(1,location.locationId().value(),new Revision(1),new Sequence(0),clock),
            location.locationId(),SOURCE,location.externalId(),DIM,new Aabb(DIM,0,0,0,1,2,1),Set.of(),Set.of(),input.exportRevision(),true));
        return new MetadataGenerationPage(LocationTransfer.identity(transfer),SOURCE,input.exportRevision(),input.exportDigest(),0,1,List.of(state),Map.of(state.key(),new Revision(0)));
    }
    private static void world(JournalStore store)throws Exception {
        var tick=new Tick(1);var header=new AggregateHeader(1,WORLD.value(),new Revision(1),new Sequence(0),tick);
        var engine=new EngineControlState(1,new Revision(0),tick,tick,new UnitValue(0),tick,Optional.empty(),new UnitValue(0));
        var world=new DirectorWorldState(header,WORLD,store.session().storeGeneration(),tick,RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
            PacingState.REST,GlobalTension.CALM,new ResourceId("fixture:clock_profile"),new Fingerprint("0".repeat(64)),new Fingerprint("1".repeat(64)),
            store.session().bootEpoch(),new Sequence(0),engine);
        durable(store,new DurableAggregate(new WorldAggregate(world)),List.of(),"baseline WORLD tick1 durable");
    }
    private static void stage(JournalStore store,LocationTransfer.Prepared transfer,Tick clock)throws Exception {
        for(var fragment:transfer.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(fragment.bytesBase64()));
            durable(store,new DurableMetadataSourcePage(transfer.manifest(),fragment.pageIndex(),blob.reference()),List.of(blob),"raw fragment durable");
        }
        var page=page(transfer.manifest(),clock);var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(page));
        durable(store,new DurableMetadataStage(transfer.manifest(),0,1,blob.reference()),List.of(blob),"hidden metadata tick4242 durable");
    }
    private static long oldChronologicalFloor(JournalStore store)throws Exception {
        long floor=0;Optional<String> cursor=Optional.empty();
        do{
            var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),cursor,128);var read=await(store.read(query));
            ReadContracts.validate(read,query,store.session());check(read.status()==ReadStatus.AVAILABLE,"actual cold chronological page available");
            var page=read.page().orElseThrow();
            for(var row:page.records())if(row instanceof DurableAggregate a){floor=Math.max(floor,a.state().header().updatedTick().value());
                if(a.state() instanceof WorldAggregate w)floor=Math.max(floor,w.value().simTick().value());}
            cursor=page.nextCursor();
        }while(cursor.isPresent());return floor;
    }
    private static void stagedAndActiveCold(Path root,boolean before)throws Exception {
        var transfer=LocationTransfer.prepare(source(1));JournalStore store=open(root);
        world(store);stage(store,transfer,TIME);close(store);store=open(root);
        try{
            long legacy=oldChronologicalFloor(store);
            var actual=await(store.readStagedMetadataPage(transfer.manifest(),0)).orElseThrow();
            check(legacy==1&&actual.states().getFirst().header().updatedTick().equals(TIME),"real cold J04 retains tick4242 outside legacy recovery records with tick1");
            check(store.metadataSnapshot().orElseThrow().generations().isEmpty(),"hidden staged generation remains invisible");
            System.out.println("CLOCK_COUNTEREXAMPLE cold_legacy_floor="+legacy+" persisted_metadata_tick="+TIME.value()+" selected_regions=0");
            if(before)throw new AssertionError("LEGACY_NATIVE_CLOCK_CANNOT_RESUME_VALID_METADATA_4242_FROM_FLOOR_1");
            var snapshot=store.metadataSnapshot().orElseThrow();
            check(store.metadataClockFloor(snapshot).orElseThrow().equals(TIME),"verified staged floor is recovered without any selected metadata bodies");
            check(store.metadataPreparationTick(transfer.manifest()).orElseThrow().equals(TIME),"B04 staged blob supplies exact authoritative generation tick during migration");
            long loads=store.metadataHydrationPageLoads();
            store.metadataClockFloor(snapshot);check(store.metadataHydrationPageLoads()==loads,"clock scalar requires no cold-body hydration");
            durable(store,new DurableMetadataActivation(transfer.manifest(),Optional.empty(),1),List.of(),"activation at original prepared clock durable");
            await(store.checkpoint());await(store.evictVerifiedCheckpointRows());close(store);store=open(root);
            snapshot=store.metadataSnapshot().orElseThrow();
            check(store.metadataClockFloor(snapshot).orElseThrow().equals(TIME),"activated floor survives compacted checkpoint and cold reopen");
            check(snapshot.generations().get(SOURCE).equals(LocationTransfer.identity(transfer.manifest())),"original activation identity survives cold recovery");
        }finally{close(store);}
    }
    private static void preparationBeforeSpool(Path root)throws Exception {
        var transfer=LocationTransfer.prepare(source(2));JournalStore store=open(root);world(store);
        var fact=MetadataPreparationClock.fact(transfer.manifest(),TIME);
        durable(store,new DurableFact(fact),List.of(),"native preparation clock forced before spool/first stage");
        var frozen=store.metadataSnapshot().orElseThrow();
        check(store.metadataClockFloor(frozen).orElseThrow().equals(TIME),"pre-spool native clock is durable");
        check(store.metadataPreparationTick(transfer.manifest()).orElseThrow().equals(TIME),"exact preparation tick query returns frozen declaration");
        check(frozen.generations().isEmpty()&&store.metadataKeysPage(frozen,Set.of(AggregateKind.LOCATION),Optional.empty(),96).orElseThrow().keys().isEmpty(),"clock declaration grants no metadata head visibility");
        check(await(store.readStagedMetadataPage(transfer.manifest(),0)).isEmpty(),"clock authority exists before even first metadata stage");
        check(commit(store,new DurableFact(MetadataPreparationClock.fact(transfer.manifest(),new Tick(9999))),List.of()).ackStatus()==AckStatus.REJECTED,"changed tick for same generation fact identity refused");
        check(store.metadataClockFloor(store.metadataSnapshot().orElseThrow()).orElseThrow().equals(TIME),"rejected proposal cannot advance clock floor");
        durable(store,new DurableFact(fact),List.of(),"identical native clock replay idempotent");
        durable(store,new DurableMetadataCancellation(transfer.manifest()),List.of(),"pending clock-only generation explicitly cancelled");
        check(store.metadataClockFloor(store.metadataSnapshot().orElseThrow()).orElseThrow().equals(TIME),"cancellation cannot roll back durable time");
        await(store.checkpoint());await(store.evictVerifiedCheckpointRows());close(store);store=open(root);
        try{
            check(store.metadataClockFloor(store.metadataSnapshot().orElseThrow()).orElseThrow().equals(TIME),"cancelled historical clock survives compacted cold replay");
            check(store.metadataPreparationTick(transfer.manifest()).orElseThrow().equals(TIME),"cancelled generation retains exact historical clock evidence");
            check(commit(store,new DurableFact(fact),List.of()).ackStatus()==AckStatus.REJECTED,"cancelled generation cannot be revived by a clock replay");
            check(store.metadataSnapshot().orElseThrow().generations().isEmpty(),"cancelled time never activates source metadata");
        }finally{close(store);}
    }
    private static void orphanSpoolIsNotClockAuthority(Path root)throws Exception {
        var transfer=LocationTransfer.prepare(source(3));JournalStore store=open(root.resolve("real-j04"));
        try{
            var proposed=page(transfer.manifest(),TIME);
            MetadataPlanSpool.prepare(root.resolve("inputs"),transfer.manifest(),0,proposed.states(),proposed.beforeRevisions());
            var local=MetadataPlanSpool.preparedTick(root.resolve("inputs"),transfer.manifest());
            check(local.equals(Optional.of(TIME))&&store.metadataPreparationTick(transfer.manifest()).isEmpty(),"actual B04-style orphan spool has a proposed time but no J04 clock declaration");
            try{MetadataPreparationClock.proposalTick(store.metadataPreparationTick(transfer.manifest()),local,new Tick(9999));throw new AssertionError("accepted orphan spool clock");}
            catch(IllegalArgumentException refusal){check(refusal.getMessage().contains("orphan prepared spool"),"orphan spool is refused precisely even below current native time");}
            check(store.metadataClockFloor(store.metadataSnapshot().orElseThrow()).orElseThrow().value()==0,"private spool never advances verified durable time");
            check(MetadataPreparationClock.proposalTick(Optional.empty(),Optional.empty(),new Tick(7)).equals(new Tick(7)),"fresh proposal uses current native clock without a private authority");
            try{MetadataPreparationClock.proposalTick(Optional.of(TIME),Optional.of(new Tick(1)),new Tick(9999));throw new AssertionError("accepted altered spool time");}
            catch(IllegalArgumentException refusal){check(refusal.getMessage().contains("differs"),"private clock conflicting with J04 refused");}
            try{MetadataPreparationClock.proposalTick(Optional.of(TIME),Optional.empty(),new Tick(1));throw new AssertionError("accepted future clock");}
            catch(IllegalArgumentException refusal){check(refusal.getMessage().contains("ahead"),"native current-clock fence remains effective");}
        }finally{close(store);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("before|after NEW_PRIVATE_ROOT");Path root=Path.of(args[1]).toAbsolutePath();Files.createDirectory(root);
        stagedAndActiveCold(root.resolve("stage-active"),args[0].equals("before"));
        preparationBeforeSpool(root.resolve("before-spool"));orphanSpoolIsNotClockAuthority(root.resolve("orphan"));
        System.out.println("PASS MetadataClockRecoveryTests assertions="+assertions+" scope=J04_WORLDLESS_COLD_CLOCK_NOT_NATIVE_GAMEPLAY");
    }
}
