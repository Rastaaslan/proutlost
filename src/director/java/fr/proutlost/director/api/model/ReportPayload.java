package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ReportPayload(String message, Set<CorrelationId> correlations) implements AdminPayload {
 public ReportPayload { text(message); correlations=set(correlations); require(!message.isBlank()&&message.length()<=1000,"report bound"); }
}
