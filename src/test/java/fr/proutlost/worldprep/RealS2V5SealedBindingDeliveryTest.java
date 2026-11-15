package fr.proutlost.worldprep;

import com.google.gson.GsonBuilder;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opted-in, independently backed V5 DEV world. Seals immutable
 * source-bound request ONLY, never starts Minecraft or a physical pass.
 */
class RealS2V5SealedBindingDeliveryTest {
    @Test void sealRealV5IntentAgainstBothActualVerifiedBackupHeaders()throws Exception {
        String flag=System.getenv("WORLDPREP_V5_SEAL_DEV_WORLD");
        Assumptions.assumeTrue(flag!=null&&!flag.isBlank(),
            "Explicit v5 isolated DEV seal opt-in required");
        Path world=Path.of(flag).toRealPath();
        assertTrue(world.endsWith("dragon_island_s2_v5_prebound_dev"));
        assertTrue(Files.exists(world.resolve(".S2_COPY_FINISHED")));
        assertFalse(Files.isSymbolicLink(world));
        Path root=world.getParent();
        var prior=QualificationBinding.object(
            Files.readString(root.resolve("V5_DEV_TWO_BACKUPS_JAVA_FULL_CERTIFICATE_20261024.json")));
        assertEquals("JAVA_FULL_BACKUP_PAIR_VALIDATED_V5_PRECOPY_DEV_NOT_SEALED",
            prior.get("status").getAsString());
        assertEquals(2,prior.get("copies").getAsInt());
        assertEquals(47331,prior.get("filesPerCopy").getAsInt());
        var marker=QualificationBinding.object(
            Files.readString(world.resolve("worldprep-candidate.json")));
        assertEquals(world.toString(),marker.get("candidateDirectory").getAsString());
        String batch="46fd7fe3-8ecc-3256-b4c9-08a26b4f42c8";
        Path directory=world.resolve("worldprep-v2/physical/"+
            "3f60de212b487e6204ac185257284bcd73de9a987006a6622704f5fe54409bcc"+
            "/qualification-"+batch);
        var binding=new QualificationBinding(world,directory,NativeV5QualificationIntent.class);
        assertFalse(Files.exists(world.resolve(QualificationBinding.REQUEST)));
        assertFalse(binding.bound());
        var a=QualificationBinding.decode(QualificationBinding.read(binding.first(),65536),
            PagedBackupVerifier.Header.class);
        var b=QualificationBinding.decode(QualificationBinding.read(binding.second(),65536),
            PagedBackupVerifier.Header.class);
        assertEquals(2,a.version());assertEquals(2,b.version());
        assertEquals(47331,a.files());assertEquals(47331,b.files());
        assertEquals(370,a.pages());assertEquals(370,b.pages());
        assertEquals(world.toString(),a.sourceRoot());
        assertEquals(world.toString(),b.sourceRoot());
        assertNotEquals(a.backupRoot(),b.backupRoot());
        var saved=binding.seal();
        assertEquals(saved,binding.verify());
        var exact=QualificationBinding.read(world.resolve(QualificationBinding.REQUEST),4096);
        assertEquals(saved.request(),exact);
        var request=QualificationBinding.decode(exact,NativeV5QualificationIntent.class);
        MultiSpeciesFaunaPlan.verify(request.multiSpecies());
        assertEquals(UUID.fromString(batch),request.batch());
        assertEquals(5,request.version());
        assertEquals(2,request.multiSpecies().stages().size());
        assertNotEquals(PagedBackupVerifier.ZERO,request.backupSha256());
        var area=new WorldPrepSavedData.Area("proutlost:s2_full_ecology",
            "minecraft:overworld",4496,-1666,5236,-943);
        assertEquals(request,NativeV5ContractGate.requireActualSealedBinding(
            world,directory,exact,request.multiSpecies(),area,false));
        assertFalse(Files.exists(directory.resolve("pipeline/ECOLOGY_RECEIPTS.intent")));
        Path proof=root.resolve("S2_CB20158_V5_SEAL_CERTIFICATE.json");
        assertFalse(Files.exists(proof));
        var note=new LinkedHashMap<String,Object>();
        note.put("status","S2_REAL_TWO_BACKUP_V5_BOUND_NATIVE_RUNTIME_NOT_YET_STARTED");
        note.put("world",world.toString());
        note.put("batch",batch);
        note.put("backupHeaders",2);
        note.put("filesPerBackup",47331);
        note.put("twoSpecies",request.multiSpecies().stages().stream()
            .map(MultiSpeciesFaunaPlan.Stage::species).toList());
        note.put("requestSha256",saved.requestSha256());
        note.put("originalEcologyWorldModified",false);
        note.put("directorG5Admitted",false);
        note.put("finalMapReady",false);
        Files.writeString(proof,new GsonBuilder().setPrettyPrinting().create().toJson(note)+"\n",
            StandardOpenOption.CREATE_NEW);
        System.out.println("S2_REAL_V5_SEALED_DEV_ONLY backups=2 species=2 noMinecraftMutation=true");
    }
}
