package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PublicStatusData(HealthMode health, boolean localAnomaly, Set<fr.proutlost.director.api.error.ReasonCode> reasons) implements AdminData {
 public PublicStatusData { Objects.requireNonNull(health); reasons=set(reasons);  }
}
