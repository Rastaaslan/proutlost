package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiCandidate(CandidateId candidateId, DefinitionId eventDefinitionId, Category category, Set<Family> families, Map<Feature,UnitValue> features, double deterministicScore, Map<String,UnitValue> normalizedCosts, Set<Pseudonym> targetPseudonyms, Set<TagId> contextTags) {
 public AiCandidate { Objects.requireNonNull(candidateId); Objects.requireNonNull(eventDefinitionId); Objects.requireNonNull(category); families=set(families); features=map(features); finite(deterministicScore); normalizedCosts=map(normalizedCosts); targetPseudonyms=set(targetPseudonyms); contextTags=set(contextTags);  }
}
