package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record GoatVisit(String role, TagId anchorTag) implements PrimitiveArgs {
 public GoatVisit { text(role); Objects.requireNonNull(anchorTag);  }
}
