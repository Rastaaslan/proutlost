package fr.proutlost.worldprep.physical;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ContraV5SegmentedFaunaColdAuditTest {
    @Test void noMissingFakedSpeciesOrPhysicalWalsCanBeUpgradedToCertification(@TempDir Path temp){
        assertThrows(IOException.class,()->ContraV5SegmentedFaunaColdAudit.audit(
            temp,temp,null,"",null));
        assertFalse(java.nio.file.Files.exists(temp.resolve("contra-v5-segmented-fauna")));
    }
}
