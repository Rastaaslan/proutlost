package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record BlobRef(Fingerprint hash, long bytes, int codecVersion, ResourceId codecId) {
 public BlobRef { Objects.requireNonNull(hash); nonnegative(bytes); nonnegative(codecVersion); Objects.requireNonNull(codecId); require(codecVersion>0,"codec version"); }
}
