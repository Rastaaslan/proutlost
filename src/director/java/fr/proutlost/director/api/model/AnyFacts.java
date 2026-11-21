package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AnyFacts(List<FactPredicate> predicates) implements FactPredicate {
 public AnyFacts { predicates=list(predicates); require(!predicates.isEmpty(),"predicate"); }
}
