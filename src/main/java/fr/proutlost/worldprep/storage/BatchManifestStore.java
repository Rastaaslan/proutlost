package fr.proutlost.worldprep.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned header and bounded immutable stage records. A receipt is durable BEFORE SavedData advances. */
public final class BatchManifestStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Header(int schemaVersion, UUID batchId, String dimension, String area, long worldSeed,
                         int minX, int minZ, int maxX, int maxZ, long stages, String pipelineVersion) {}
    public record Stage(int schemaVersion, UUID batchId, long sequence, long tileIndex, String tileId, String stage,
                        String artifact, String artifactSha256, String support, String completion,
                        UUID childId, UUID planId, UUID snapshotId, long plannedChanges, long appliedPlanEntries, String previousReceiptSha256) {}
    public record Completion(int schemaVersion, UUID batchId, long stages, String finalReceiptSha256, String status) {}

    public static Path directory(Path root, UUID batch) { return root.resolve("batches").resolve(batch.toString()); }
    public static void initialize(Path root, Header header) throws IOException {
        if (header.schemaVersion()!=1 || header.batchId()==null || header.stages()<1 || header.pipelineVersion()==null)
            throw new IllegalArgumentException("Invalid batch identity");
        publish(directory(root,header.batchId()).resolve("manifest.json"), JSON.toJson(header));
    }
    public static Path stagePath(Path root, UUID batch, long sequence) {
        if(sequence<0)throw new IllegalArgumentException("Negative stage sequence");
        return directory(root,batch).resolve(String.format(java.util.Locale.ROOT,"%020d.json",sequence));
    }
    public static String artifactHash(Path root, String artifact) throws IOException { return hash(Files.readAllBytes(artifactPath(root,artifact))); }
    private static Path artifactPath(Path root, String artifact) throws IOException {
        if(artifact==null || !(artifact.matches("discovery/[0-9a-f]{64}\\.json")
                || artifact.matches("plans/[0-9a-f-]{36}/manifest\\.wpm")))throw new IOException("Invalid artifact path");
        Path normalized=root.toAbsolutePath().normalize(), target=normalized.resolve(artifact);
        for(Path p=target;!p.equals(normalized);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink artifact refused");
        if(!Files.isRegularFile(target)||Files.size(target)>16*1024*1024)throw new IOException("Artifact missing or oversized: "+artifact);
        return target;
    }
    public static String previousHash(Path root, UUID batch, long sequence) throws IOException {
        return sequence==0?hash(Files.readAllBytes(directory(root,batch).resolve("manifest.json")))
                :hash(Files.readAllBytes(stagePath(root,batch,sequence-1)));
    }
    public static void record(Path root, Header header, Stage stage) throws IOException {
        initialize(root,header);
        validate(root,header,stage);
        publish(stagePath(root,header.batchId(),stage.sequence()),JSON.toJson(stage));
    }
    public static Stage read(Path root, Header header, long sequence) throws IOException {
        requireHeader(root,header);
        var file=stagePath(root,header.batchId(),sequence);
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>64*1024)throw new IOException("Receipt missing or oversized");
        try {
            Stage stage=JSON.fromJson(Files.readString(file),Stage.class);
            if(stage==null||stage.sequence()!=sequence)throw new IOException("Receipt sequence mismatch");
            validate(root,header,stage);return stage;
        } catch(RuntimeException e){throw new IOException("Malformed receipt",e);}
    }
    private static void validate(Path root, Header header, Stage stage) throws IOException {
        if(stage.schemaVersion()!=1 || !header.batchId().equals(stage.batchId()) || stage.sequence()<0
                || stage.sequence()>=header.stages() || stage.tileIndex()<0 || stage.tileId()==null || stage.stage()==null
                || stage.support()==null || !"COMPLETED".equals(stage.completion()) || stage.plannedChanges()<0 || stage.appliedPlanEntries()<0 || stage.appliedPlanEntries()>stage.plannedChanges()
                || !previousHash(root,header.batchId(),stage.sequence()).equals(stage.previousReceiptSha256())
                || !artifactHash(root,stage.artifact()).equals(stage.artifactSha256()))throw new IOException("Receipt identity/content mismatch");
    }
    /** Streaming downstream verification; memory stays bounded to one receipt and one small artifact. */
    public static void validateAll(Path root, Header header) throws IOException {
        requireHeader(root,header);
        for(long i=0;i<header.stages();i++)read(root,header,i);
    }
    private static void requireHeader(Path root,Header header)throws IOException {
        var file=directory(root,header.batchId()).resolve("manifest.json");
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>64*1024
                ||!Files.readString(file).equals(JSON.toJson(header)))throw new IOException("Batch header identity mismatch");
    }
    public static void complete(Path root, Header header) throws IOException {
        validateAll(root,header);
        publishCompletion(root,header);
    }
    private static void publishCompletion(Path root, Header header) throws IOException {
        publish(directory(root,header.batchId()).resolve("completed.json"),JSON.toJson(new Completion(1,header.batchId(),header.stages(),
                hash(Files.readAllBytes(stagePath(root,header.batchId(),header.stages()-1))),"STAGES_COMPLETED_DIRECTOR_NOT_READY")));
    }
    /** One receipt/artifact per step, with no completion publication until the entire chain passes.
     * Inputs must remain immutable during validation, as for every batch artifact.
     * A fresh process must start a fresh scan; this object is deliberately not serializable.
     */
    public static final class CompletionScan {
        private final Path root;
        private final Header header;
        private long next;
        public CompletionScan(Path root, Header header) { this.root=root; this.header=header; }
        public boolean step() throws IOException {
            if(next<header.stages()) { read(root,header,next); next++; }
            if(next<header.stages()) return false;
            publishCompletion(root,header);
            return true;
        }
    }
    private static void publish(Path target,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(Files.isSymbolicLink(target))throw new IOException("Symlink receipt refused");
        if(Files.exists(target)){
            if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target)))throw new IOException("Immutable batch record conflict: "+target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path staging=Files.createTempFile(target.getParent(),".batch-",".partial");
        try(var channel=FileChannel.open(staging,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);
        try(var channel=FileChannel.open(target.getParent(),StandardOpenOption.READ)){channel.force(true);}
        catch(UnsupportedOperationException e){/* Directory fsync not exposed by this provider. */}
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private BatchManifestStore() {}
}
