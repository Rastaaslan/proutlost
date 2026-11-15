package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ChainInstance(AggregateHeader header, ChainId chainId, DefinitionRef recipe, ChainMode mode, ChainState state, Set<String> currentNodes, Set<FactId> consumedFactIds, Set<RegionId> lineageRefs, List<ResourceId> plannedHorizon, List<BranchHistory> branchHistory, Optional<ResourceId> regionalStateRef, UnitValue relevance, Tick nextEvaluationTick) {
 public ChainInstance { Objects.requireNonNull(header); Objects.requireNonNull(chainId); Objects.requireNonNull(recipe); Objects.requireNonNull(mode); Objects.requireNonNull(state); currentNodes=set(currentNodes); consumedFactIds=set(consumedFactIds); lineageRefs=set(lineageRefs); plannedHorizon=list(plannedHorizon); branchHistory=list(branchHistory); Objects.requireNonNull(regionalStateRef); Objects.requireNonNull(relevance); Objects.requireNonNull(nextEvaluationTick);  }
}
