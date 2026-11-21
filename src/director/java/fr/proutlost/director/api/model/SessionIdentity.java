package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SessionIdentity(WorldId worldId, Epoch bootEpoch, Epoch connectionEpoch, DimensionId dimension, PlayerId playerId) {
 public SessionIdentity { Objects.requireNonNull(worldId); Objects.requireNonNull(bootEpoch); Objects.requireNonNull(connectionEpoch); Objects.requireNonNull(dimension); Objects.requireNonNull(playerId);  }
}
