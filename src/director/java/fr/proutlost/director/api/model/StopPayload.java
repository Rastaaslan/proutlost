package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record StopPayload(Optional<EventInstanceId> instanceId, boolean all, String reason) implements AdminPayload {
 public StopPayload { Objects.requireNonNull(instanceId); text(reason); require(all!=instanceId.isPresent(),"stop scope"); }
}
