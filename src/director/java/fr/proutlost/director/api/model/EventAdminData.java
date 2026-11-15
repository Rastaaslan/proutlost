package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventAdminData(EventInstance event, List<MutationRecord> mutations, List<ResourceClaim> claims, List<DirectorFact> facts) implements AdminData {
 public EventAdminData { Objects.requireNonNull(event); mutations=list(mutations); claims=list(claims); facts=list(facts);  }
}
