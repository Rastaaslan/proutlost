package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DecisionInput(SnapshotBatch context, MemoryView memory, CatalogSnapshot catalog, ConfigView config, List<RequestView> requests, List<ChainInstance> chains, long seed, DecisionState state, Optional<AiProposalResult> advisorySnapshot) {
 public DecisionInput { Objects.requireNonNull(state); Objects.requireNonNull(advisorySnapshot); Objects.requireNonNull(context); Objects.requireNonNull(memory); Objects.requireNonNull(catalog); Objects.requireNonNull(config); requests=list(requests); chains=list(chains);   }
}
