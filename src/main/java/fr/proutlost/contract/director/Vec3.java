package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record Vec3(
    double x,
    double y,
    double z) {
    public Vec3 {
        Checks.require(Double.isFinite(x), "Nonfinite x");
        Checks.require(Double.isFinite(y), "Nonfinite y");
        Checks.require(Double.isFinite(z), "Nonfinite z");
    }
}
