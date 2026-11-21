package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SupportDisc(BlockPosition center, double radius) {
 public SupportDisc { Objects.requireNonNull(center); finite(radius); require(radius>=0,"radius"); }
}
