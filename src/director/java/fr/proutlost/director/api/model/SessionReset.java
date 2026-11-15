package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SessionReset(int protocolVersion, WorldId worldId, Epoch bootEpoch, Epoch connectionEpoch, Set<PerceptionKind> authorizedEffects, Map<ResourceId,Fingerprint> authorizedAssets) {
 public SessionReset { nonnegative(protocolVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(bootEpoch); Objects.requireNonNull(connectionEpoch); authorizedEffects=set(authorizedEffects); authorizedAssets=map(authorizedAssets); require(protocolVersion==1,"protocol version"); }
}
