package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record PlanningRequest(EventStep step,PlanningSnapshot snapshot) {public PlanningRequest {Objects.requireNonNull(step);Objects.requireNonNull(snapshot);snapshot.bindings().ifPresent(b->require(b.stepId().equals(step.id()),"planning step binding"));}}
