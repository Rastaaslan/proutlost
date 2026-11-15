package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventDefinition(int schemaVersion, Revision contentRevision, DefinitionId id, Calibration calibration, Category category, Set<Family> families, boolean enabled, Selection selection, Target target, Condition eligibility, Timing timing, Uniqueness uniqueness, BudgetVector budget, Conflicts conflicts, Safety safety, List<EventStep> steps, RecoveryPolicy recovery, Completion completion) {
 public EventDefinition { nonnegative(schemaVersion); Objects.requireNonNull(contentRevision); Objects.requireNonNull(id); Objects.requireNonNull(calibration); Objects.requireNonNull(category); families=set(families); Objects.requireNonNull(selection); Objects.requireNonNull(target); Objects.requireNonNull(eligibility); Objects.requireNonNull(timing); Objects.requireNonNull(uniqueness); Objects.requireNonNull(budget); Objects.requireNonNull(conflicts); Objects.requireNonNull(safety); steps=list(steps); Objects.requireNonNull(recovery); Objects.requireNonNull(completion); require(schemaVersion==1 && contentRevision.value()>0 && !steps.isEmpty() && !families.isEmpty(),"event contract"); }
}
