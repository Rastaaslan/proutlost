package fr.proutlost.worldprep;

import com.google.gson.JsonObject;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import fr.proutlost.worldprep.physical.NativeQualificationSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit operator-only DEV freeze. Never seals an unqualified world or starts Minecraft. */
class RealDevV4IntentFreezeTest {
    @Test void freezeActualTypedIntentBeforeAnyBackupExists() throws Exception {
        var enabled=System.getenv("WORLDPREP_DEV_V4_INTENT_INPUT");
        Assumptions.assumeTrue(enabled!=null&&!enabled.isBlank(),
            "Real DEV candidate freezing is strictly opt-in");
        Path input=Path.of(enabled).toRealPath();
        JsonObject object=QualificationBinding.object(Files.readString(input));
        assertEquals(4,object.get("version").getAsInt());
        assertEquals("DEV_SERVICE_PROBE",object.get("directorReceipt").getAsString());
        Path world=Path.of(System.getenv("WORLDPREP_DEV_V4_CANDIDATE")).toRealPath();
        assertTrue(Files.isRegularFile(world.resolve(".S2_COPY_FINISHED")));
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        Path first=Path.of(object.get("backupRootA").getAsString());
        Path second=Path.of(object.get("backupRootB").getAsString());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        Path backupManifest=Path.of(object.get("backupManifest").getAsString());
        assertFalse(backupManifest.isAbsolute());
        Path directory=world.resolve(backupManifest).getParent();
        assertTrue(directory.startsWith(world.resolve("worldprep-v2/physical")));
        var freezer=new QualificationBinding(world,directory,NativeQualificationSession.Request.class);
        var requestText=QualificationBinding.canonical(object);
        String digest=freezer.freeze(requestText);
        assertEquals(digest,freezer.freeze(requestText));
        assertEquals(requestText,freezer.intent());
        assertFalse(freezer.bound());
        assertThrows(java.io.IOException.class,freezer::verify,
            "No backups or sealed receipt may be invented by freezing");
        System.out.println("WORLDPREP_V4_REAL_PRECOPY_FROZEN batch="+object.get("batch").getAsString()+
            " sha256="+digest+" DEV_ONLY_NO_BACKUP_NO_DIRECTOR_RECEIPT");
    }
}
