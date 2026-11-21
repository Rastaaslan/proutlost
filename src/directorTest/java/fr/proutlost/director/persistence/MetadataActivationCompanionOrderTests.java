package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real private J04 records. A companion cannot redefine the previous source
 * before activation checks the exact retirement delta, regardless of WAL order. */
public final class MetadataActivationCompanionOrderTests {
    private static final WorldId WORLD=new WorldId(new UUID(18181,1));
    private static final ResourceId SOURCE=new ResourceId("fixture:activation_companion_order");
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    private static final ModelCodec<LocationImport> INPUT=new ModelCodec<>(LocationImport.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static final ModelCodec<DurableRecord> RECORD=new ModelCodec<>(DurableRecord.class);
    private static int assertions;
    private MetadataActivationCompanionOrderTests(){}
    private static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static JournalStore open(Path root)throws Exception{return await(JournalStore.open(root,WORLD,OPTIONS));}
    private static CommitAck put(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs)throws Exception{
        var hash=new java.io.ByteArrayOutputStream();for(var r:records)hash.writeBytes(RECORD.encode(r));
        return await(store.append(new DurableBatch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs,Optional.empty(),BlobContent.hash(hash.toByteArray()))));
    }
    private static void durable(JournalStore store,List<DurableRecord> records,List<BlobContent> blobs,String why)throws Exception{check(put(store,records,blobs).ackStatus()==AckStatus.DURABLE,why);}
    private static LocationImport source(int count,long revision){
        var rows=new ArrayList<ImportedLocation>();for(int i=0;i<count;i++)rows.add(new ImportedLocation(new LocationId(new UUID(18182,i)),"synthetic-order-site-"+i,Set.of(),DIM,new ImportBounds(i,0,i,i+1,2,i+1),Set.of()));
        var raw=new LocationImport(1,WORLD,SOURCE,new Revision(revision),new Fingerprint("0".repeat(64)),List.of(),rows,List.of());
        return new LocationImport(1,WORLD,SOURCE,raw.exportRevision(),LocationTransferContract.digest(raw),List.of(),rows,List.of());
    }
    private static AggregateState state(ImportedLocation original,LocationImport source,long revision,boolean active){
        var b=original.bounds();return new LocationAggregate(new LocationRecord(new AggregateHeader(1,original.locationId().value(),new Revision(revision),new Sequence(0),new Tick(revision==1?10:20)),
            original.locationId(),SOURCE,original.externalId(),DIM,new Aabb(DIM,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive()),Set.of(),Set.of(),source.exportRevision(),active));
    }
    private static DirectorFact fact(LocationImport source){
        var identity="director-location-import-v1/"+WORLD.value()+"/"+SOURCE.value()+"/"+source.exportRevision().value()+"/"+source.exportDigest().value();
        return new DirectorFact(new FactId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))),new FactType("proutlost_director:location_import_applied"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("source_namespace",new JsonValue.Text(SOURCE.value()),"export_revision",new JsonValue.Text(Long.toString(source.exportRevision().value())),"export_digest",new JsonValue.Text(source.exportDigest().value()),"location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(INPUT.encode(source)))),true,Optional.empty());
    }
    private record Prepared(LocationImport old,LocationImport current,LocationTransferManifest transfer,DurableRecord activation,AggregateKey retired){}
    private static Prepared prepare(JournalStore store,boolean versioned,boolean complete)throws Exception{
        var old=source(2,1);var current=source(1,2);var seed=new ArrayList<DurableRecord>();for(var row:old.locations())seed.add(new DurableAggregate(state(row,old,1,true)));seed.add(new DurableFact(fact(old)));
        durable(store,seed,List.of(),"legacy R1 source and two actual active location heads durable");
        var transfer=LocationTransferContract.prepare(current);
        for(var part:transfer.pages()){
            var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(part.bytesBase64()));
            durable(store,List.of(new DurableMetadataSourcePage(transfer.manifest(),part.pageIndex(),blob.reference())),List.of(blob),"exact R2 source fragment durable");
        }
        durable(store,List.of(new DurableFact(MetadataPreparationClockContract.fact(transfer.manifest(),new Tick(20)))),List.of(),"R2 preparation clock forced");
        var epoch=new Epoch(versioned?1:0);
        if(versioned)durable(store,List.of(new DurableFact(MetadataAttemptContract.reprepare(transfer.manifest(),new Epoch(0),Optional.empty(),new Tick(20)))),List.of(),"technical attempt1 forced without changing R2 export identity");
        var states=new ArrayList<AggregateState>();states.add(state(current.locations().getFirst(),current,2,true));
        if(complete)states.add(state(old.locations().getLast(),current,2,false));
        var before=new HashMap<AggregateKey,Revision>();for(var row:states)before.put(row.key(),new Revision(1));
        var page=new MetadataGenerationPage(LocationTransferContract.identity(transfer.manifest()),SOURCE,current.exportRevision(),current.exportDigest(),0,1,states,before);
        var blob=BlobContent.capture(new ResourceId("proutlost_director:metadata_generation_page"),1,PAGE.encode(page));
        var stage=new DurableMetadataStage(transfer.manifest(),0,1,blob.reference());
        durable(store,List.of(versioned?new DurableFact(MetadataAttemptContract.stage(epoch,stage)):stage),List.of(blob),"bounded metadata plan page durable");
        var activation=new DurableMetadataActivation(transfer.manifest(),Optional.empty(),1);
        return new Prepared(old,current,transfer.manifest(),versioned?new DurableFact(MetadataAttemptContract.activate(epoch,activation)):activation,new AggregateKey(AggregateKind.LOCATION,old.locations().getLast().locationId().value()));
    }
    private static boolean active(JournalStore store,AggregateKey key)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();var query=new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1);var result=await(store.readMetadata(snapshot,query));
        ReadContracts.validate(result,query,store.session());check(result.status()==ReadStatus.AVAILABLE&&!result.page().orElseThrow().hasMore()&&result.page().orElseThrow().aggregates().size()==1,"exact retired identity head is verifiably available");
        return ((LocationAggregate)result.page().orElseThrow().aggregates().getFirst().state()).value().active();
    }
    private static void missingRetirement(Path root,boolean versioned,boolean fixed)throws Exception{
        var store=open(root);try{
            var p=prepare(store,versioned,false);
            check(put(store,List.of(p.activation()),List.of()).ackStatus()==AckStatus.REJECTED,"missing retirement rejected without companion");
            var result=put(store,List.of(new DurableFact(fact(p.current())),p.activation()),List.of());
            System.out.println("COMPANION_ORDER_COUNTEREXAMPLE versioned="+versioned+" missing_retirement=true without_companion=REJECTED companion_first="+result.ackStatus());
            if(fixed){check(result.ackStatus()==AckStatus.REJECTED&&result.durableSequence().value()==0,"companion-before-activation cannot erase previous-source retirement requirements");
                check(store.metadataSnapshot().orElseThrow().generations().isEmpty(),"incomplete R2 never becomes active");
                check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(p.old()),"exact previous legacy R1 remains authoritative after refusal");
                check(active(store,p.retired()),"old R1 active location remains unchanged under still-active R1 source");
            }else{check(result.ackStatus()==AckStatus.DURABLE,"pre-fix reproduces actual accepted incorrect companion-first activation");
                check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(p.current())&&active(store,p.retired()),"pre-fix visibly contradicts R2 retirement by retaining removed identity active");}
        }finally{await(store.closeAsync());}
    }
    private static void validRetirement(Path root,boolean versioned)throws Exception{
        var store=open(root);Prepared p;try{
            p=prepare(store,versioned,true);
            durable(store,List.of(new DurableFact(fact(p.current())),p.activation()),List.of(),"valid retirement plus companion-first remains accepted independent of WAL order");
            check(!active(store,p.retired()),"real retired identity is inactive after guarded complete activation");
            check(store.metadataSnapshot().orElseThrow().generations().get(SOURCE).equals(LocationTransferContract.identity(p.transfer())),"exact R2 generation active");
            check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(p.current()),"exact R2 source bytes authoritative after valid activation");
            await(store.checkpoint());await(store.evictVerifiedCheckpointRows());
        }finally{await(store.closeAsync());}
        store=open(root);try{
            check(!store.readOnlySafe(),"valid companion-first historical WAL remains readable in new store session");
            check(!active(store,p.retired()),"retirement survives verified checkpoint and cold reopen");
            check(await(store.readMetadataSource(store.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(p.current()),"exact R2 source survives cold reopen");
        }finally{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!Set.of("before","fixed").contains(args[0]))throw new IllegalArgumentException("before|fixed NEW_PRIVATE_ROOT");
        boolean fixed=args[0].equals("fixed");var root=Path.of(args[1]);Files.createDirectory(root);
        for(boolean versioned:List.of(false,true))missingRetirement(root.resolve(versioned?"versioned-incomplete":"legacy-incomplete"),versioned,fixed);
        if(!fixed)throw new AssertionError("COMPANION_FIRST_ERASED_PREVIOUS_SOURCE_RETIREMENT_BEFORE_CORRECTION");
        for(boolean versioned:List.of(false,true))validRetirement(root.resolve(versioned?"versioned-complete":"legacy-complete"),versioned);
        System.out.println("PASS MetadataActivationCompanionOrderTests assertions="+assertions+" scope=ACTUAL_PRIVATE_J04_SYNTHETIC_ONLY_NO_NATIVE_OR_CANONICAL_RECEIPT");
    }
}
