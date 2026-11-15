package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record LaboratoryAdminData(ResourceId fixtureId, DecisionProposal proposal, List<fr.proutlost.director.api.error.ReasonCode> admissionReasons) implements AdminData {
 public LaboratoryAdminData { Objects.requireNonNull(fixtureId); Objects.requireNonNull(proposal); admissionReasons=list(admissionReasons);  }
}
