package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.admin.server.NativeReadCommandFence;
import fr.proutlost.director.persistence.JournalStore;
import java.util.*;

/** Explicit, bounded read of a real MutationAggregate and current resource owners.
 * No EventAggregate, ownership UUID, blob content, WAL index or write is created.
 * The caller independently authenticates the actual session/OP4 before entry. */
public final class DirectorNativeMutationReadBridge {
    private DirectorNativeMutationReadBridge() {}
    private static final int MAX_RESOURCES=16,MAX_RESIDENT=4096,MAX_WIRE_BYTES=22000;

    private static boolean bound(DurableStore store,StoreSession session,DirectorWorldState world) {
        return session.equals(store.session()) && session.worldId().equals(world.worldId()) &&
            session.bootEpoch().equals(world.bootEpoch()) &&
            session.storeGeneration().equals(world.storeGeneration());
    }
    private static Optional<JournalStore.ResidentAggregateKeys> ownershipKeys(DurableStore store,Set<ResourceKey> resources) {
        if(store instanceof DirectorLabReadOnlyStore readOnly)
            return readOnly.residentOwnershipKeys(resources,MAX_RESIDENT);
        if(store instanceof JournalStore journal)
            return journal.residentOwnershipKeys(resources,MAX_RESIDENT);
        return Optional.empty();
    }
    private static StoreView read(DurableStore store,StoreSession session,StoreQuery query) {
        var result=store.view(query);
        ReadContracts.validate(result,query,session);
        if(result.status()!=ReadStatus.AVAILABLE || !session.equals(store.session()))
            throw new IllegalStateException("mutation inspection unavailable");
        var page=result.page().orElseThrow();
        if(page.hasMore() || page.nextCursor().isPresent() || !page.records().isEmpty())
            throw new IllegalStateException("mutation inspection must be exact keyed read");
        return page;
    }
    private static Set<ResourceKey> resources(MutationRecord mutation) {
        Set<ResourceKey> resources=new HashSet<>();
        for(var position:mutation.footprint().cells())
            resources.add(new ResourceKey(OwnershipDomain.BLOCK,position.dimension(),
                position.x()+","+position.y()+","+position.z()));
        resources.addAll(mutation.readSetVersions().epochs().keySet());
        resources.addAll(mutation.readSetVersions().fingerprints().keySet());
        if(resources.size()>MAX_RESOURCES)
            throw new IllegalStateException("mutation resources exceed inspection bound");
        return Set.copyOf(resources);
    }
    private static Optional<AdminSnapshot> snapshot(AdminQuery request,DirectorWorldState world,
            Sequence cut,List<AdminRow> rows) {
        var result=new AdminSnapshot(request.requestId(),world.header().revision(),world.simTick(),
            cut,rows,Optional.empty(),NativeReadCommandFence.supported());
        return new ModelCodec<>(AdminSnapshot.class).encode(result).length<=MAX_WIRE_BYTES?
            Optional.of(result):Optional.empty();
    }
    public static Optional<AdminSnapshot> page(DurableStore store,AdminQuery request,DirectorWorldState world) {
        Objects.requireNonNull(store);Objects.requireNonNull(request);Objects.requireNonNull(world);
        try {
            var session=store.session();
            if(request.panel()!=AdminPanel.ACTIVE || !(request.filter() instanceof MutationFilter filter) ||
               request.cursor().isPresent() || request.limit()>50 || !bound(store,session,world) ||
               request.expectedSnapshotRevision().filter(r->!r.equals(world.header().revision())).isPresent())
                return Optional.empty();
            // Verify this is the published operational map, including read-only/cap refusal,
            // even when the requested MutationId does not exist.
            var initial=ownershipKeys(store,Set.of());
            if(initial.isEmpty() || !initial.orElseThrow().session().equals(session))return Optional.empty();
            var cut=initial.orElseThrow().cut();
            var key=new AggregateKey(AggregateKind.MUTATION,filter.mutationId().value());
            var mutationPage=read(store,session,new StoreQuery(world.worldId(),Set.of(key),
                new Sequence(0),Optional.empty(),1));
            if(!mutationPage.lastDurableSequence().equals(cut) || !bound(store,session,world))
                return Optional.empty();
            if(mutationPage.aggregates().isEmpty())return snapshot(request,world,cut,List.of());
            if(mutationPage.aggregates().size()!=1)return Optional.empty();
            var restored=mutationPage.aggregates().getFirst();
            if(!(restored.state() instanceof MutationAggregate aggregate) ||
               !aggregate.key().equals(key) || restored.committedSequence().compareTo(cut)>0)
                return Optional.empty();
            var mutation=aggregate.value();
            if(!mutation.mutationId().equals(filter.mutationId()) ||
               !mutation.header().id().equals(mutation.mutationId().value()) ||
               mutation.header().lastSequence().compareTo(cut)>0 || mutation.durableSeq().compareTo(cut)>0)
                return Optional.empty();
            var resources=resources(mutation);
            var discovered=ownershipKeys(store,resources);
            if(discovered.isEmpty() || !discovered.orElseThrow().session().equals(session) ||
               !discovered.orElseThrow().cut().equals(cut) || !bound(store,session,world))
                return Optional.empty();
            var ownerKeys=discovered.orElseThrow().keys();
            if(ownerKeys.size()>MAX_RESOURCES)return Optional.empty();
            Map<ResourceKey,OwnershipRecord> owners=new HashMap<>();
            if(!ownerKeys.isEmpty()) {
                var ownersPage=read(store,session,new StoreQuery(world.worldId(),ownerKeys,
                    new Sequence(0),Optional.empty(),ownerKeys.size()));
                if(!ownersPage.lastDurableSequence().equals(cut) ||
                   ownersPage.aggregates().size()!=ownerKeys.size())return Optional.empty();
                for(var entry:ownersPage.aggregates()) {
                    if(!(entry.state() instanceof OwnershipAggregate ownerAggregate) ||
                       !ownerKeys.contains(ownerAggregate.key()) ||
                       entry.committedSequence().compareTo(cut)>0)return Optional.empty();
                    var owner=ownerAggregate.value();
                    if(!resources.contains(owner.resourceKey()) || owner.header().lastSequence().compareTo(cut)>0 ||
                       owners.putIfAbsent(owner.resourceKey(),owner)!=null)return Optional.empty();
                }
            }
            if(!bound(store,session,world))return Optional.empty();
            // Scope uses the real linked event identity; the data and query remain MUTATION.
            // A current owner may belong to another mutation after resource reuse.
            var row=new AdminRow(new ScopeRef(ScopeKind.EVENT,mutation.instanceId().value()),
                mutation.header().revision(),mutation.header().updatedTick(),
                new MutationAdminData(mutation,new OwnershipView(owners),resources));
            return snapshot(request,world,cut,List.of(row));
        } catch(RuntimeException staleCorruptUnavailableOrOversize) {
            return Optional.empty();
        }
    }
}
