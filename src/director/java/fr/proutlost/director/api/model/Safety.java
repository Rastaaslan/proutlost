package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record Safety(Risk risk, Set<CapabilityId> requiredCapabilities, Optional<ResourceId> mutationProfile, Optional<ResourceId> warningProfile, PlayerEditPolicy playerEditPolicy, JournalPolicy journalPolicy, ParticipantLoss participantLoss) {
 public Safety { Objects.requireNonNull(risk); requiredCapabilities=set(requiredCapabilities); Objects.requireNonNull(mutationProfile); Objects.requireNonNull(warningProfile); Objects.requireNonNull(playerEditPolicy); Objects.requireNonNull(journalPolicy); Objects.requireNonNull(participantLoss); require(risk!=Risk.CONTROLLED_DANGER || warningProfile.isPresent(),"warning profile required"); }
}
