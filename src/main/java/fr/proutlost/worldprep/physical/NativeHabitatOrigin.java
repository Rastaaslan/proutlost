package fr.proutlost.worldprep.physical;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/** Conservative bounded first-air-to-habitat origin calculation.
 * FirstAirY is Minecraft's heightmap first available space, not the solid top.
 * For aquatic species the requested waterDepth counts water cells from the
 * selected entity origin DOWN to the first solid substrate.
 *
 * This helper neither loads/generates chunks nor reads world state: the caller
 * supplies an existing-chunk-checked WATER predicate.
 */
public final class NativeHabitatOrigin {
    public static OptionalInt chooseY(int firstAirY,int minBuildY,int waterDepth,
                                       IntPredicate waterAtY) {
        if(waterDepth<0||waterDepth>32)
            throw new IllegalArgumentException("Water depth limit 0..32");
        if(waterAtY==null)
            throw new IllegalArgumentException("Water evidence source required");
        if(waterDepth==0)
            return OptionalInt.of(firstAirY); // no extra +1 on land
        for(int below=1;below<=33;below++) {
            long y=(long)firstAirY-below;
            if(y<minBuildY)return OptionalInt.empty();
            if(!waterAtY.test((int)y)) {
                int present=below-1;
                return present>=waterDepth
                    ?OptionalInt.of((int)y+waterDepth)
                    :OptionalInt.empty();
            }
        }
        // Seabed absent in the finite scan. NEVER infer a floor in open water.
        return OptionalInt.empty();
    }
    /** Reject an invalid native collision/substrate envelope before any world
     * read. This is a candidate mismatch, never a reason to invent y=-65 or
     * to raise a fatal outside-build-height exception while surveying fauna.
     */
    public static boolean verticalEnvelopeFits(int originY,int minBuildY,int maxBuildY,int waterDepth) {
        if(waterDepth<0||waterDepth>32||minBuildY>=maxBuildY)return false;
        long substrate=(long)originY-Math.max(1,waterDepth);
        long clearance=(long)originY+2L;
        return substrate>=minBuildY&&clearance<maxBuildY;
    }
    private NativeHabitatOrigin() {}
}
