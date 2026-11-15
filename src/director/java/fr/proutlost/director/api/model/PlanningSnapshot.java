package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlanningSnapshot(SnapshotBatch snapshot, OwnershipView ownership, BudgetView budgets, BlobBundle blobs, Optional<PlanningBindings> bindings) {
 public PlanningSnapshot(SnapshotBatch snapshot,OwnershipView ownership,BudgetView budgets,BlobBundle blobs){this(snapshot,ownership,budgets,blobs,Optional.empty());}
 public PlanningSnapshot { Objects.requireNonNull(bindings); if(bindings.isPresent()){var b=bindings.orElseThrow();require(b.scopes().worldId().equals(snapshot.scopes().worldId()),"planning world");require(snapshot.context().players().keySet().containsAll(b.incidentalAudience())&&b.roles().roles().values().stream().allMatch(v->snapshot.context().players().keySet().containsAll(v)),"planning audience evidence");} Objects.requireNonNull(blobs); Objects.requireNonNull(snapshot); Objects.requireNonNull(ownership); Objects.requireNonNull(budgets);  }
}
