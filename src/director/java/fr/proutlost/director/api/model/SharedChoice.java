package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SharedChoice(List<String> roles, TagId anchorTag, List<ResourceId> choiceIds, TimeoutPolicy timeoutPolicy) implements PrimitiveArgs {
 public SharedChoice { roles=list(roles); roles.forEach(Checks::text); require(!roles.isEmpty() && new HashSet<>(roles).size()==roles.size(),"choice roles"); Objects.requireNonNull(anchorTag); choiceIds=list(choiceIds); require(choiceIds.size()>=2 && new HashSet<>(choiceIds).size()==choiceIds.size(),"choice ids"); Objects.requireNonNull(timeoutPolicy);  }
}
