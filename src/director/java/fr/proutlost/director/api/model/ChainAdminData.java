package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ChainAdminData(ChainInstance chain, List<DirectorFact> consumedFacts) implements AdminData {
 public ChainAdminData { Objects.requireNonNull(chain); consumedFacts=list(consumedFacts);  }
}
