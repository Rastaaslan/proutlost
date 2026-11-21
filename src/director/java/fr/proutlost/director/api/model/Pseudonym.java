package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Pseudonym(String value) {
 public Pseudonym { text(value); require(value.matches("p_[a-z0-9]{16,64}"),"opaque pseudonym"); }
}
