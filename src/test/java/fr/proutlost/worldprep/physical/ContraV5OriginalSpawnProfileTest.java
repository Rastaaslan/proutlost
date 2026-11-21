package fr.proutlost.worldprep.physical;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ContraV5OriginalSpawnProfileTest {
    @Test void originalWorldPainterContraSpawnCanBeFrozenWithoutChangingExistingMapRules(){
        var actual=new NativeSpawnAudit.Spec("CONTRA_S2",2048,127,2048,4,3,9);
        assertEquals("CONTRA_S2",actual.profile());
        assertEquals(2048,actual.x());assertEquals(127,actual.y());
        assertEquals(2048,actual.z());
        assertThrows(IllegalArgumentException.class,()->new NativeSpawnAudit.Spec(
            "CONTRA_S2",2049,127,2048,4,3,9));
        assertThrows(IllegalArgumentException.class,()->new NativeSpawnAudit.Spec(
            "CONTRA_S2",2048,128,2048,4,3,9));
        assertThrows(IllegalArgumentException.class,()->new NativeSpawnAudit.Spec(
            "CONTRA_S2",2048,127,2048,5,3,9));
        assertThrows(IllegalArgumentException.class,()->new NativeSpawnAudit.Spec(
            "CONTRA_S2",2048,127,2048,4,3,82));
        assertDoesNotThrow(()->new NativeSpawnAudit.Spec(
            "MAP",4871,75,-1370,4,1,5));
        assertThrows(IllegalArgumentException.class,()->new NativeSpawnAudit.Spec(
            "MAP",2048,127,2048,4,1,5));
    }
}
