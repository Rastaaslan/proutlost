package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Selection(double baseWeight, double scoreFloor) {
 public Selection { finite(baseWeight); finite(scoreFloor); require(baseWeight>0 && scoreFloor>=0,"selection"); }
}
