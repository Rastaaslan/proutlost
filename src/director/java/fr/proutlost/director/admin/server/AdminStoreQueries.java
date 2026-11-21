package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;

/** One bounded in-memory store page per call. A missing audit is not reported as absent
 * until the caller has exhausted the continuation. No blob/disk reads occur on the actor. */
public final class AdminStoreQueries {
    public record AuditPage(Optional<AuditDecision> audit,Optional<String> continuation,Sequence durableSequence) {}
    private final DurableStore store;
    public AdminStoreQueries(DurableStore store) {this.store=Objects.requireNonNull(store);}
    private StoreView requireResident(StoreQuery query) {
        var result = store.view(query);
        ReadContracts.validate(result, query, store.session());
        if (result.status() != ReadStatus.AVAILABLE)
            throw new IllegalStateException("Admin store view not resident: " + result.status());
        return result.page().orElseThrow();
    }
    public AuditPage audit(DecisionId id,StoreQuery query) {
        var page=requireResident(query);
        if(page.hasMore()!=page.nextCursor().isPresent()) throw new IllegalStateException("Inconsistent store pagination");
        var audit=page.records().stream().filter(DurableAudit.class::isInstance).map(DurableAudit.class::cast)
                .map(DurableAudit::value).filter(a->a.decisionId().equals(id)).findFirst();
        return new AuditPage(audit,page.nextCursor(),page.lastDurableSequence());
    }
    public Optional<DirectorWorldState> world(WorldId id) {
        var page=requireResident(new StoreQuery(id,Set.of(new AggregateKey(AggregateKind.WORLD,id.value())),new Sequence(0),Optional.empty(),1));
        return page.aggregates().stream().map(RestoredAggregate::state).filter(WorldAggregate.class::isInstance)
                .map(WorldAggregate.class::cast).map(WorldAggregate::value).filter(w->w.worldId().equals(id)).findFirst();
    }
    public Optional<DisasterAdminData> disaster(WorldId world,DisasterId id) {
        var page=requireResident(new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.DISASTER,id.value())),new Sequence(0),Optional.empty(),1));
        return page.aggregates().stream().map(RestoredAggregate::state).filter(DisasterAggregate.class::isInstance)
                .map(DisasterAggregate.class::cast).map(DisasterAggregate::value).filter(d->d.disasterId().equals(id)).map(DisasterAdminData::new).findFirst();
    }
}
