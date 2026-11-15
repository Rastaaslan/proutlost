package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ConfigView(Revision revision, Fingerprint provenanceHash, Calibration calibration, Map<String,JsonValue.Number> values) {
 public ConfigView { Objects.requireNonNull(revision); Objects.requireNonNull(provenanceHash); Objects.requireNonNull(calibration); values=map(values);  }
}
