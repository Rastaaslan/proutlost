package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
/** Read-only memory-projection service composed by bootstrap. Consumers see
 * immutable data and pinned read proofs, never a neighboring implementation. */
public interface MetadataMemoryReader {
    interface Configuration {ConfigView configuration();ProviderMode mode();}
    interface Selection {
        MetadataReadSnapshot snapshot();
        Map<AggregateKey,AggregateState> states();
        ScopeSet scopes();
        MemoryView memory();
        Set<AnchorId> requestedAnchors();
        long examinedRows();
        int peakPageRows();
        /** Revalidate the complete read-side proof, including expanded states
         * and scoped absences. This never authorizes a write or an effect. */
        boolean currentHeads(MetadataReadSnapshot current);
    }
    interface Capture {
        MetadataReadSnapshot snapshot();
        Map<AggregateKey,AggregateState> states();
        boolean sameHeads(MetadataReadSnapshot current);
        boolean sameMemoryHeads(MetadataReadSnapshot current,ScopeSet scopes,Set<AnchorId> anchors);
    }
    Selection empty(MetadataReadSnapshot snapshot,ScopeSet scopes);
    CompletionStage<Selection> exact(MetadataReadSnapshot snapshot,ScopeSet scopes,Set<AnchorId> anchors,Configuration configuration);
    CompletionStage<Capture> capture(MetadataReadSnapshot snapshot,Set<AggregateKey> keys,int maximumStates);
    /** Both inputs must originate from this service and the identical handle. */
    Selection expand(Selection base,Capture additional,ScopeSet expandedScopes);
}
