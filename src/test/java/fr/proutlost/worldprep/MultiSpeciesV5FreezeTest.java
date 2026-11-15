package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiSpeciesV5FreezeTest {
    @TempDir Path base;
    private static final UUID BATCH=UUID.fromString("3ff06572-01d5-5785-95f2-66025ab043f2");

    private NativeV5QualificationIntent input(Path root,QualificationBinding binder,UUID batch) {
        var header=new BatchManifestStore.Header(1,UUID.randomUUID(),
            "minecraft:overworld","proutlost:s2_full_ecology",-355732122L,
            4496,-1666,5236,-943,22090,"ecology-v1");
        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains","minecraft:forest","minecraft:beach"),
            Set.of("minecraft:dirt","minecraft:grass_block"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,32);
        return new NativeV5QualificationIntent(5,true,batch,
            "proutlost:s2_full_ecology",header,
            root.relativize(binder.first()).toString(),null,
            base.resolve("backup-a").toString(),base.resolve("backup-b").toString(),
            "proutlost:worldprep/stone_platform_v1",
            MultiSpeciesFaunaPlan.prepare(batch,List.of(cod,cow)),
            "proutlost:s2_dev","proutlost:v5_preflight","v5-intent-preview",
            "2026-10-24T00:00:00Z","DEV_SERVICE_PROBE","a".repeat(64),
            new NativeSpawnAudit.Spec("MAP",4871,75,-1370,4,1,5));
    }

    @Test void anIsolatedPrecopyV5IntentCannotSealWithoutBothRealBackups()throws Exception {
        Path world=Files.createDirectory(base.resolve("world"));
        Path folder=world.resolve("worldprep-v2/physical/test/qualification-"+BATCH);
        var binder=new QualificationBinding(world,folder,NativeV5QualificationIntent.class);
        var obj=QualificationBinding.JSON.toJsonTree(input(world,binder,BATCH)).getAsJsonObject();
        obj.remove("backupSha256");
        String intent=QualificationBinding.canonical(obj);
        String hash=binder.freeze(intent);
        assertTrue(hash.matches("[0-9a-f]{64}"));
        var reopened=new QualificationBinding(world,folder,NativeV5QualificationIntent.class);
        assertEquals(intent,reopened.intent());
        assertEquals(hash,reopened.freeze(intent));
        assertFalse(reopened.bound());
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        IOException error=assertThrows(IOException.class,reopened::seal);
        assertTrue(error.getMessage().contains("Missing/oversized protocol input"),
            "Sealing V5 now requires real headers, not a feature-flag bypass: "+error);
        assertFalse(reopened.bound());
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
    }

    @Test void v5CannotUseAnExistingV4RequestRecord()throws Exception {
        var world=Files.createDirectory(base.resolve("world"));
        var dir=world.resolve("worldprep-v2/physical/test/qualification-"+BATCH);
        var binder=new QualificationBinding(world,dir,NativeV5QualificationIntent.class);
        var obj=QualificationBinding.JSON.toJsonTree(input(world,binder,BATCH)).getAsJsonObject();
        obj.remove("multiSpecies");obj.add("fauna",new com.google.gson.JsonObject());
        obj.remove("backupSha256");
        assertThrows(IOException.class,()->binder.freeze(QualificationBinding.canonical(obj)));
    }

    @Test void noUnboundOrForgedSpeciesManifestCanBeFrozen()throws Exception {
        Path world=Files.createDirectory(base.resolve("world"));
        var binder=new QualificationBinding(world,
            world.resolve("worldprep-v2/physical/test/qualification-"+BATCH),
            NativeV5QualificationIntent.class);
        var request=input(world,binder,BATCH);
        var obj=QualificationBinding.JSON.toJsonTree(request).getAsJsonObject();
        obj.remove("backupSha256");
        obj.getAsJsonObject("multiSpecies").addProperty("manifestSha256","0".repeat(64));
        assertThrows(IOException.class,()->binder.freeze(QualificationBinding.canonical(obj)));
        assertFalse(binder.bound());
    }

    @Test void newVersionRequiresNewBatchAndCannotReuseAnOldOne() {
        var plan=MultiSpeciesFaunaPlan.prepare(BATCH,List.of(
            new NativeFaunaPass.Rule("minecraft:cow",Set.of("minecraft:plains"),
                Set.of("minecraft:grass_block"),-64,319,-10f,10f,0,64,16,2,16,true,8,32),
            new NativeFaunaPass.Rule("minecraft:cod",Set.of("minecraft:ocean"),
                Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,32)));
        assertThrows(IllegalArgumentException.class,()->
            new NativeV5QualificationIntent(5,true,UUID.randomUUID(),
                "proutlost:s2_full_ecology",
                new BatchManifestStore.Header(1,UUID.randomUUID(),"minecraft:overworld",
                    "proutlost:s2_full_ecology",-355732122L,4496,-1666,5236,-943,22090,"ecology-v1"),
                "path",null,"/tmp/a","/tmp/b",
                "proutlost:worldprep/stone_platform_v1",plan,
                "world","build","revision","2026-10-24T00:00:00Z",
                "DEV_SERVICE_PROBE","a".repeat(64),
                new NativeSpawnAudit.Spec("MAP",4871,75,-1370,4,1,5)));
    }
}
