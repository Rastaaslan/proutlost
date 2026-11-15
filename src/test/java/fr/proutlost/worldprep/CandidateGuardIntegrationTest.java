package fr.proutlost.worldprep;

import fr.proutlost.worldprep.runtime.CandidateGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CandidateGuardIntegrationTest {
    @TempDir Path root;

    @Test void correctlyReboundOfflineCopyIsAccepted() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("worldprep-candidate.json"), marker(candidate));
        assertDoesNotThrow(() -> CandidateGuard.requireCandidate(candidate));
    }

    @Test void copiedMarkerWithOriginalSourceIdentityIsRefused() throws Exception {
        Path original = Files.createDirectory(root.resolve("original"));
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("worldprep-candidate.json"), marker(original));
        assertThrows(IllegalStateException.class, () -> CandidateGuard.requireCandidate(candidate));
    }

    @Test void missingMarkerRefusesCandidate() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        assertThrows(IllegalStateException.class, () -> CandidateGuard.requireCandidate(candidate));
    }

    @Test void offlineModeMustBeExplicit() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("worldprep-candidate.json"),
            marker(candidate).replace("true", "false"));
        assertThrows(IllegalStateException.class, () -> CandidateGuard.requireCandidate(candidate));
    }

    @Test void symlinkedMarkerMustBeRefused() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Path marker = root.resolve("external.json");
        Files.writeString(marker, marker(candidate));
        Files.createSymbolicLink(candidate.resolve("worldprep-candidate.json"), marker);
        assertThrows(IllegalStateException.class, () -> CandidateGuard.requireCandidate(candidate));
    }

    private static String marker(Path candidate) {
        return "{\"schemaVersion\":1,\"candidateDirectory\":\"" +
            candidate.toAbsolutePath() + "\",\"offlineBaseline\":true}";
    }
}
