package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Candidate(CandidateId id, DefinitionRef definition, boolean eligible, List<fr.proutlost.director.api.error.ReasonCode> reasons, List<ScoreContribution> contributions, double score, RoleAssignments roles, ScopeSet scopes) {
 public Candidate { Objects.requireNonNull(id); Objects.requireNonNull(definition); reasons=list(reasons); contributions=list(contributions); finite(score); Objects.requireNonNull(roles); Objects.requireNonNull(scopes);  }
}
