package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DirectorRequirement(RequestId requestId, Selector selector, TargetConstraints targetConstraints, FactPredicate requiredFactPredicate, FulfillmentMode fulfillmentMode) implements RequestPayload {
 public DirectorRequirement { Objects.requireNonNull(requestId); Objects.requireNonNull(selector); Objects.requireNonNull(targetConstraints); Objects.requireNonNull(requiredFactPredicate); Objects.requireNonNull(fulfillmentMode);  }
}
