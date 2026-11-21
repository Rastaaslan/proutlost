package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Role(int min, int max, boolean overlap) {
 public Role { nonnegative(min); nonnegative(max); require(min<=max,"role bounds"); }
}
