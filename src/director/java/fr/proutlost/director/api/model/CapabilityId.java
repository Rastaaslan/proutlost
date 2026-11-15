package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CapabilityId(String value) {
 public CapabilityId { text(value); require(value.matches("[a-z0-9_]+(?:[.][a-z0-9_]+)+"),"capability id"); }
}
