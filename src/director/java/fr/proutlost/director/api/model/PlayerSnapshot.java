package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayerSnapshot(PlayerId playerId, Revision revision, Tick capturedTick, PlayerEligibility eligibility, PositionSnapshot position, Set<RegionId> regionIds, Set<UUID> groupIds, DurationTicks isolatedTicks, Set<ObservableReaction> observedReactions, ClientCapabilitySnapshot clientCapabilities, Set<TagId> contextTags) {
 public PlayerSnapshot { Objects.requireNonNull(playerId); Objects.requireNonNull(revision); Objects.requireNonNull(capturedTick); Objects.requireNonNull(eligibility); Objects.requireNonNull(position); regionIds=set(regionIds); groupIds=set(groupIds); Objects.requireNonNull(isolatedTicks); observedReactions=set(observedReactions); Objects.requireNonNull(clientCapabilities); contextTags=set(contextTags);  }
}
