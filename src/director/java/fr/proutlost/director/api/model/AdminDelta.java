package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminDelta(UUID subscriptionId, Revision fromRevision, Revision toRevision, List<AdminRow> changedRows) {
 public AdminDelta { Objects.requireNonNull(subscriptionId); Objects.requireNonNull(fromRevision); Objects.requireNonNull(toRevision); changedRows=list(changedRows); require(toRevision.compareTo(fromRevision)>0&&changedRows.size()<=50,"delta bounds"); }
}
