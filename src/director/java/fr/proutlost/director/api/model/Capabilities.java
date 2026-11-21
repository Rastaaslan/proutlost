package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Capabilities(int protocolVersion, Epoch connectionEpoch, Set<PerceptionKind> supportedEffects, Map<ResourceId,Fingerprint> assetHashes) {
 public Capabilities { nonnegative(protocolVersion); Objects.requireNonNull(connectionEpoch); supportedEffects=set(supportedEffects); assetHashes=map(assetHashes); require(protocolVersion==1,"protocol version"); }
}
