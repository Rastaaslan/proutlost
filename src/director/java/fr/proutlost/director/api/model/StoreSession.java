package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record StoreSession(WorldId worldId, Epoch storeGeneration, Epoch bootEpoch, SessionId authoritySession) {
 public StoreSession { Objects.requireNonNull(worldId); Objects.requireNonNull(storeGeneration); Objects.requireNonNull(bootEpoch); Objects.requireNonNull(authoritySession);  }
}
