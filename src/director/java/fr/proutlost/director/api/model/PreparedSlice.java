package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PreparedSlice(SliceId sliceId, OperationToken operationToken, Footprint footprint, ReadSet preconditions, List<EffectIntent> operations, BudgetVector maximumWork) {
 public PreparedSlice { Objects.requireNonNull(sliceId); Objects.requireNonNull(operationToken); Objects.requireNonNull(footprint); Objects.requireNonNull(preconditions); operations=list(operations); Objects.requireNonNull(maximumWork);  }
}
