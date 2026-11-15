package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AiArcProposal(ChainId chainId, List<ResourceId> transitionIds) {
 public AiArcProposal { Objects.requireNonNull(chainId); transitionIds=list(transitionIds); require(!transitionIds.isEmpty(),"empty arc"); }
}
