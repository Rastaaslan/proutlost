package fr.proutlost.worldprep.world;
/** Existing FULL terrain may need normal native light loading; never fabricate terrain or light status. */
public final class NativeChunkEligibility {
 private NativeChunkEligibility() {}
 public static boolean canPromote(boolean full,boolean lightCorrect,boolean upgrading,boolean structuresOnly) {
  return full && !upgrading && (lightCorrect || structuresOnly);
 }
}
