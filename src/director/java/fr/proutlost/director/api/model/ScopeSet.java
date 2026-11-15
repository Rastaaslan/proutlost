package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ScopeSet(WorldId worldId, Set<ScopeRef> scopes) {
 public ScopeSet { Objects.requireNonNull(worldId); scopes=set(scopes);  }
}
