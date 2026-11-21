package fr.proutlost.worldprep;
import fr.proutlost.worldprep.recovery.MutationSemantics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryModelTest {
 @Test void applyAndRollbackAreIdempotentAndConflictOnThirdState(){
  assertEquals(MutationSemantics.Decision.WRITE,MutationSemantics.apply("before","before","after"));
  assertEquals(MutationSemantics.Decision.NO_OP,MutationSemantics.apply("after","before","after"));
  assertEquals(MutationSemantics.Decision.CONFLICT,MutationSemantics.apply("other","before","after"));
  assertEquals(MutationSemantics.Decision.WRITE,MutationSemantics.rollback("after","before","after"));
  assertEquals(MutationSemantics.Decision.NO_OP,MutationSemantics.rollback("before","before","after"));
  assertEquals(MutationSemantics.Decision.CONFLICT,MutationSemantics.rollback("other","before","after"));
 }
}
