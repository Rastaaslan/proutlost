package fr.proutlost.director.api.model;
import java.util.*;
/** Fresh actor-thread capture immediately before stop; immutable data, no live Minecraft reference.
 * Ownership includes playerOverrideEpoch/owner tokens/disposition, not just the aggregate revision.
 */
public record StabilizationLiveSnapshot(StoreSession session, LiveValidationSnapshot validation, OwnershipView ownership) {
 public StabilizationLiveSnapshot {Objects.requireNonNull(session);Objects.requireNonNull(validation);Objects.requireNonNull(ownership);}
}
