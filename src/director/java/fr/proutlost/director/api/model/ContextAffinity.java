package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record ContextAffinity(Condition predicate,UnitValue affinity,UnitValue weight) {public ContextAffinity {Objects.requireNonNull(predicate);Objects.requireNonNull(affinity);Objects.requireNonNull(weight);}}
