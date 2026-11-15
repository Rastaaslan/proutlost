package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EffectBlobs(Set<BlobRef> snapshots, BlobRef preparedPlan, BlobRef stopPlan, Map<ResourceKey,BlobRef> preStates, Map<ResourceKey,BlobRef> expectedPostStates) {
 public EffectBlobs { snapshots=set(snapshots); Objects.requireNonNull(preparedPlan); Objects.requireNonNull(stopPlan); preStates=map(preStates); expectedPostStates=map(expectedPostStates);  }
}
