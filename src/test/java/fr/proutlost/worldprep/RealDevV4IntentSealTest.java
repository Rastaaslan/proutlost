package fr.proutlost.worldprep;

import com.google.gson.JsonObject;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import fr.proutlost.worldprep.physical.NativeQualificationSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit DEV-only one-shot seal of a previously frozen v4 intent.
 * Requires the exact preserved full Java audit XML and two complete, immutable
 * backup cursors; never launches a server or imports a Director catalogue.
 */
class RealDevV4IntentSealTest {
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static JsonObject object(Path path) throws Exception {
        return QualificationBinding.object(Files.readString(path));
    }
    private static void requireCompleteCursor(Path path,long files,long pages) throws Exception {
        var envelope=object(path);
        String payload=envelope.get("payload").getAsString();
        assertEquals(sha(payload),envelope.get("sha256").getAsString(),"Cursor checksum");
        var receipt=QualificationBinding.object(payload);
        assertEquals(pages,receipt.get("page").getAsLong());
        assertEquals(files,receipt.get("files").getAsLong());
    }
    private static void requirePinnedJavaPass(Path xml,Path generatedAfter) throws Exception {
        assertEquals("e44d7f61bea024d2b2253b0d06386636f92a539539c6e93141961df8e4bb8cec",
            sha(Files.readString(xml)),"Exact immutable Java proof SHA mismatch");
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var suite=factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
        assertEquals("testsuite",suite.getTagName());
        assertEquals("fr.proutlost.worldprep.RealOfflineV4BackupVerificationTest",suite.getAttribute("name"));
        assertEquals("1",suite.getAttribute("tests"));
        for(String key:new String[]{"skipped","errors","failures"})
            assertEquals("0",suite.getAttribute(key),"Previous Java test did not PASS");
        assertEquals(1,suite.getElementsByTagName("testcase").getLength());
        assertEquals(0,suite.getElementsByTagName("failure").getLength());
        String output=suite.getElementsByTagName("system-out").item(0).getTextContent();
        assertTrue(output.contains("pagesA=370/370 pagesB=370/370"),"Incomplete real pair check");
        assertFalse(Instant.parse(suite.getAttribute("timestamp"))
            .isBefore(Files.getLastModifiedTime(generatedAfter).toInstant()),"Stale proof");
    }
    @Test void sealOnlyAfterRealBackupAuditWasProven() throws Exception {
        String requested=System.getenv("WORLDPREP_DEV_V4_SEAL_WORLD");
        Assumptions.assumeTrue(requested!=null&&!requested.isBlank(),
            "Real DEV v4 seal is opt-in only; never part of ordinary tests");

        Path world=Path.of(requested).toRealPath();
        Path xml=Path.of(System.getenv("WORLDPREP_DEV_V4_FULL_AUDIT_XML")).toRealPath();
        Path input=Path.of(System.getenv("WORLDPREP_DEV_V4_INTENT_INPUT")).toRealPath();
        Path prebound=Path.of(System.getenv("WORLDPREP_DEV_V4_PREBOUND_PROOF")).toRealPath();
        var proof=object(prebound);
        assertEquals("PREBOUND_DEV_V4_TWO_BACKUPS_CREATED_NOT_SEALED",proof.get("status").getAsString());
        requirePinnedJavaPass(xml,prebound);

        JsonObject intended=object(input);
        assertEquals(4,intended.get("version").getAsInt());
        assertEquals("DEV_SERVICE_PROBE",intended.get("directorReceipt").getAsString());
        UUID batch=UUID.fromString(intended.get("batch").getAsString());
        assertEquals(batch.toString(),proof.get("batch").getAsString());
        assertEquals(sha(QualificationBinding.canonical(intended)),
                     proof.get("intentSha256").getAsString());

        Path directory=world.resolve(intended.get("backupManifest").getAsString()).getParent();
        var binder=new QualificationBinding(world,directory,NativeQualificationSession.Request.class);
        assertFalse(binder.bound(),"Prior binding must never be overwritten");
        assertFalse(Files.exists(world.resolve("worldprep-qualification.json")),
            "Prior runtime request must not be replaced");
        assertEquals(QualificationBinding.canonical(intended),binder.intent());

        Path backupFolder=prebound.getParent();
        for(String suffix:new String[]{"a","b"}) {
            var header=QualificationBinding.decode(
                Files.readString(directory.resolve("backup-"+suffix+".json")),
                PagedBackupVerifier.Header.class);
            assertEquals(world.toString(),header.sourceRoot());
            assertEquals(370,header.pages());
            assertEquals(47331,header.files());
            assertEquals(backupFolder.resolve("backup-"+suffix).toString(),header.backupRoot());
            requireCompleteCursor(backupFolder.resolve("java-independent-audit/backup-"+
                suffix+"/cursor.json"),header.files(),header.pages());
        }

        var result=binder.seal(); // immutable binding WAL precedes runtime-request publication
        assertEquals(1,result.version());
        assertTrue(binder.bound());
        assertEquals(result,binder.verify());
        var deployed=QualificationBinding.decode(
            Files.readString(world.resolve("worldprep-qualification.json")),
            NativeQualificationSession.Request.class);
        assertEquals(4,deployed.version());
        assertEquals(batch,deployed.batch());
        assertEquals("DEV_SERVICE_PROBE",deployed.directorReceipt());
        assertEquals("MAP",deployed.spawn().profile());
        System.out.println("WORLDPREP_V4_DEV_SEALED_VERIFIED batch="+batch+
            " requestSha="+result.requestSha256()+" DEV_ONLY_NO_NATIVE_GAMEPLAY_OR_DIRECTOR_ADMISSION");
    }
}
