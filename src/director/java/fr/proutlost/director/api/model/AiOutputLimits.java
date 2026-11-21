package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiOutputLimits(int maximumBytes, int maximumAdjustments, int maximumArcs, int maximumTransitions, double maxAbsLogDelta, BudgetVector arcBudget) {
 public AiOutputLimits { nonnegative(maximumBytes); nonnegative(maximumAdjustments); nonnegative(maximumArcs); nonnegative(maximumTransitions); finite(maxAbsLogDelta); Objects.requireNonNull(arcBudget); require(maximumBytes>0&&maximumBytes<=1048576&&maxAbsLogDelta>=0,"AI limits"); }
}
