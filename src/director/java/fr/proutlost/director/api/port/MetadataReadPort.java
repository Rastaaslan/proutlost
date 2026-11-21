package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
/** Read capability only. Implementations retain storage authority and validate
 * ownership/session of opaque snapshots on every operation. No append, restore,
 * permission, file access or execution capability can be obtained from this port. */
public interface MetadataReadPort extends MetadataReadPortProvider {
    @Override default MetadataReadPort metadataReads(){return this;}
    StoreSession session();
    boolean readOnlySafe();
    boolean owns(MetadataReadSnapshot snapshot);
    Optional<MetadataReadSnapshot> snapshot();
    Optional<MetadataReadKeyPage> metadataKeysPage(MetadataReadSnapshot snapshot,Set<AggregateKind> kinds,Optional<AggregateKey> after,int limit);
    Revision metadataRevision(MetadataReadSnapshot snapshot,AggregateKey key);
    Optional<ResourceId> metadataOwner(MetadataReadSnapshot snapshot,AggregateKey key);
    Optional<Map<AggregateKind,Sequence>> metadataHeadVersions(MetadataReadSnapshot snapshot,Set<AggregateKind> kinds);
    Optional<Map<PlayerId,Sequence>> metadataSignificantVersions(MetadataReadSnapshot snapshot,Set<PlayerId> players);
    CompletionStage<StoreReadResult> readMetadata(MetadataReadSnapshot snapshot,StoreQuery query);
    CompletionStage<Optional<LocationImport>> readMetadataSource(MetadataReadSnapshot snapshot,ResourceId source);
    Optional<StoreTransactionReadback> readback(TransactionId transaction);
}
