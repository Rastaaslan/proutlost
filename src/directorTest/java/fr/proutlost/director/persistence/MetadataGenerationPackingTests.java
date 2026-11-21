package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Encoding-only synthetic runtime shapes. No admission, J04 write, or physical/catalogue claim. */
public final class MetadataGenerationPackingTests {
    private MetadataGenerationPackingTests(){}
    private static final WorldId WORLD=new WorldId(new UUID(9008,1));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE=new ResourceId("fixture:metadata_byte_packing");
    private static final Fingerprint GENERATION=new Fingerprint("2".repeat(64));
    private static final ModelCodec<DurableRecord> OLD=new ModelCodec<>(DurableRecord.class);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static int assertions;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static RegionAggregate region(int count,int ordinal,int padding){
        var boxes=new ArrayList<Aabb>();boxes.add(new Aabb(DIM,ordinal,0,ordinal,ordinal+1,1,ordinal+1));
        for(int i=0;i<count;i++)boxes.add(new Aabb(DIM,1_000_000_000+i,1_000_000_000,1_000_000_000+i,1_000_000_001+i,1_000_000_001,1_000_000_001+i));
        var refs=new HashSet<MemoryId>();for(int i=0;i<padding;i++)refs.add(new MemoryId(new UUID(9009,i)));
        var id=new RegionId(new UUID(9010,ordinal));
        return new RegionAggregate(new RegionRecord(new AggregateHeader(1,id.value(),new Revision(2),new Sequence(0),new Tick(10)),id,DIM,
            RegionStatus.ACTIVE,new SupportShape(boxes,List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),refs));
    }
    private static LocationImport source(){
        var regions=List.of(new ImportedRegion(new RegionId(new UUID(9010,0)),DIM,new ImportBounds(0,0,0,1,1,1),Set.of()),
            new ImportedRegion(new RegionId(new UUID(9010,1)),DIM,new ImportBounds(1,0,1,2,1,2),Set.of()));
        var draft=new LocationImport(1,WORLD,SOURCE,new Revision(2),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        return new LocationImport(1,WORLD,SOURCE,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,List.of(),List.of());
    }
    private static Map<AggregateKey,Revision> guards(List<AggregateState> states,boolean memory){
        var guards=new HashMap<AggregateKey,Revision>();for(var state:states){guards.put(state.key(),new Revision(1));
            if(memory)guards.put(new AggregateKey(AggregateKind.REGION_MEMORY,state.key().id()),new Revision(4));}
        return Map.copyOf(guards);
    }
    private static MetadataGenerationPage page(List<AggregateState> states,Map<AggregateKey,Revision> guards){
        return new MetadataGenerationPage(GENERATION,SOURCE,new Revision(2),source().exportDigest(),0,1,states,guards);
    }
    private static DurableBatch oldBatch(RegionAggregate state){
        return ResultTransaction.batch(new TransactionId(new UUID(9011,1)),new Epoch(1),Map.of(new ScopeRef(ScopeKind.REGION,state.key().id()),new Revision(1)),List.of(new DurableAggregate(state)),List.of());
    }
    private static int recordBytes(RegionAggregate state){return OLD.encode(new DurableAggregate(state)).length;}
    private static int batchBytes(RegionAggregate state){return new ModelCodec<>(DurableBatch.class).encode(oldBatch(state)).length;}
    private static boolean recordFits(int count,int padding){try{recordBytes(region(count,0,padding));return true;}catch(IllegalArgumentException bounds){return false;}}
    private static boolean batchFits(int count,int padding){try{batchBytes(region(count,0,padding));return true;}catch(IllegalArgumentException bounds){return false;}}
    private static int largest(boolean batch,int padding){
        int low=0,high=12000;while(low<high){int mid=(low+high+1)>>>1;boolean yes=batch?batchFits(mid,padding):recordFits(mid,padding);if(yes)low=mid;else high=mid-1;}return low;
    }
    private static void pairedBefore(boolean uncaught){
        var input=source();var states=List.<AggregateState>of(region(4000,0,0),region(4000,1,0));var expected=guards(states,true);
        int sourceBytes=new ModelCodec<>(LocationImport.class).encode(input).length;
        check(sourceBytes<CanonicalJsonV1.MAX_BYTES,"frozen original source remains under existing one-MiB G0 cap");
        System.out.println("PAIR_SOURCE_BYTES="+sourceBytes+" AABBS_PER_RUNTIME_REGION=4001");
        for(var state:states){var region=(RegionAggregate)state;int old=recordBytes(region);int batch=batchBytes(region);
            check(old<=CanonicalJsonV1.MAX_BYTES&&batch<=CanonicalJsonV1.MAX_BYTES,"each original runtime aggregate and actual ResultTransaction.batch individually admissible");
            System.out.println("PAIR_OLD_RECORD_BYTES="+old+" OLD_BATCH_BYTES="+batch);}
        if(uncaught){PAGE.encode(page(states,expected));throw new AssertionError("unpacked pair unexpectedly fits");}
        try{PAGE.encode(page(states,expected));throw new AssertionError("unpacked two-state native plan exceeds codec but was accepted");}
        catch(IllegalArgumentException expectedFailure){System.out.println("UNPACKED_PAIR_REJECTED="+expectedFailure.getMessage());assertions++;}
    }
    private static void packingAfter(){
        var states=List.<AggregateState>of(region(4000,0,0),region(4000,1,0));var before=guards(states,true);
        var chunks=MetadataGenerationPacking.pack(GENERATION,SOURCE,new Revision(2),source().exportDigest(),states,before);
        check(chunks.size()>=2,"bytes/items force several native metadata chunks before count limit");
        var retained=new HashMap<AggregateKey,AggregateState>();var retainedGuards=new HashMap<AggregateKey,Revision>();
        for(int i=0;i<chunks.size();i++){
            var chunk=chunks.get(i);var finalPage=chunk.page(GENERATION,SOURCE,new Revision(2),source().exportDigest(),i,chunks.size());
            byte[] bytes=PAGE.encode(finalPage);check(bytes.length<=CanonicalJsonV1.MAX_BYTES,"actual final page keeps original JSON byte cap");
            check(chunk.states().size()<=96&&chunk.beforeRevisions().size()<=96,"original combined guard/state count remains bounded");
            check(PAGE.decode(bytes).equals(finalPage),"actual encoded final page round trips without any field loss");
            for(var state:chunk.states())check(retained.put(state.key(),state)==null,"state not duplicated between packed pages");
            for(var entry:chunk.beforeRevisions().entrySet())check(retainedGuards.put(entry.getKey(),entry.getValue())==null,"guard not duplicated between packed pages");
            System.out.println("PACKED_PAGE="+i+" FINAL_BYTES="+bytes.length+" STATES="+chunk.states().size()+" GUARDS="+chunk.beforeRevisions().size());
        }
        check(retained.size()==states.size()&&states.stream().allMatch(value->value.equals(retained.get(value.key()))),"all exact runtime states preserved");
        check(retainedGuards.equals(before),"all exact state and unchanged memory CAS guards preserved");
        var guardOnly=MetadataGenerationPacking.pack(GENERATION,SOURCE,new Revision(2),source().exportDigest(),List.of(),before);
        check(!guardOnly.isEmpty()&&guardOnly.stream().allMatch(chunk->chunk.states().isEmpty()),"unchanged prerequisite-only page retained");
        var empty=MetadataGenerationPacking.pack(GENERATION,SOURCE,new Revision(2),source().exportDigest(),List.of(),Map.of());check(empty.isEmpty(),"truly empty generation needs no invented page");
    }
    private static void singletonBoundary(){
        int count=largest(false,0);var atRecord=region(count,0,0);int oldRecord=recordBytes(atRecord);
        System.out.println("SINGLETON_MAX_OLD_RECORD_AABBS="+(count+1)+" OLD_RECORD_BYTES="+oldRecord);
        boolean oldBatchFits;try{System.out.println("SINGLETON_OLD_BATCH_BYTES="+batchBytes(atRecord));oldBatchFits=true;}catch(IllegalArgumentException refusal){oldBatchFits=false;System.out.println("SINGLETON_OLD_BATCH_REFUSED="+refusal.getMessage());}
        try{
            var output=MetadataGenerationPacking.pack(GENERATION,SOURCE,new Revision(2),source().exportDigest(),List.of(atRecord),guards(List.of(atRecord),false));
            for(int i=0;i<output.size();i++)check(PAGE.encode(output.get(i).page(GENERATION,SOURCE,new Revision(2),source().exportDigest(),i,output.size())).length<=CanonicalJsonV1.MAX_BYTES,"near-boundary actual singleton page fits");
            System.out.println("SINGLETON_HELPER_RESULT=PACKED OLD_BATCH_VALID="+oldBatchFits);
        }catch(MetadataGenerationPacking.UnrepresentableMetadataStateException refusal){
            check(refusal.key().equals(atRecord.key()),"unrepresentable singleton refused with exact key before any staging");
            System.out.println("SINGLETON_HELPER_RESULT=EXPLICIT_UNREPRESENTABLE OLD_BATCH_VALID="+oldBatchFits+" KEY="+refusal.key());
        }
        // Test the actual old batch boundary independently; a plain-record fit is
        // not evidence that the old native transaction would have been admitted.
        int batchCount=largest(true,0);var atBatch=region(batchCount,0,0);
        System.out.println("SINGLETON_MAX_OLD_BATCH_AABBS="+(batchCount+1)+" OLD_RECORD_BYTES="+recordBytes(atBatch)+" OLD_BATCH_BYTES="+batchBytes(atBatch));
        var chunks=MetadataGenerationPacking.pack(GENERATION,SOURCE,new Revision(2),source().exportDigest(),List.of(atBatch),guards(List.of(atBatch),true));
        for(int i=0;i<chunks.size();i++)check(PAGE.encode(chunks.get(i).page(GENERATION,SOURCE,new Revision(2),source().exportDigest(),i,chunks.size())).length<=CanonicalJsonV1.MAX_BYTES,"old-batch-valid boundary retains bounded emitted pages");
        var recovered=new HashMap<AggregateKey,Revision>();chunks.forEach(chunk->recovered.putAll(chunk.beforeRevisions()));
        check(recovered.equals(guards(List.of(atBatch),true)),"boundary singleton retains unchanged memory guard even when guard gets its own page");
        System.out.println("OLD_BATCH_BOUNDARY_PACKED_CHUNKS="+chunks.size());
    }
    public static void main(String[] args){
        if(args.length>0&&args[0].equals("before")){pairedBefore(true);return;}
        pairedBefore(false);packingAfter();singletonBoundary();
        System.out.println("PASS MetadataGenerationPackingTests assertions="+assertions+" scope=PURE_ENCODING_SYNTHETIC_RUNTIME_SHAPES_NO_NATIVE_WRITES");
    }
}
