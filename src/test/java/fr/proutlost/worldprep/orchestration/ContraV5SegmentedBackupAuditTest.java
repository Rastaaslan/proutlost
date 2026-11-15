package fr.proutlost.worldprep.orchestration;

import com.google.gson.Gson;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** True files copied into independent A/B roots, not mocked SHA receipts. */
public class ContraV5SegmentedBackupAuditTest {
    @TempDir Path home;
    private static final Gson JSON=new Gson();
    private static final String PIN="worldprep-qualification-pin.json";
    private static String fileSha(Path p)throws IOException {
        try{
            var m=MessageDigest.getInstance("SHA-256");
            m.update(Files.readAllBytes(p));
            return HexFormat.of().formatHex(m.digest());
        }catch(NoSuchAlgorithmException x){throw new AssertionError(x);}
    }
    private static MessageDigest hash(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static void write(Path path,String content)throws IOException{
        Files.createDirectories(path.getParent());Files.writeString(path,content,StandardOpenOption.CREATE_NEW);
    }
    private static void copy(Path source,Path target)throws IOException{
        Files.createDirectories(target.getParent());
        Files.copy(source,target,StandardCopyOption.COPY_ATTRIBUTES);
    }
    private record Fixture(Path world,Path a,Path b,Path manifest,Path firstPageA){}
    private Fixture create()throws IOException{
        Path world=home.resolve("world"),a=home.resolve("copy-A"),b=home.resolve("copy-B"),bundle=home.resolve("manifest-bundle");
        Files.createDirectories(world);Files.createDirectories(a);Files.createDirectories(b);
        write(world.resolve("session.lock"),"");
        write(world.resolve(PIN),"pinned-generation-1");
        write(world.resolve("a.txt"),"first");
        write(world.resolve("folder/b.data"),"second");
        write(world.resolve("k.txt"),"third");
        write(world.resolve("z.txt"),"fourth");
        String pinSha=fileSha(world.resolve(PIN));
        List<List<String>> names=List.of(List.of("a.txt","folder/b.data"),List.of("k.txt",PIN),List.of("z.txt"));
        // These are not real production manifests; tests intentionally use multiple bounded segments.
        List<ContraV5SegmentedBackupAudit.Segment> segments=new ArrayList<>();
        MessageDigest inventory=hash();
        long files=0,pages=0;
        for(int i=0;i<names.size();i++){
            Path dir=bundle.resolve(String.format(Locale.ROOT,"segments/segment-%02d",i));
            Files.createDirectories(dir);
            List<PagedBackupVerifier.Entry> ea=new ArrayList<>(),eb=new ArrayList<>();
            for(String n:names.get(i)){
                Path origin=world.resolve(n),dstA=a.resolve(n),dstB=b.resolve(n);
                copy(origin,dstA);copy(origin,dstB);
                long bytes=Files.size(origin);
                String sha=fileSha(origin);
                long srcMillis=Files.getLastModifiedTime(origin).toMillis();
                long aMillis=Files.getLastModifiedTime(dstA).toMillis();
                long bMillis=Files.getLastModifiedTime(dstB).toMillis();
                ea.add(new PagedBackupVerifier.Entry(n,bytes,sha,srcMillis,aMillis));
                eb.add(new PagedBackupVerifier.Entry(n,bytes,sha,srcMillis,bMillis));
                inventory.update((n+"\u0000"+bytes+"\u0000"+sha+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String textA=JSON.toJson(new PagedBackupVerifier.Page(2,0,"0".repeat(64),ea));
            String textB=JSON.toJson(new PagedBackupVerifier.Page(2,0,"0".repeat(64),eb));
            write(dir.resolve("backup-a.json.pages/page-0.json"),textA);
            write(dir.resolve("backup-b.json.pages/page-0.json"),textB);
            String lastA=PhysicalStore.sha(textA),lastB=PhysicalStore.sha(textB);
            String headerA=JSON.toJson(new PagedBackupVerifier.Header(2,world.toString(),a.toString(),PIN,pinSha,1,ea.size(),lastA));
            String headerB=JSON.toJson(new PagedBackupVerifier.Header(2,world.toString(),b.toString(),PIN,pinSha,1,eb.size(),lastB));
            write(dir.resolve("backup-a.json"),headerA);
            write(dir.resolve("backup-b.json"),headerB);
            segments.add(new ContraV5SegmentedBackupAudit.Segment(i,ea.size(),1,
                names.get(i).getFirst(),names.get(i).getLast(),PhysicalStore.sha(headerA),
                PhysicalStore.sha(headerB),lastA,lastB));
            files+=ea.size();pages++;
        }
        Path manifest=bundle.resolve("manifest.json");
        String digest=HexFormat.of().formatHex(inventory.digest());
        write(manifest,JSON.toJson(new ContraV5SegmentedBackupAudit.Manifest(3,
            world.toString(),a.toString(),b.toString(),
            "873e51c5-b528-4fb7-bc34-7e0221d6c59d",PIN,pinSha,files,pages,digest,segments)));
        return new Fixture(world,a,b,manifest,bundle.resolve("segments/segment-00/backup-a.json.pages/page-0.json"));
    }
    @Test void trueMultipleSegmentsFullSourceAndTwoCopies()throws Exception{
        var f=create();
        var result=ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b());
        assertEquals(5,result.files());
        assertEquals(3,result.segments());
        assertEquals(3,result.pages());
        assertEquals("COLD_SEGMENTED_A_B_CONTENT_AND_FULL_COVERAGE_SHA_VERIFIED_NOT_V5_QUALIFIED",result.status());
    }
    @Test void refusesBitRotAndAdditionalFile()throws Exception{
        var f=create();
        Files.writeString(f.b().resolve("k.txt"),"malicious");
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void refusesOmittedInventoryFileAndMissingPage()throws Exception{
        var f=create();
        Files.delete(f.b().resolve("z.txt"));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
        Files.delete(f.firstPageA());
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void refusesWorldLockedByOtherProcess()throws Exception{
        var f=create();
        try(var lockChannel=FileChannel.open(f.world().resolve("session.lock"),StandardOpenOption.WRITE);
            var lock=lockChannel.lock()) {
            assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
        }
    }
    @Test void refusesHeaderOrChainTampering()throws Exception{
        var f=create();
        Files.writeString(f.firstPageA(),"{}");
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void refusesExplicitDuplicateOrOverlappingSegment()throws Exception{
        var f=create();
        String old=Files.readString(f.manifest());
        var tampered=old.replace("\"ordinal\":1","\"ordinal\":0");
        assertNotEquals(old,tampered);
        Files.writeString(f.manifest(),tampered);
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void refusesUninventoriedExtraFileEvenIfAllOtherHashesMatch()throws Exception{
        var f=create();
        write(f.a().resolve("not-in-manifest.txt"),"extra");
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void refusesIndependenceViolationHardlink()throws Exception{
        var f=create();
        Path bFile=f.b().resolve("a.txt");
        Files.delete(bFile);
        Files.createLink(bFile,f.a().resolve("a.txt"));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
    }
    @Test void pythonProducedRealIndependentColdBundleIsJavaVerified()throws Exception{
        String supplied=System.getenv("RUN58_FIXTURE_MANIFEST");
        org.junit.jupiter.api.Assumptions.assumeTrue(supplied!=null&&!supplied.isBlank(),"No external DEV fixture selected");
        Path bundle=Path.of(supplied);
        var m=QualificationBinding.decode(Files.readString(bundle),ContraV5SegmentedBackupAudit.Manifest.class);
        var out=ContraV5SegmentedBackupAudit.verify(bundle,Path.of(m.world()),Path.of(m.backupA()),Path.of(m.backupB()));
        assertEquals(5,out.files());assertEquals(3,out.segments());assertEquals(3,out.pages());
    }
    @Test void failureNeverCachesSuccessAndCleanColdRetryRechecksAllBytes()throws Exception{
        var f=create();
        Path p=f.b().resolve("k.txt");
        var beforeTime=Files.getLastModifiedTime(p);
        Files.writeString(p,"bad");
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()));
        // In this isolated DEV fixture only: restore bytes AND mtime, then full cold re-read.
        Files.copy(f.world().resolve("k.txt"),p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
        Files.setLastModifiedTime(p,beforeTime);
        assertEquals(5,ContraV5SegmentedBackupAudit.verify(f.manifest(),f.world(),f.a(),f.b()).files());
    }

    @Test void exactV5QualificationIdentityMustMatchTheActualFrozenBatch()throws Exception{
        var f=create();
        UUID expected=UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d");
        var passed=ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),expected);
        assertEquals(5,passed.files());
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),UUID.randomUUID()));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),null));
    }
    @Test void eachSegmentPreservesTheHistoricTwoPointFiveMillionFileLimit()throws Exception{
        var f=create();
        var o=com.google.gson.JsonParser.parseString(Files.readString(f.manifest())).getAsJsonObject();
        o.getAsJsonArray("segments").get(0).getAsJsonObject().addProperty("files",2_500_001);
        Files.writeString(f.manifest(),JSON.toJson(o));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d")));
    }
    @Test void eachSegmentPreservesTheHistoricTwentyThousandPageLimit()throws Exception{
        var f=create();
        var o=com.google.gson.JsonParser.parseString(Files.readString(f.manifest())).getAsJsonObject();
        o.getAsJsonArray("segments").get(0).getAsJsonObject().addProperty("pages",20_001);
        Files.writeString(f.manifest(),JSON.toJson(o));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d")));
    }
    @Test void aDifferentCanonicalUUIDCannotPassTheActualV5IdentityGate()throws Exception{
        var f=create();
        var o=com.google.gson.JsonParser.parseString(Files.readString(f.manifest())).getAsJsonObject();
        o.addProperty("qualification","11111111-1111-4111-8111-111111111111");
        Files.writeString(f.manifest(),JSON.toJson(o));
        assertThrows(IOException.class,()->ContraV5SegmentedBackupAudit.verifyForQualification(
            f.manifest(),f.world(),f.a(),f.b(),UUID.fromString("873e51c5-b528-4fb7-bc34-7e0221d6c59d")));
    }

}
