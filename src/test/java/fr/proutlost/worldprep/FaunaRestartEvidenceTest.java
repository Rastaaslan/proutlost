package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.FaunaRestartEvidence;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class FaunaRestartEvidenceTest {
    final UUID entity=UUID.randomUUID(),jvm=UUID.randomUUID();
    FaunaRestartEvidence evidence(){return new FaunaRestartEvidence(3,"a".repeat(64),entity,jvm,"b".repeat(64),"POST_CLEANUP_SAVE_READBACK_NOT_GLOBAL_QUALIFICATION");}
    @Test void individualPassCannotStandInForPostCleanupDiskProof(){assertThrows(IllegalArgumentException.class,()->new FaunaRestartEvidence(3,"a".repeat(64),entity,jvm,"b".repeat(64),"PASS"));}
    @Test void sameJvmCannotProveRestart(){assertThrows(IllegalStateException.class,()->evidence().requireRestart("a".repeat(64),entity,jvm));}
    @Test void changedPlanOrReplacementUuidRefused(){assertThrows(IllegalStateException.class,()->evidence().requireRestart("c".repeat(64),entity,UUID.randomUUID()));assertThrows(IllegalStateException.class,()->evidence().requireRestart("a".repeat(64),UUID.randomUUID(),UUID.randomUUID()));}
    @Test void exactPlanEntityAndNewJvmAcceptedWithoutGlobalReadiness(){assertDoesNotThrow(()->evidence().requireRestart("a".repeat(64),entity,UUID.randomUUID()));assertTrue(evidence().status().endsWith("NOT_GLOBAL_QUALIFICATION"));}
}
