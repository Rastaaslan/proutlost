package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** READ ONLY on an independently rebased S2 clone; no v5 runtime published. */
class RealDevV5FrozenReadbackTest {
    @Test void verifiesPostCopyIdentityWithoutSealingOrRunningMinecraft() throws Exception {
        String configured=System.getenv("WORLDPREP_DEV_V5_READBACK_WORLD");
        Assumptions.assumeTrue(configured!=null&&!configured.isBlank(),
            "Explicit real S2 v5 pre-copy proof readback required");
        Path world=Path.of(configured).toRealPath();
        assertTrue(world.endsWith("dragon_island_s2_v5_prebound_dev"));
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")),
            "Existing production/DEV request cannot be replaced");
        var original=QualificationBinding.object(
            Files.readString(world.resolve("worldprep-candidate.json")));
        assertEquals(world.toString(),original.get("candidateDirectory").getAsString());
        assertTrue(original.get("offlineBaseline").getAsBoolean());

        Path dir=world.resolve("worldprep-v2/physical")
            .resolve(PhysicalStore.sha("minecraft:overworld"));
        var candidates=Files.list(dir).filter(x->x.getFileName().toString().startsWith("qualification-")).toList();
        assertEquals(1,candidates.size(),"Unexpected foreign v5 qualification journal");
        var binder=new QualificationBinding(world,candidates.getFirst(),NativeV5QualificationIntent.class);
        assertFalse(binder.bound(),"Never auto-publish a v5 binding");
        var text=binder.intent();
        var obj=QualificationBinding.object(text);
        assertEquals(5,obj.get("version").getAsInt());
        assertEquals("DEV_SERVICE_PROBE",obj.get("directorReceipt").getAsString());
        obj.addProperty("backupSha256",PagedBackupVerifier.ZERO);
        var typed=QualificationBinding.decode(QualificationBinding.canonical(obj),
            NativeV5QualificationIntent.class);
        MultiSpeciesFaunaPlan.verify(typed.multiSpecies());
        assertEquals(2,typed.multiSpecies().stages().size());
        assertEquals(Set.of("minecraft:cow","minecraft:cod"),
            typed.multiSpecies().stages().stream().map(MultiSpeciesFaunaPlan.Stage::species)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(typed.batch(),typed.multiSpecies().parentBatch());
        assertEquals(world.relativize(binder.first()).toString(),typed.backupManifest());

        var a=QualificationBinding.decode(
            Files.readString(binder.first()),PagedBackupVerifier.Header.class);
        var b=QualificationBinding.decode(
            Files.readString(binder.second()),PagedBackupVerifier.Header.class);
        assertEquals(world.toString(),a.sourceRoot());
        assertEquals(a.sourceRoot(),b.sourceRoot());
        assertEquals(2,a.version());assertEquals(2,b.version());
        assertEquals(47331,a.files());assertEquals(a.files(),b.files());
        assertEquals(370,a.pages());assertEquals(a.pages(),b.pages());
        assertEquals(typed.backupRootA(),a.backupRoot());
        assertEquals(typed.backupRootB(),b.backupRoot());
        assertNotEquals(a.backupRoot(),b.backupRoot());
        assertEquals(a.quiescentPinSha256(),b.quiescentPinSha256());
        assertThrows(java.io.IOException.class,binder::seal,
            "Native v5 must still refuse publication until runtime and Director are qualified");
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")));
        System.out.println("S2_V5_PRECOPY_READBACK_PASS batch="+typed.batch()
            +" intent="+PhysicalStore.sha(text)
            +" cowCodDistinct="+typed.multiSpecies().stages().size()
            +" backupFiles="+a.files()+" pages="+a.pages()
            +" v5StillSealed=false runtimeStarted=false");
    }
}
