package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.api.port.ProviderProvenance;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Mandatory lab-only J04 projection boundary. NO append can pass through it;
 * even accidental access from a live J03/J05 coordinator cannot write WAL.
 * The projection is not admitted as a PRODUCTION provider.
 */
public final class DirectorLabReadOnlyStore
        implements DurableStore,DirectorBootstrap.TestOnlyBackend,ProviderProvenance {
    @Override public java.util.Set<ProviderMode> supportedModes(){return java.util.Set.of(ProviderMode.TEST_ONLY);}
    private final DurableStore verified;
    private final StoreSession frozenSession;
    public DirectorLabReadOnlyStore(DurableStore verified) {
        this.verified=Objects.requireNonNull(verified);
        frozenSession=verified.session();
    }
    private void bound(){
        if(!frozenSession.equals(verified.session()))
            throw new IllegalStateException("stale J04 recovered session");
    }
    /** Read-only key discovery from the same already recovered writer; no append is exposed. */
    public java.util.Optional<fr.proutlost.director.persistence.JournalStore.ResidentAggregateKeys> residentAggregateKeys(
            java.util.Set<AggregateKind> kinds,int maximumEntries) {
        bound();
        if(!(verified instanceof fr.proutlost.director.persistence.JournalStore journal))return java.util.Optional.empty();
        var discovered=journal.residentAggregateKeys(kinds,maximumEntries);
        bound();
        return discovered.filter(keys->keys.session().equals(frozenSession));
    }
    /** Resource-specific key discovery only; no write API is exposed. */
    public java.util.Optional<fr.proutlost.director.persistence.JournalStore.ResidentAggregateKeys> residentOwnershipKeys(
            java.util.Set<ResourceKey> resources,int maximumEntries) {
        bound();
        if(!(verified instanceof fr.proutlost.director.persistence.JournalStore journal))return java.util.Optional.empty();
        var discovered=journal.residentOwnershipKeys(resources,maximumEntries);
        bound();
        return discovered.filter(keys->keys.session().equals(frozenSession));
    }
    @Override public CompletionStage<CommitAck> append(DurableBatch forbidden) {
        throw new SecurityException("lab memory read projection cannot write WAL");
    }
    @Override public StoreReadResult view(StoreQuery query) {
        bound();
        if(!query.worldId().equals(frozenSession.worldId()))
            throw new IllegalArgumentException("foreign world read");
        return verified.view(query);
    }
    @Override public CompletionStage<StoreReadResult> read(StoreQuery query) {
        bound();
        if(!query.worldId().equals(frozenSession.worldId()))
            throw new IllegalArgumentException("foreign world read");
        return verified.read(query);
    }
    @Override public StoreSession session() {
        bound();
        return frozenSession;
    }
    @Override public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request) {
        bound();
        if(!request.worldId().equals(frozenSession.worldId()))
            throw new IllegalArgumentException("foreign world blob read");
        return verified.readBlobs(request);
    }
}
