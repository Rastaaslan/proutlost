package fr.proutlost.worldprep.orchestration;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Never make the old V5 world eligible for Contra's 2.5M/20k limits. */
class FrozenBackupContraCandidateRun9Test {
    @Test void newNvmeCandidateExactlyMatchesAndNothingElse(){
        assertTrue(FrozenBackupPairVerifier.exactScaledContraCandidate(
            Path.of("/home/damien/contra-ecology-run6/Contra-worldprep-modern-run6")));
        assertFalse(FrozenBackupPairVerifier.exactScaledContraCandidate(
            Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/Contra-worldprep-modern")));
        assertFalse(FrozenBackupPairVerifier.exactScaledContraCandidate(
            Path.of("/home/damien/worldprep-ocean-server/Contra-S2")));
        assertFalse(FrozenBackupPairVerifier.exactScaledContraCandidate(
            Path.of("/home/damien/contra-ecology-run6/Contra-worldprep-modern-run6-similar")));
        assertFalse(FrozenBackupPairVerifier.exactScaledContraCandidate(null));
    }
}
