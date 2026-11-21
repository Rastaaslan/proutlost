package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayersPresent(int minimum) implements Condition {
 public PlayersPresent { nonnegative(minimum);  }
}
