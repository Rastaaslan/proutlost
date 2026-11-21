package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;

/**
 * J07 read-only EVENT/DISASTER/CHAIN projection from one verified J04 WAL cut.
 * Mutation details are shown only when every referenced mutation is resident,
 * belongs to the selected event and is read at exactly the caller's WAL cut.
 * Claims/effects and consumed chain facts do not yet have a complete native
 * join: never render a misleading empty list for those references.
 * No mutation authority, world access or disk IO is created by this class.
 */
public final class AdminActiveWalProjection {
    private static final int MAX_MUTATIONS_PER_ROW = 16;
    private AdminActiveWalProjection() {}

    public static Optional<AdminRow> row(DurableStore store, AggregateState state,
            AdminFilter filter, WorldId world, Sequence cut) {
        return row(store,state,filter,world,cut,Set.of()); // Default/production remains STRICT.
    }
    /** Trusted native bootstrap only. Not a client flag, permit or admission. */
    public static Optional<AdminRow> nativeTestOnlyRow(DurableStore store,AggregateState state,
            AdminFilter filter,WorldId world,Sequence cut,ProviderMode mode,Set<DefinitionId> knownDefinitions) {
        Objects.requireNonNull(knownDefinitions);
        if(mode!=ProviderMode.TEST_ONLY||knownDefinitions.size()!=9)return Optional.empty();
        return row(store,state,filter,world,cut,Set.copyOf(knownDefinitions));
    }
    private static Optional<AdminRow> row(DurableStore store,AggregateState state,
            AdminFilter filter,WorldId world,Sequence cut,Set<DefinitionId> rawNativeDefinitions) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(state);
        Objects.requireNonNull(filter);
        Objects.requireNonNull(world);
        Objects.requireNonNull(cut);
        try {
            var session=store.session();
            if (!session.worldId().equals(world)) return Optional.empty();
            if (state instanceof EventAggregate aggregate && filter instanceof EventFilter selected) {
                EventInstance event = aggregate.value();
                // J04 key routing alone does not certify the embedded header:
                // never display a forged identity or a future WAL revision.
                if (!event.header().id().equals(event.instanceId().value())
                        || event.header().lastSequence().compareTo(cut) > 0)
                    return Optional.empty();
                if (selected.instanceId().filter(id -> !id.equals(event.instanceId())).isPresent()
                        || !selected.states().isEmpty() && !selected.states().contains(event.state()))
                    return Optional.empty();
                // Default/production stays strict. The trusted native TEST_ONLY
                // path may expose known EVENT raw IDs, labelled unjoined; it
                // never fabricates claims/effect details or relaxes mutations.
                if ((!event.reservationRefs().isEmpty() || !event.effectRefs().isEmpty()) &&
                        !rawNativeDefinitions.contains(event.definition().id()))
                    return Optional.empty();
                var mutations = mutations(store, event.instanceId(), event.mutationRefs(), world, cut,session);
                if (mutations.isEmpty() || !session.equals(store.session())) return Optional.empty();
                return Optional.of(new AdminRow(
                    new ScopeRef(ScopeKind.EVENT, event.instanceId().value()),
                    event.header().revision(), event.header().updatedTick(),
                    new EventAdminData(event, mutations.orElseThrow(), List.of(), List.of())));
            }
            if (state instanceof DisasterAggregate aggregate && filter instanceof EventFilter selected) {
                var disaster=aggregate.value();
                if (!disaster.header().id().equals(disaster.disasterId().value())
                        || disaster.header().lastSequence().compareTo(cut)>0
                        || !selected.states().isEmpty()
                        || selected.instanceId().filter(id->!id.equals(disaster.eventInstanceId())).isPresent())
                    return Optional.empty();
                // Validate actual referenced mutation records directly against the disaster's
                // real event ID. No EventAggregate is invented for this join.
                var joined=mutations(store,disaster.eventInstanceId(),disaster.mutationRefs(),world,cut,session);
                if(joined.isEmpty() || !session.equals(store.session()))return Optional.empty();
                return Optional.of(new AdminRow(
                    new ScopeRef(ScopeKind.EVENT,disaster.eventInstanceId().value()),
                    disaster.header().revision(),disaster.header().updatedTick(),
                    new DisasterAdminData(disaster)));
            }
            if (state instanceof ChainAggregate aggregate && filter instanceof ChainFilter selected) {
                var chain = aggregate.value();
                if (!chain.header().id().equals(chain.chainId().value())
                        || chain.header().lastSequence().compareTo(cut) > 0)
                    return Optional.empty();
                if (selected.chainId().filter(id -> !id.equals(chain.chainId())).isPresent()
                        || !chain.consumedFactIds().isEmpty())
                    return Optional.empty();
                return Optional.of(new AdminRow(
                    new ScopeRef(ScopeKind.WORLD, world.value()),
                    chain.header().revision(), chain.header().updatedTick(),
                    new ChainAdminData(chain, List.of())));
            }
            return Optional.empty();
        } catch (RuntimeException unavailableOrCorrupt) {
            // Incomplete/invalid WAL joins are unknown, never an empty success.
            return Optional.empty();
        }
    }

    public static Optional<List<MutationRecord>> mutationsForEvent(DurableStore store,EventInstanceId event,
            Set<MutationId> references,WorldId world,Sequence cut){
        return mutations(store,event,references,world,cut,store.session());
    }
    private static Optional<List<MutationRecord>> mutations(DurableStore store,
            EventInstanceId eventId, Set<MutationId> references, WorldId world, Sequence cut,StoreSession session) {
        if (references.isEmpty()) return Optional.of(List.of());
        if (references.size() > MAX_MUTATIONS_PER_ROW)
            return Optional.empty();
        Set<AggregateKey> keys = new HashSet<>();
        for (MutationId id : references)
            keys.add(new AggregateKey(AggregateKind.MUTATION, id.value()));
        if (keys.size() != references.size()) return Optional.empty();
        var query = new StoreQuery(world, keys, new Sequence(0),
            Optional.empty(), keys.size());
        var read = store.view(query);
        ReadContracts.validate(read, query, session);
        if (read.status() != ReadStatus.AVAILABLE || !session.equals(store.session())) return Optional.empty();
        var page = read.page().orElseThrow();
        if (!page.lastDurableSequence().equals(cut) || page.hasMore()
                || page.nextCursor().isPresent() || !page.records().isEmpty()
                || page.aggregates().size() != keys.size())
            return Optional.empty();
        Map<MutationId,MutationRecord> joined = new TreeMap<>();
        for (var restored : page.aggregates()) {
            if (!(restored.state() instanceof MutationAggregate aggregate))
                return Optional.empty();
            var mutation = aggregate.value();
            if (!keys.contains(restored.state().key())
                    || restored.committedSequence().compareTo(cut) > 0
                    || !mutation.instanceId().equals(eventId)
                    || !mutation.header().id().equals(mutation.mutationId().value())
                    || mutation.durableSeq().compareTo(cut) > 0
                    || joined.putIfAbsent(mutation.mutationId(), mutation) != null)
                return Optional.empty();
        }
        return joined.keySet().equals(references)
            ? Optional.of(List.copyOf(joined.values())) : Optional.empty();
    }
}