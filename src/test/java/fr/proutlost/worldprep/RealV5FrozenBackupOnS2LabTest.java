package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.FrozenBackupPairVerifier;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit DEV-only *real* A/B/live verifier smoke test.
 * No world mutation, no skip of a frozen page, no G0/G5 claim.
 */
class RealV5FrozenBackupOnS2LabTest {
    @Test void completeTwoFrozenBackupsAndLiveContinuityAreVerified() throws Exception {
        String path=System.getProperty("proutlost.v5.real.certificate");
        Assumptions.assumeTrue(path!=null&&!path.isBlank(),
            "Real S2 source and certificates must be explicitly supplied");
        Path world=Path.of("/home/damien/worldprep-s2-integration-20261024/"
            +"dragon_island_s2_v5_prebound_dev").toRealPath();
        Path journal=world.resolve("worldprep-v2/physical/"
            +"3f60de212b487e6204ac185257284bcd73de9a987006a6622704f5fe54409bcc/"
            +"qualification-46fd7fe3-8ecc-3256-b4c9-08a26b4f42c8");
        Path a=journal.resolve("backup-a.json"),b=journal.resolve("backup-b.json");
        var gson=new com.google.gson.Gson();
        var first=gson.fromJson(Files.readString(a),PagedBackupVerifier.Header.class);
        var second=gson.fromJson(Files.readString(b),PagedBackupVerifier.Header.class);
        assertEquals(47331,first.files());
        assertEquals(first.files(),second.files());
        Path cert=Path.of(path).toRealPath();
        try(var audit=new FrozenBackupPairVerifier(world,first,second,
              Path.of(a+".pages"),Path.of(b+".pages"),cert,
              UUID.fromString("46fd7fe3-8ecc-3256-b4c9-08a26b4f42c8"))) {
            boolean done=false;
            for(int slice=0;slice<800000;slice++) {
                if(audit.stepBounded()){done=true;break;}
            }
            assertTrue(done,"Frozen snapshot verifier bounded reading did not complete");
            assertEquals(47331,audit.verifiedFiles());
            assertTrue(audit.semanticProofSha256().matches("[0-9a-f]{64}"));
        }
        System.out.println("S2_REAL_NATIVE_V5_FROZEN_AB_LIVE_VERIFIED files=47331");
    }
}
