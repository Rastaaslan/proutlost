package fr.proutlost.worldprep.orchestration;

import java.io.IOException;

/** Minimum physical proof before publishing any v4 catalogue to Director.
 * Complete sweeps of NO_ELIGIBLE_* records are not playable map coverage.
 * This is deliberately only a lower-bound gate, not a complete gameplay audit.
 */
public final class PerimeterPhysicalCompletion {
    public static void requireNonempty(long groups, long placedStructures, long verifiedFauna)
            throws IOException {
        if(groups<1 || groups>64 || placedStructures<0 || verifiedFauna<0
                ||placedStructures>groups || verifiedFauna>groups)
            throw new IOException("Invalid perimeter coverage counts");
        if(placedStructures==0)
            throw new IOException("V4_NOT_READY: no physically placed and disk-verified structures");
        if(verifiedFauna==0)
            throw new IOException("V4_NOT_READY: no physically populated and disk-verified fauna");
    }

    private PerimeterPhysicalCompletion() {}
}
