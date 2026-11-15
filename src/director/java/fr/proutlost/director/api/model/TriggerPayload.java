package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record TriggerPayload(DefinitionId eventId, RegionId regionId, Set<PlayerId> targets, Set<PlayerId> temporaryGroup) implements AdminPayload {
 public TriggerPayload { Objects.requireNonNull(eventId); Objects.requireNonNull(regionId); targets=set(targets); temporaryGroup=set(temporaryGroup);  }
}
