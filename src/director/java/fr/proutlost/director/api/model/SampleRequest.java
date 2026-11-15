package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SampleRequest(RequestId requestId, ScopeSet scopes, Set<SensorKind> sensorSet, BudgetVector maxWork) {
 public SampleRequest { Objects.requireNonNull(requestId); Objects.requireNonNull(scopes); sensorSet=set(sensorSet); Objects.requireNonNull(maxWork);  }
}
