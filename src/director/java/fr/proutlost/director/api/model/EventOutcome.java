package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventOutcome(Outcome status, Set<FactId> facts, List<fr.proutlost.director.api.error.ReasonCode> reasons) {
 public EventOutcome { Objects.requireNonNull(status); facts=set(facts); reasons=list(reasons);  }
}
