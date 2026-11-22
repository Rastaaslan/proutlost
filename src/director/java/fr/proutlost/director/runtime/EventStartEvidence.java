package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.EventState;
import fr.proutlost.director.api.model.Tick;
import java.util.Objects;
import java.util.Optional;

/** Records when this actor first durably observes real execution, never admission alone. */
public final class EventStartEvidence {
    private EventStartEvidence() {}
    public static Optional<Tick> observe(Optional<Tick> previous, EventState transition,
            Tick observedAt, boolean actualExecutionObserved) {
        Objects.requireNonNull(previous);Objects.requireNonNull(transition);Objects.requireNonNull(observedAt);
        if(previous.isPresent())return previous;
        return transition==EventState.RUNNING&&actualExecutionObserved?Optional.of(observedAt):Optional.empty();
    }
}
