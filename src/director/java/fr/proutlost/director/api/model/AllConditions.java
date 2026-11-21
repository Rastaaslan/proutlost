package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AllConditions(List<Condition> conditions) implements Condition {
 public AllConditions { conditions=list(conditions); require(!conditions.isEmpty(),"empty conditions"); }
}
