package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Warning(String role, ResourceId profile, DurationTicks minimumLeadTicks) implements PrimitiveArgs {
 public Warning { text(role); Objects.requireNonNull(profile); Objects.requireNonNull(minimumLeadTicks);  }
}
