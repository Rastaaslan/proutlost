package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ReadSet(Map<ScopeRef,Revision> revisions, Map<ResourceKey,Epoch> epochs, Map<ResourceKey,Fingerprint> fingerprints) {
 public ReadSet { revisions=map(revisions); epochs=map(epochs); fingerprints=map(fingerprints);  }
}
