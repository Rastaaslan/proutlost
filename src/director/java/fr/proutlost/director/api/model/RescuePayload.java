package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RescuePayload(Optional<PlayerId> target, boolean self) implements AdminPayload {
 public RescuePayload { Objects.requireNonNull(target); require(self!=target.isPresent(),"rescue target"); }
}
