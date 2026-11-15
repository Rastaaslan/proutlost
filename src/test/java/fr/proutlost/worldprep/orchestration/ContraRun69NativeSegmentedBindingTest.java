package fr.proutlost.worldprep.orchestration;

import com.google.gson.JsonObject;
import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RUN69. Native V5 segmented WAL and full cold SHA: self-contained Java fixture
 * on JUnit temp HDD, never touches RUN55 or an original Contra world.
 */
public class ContraRun69NativeSegmentedBindingTest {
    @TempDir Path temp;
    static final UUID ECO=UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d");
    static final UUID V5=UUID.fromString("cbdc5190-1e25-4a9d-86e5-fbd0d8418369");
    static final String PIN="worldprep-qualification-pin.json";
    static final String ZERO="0".repeat(64);
    record Fixture(Path world,Path manifest,Path a,Path b,
                   QualificationBinding binder,NativeV5QualificationIntent intended) {}
    static String sha(byte[] bytes)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String sha(Path file)throws Exception{return sha(Files.readAllBytes(file));}
    static String json(Object obj){return QualificationBinding.JSON.toJson(obj);}
    static void put(Path file,String bytes)throws IOException{
        Files.createDirectories(file.getParent());Files.writeString(file,bytes);
    }
    Fixture fixture()throws Exception{
        Path world=temp.resolve("world"),dest=temp.resolve("actual-independent-copies");
        Path a=dest.resolve("copy-A"),b=dest.resolve("copy-B");
        Path bundle=dest.resolve("manifest-bundle"),segment=bundle.resolve("segments/segment-00");
        Files.createDirectories(world);Files.createDirectories(a);Files.createDirectories(b);
        Files.createDirectories(segment);
        put(world.resolve("session.lock"),"");
        put(world.resolve("alpha.txt"),"alpha-contents");
        put(world.resolve(PIN),"{\"generation\":\"fixture-run69\"}\n");
        List<String> paths=List.of("alpha.txt",PIN);
        String pinSha=sha(world.resolve(PIN));
        List<PagedBackupVerifier.Entry> rowsA=new ArrayList<>(),rowsB=new ArrayList<>();
        var inventory=MessageDigest.getInstance("SHA-256");
        for(String name:paths){
            Path src=world.resolve(name),left=a.resolve(name),right=b.resolve(name);
            Files.copy(src,left,StandardCopyOption.COPY_ATTRIBUTES);
            Files.copy(src,right,StandardCopyOption.COPY_ATTRIBUTES);
            long bytes=Files.size(src),ms=Files.getLastModifiedTime(src).toMillis();
            String hash=sha(src);
            rowsA.add(new PagedBackupVerifier.Entry(name,bytes,hash,ms,
                Files.getLastModifiedTime(left).toMillis()));
            rowsB.add(new PagedBackupVerifier.Entry(name,bytes,hash,ms,
                Files.getLastModifiedTime(right).toMillis()));
            inventory.update((name+"\0"+bytes+"\0"+hash+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path pageA=segment.resolve("backup-a.json.pages/page-0.json");
        Path pageB=segment.resolve("backup-b.json.pages/page-0.json");
        put(pageA,json(new PagedBackupVerifier.Page(2,0,ZERO,rowsA)));
        put(pageB,json(new PagedBackupVerifier.Page(2,0,ZERO,rowsB)));
        var headerA=new PagedBackupVerifier.Header(2,world.toString(),a.toString(),
            PIN,pinSha,1,2,sha(pageA));
        var headerB=new PagedBackupVerifier.Header(2,world.toString(),b.toString(),
            PIN,pinSha,1,2,sha(pageB));
        Path hA=segment.resolve("backup-a.json"),hB=segment.resolve("backup-b.json");
        put(hA,json(headerA));put(hB,json(headerB));
        var seg=new ContraV5SegmentedBackupAudit.Segment(0,2,1,"alpha.txt",PIN,
            sha(hA),sha(hB),sha(pageA),sha(pageB));
        Path manifest=bundle.resolve("manifest.json");
        var typed=new ContraV5SegmentedBackupAudit.Manifest(3,world.toString(),
            a.toString(),b.toString(),V5.toString(),PIN,pinSha,2,1,
            HexFormat.of().formatHex(inventory.digest()),List.of(seg));
        put(manifest,json(typed));

        var ecological=new BatchManifestStore.Header(1,ECO,"minecraft:overworld",
            "proutlost:contra_s2_ecology",27594267L,16,16,4079,4079,645160,
            "ecology-batch-v3");
        var old=ContraV5PreCopyBootstrapRun9.plan(world,dest,V5,ecological,
            "1".repeat(64),Instant.parse("2026-10-29T04:00:00Z"));
        var intended=new NativeV5QualificationIntent(old.version(),old.enabled(),
            old.batch(),old.area(),old.ecology(),manifest.toString(),null,
            a.toString(),b.toString(),old.template(),old.multiSpecies(),old.worldId(),
            old.buildId(),old.revision(),old.validatedAt(),old.directorReceipt(),
            old.directorImporterSha256(),old.spawn());
        var binder=new QualificationBinding(world,
            ContraV5PreCopyBootstrapRun9.journal(world,V5),NativeV5QualificationIntent.class);
        JsonObject frozen=QualificationBinding.JSON.toJsonTree(intended).getAsJsonObject();
        frozen.remove("backupSha256");
        binder.freeze(QualificationBinding.canonical(frozen));
        return new Fixture(world,manifest,a,b,binder,intended);
    }
    @Test void coldSHAFullABThenNativeSegmentWALAndRuntimePublish()throws Exception{
        var f=fixture();
        assertFalse(f.binder().segmentedBound());
        assertFalse(f.binder().bound());
        assertFalse(Files.exists(f.world().resolve(QualificationBinding.REQUEST)));
        var sealed=f.binder().sealSegmented(); // REAL SHA on every file A+B+source
        assertEquals(3,sealed.version());
        assertEquals(2,sealed.verifiedFiles());
        assertEquals(1,sealed.verifiedSegments());
        assertEquals(1,sealed.verifiedPages());
        assertEquals(sha(f.manifest()),sealed.manifestSha256());
        assertTrue(f.binder().segmentedBound());
        assertEquals(sealed,f.binder().verifySegmented());
        assertEquals(sealed.request(),Files.readString(
            f.world().resolve(QualificationBinding.REQUEST)));
        var request=QualificationBinding.decode(sealed.request(),NativeV5QualificationIntent.class);
        assertEquals(V5,request.batch());
        assertEquals(645160,request.ecology().stages());
        assertEquals(sealed.manifestSha256(),request.backupSha256());
        var area=new WorldPrepSavedData.Area("proutlost:contra_s2_ecology",
            "minecraft:overworld",16,16,4079,4079);
        assertEquals(request,NativeV5ContractGate.requireActualSealedBinding(
            f.world(),ContraV5PreCopyBootstrapRun9.journal(f.world(),V5),
            sealed.request(),request.multiSpecies(),area,false));
        assertThrows(IOException.class,f.binder()::sealSegmented);
        assertThrows(IOException.class,f.binder()::seal);
    }
    @Test void activeSessionLockRefusesNativeSealWithNoNewWAL()throws Exception{
        var f=fixture();
        try(var channel=FileChannel.open(f.world().resolve("session.lock"),
                StandardOpenOption.WRITE);
            var held=channel.lock()){
            assertThrows(IOException.class,f.binder()::sealSegmented);
        }
        assertFalse(f.binder().segmentedBound());
        assertFalse(Files.exists(f.world().resolve(QualificationBinding.REQUEST)));
    }
    @Test void missingColdBackupBytesRefuseBeforeNativeSeal()throws Exception{
        var f=fixture();
        Files.delete(f.b().resolve("alpha.txt"));
        assertThrows(IOException.class,f.binder()::sealSegmented);
        assertFalse(f.binder().segmentedBound());
        assertFalse(Files.exists(f.world().resolve(QualificationBinding.REQUEST)));
    }
    @Test void manifestTamperedAfterSealCannotVerifyRuntime()throws Exception{
        var f=fixture();f.binder().sealSegmented();
        Files.writeString(f.manifest(),Files.readString(f.manifest())+"\n");
        assertThrows(IOException.class,f.binder()::verifySegmented);
    }
    @Test void immutableABPinChangeAfterSealFailsClosed()throws Exception{
        var f=fixture();f.binder().sealSegmented();
        Files.writeString(f.a().resolve(PIN),"modified");
        assertThrows(IOException.class,f.binder()::verifySegmented);
    }
    @Test void noColdAuditOrUnsealedLegacyV2IsEverAcceptedForContra()throws Exception{
        var f=fixture();
        assertThrows(IOException.class,f.binder()::verifySegmented);
        assertThrows(IOException.class,f.binder()::seal);
        assertFalse(Files.exists(f.world().resolve(QualificationBinding.REQUEST)));
    }
    @Test void wrongQualificationUUIDRefusesWithoutRuntimePublication()throws Exception{
        var f=fixture();
        String wrong=Files.readString(f.manifest()).replace(V5.toString(),
            UUID.fromString("f5b2a7e0-c21d-4313-84fb-5692bdb34026").toString());
        Files.writeString(f.manifest(),wrong);
        assertThrows(IOException.class,f.binder()::sealSegmented);
        assertFalse(f.binder().segmentedBound());
    }
}
