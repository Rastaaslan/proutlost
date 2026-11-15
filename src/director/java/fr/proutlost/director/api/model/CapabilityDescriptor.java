package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CapabilityDescriptor(CapabilityId id, int version, ProviderMode mode, CapabilityStatus status, ResourceId providerId, Set<String> qualificationTests) {
 public CapabilityDescriptor { Objects.requireNonNull(id); nonnegative(version); Objects.requireNonNull(mode); Objects.requireNonNull(status); Objects.requireNonNull(providerId); qualificationTests=set(qualificationTests); require(version>0,"version"); }
}
