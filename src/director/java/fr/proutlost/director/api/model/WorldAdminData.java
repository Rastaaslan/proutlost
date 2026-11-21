package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record WorldAdminData(DirectorWorldState state, BudgetView budgets, Map<ResourceId,Long> queueDepths, List<SystemError> errors) implements AdminData {
 public WorldAdminData { Objects.requireNonNull(state); Objects.requireNonNull(budgets); queueDepths=map(queueDepths); errors=list(errors);  }
}
