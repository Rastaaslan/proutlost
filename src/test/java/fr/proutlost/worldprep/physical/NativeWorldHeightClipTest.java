package fr.proutlost.worldprep.physical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeWorldHeightClipTest {
 @Test void nativeUpperBoundaryIsClippedLikeMinecraftPlacement() throws Exception {
  var b=new NativeRegistryStructurePass.Bounds(0,-18,0,100,320,100);
  var result=NativeRegistryStructurePass.writableBounds(b,-64,320);
  assertEquals(319,result.maxY());assertEquals(-18,result.minY());assertEquals(320,b.maxY());
 }
 @Test void ordinaryStructureBoundsAreUnchanged() throws Exception {
  var b=new NativeRegistryStructurePass.Bounds(0,-37,0,20,-25,20);
  assertEquals(b,NativeRegistryStructurePass.writableBounds(b,-64,320));
 }
 @Test void noIntersectionIsNotAValidStructure() {
  var b=new NativeRegistryStructurePass.Bounds(0,320,0,20,350,20);
  assertThrows(java.io.IOException.class,()->NativeRegistryStructurePass.writableBounds(b,-64,320));
 }
}
