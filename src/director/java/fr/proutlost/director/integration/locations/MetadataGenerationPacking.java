package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Byte/item-bounded packing after the native planner has checked each read page.
 * Run on a private IO/planning worker: encoding may visit large preserved runtime shapes.
 * The result has no persistence or admission effect. Freeze the FINAL page count only
 * after all read pages were packed, before submitting any immutable J04 plan page.
 */
public final class MetadataGenerationPacking {
    private MetadataGenerationPacking(){}
    public static final int MAX_PAGE_COUNT=1024;
    private static final ModelCodec<MetadataGenerationPage> CODEC=new ModelCodec<>(MetadataGenerationPage.class);
    private static final Comparator<AggregateKey> ORDER=Comparator.comparing(key->key.kind()+":"+key.id());
    public record Chunk(List<AggregateState> states,Map<AggregateKey,Revision> beforeRevisions){
        public Chunk{states=List.copyOf(states);beforeRevisions=Map.copyOf(beforeRevisions);}
        public MetadataGenerationPage page(Fingerprint generation,ResourceId source,Revision revision,
                Fingerprint digest,int index,int count){
            return new MetadataGenerationPage(generation,source,revision,digest,index,count,states,beforeRevisions);
        }
    }
    /** A precise structural refusal, before a J04 source or generation is made visible.
     * It is never permission to discard fields or enlarge CanonicalJsonV1's limits. */
    public static final class UnrepresentableMetadataStateException extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        private final transient AggregateKey key;
        private UnrepresentableMetadataStateException(AggregateKey key,Throwable cause){
            super("metadata singleton cannot fit unchanged G0 JSON bounds: "+key,cause);this.key=key;
        }
        public AggregateKey key(){return key;}
    }
    private static void check(Fingerprint generation,ResourceId source,Revision revision,Fingerprint digest,
            List<AggregateState> states,Map<AggregateKey,Revision> guards){
        // Use the largest legal counters. Actual final counters need no more bytes;
        // identity fields, schema depth, collection cardinality and bodies stay exact.
        CODEC.encode(new MetadataGenerationPage(generation,source,revision,digest,
            MAX_PAGE_COUNT-1,MAX_PAGE_COUNT,states,guards));
    }
    public static List<Chunk> pack(Fingerprint generation,ResourceId source,Revision revision,Fingerprint digest,
            List<AggregateState> states,Map<AggregateKey,Revision> beforeRevisions){
        Objects.requireNonNull(generation);Objects.requireNonNull(source);Objects.requireNonNull(revision);Objects.requireNonNull(digest);
        states=List.copyOf(states);beforeRevisions=Map.copyOf(beforeRevisions);
        Checks.require(states.size()<=96&&beforeRevisions.size()<=96,"metadata packing input key bound");
        // Structural/semantic DTO errors are input refusals, never reclassified as
        // a capacity issue merely because the encoder is invoked during packing.
        new MetadataGenerationPage(generation,source,revision,digest,MAX_PAGE_COUNT-1,MAX_PAGE_COUNT,states,beforeRevisions);
        if(beforeRevisions.isEmpty())return List.of();
        try{check(generation,source,revision,digest,states,beforeRevisions);return List.of(new Chunk(states,beforeRevisions));}
        catch(IllegalArgumentException oversizedTogether){/* Split only a representation that failed the actual codec. */}
        var byKey=new HashMap<AggregateKey,AggregateState>();
        for(var state:states){Checks.require(byKey.putIfAbsent(state.key(),state)==null,"metadata packing duplicate state");
            Checks.require(beforeRevisions.containsKey(state.key()),"metadata packing missing CAS guard");}
        var result=new ArrayList<Chunk>();var currentStates=new ArrayList<AggregateState>();
        var currentGuards=new LinkedHashMap<AggregateKey,Revision>();
        for(var key:beforeRevisions.keySet().stream().sorted(ORDER).toList()){
            var nextStates=new ArrayList<>(currentStates);var state=byKey.get(key);if(state!=null)nextStates.add(state);
            var nextGuards=new LinkedHashMap<>(currentGuards);nextGuards.put(key,beforeRevisions.get(key));
            try{check(generation,source,revision,digest,nextStates,nextGuards);}
            catch(IllegalArgumentException packedPageTooLarge){
                if(currentGuards.isEmpty())throw new UnrepresentableMetadataStateException(key,packedPageTooLarge);
                result.add(new Chunk(currentStates,currentGuards));currentStates.clear();currentGuards.clear();
                nextStates=new ArrayList<>();if(state!=null)nextStates.add(state);
                nextGuards=new LinkedHashMap<>();nextGuards.put(key,beforeRevisions.get(key));
                try{check(generation,source,revision,digest,nextStates,nextGuards);}
                catch(IllegalArgumentException singletonTooLarge){throw new UnrepresentableMetadataStateException(key,singletonTooLarge);}
            }
            currentStates=nextStates;currentGuards=nextGuards;
        }
        if(!currentGuards.isEmpty())result.add(new Chunk(currentStates,currentGuards));
        return List.copyOf(result);
    }
}
