package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ExecutionPlan(CandidateId candidateId, DefinitionRef definition, List<EffectIntent> effects, RoleAssignments roles, ScopeSet scopes, ReadSet readSet) {
 public ExecutionPlan { Objects.requireNonNull(candidateId); Objects.requireNonNull(definition); effects=list(effects); Objects.requireNonNull(roles); Objects.requireNonNull(scopes); Objects.requireNonNull(readSet);  }
}
