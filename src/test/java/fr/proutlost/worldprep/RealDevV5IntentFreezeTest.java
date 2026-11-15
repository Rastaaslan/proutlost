package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** One-shot DEV-only v5 PRE-COPY freeze on a separately verified S2 ecological clone.
 * NEVER seals, publishes runtime request, or starts a Minecraft server.
 */
class RealDevV5IntentFreezeTest {
    private static final UUID BATCH=UUID.nameUUIDFromBytes(
        "proutlost:s2:v5:multi-species:20261024".getBytes(StandardCharsets.UTF_8));
    private static final UUID ECOLOGY=UUID.fromString("f1d00b2a-d490-466e-ae03-0cab665a2ceb");

    @Test void freezeRealV5SpeciesPlanBeforeTwoFutureBackupCopies() throws Exception {
        String path=System.getenv("WORLDPREP_DEV_V5_FREEZE_WORLD");
        Assumptions.assumeTrue(path!=null&&!path.isBlank(),"Explicit v5 S2 offline DEV opt-in only");
        Path world=Path.of(path).toRealPath();
        assertTrue(world.endsWith("dragon_island_s2_v5_prebound_dev"));
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        assertFalse(Files.exists(world.resolve("worldprep-qualification-pin.json")));
        assertTrue(Files.exists(world.resolve(".S2_COPY_FINISHED")));
        var marker=QualificationBinding.object(Files.readString(world.resolve("worldprep-candidate.json")));
        assertEquals(world.toString(),marker.get("candidateDirectory").getAsString());
        assertTrue(marker.get("offlineBaseline").getAsBoolean());
        Path futureBackups=Path.of("/srv/storage/worldprep-s2-backups/cb20131-v5-prebound-dev");
        assertFalse(Files.exists(futureBackups),"Never freeze on an existing backup pair");

        Path ecologyFile=world.resolve("worldprep-v2/batches/"+ECOLOGY+"/manifest.json");
        var ecology=QualificationBinding.decode(Files.readString(ecologyFile),
            BatchManifestStore.Header.class);
        assertEquals(ECOLOGY,ecology.batchId());
        assertEquals("proutlost:s2_full_ecology",ecology.area());
        assertEquals(-355732122L,ecology.worldSeed());
        assertEquals(22090L,ecology.stages());
        assertEquals(4496,ecology.minX());assertEquals(5236,ecology.maxX());
        assertEquals(-1666,ecology.minZ());assertEquals(-943,ecology.maxZ());

        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains","minecraft:forest","minecraft:beach"),
            Set.of("minecraft:grass_block","minecraft:dirt"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean"),
            Set.of("minecraft:sand"),
            -64,319,-10f,10f,6,64,16,2,16,true,8,32);
        var habitat=MultiSpeciesFaunaPlan.prepare(BATCH,List.of(cow,cod));
        MultiSpeciesFaunaPlan.verify(habitat);
        Path journal=world.resolve("worldprep-v2/physical")
            .resolve(PhysicalStore.sha("minecraft:overworld"))
            .resolve("qualification-"+BATCH);
        var binder=new QualificationBinding(world,journal,NativeV5QualificationIntent.class);
        var intended=new NativeV5QualificationIntent(5,true,BATCH,
            "proutlost:s2_full_ecology",ecology,
            world.relativize(binder.first()).toString(),null,
            futureBackups.resolve("backup-a").toString(),futureBackups.resolve("backup-b").toString(),
            "proutlost:worldprep/stone_platform_v1",habitat,
            "proutlost:s2_v5_dev","proutlost:cb20131_dev",
            "cb20131-frozen-20261024","2026-10-24T18:58:00Z",
            "DEV_SERVICE_PROBE",
            "9753cb99a57a6a1067dae7d4ee165459f387bacd74062d2db3eca1766396b196",
            new NativeSpawnAudit.Spec("MAP",4871,75,-1370,4,1,5));
        var json=QualificationBinding.JSON.toJsonTree(intended).getAsJsonObject();
        json.remove("backupSha256");
        String input=QualificationBinding.canonical(json);
        String frozenDigest=binder.freeze(input);
        assertEquals(input,binder.intent());
        assertEquals(frozenDigest,binder.freeze(input));
        assertFalse(binder.bound());
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        assertFalse(Files.exists(futureBackups));
        assertThrows(java.io.IOException.class,binder::seal,
            "No v5 binding may be published before native + Director qualification");
        assertFalse(binder.bound());
        System.out.println("S2_V5_DEV_REAL_PRECOPY_INTENT_FROZEN batch="+BATCH+
            " sha256="+frozenDigest+" cowCod=2 backupsCreated=false bindingSealed=false");
    }
}
