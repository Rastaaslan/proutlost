package fr.proutlost.worldprep.orchestration;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** RUN69: actual RUN69 Python crashed/resumed multi-segment output is read
 * by independent Java full-content SHA audit. No production world touched.
 */
public class ContraRun69PythonJavaColdResumeTest {
    private static final Path BASE=Path.of(
        "/srv/storage/proutlost-s2-candidates/contra-20261027/contra-run69-v5-native-segmented-binding-clone-20261029");
    private static final UUID UUID_V5=UUID.fromString(
        "cbdc5190-1e25-4a9d-86e5-fbd0d8418369");
    private static final Path BUNDLE=BASE.resolve("fixture-snapshot-run69-crash-cold-resume");
    @Test void independentJavaAcceptsFullyColdSHACheckedPythonCrashResumedBundle()throws Exception{
        var receipt=ContraV5SegmentedBackupAudit.verifyForQualification(
            BUNDLE.resolve("manifest-bundle/manifest.json"),
            BASE.resolve("fixture-world"),BUNDLE.resolve("copy-A"),BUNDLE.resolve("copy-B"),UUID_V5);
        assertEquals(4,receipt.files());
        assertEquals(2,receipt.pages());
        assertEquals(2,receipt.segments());
        assertEquals("COLD_SEGMENTED_A_B_CONTENT_AND_FULL_COVERAGE_SHA_VERIFIED_NOT_V5_QUALIFIED",
            receipt.status());
        assertThrows(java.io.IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            BUNDLE.resolve("manifest-bundle/manifest.json"),
            BASE.resolve("fixture-world"),BUNDLE.resolve("copy-A"),BUNDLE.resolve("copy-B"),
            UUID.fromString("74b58bb7-75cd-4d2c-bbda-f85bc2b58676")));
    }
}
