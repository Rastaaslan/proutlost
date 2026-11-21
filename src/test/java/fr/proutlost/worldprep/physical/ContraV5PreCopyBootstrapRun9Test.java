package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.director.CatalogImportProbe;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh Contra-specific offline native freeze contract. NO real-world mutations. */
class ContraV5PreCopyBootstrapRun9Test {
    @TempDir Path temp;
    private static final UUID ECO=UUID.fromString("311f04f1-2aef-47d2-af22-c143f35a1c91");
    private static final UUID BATCH=UUID.fromString("faa74e5e-9e8d-4e62-a6e2-8b1ea27ad642");
    private static BatchManifestStore.Header header(){
        return new BatchManifestStore.Header(1,ECO,"minecraft:overworld",
            "proutlost:contra_s2_ecology",27594267L,16,16,4079,4079,
            645160,"ecology-batch-v3");
    }
    @Test void pureExactParentIntentHasRealSpawnTwoSpeciesAndDistinctUUID(){
        var world=temp.resolve("world");var backup=temp.resolve("backups");
        var req=ContraV5PreCopyBootstrapRun9.plan(world,backup,BATCH,
            header(),"1".repeat(64),Instant.parse("2026-10-28T05:00:00Z"));
        assertEquals(5,req.version());
        assertEquals(BATCH,req.batch());
        assertEquals("proutlost:contra_s2_ecology",req.area());
        assertEquals(645160,req.ecology().stages());
        assertEquals(2,req.multiSpecies().stages().size());
        MultiSpeciesFaunaPlan.verify(req.multiSpecies());
        assertEquals(Set.of("minecraft:cow","minecraft:cod"),
            new HashSet<>(req.multiSpecies().stages().stream()
                .map(MultiSpeciesFaunaPlan.Stage::species).toList()));
        assertEquals("CONTRA_S2",req.spawn().profile());
        assertEquals(2048,req.spawn().x());assertEquals(127,req.spawn().y());
        assertEquals(2048,req.spawn().z());
        assertEquals(ContraV5PreCopyBootstrapRun9.journal(world,BATCH)
            .resolve("backup-a.json").toString(),
            world.resolve(req.backupManifest()).toString());
        assertEquals(backup.resolve("backup-a").toString(),req.backupRootA());
        assertEquals(backup.resolve("backup-b").toString(),req.backupRootB());
        assertNull(req.backupSha256());
    }
    @Test void preCopyBindingIsImmutableAndCannotSealWithoutTwoPhysicalHeaders()throws Exception{
        var world=temp.resolve("world");
        var backup=temp.resolve("backup-indep");
        Files.createDirectories(world);
        var req=ContraV5PreCopyBootstrapRun9.plan(world,backup,BATCH,
            header(),"2".repeat(64),Instant.parse("2026-10-28T05:01:00Z"));
        var obj=QualificationBinding.JSON.toJsonTree(req).getAsJsonObject();
        obj.remove("backupSha256");
        var canonical=QualificationBinding.canonical(obj);
        var binder=new QualificationBinding(world,
            ContraV5PreCopyBootstrapRun9.journal(world,BATCH),
            NativeV5QualificationIntent.class);
        var digest=binder.freeze(canonical);
        assertEquals(digest,PhysicalStore.sha(canonical));
        assertEquals(canonical,binder.intent());
        assertFalse(binder.bound());
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        assertThrows(java.io.IOException.class,binder::seal);
        assertThrows(java.io.IOException.class,()->
            binder.freeze(canonical.replace("minecraft:cow","minecraft:pig")));
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
    }
    @Test void wrongSeedOrNonContraCannotCreatePreCopyProtocol(){
        var old=header();
        assertThrows(IllegalArgumentException.class,()->ContraV5PreCopyBootstrapRun9.plan(
            temp.resolve("world"),temp.resolve("backups"),BATCH,
            new BatchManifestStore.Header(1,ECO,"minecraft:overworld",
                "proutlost:contra_s2_ecology",27594268L,16,16,4079,4079,
                645160,"ecology-batch-v3"),"1".repeat(64),Instant.now()));
        assertThrows(IllegalArgumentException.class,()->ContraV5PreCopyBootstrapRun9.plan(
            temp.resolve("world"),temp.resolve("backups"),ECO,
            old,"1".repeat(64),Instant.now()));
        assertThrows(IllegalArgumentException.class,()->ContraV5PreCopyBootstrapRun9.plan(
            temp.resolve("world"),temp.resolve("backups"),BATCH,
            old,"NOT_A_SHA",Instant.now()));
    }
    @Test void isolatedDevAndProductionResourcesEachSelectOneExplicitConsumer()throws Exception{
        // The unit classpath contains both resource roots. Inspect each role
        // separately rather than removing the real production provider for tests.
        var both=ServiceLoader.load(CatalogImportProbe.class).stream()
            .map(p->p.type().getName()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("fr.proutlost.director.catalog.QualificationImportProbe",
            "fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe"),both);
        assertEquals("fr.proutlost.director.catalog.QualificationImportProbe",
            onlyConsumer(Path.of("src/test/resources")));
        assertEquals("fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe",
            onlyConsumer(Path.of("src/main/resources")));
        assertTrue(Files.exists(temp));
    }
    private static String onlyConsumer(Path resourceRoot)throws Exception{
        String descriptor="META-INF/services/"+CatalogImportProbe.class.getName();
        try(var loader=new java.net.URLClassLoader(
                new java.net.URL[]{resourceRoot.toAbsolutePath().toUri().toURL()},
                CatalogImportProbe.class.getClassLoader()){
            @Override public java.util.Enumeration<java.net.URL> getResources(String name)
                    throws java.io.IOException{
                return name.equals(descriptor)?findResources(name):super.getResources(name);
            }
        }){
            var providers=ServiceLoader.load(CatalogImportProbe.class,loader).stream().toList();
            assertEquals(1,providers.size(),"Each resource root must retain exactly one consumer");
            return providers.getFirst().type().getName();
        }
    }
}
