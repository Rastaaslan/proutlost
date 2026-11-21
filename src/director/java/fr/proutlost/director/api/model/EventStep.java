package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EventStep(String id, List<String> after, DurationTicks delayTicks, PrimitiveId primitive, PrimitiveArgs args, OnFailure onFailure) {
 public EventStep { text(id); require(id.matches("^[a-z][a-z0-9_]*$"),"step id"); after=list(after); after.forEach(Checks::text); require(new HashSet<>(after).size()==after.size(),"duplicate predecessor"); Objects.requireNonNull(delayTicks); Objects.requireNonNull(primitive); Objects.requireNonNull(args); Objects.requireNonNull(onFailure);  }
}
