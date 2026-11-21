package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminRow(ScopeRef scope, Revision revision, Tick capturedTick, AdminData data) {
 public AdminRow { Objects.requireNonNull(scope); Objects.requireNonNull(revision); Objects.requireNonNull(capturedTick); Objects.requireNonNull(data);  }
}
