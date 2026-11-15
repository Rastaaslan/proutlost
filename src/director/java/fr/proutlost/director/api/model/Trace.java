package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Trace(TagId anchorTag, TagId stateTag, boolean persistent) implements PrimitiveArgs {
 public Trace { Objects.requireNonNull(anchorTag); Objects.requireNonNull(stateTag);  }
}
