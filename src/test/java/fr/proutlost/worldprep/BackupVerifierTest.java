package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.BackupVerifier;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BackupVerifierTest {
    @TempDir Path root;
    @Test void bothIndependentCopiesAreVerifiedBeforeCompletion()throws Exception {
        var source=Files.createDirectory(root.resolve("source"));var backup=Files.createDirectory(root.resolve("backup"));
        Files.writeString(source.resolve("level.dat"),"snapshot");Files.writeString(backup.resolve("level.dat"),"snapshot");
        var manifest=new BackupVerifier.Manifest(1,source.toString(),backup.toString(),"fixture-quiescent-generation",List.of(new BackupVerifier.FileProof("level.dat",8,PhysicalStore.sha("snapshot"))));
        var verifier=new BackupVerifier(manifest,source);assertFalse(verifier.step());assertFalse(verifier.step());assertFalse(verifier.step());assertTrue(verifier.step());
    }
    @Test void corruptBackupAndAliasedSourceAreRefused()throws Exception {
        var source=Files.createDirectory(root.resolve("source"));var backup=Files.createDirectory(root.resolve("backup"));
        Files.writeString(source.resolve("level.dat"),"snapshot");Files.writeString(backup.resolve("level.dat"),"modified");
        var files=List.of(new BackupVerifier.FileProof("level.dat",8,PhysicalStore.sha("snapshot")));
        var verifier=new BackupVerifier(new BackupVerifier.Manifest(1,source.toString(),backup.toString(),"fixture",files),source);
        assertFalse(verifier.step());assertThrows(IOException.class,verifier::step);
        assertThrows(IOException.class,()->new BackupVerifier(new BackupVerifier.Manifest(1,source.toString(),source.toString(),"fixture",files),source));
    }
    @Test void omittedWorldFileCannotQualifyPartialBackup()throws Exception {
        var source=Files.createDirectory(root.resolve("source"));var backup=Files.createDirectory(root.resolve("backup"));
        Files.writeString(source.resolve("level.dat"),"snapshot");Files.writeString(backup.resolve("level.dat"),"snapshot");
        Files.writeString(source.resolve("omitted.mca"),"real world data");
        var files=List.of(new BackupVerifier.FileProof("level.dat",8,PhysicalStore.sha("snapshot")));
        var verifier=new BackupVerifier(new BackupVerifier.Manifest(1,source.toString(),backup.toString(),"fixture",files),source);
        assertFalse(verifier.step());assertFalse(verifier.step());assertThrows(IOException.class,verifier::step);
    }
}
