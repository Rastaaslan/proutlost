package fr.proutlost.worldprep.plan;
/** Central stale-preview guard used before any planned mutation. */
public final class BiomePlanGuard {public static void requireCurrent(long persistedTerrainSignature,long currentTerrainSignature){if(persistedTerrainSignature!=currentTerrainSignature)throw new IllegalStateException("PREVIEW STALE");}private BiomePlanGuard(){}}
