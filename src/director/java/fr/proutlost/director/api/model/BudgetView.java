package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record BudgetView(BudgetVector capacity, BudgetVector reserved, Map<ScopeRef,BudgetVector> scopes) {
 public BudgetView { Objects.requireNonNull(capacity); Objects.requireNonNull(reserved); scopes=map(scopes);  }
}
