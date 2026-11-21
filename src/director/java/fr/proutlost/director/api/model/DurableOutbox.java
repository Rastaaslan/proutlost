package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DurableOutbox(DirectorEnvelope value) implements DurableRecord {
 public DurableOutbox { Objects.requireNonNull(value);  }
}
