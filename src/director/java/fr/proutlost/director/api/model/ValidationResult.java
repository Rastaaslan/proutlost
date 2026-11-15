package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ValidationResult(boolean valid, List<fr.proutlost.director.api.error.ReasonCode> reasons, ReadSet checkedVersions) {
 public ValidationResult { reasons=list(reasons); Objects.requireNonNull(checkedVersions); require(valid==reasons.isEmpty(),"validation result"); }
}
