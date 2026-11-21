package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AllFacts(List<FactPredicate> predicates) implements FactPredicate {
 public AllFacts { predicates=list(predicates); require(!predicates.isEmpty(),"predicate"); }
}
