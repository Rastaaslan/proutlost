package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiAdjustment(CandidateId candidateId, double deltaLogWeight) {
 public AiAdjustment { Objects.requireNonNull(candidateId); finite(deltaLogWeight);  }
}
