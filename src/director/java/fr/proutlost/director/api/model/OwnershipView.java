package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record OwnershipView(Map<ResourceKey,OwnershipRecord> records) {
 public OwnershipView { records=map(records);  }
}
