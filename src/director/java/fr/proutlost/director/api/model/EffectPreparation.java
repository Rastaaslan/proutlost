package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EffectPreparation(EffectIntent intent, ValidationResult validation, Optional<PreparedEffect> prepared, BlobBundle blobs) {
 public EffectPreparation { Objects.requireNonNull(blobs); Objects.requireNonNull(intent); Objects.requireNonNull(validation); Objects.requireNonNull(prepared);  }
}
