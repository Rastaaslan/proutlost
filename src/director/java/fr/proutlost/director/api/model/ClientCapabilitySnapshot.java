package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ClientCapabilitySnapshot(int protocolVersion, Set<ResourceId> effectKinds, Map<ResourceId,Fingerprint> assets, boolean connected) {
 public ClientCapabilitySnapshot { nonnegative(protocolVersion); effectKinds=set(effectKinds); assets=map(assets); require(protocolVersion==1,"protocol"); }
}
