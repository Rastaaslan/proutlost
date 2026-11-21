package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdmissionRequest(DecisionId proposalId, ReadSet expectedRevisions, List<ResourceClaim> claims, RoleAssignments targetBindings, ExecutionPlan plan) {
 public AdmissionRequest { Objects.requireNonNull(proposalId); Objects.requireNonNull(expectedRevisions); claims=list(claims); Objects.requireNonNull(targetBindings); Objects.requireNonNull(plan);  }
}
