package fr.proutlost.worldprep.physical;
import org.junit.jupiter.api.Test;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class NativeStructureReferencesTest {
 @Test void emptyRegistryEntriesAreNotOccupiedStructures() {
  assertFalse(NativeRegistryStructurePass.hasReferences(List.of(new LongOpenHashSet(),new LongOpenHashSet())));
 }
 @Test void actualReferenceRemainsProtectedIncludingChunkZero() {
  assertTrue(NativeRegistryStructurePass.hasReferences(List.of(new LongOpenHashSet(new long[]{0L}))));
 }
 @Test void absentReferencesAreFree() {assertFalse(NativeRegistryStructurePass.hasReferences(List.of()));}
}
