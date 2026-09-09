package fr.proutlost.worldprep;
import fr.proutlost.worldprep.override.ProtectionResolver;
import fr.proutlost.worldprep.area.WorldPrepArea;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuthoringSafetyTest {
 @Test void protectionPrecedenceIsFailClosed(){assertEquals(ProtectionResolver.Reason.LOCATION,ProtectionResolver.resolve(new ProtectionResolver.Input(true,true,true,true,false,false)));assertEquals(ProtectionResolver.Reason.BLOCK_ENTITY,ProtectionResolver.resolve(new ProtectionResolver.Input(false,false,false,true,true,true)));assertEquals(ProtectionResolver.Reason.UNKNOWN_CONTENT,ProtectionResolver.resolve(new ProtectionResolver.Input(false,false,false,false,false,true)));}
 @Test void negativeCoordinatesAndOverflowAreSafe(){var a=WorldPrepArea.between("proutlost:test","minecraft:overworld",-1,-2,-10,-20);assertTrue(a.contains(-5,-5));assertThrows(ArithmeticException.class,()->new WorldPrepArea("proutlost:huge","minecraft:overworld",Integer.MIN_VALUE,0,Integer.MAX_VALUE,1).width());}
}
