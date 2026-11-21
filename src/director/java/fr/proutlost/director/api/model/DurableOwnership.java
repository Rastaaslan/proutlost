package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DurableOwnership(OwnershipRecord value) implements DurableRecord {
 public DurableOwnership { Objects.requireNonNull(value);  }
}
