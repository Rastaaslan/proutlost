package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AnyConditions(List<Condition> conditions) implements Condition {
 public AnyConditions { conditions=list(conditions); require(!conditions.isEmpty(),"empty conditions"); }
}
