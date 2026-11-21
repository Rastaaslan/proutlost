package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.concurrent.*;

/** A bounded aggregate working set read in pages from one immutable J04 cut.
 * A later cut can reuse these bodies only after every selected head revision
 * and source generation is checked against that newer authoritative index.
 * No StoreQuery/page limit is treated as a total working-set limit.
 */
public final class ScopedAggregateCapture {
    private static final int PAGE=96;
    public record View(MetadataReadSnapshot snapshot,Map<AggregateKey,AggregateState> states) {
        public View {Objects.requireNonNull(snapshot);states=Map.copyOf(states);}
        public boolean sameMemoryHeads(MetadataReadPortProvider source,MetadataReadSnapshot current,ScopeSet coverage,Set<AnchorId> demandedAnchors) {
            var store=source.metadataReads();
            return sameHeads(store,current)&&sameMemoryAbsences(store,current,coverage,demandedAnchors);
        }
        /** Reuse of a selected key set requires every scoped missing head to
         * remain genuinely absent; present bodies are reloaded separately. */
        public boolean sameMemoryAbsences(MetadataReadPortProvider source,MetadataReadSnapshot current,ScopeSet coverage,Set<AnchorId> demandedAnchors) {
            var store=source.metadataReads();
            if(!store.owns(snapshot)||!store.owns(current)||!snapshot.session().equals(current.session())
                ||!snapshot.generations().equals(current.generations())||!snapshot.sources().equals(current.sources()))return false;
            for(var scope:coverage.scopes()) {
                AggregateKind kind=switch(scope.kind()){case REGION->AggregateKind.REGION;case PLAYER->AggregateKind.PLAYER_OPERATIONAL;case LOCATION->AggregateKind.LOCATION;default->null;};
                if(kind==null)continue;
                var key=new AggregateKey(kind,scope.id());
                if(!states.containsKey(key)&&store.metadataRevision(current,key).value()!=0)return false;
                // A selected or provisional region may not have a first memory
                // body yet. Its absence is part of this exact scoped proof;
                // appearance must be observed before a reducer may write it.
                if(scope.kind()==ScopeKind.REGION){
                    var memory=new AggregateKey(AggregateKind.REGION_MEMORY,scope.id());
                    if(!states.containsKey(memory)&&store.metadataRevision(current,memory).value()!=0)return false;
                }
            }
            for(var anchor:demandedAnchors){var key=new AggregateKey(AggregateKind.ANCHOR,anchor.value());
                if(!states.containsKey(key)&&store.metadataRevision(current,key).value()!=0)return false;}
            return true;
        }
        public boolean sameHeads(MetadataReadPortProvider source,MetadataReadSnapshot current) {
            var store=source.metadataReads();if(!store.owns(snapshot)||!store.owns(current))return false;
            if(!snapshot.session().equals(current.session())||!snapshot.generations().equals(current.generations())
                ||!snapshot.sources().equals(current.sources()))return false;
            for(var entry:states.entrySet())if(!store.metadataRevision(current,entry.getKey()).equals(entry.getValue().header().revision()))return false;
            return true;
        }
    }
    private ScopedAggregateCapture(){}
    public static CompletionStage<View> load(MetadataReadPortProvider source,MetadataReadSnapshot snapshot,
            Set<AggregateKey> keys,int maximumStates) {
        var store=Objects.requireNonNull(source).metadataReads();Objects.requireNonNull(snapshot);Objects.requireNonNull(keys);
        if(!store.owns(snapshot))throw new IllegalStateException("foreign or revoked metadata snapshot");
        if(maximumStates<1||keys.size()>maximumStates)throw new IllegalArgumentException("scoped aggregate working-set capacity");
        var sorted=keys.stream().sorted(Comparator.comparing(k->k.kind()+":"+k.id())).toList();
        return page(store,snapshot,sorted,0,new HashMap<>()).thenApply(states->new View(snapshot,states));
    }
    private static CompletionStage<Map<AggregateKey,AggregateState>> page(MetadataReadPort store,
            MetadataReadSnapshot snapshot,List<AggregateKey> keys,int start,Map<AggregateKey,AggregateState> candidate) {
        if(start==keys.size())return CompletableFuture.completedFuture(candidate);
        int end=Math.min(keys.size(),start+PAGE);
        var wanted=Set.copyOf(keys.subList(start,end));
        var query=new StoreQuery(snapshot.session().worldId(),wanted,new Sequence(0),Optional.empty(),PAGE);
        return store.readMetadata(snapshot,query).thenCompose(result->{
            ReadContracts.validate(result,query,snapshot.session());
            if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("scoped aggregate page "+result.status());
            var read=result.page().orElseThrow();
            if(read.hasMore()||!read.lastDurableSequence().equals(snapshot.cut())||!read.records().isEmpty()
                ||read.aggregates().size()!=wanted.size())throw new IllegalStateException("scoped aggregate page completeness/cut");
            for(var row:read.aggregates())if(!wanted.contains(row.state().key())||candidate.putIfAbsent(row.state().key(),row.state())!=null)
                throw new IllegalStateException("scoped aggregate duplicate/foreign key");
            return page(store,snapshot,keys,end,candidate);
        });
    }
}
