package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract-only, no Minecraft world or native pass started. */
class NativeV5ContractGateTest {
    private static final UUID PARENT=
        UUID.fromString("46fd7fe3-8ecc-3256-b4c9-08a26b4f42c8");
    private static MultiSpeciesFaunaPlan.Manifest species() {
        var cow=new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains"),Set.of("minecraft:grass_block"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
        var cod=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean"),Set.of("minecraft:sand"),
            -64,319,-10f,10f,6,64,16,2,16,true,8,32);
        return MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow,cod));
    }
    private static WorldPrepSavedData.Area area() {
        return new WorldPrepSavedData.Area("proutlost:s2_full_ecology",
            "minecraft:overworld",4496,-1666,5236,-943);
    }
    private static String request(String sha) {
        var header=new BatchManifestStore.Header(1,UUID.randomUUID(),
            "minecraft:overworld","proutlost:s2_full_ecology",
            -355732122L,4496,-1666,5236,-943,22090,"ecology-v1");
        var typed=new NativeV5QualificationIntent(5,true,PARENT,
            "proutlost:s2_full_ecology",header,
            "worldprep-v2/physical/test/backup-a.json",sha,
            "/srv/storage/s2-v5-a","/srv/storage/s2-v5-b",
            "proutlost:worldprep/stone_platform_v1",species(),
            "proutlost:s2","proutlost:v5","v5-test","2026-10-24T00:00:00Z",
            "DEV_SERVICE_PROBE","a".repeat(64),
            new NativeSpawnAudit.Spec("MAP",4871,75,-1370,4,1,5));
        return QualificationBinding.canonical(
            QualificationBinding.JSON.toJsonTree(typed));
    }

    @Test void exactTypedShapeIsAcceptedOnlyForDEVPreflight()throws Exception {
        var intent=NativeV5ContractGate.requireSealedRequest(
            request("b".repeat(64)),species(),area(),false);
        assertEquals(5,intent.version());
        assertEquals(2,intent.multiSpecies().stages().size());
    }
    @Test void actualUnsealedPrecopyIntentCannotStartPhysicalWork() {
        assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            request(PagedBackupVerifier.ZERO),species(),area(),false));
    }
    @Test void productionRejectsLabConsumerBeforeOpeningChildJournals() {
        var error=assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            request("b".repeat(64)),species(),area(),true));
        assertTrue(error.getMessage().contains("DEV_SERVICE_PROBE"));
    }
    @Test void substitutedChildRulesOrBatchCannotEnterNativeAdapter() {
        var newRule=new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:cold_ocean"),Set.of("minecraft:sand"),
            -64,319,-10f,10f,6,64,16,2,16,true,8,32);
        var other=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(
            species().stages().get(1).rule(),newRule));
        assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            request("b".repeat(64)),other,area(),false));
    }
    @Test void changedEcologicalBoundsCannotEnterNativeAdapter() {
        var wrong=new WorldPrepSavedData.Area("proutlost:s2_full_ecology",
            "minecraft:overworld",1,1,100000,100000);
        assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            request("b".repeat(64)),species(),wrong,false));
    }
    @Test void validLookingRuntimeBytesWithoutRealBindingCannotRun() throws Exception {
        var world=java.nio.file.Files.createTempDirectory("v5-unsealed-runtime-test-");
        var directory=world.resolve("worldprep-v2/physical/test/qualification-"+PARENT);
        var refused=assertThrows(IOException.class,()->
            NativeV5ContractGate.requireActualSealedBinding(
                world,directory,request("b".repeat(64)),species(),area(),false));
        assertTrue(refused.getMessage().contains("Unsealed v5 journal"));
        assertFalse(java.nio.file.Files.exists(world.resolve("worldprep-qualification.json")));
    }

    @Test void nullOrMissingV5RequestRefusesBeforeAnyWorldTouch() {
        assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            null,species(),area(),false));
        assertThrows(IOException.class,()->NativeV5ContractGate.requireSealedRequest(
            "",species(),area(),false));
    }
}
