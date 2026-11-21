package fr.proutlost.worldprep.orchestration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Verify operator's quiescent inventory against both physical copies, at most 1 MiB per step.
 * This never creates a backup or changes either copy. Inventory traversal rejects omitted files.
 */
public final class BackupVerifier implements AutoCloseable {
    public record FileProof(String path,long bytes,String sha256) {
        public FileProof {if(path==null||bytes<0||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();}
    }
    public record Manifest(int version,String sourceRoot,String backupRoot,String quiescentGeneration,List<FileProof> files) {
        public Manifest {files=List.copyOf(files);if(version!=1||files.isEmpty()||files.size()>4096||quiescentGeneration==null||quiescentGeneration.isBlank())throw new IllegalArgumentException("Quiescent bounded inventory required");}
    }
    private final Manifest manifest;
    private final Path source,backup;
    private final MessageDigest digest;
    private final Set<String> expected;
    private final Set<Path> transientPaths;
    private java.util.stream.Stream<Path> inventory;
    private Iterator<Path> entries;
    private int inventoryCopy,inventoryEntries;
    private int cursor,copy;
    private long offset;
    public BackupVerifier(Manifest manifest,Path expectedSource)throws IOException {
        this(manifest,expectedSource,Set.of());
    }
    public BackupVerifier(Manifest manifest,Path expectedSource,Set<Path> transientPaths)throws IOException {
        this.transientPaths=Set.copyOf(transientPaths);
        for(var p:this.transientPaths)if(p.isAbsolute()||p.toString().isEmpty()||!p.normalize().equals(p)||p.startsWith(".."))throw new IOException("Invalid transient inventory exclusion");
        expected=new HashSet<>();
        for(var file:manifest.files()) {
            var p=Path.of(file.path());
            if(p.isAbsolute()||!p.normalize().equals(p)||p.startsWith("..")||file.path().isEmpty())throw new IOException("Noncanonical inventory path");
            expected.add(file.path());
        }
        this.manifest=manifest;source=Path.of(manifest.sourceRoot()).toRealPath();backup=Path.of(manifest.backupRoot()).toRealPath();
        if(!source.equals(expectedSource.toRealPath())||source.startsWith(backup)||backup.startsWith(source))throw new IOException("Backup is not an independent source-bound copy");
        if(manifest.files().stream().map(FileProof::path).distinct().count()!=manifest.files().size())throw new IOException("Duplicate backup inventory path");
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public boolean step()throws IOException {
        if(cursor==manifest.files().size())return inventoryStep();
        var proof=manifest.files().get(cursor);var base=copy==0?source:backup;
        var relative=Path.of(proof.path());if(relative.isAbsolute()||relative.normalize().startsWith(".."))throw new IOException("Escaping inventory path");
        var file=base.resolve(relative).normalize();
        for(Path p=file;!p.equals(base);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink inventory path");
        if(Files.exists(backup.resolve(relative))&&Files.isSameFile(source.resolve(relative),backup.resolve(relative)))throw new IOException("Aliased backup file");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=proof.bytes())throw new IOException("Backup file identity/size mismatch");
        var buffer=ByteBuffer.allocate((int)Math.min(1024*1024,proof.bytes()-offset));
        try(var input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            while(buffer.hasRemaining())if(input.read(buffer,offset+buffer.position())<=0)throw new IOException("Truncated backup file");
        }
        digest.update(buffer.array());offset+=buffer.capacity();
        if(offset==proof.bytes()) {
            if(!HexFormat.of().formatHex(digest.digest()).equals(proof.sha256()))throw new IOException("Backup checksum mismatch: "+proof.path());
            offset=0;if(copy==0)copy=1;else{copy=0;cursor++;}
        }
        return false;
    }
    private boolean inventoryStep()throws IOException {
        if(inventoryCopy==2)return true;
        Path base=inventoryCopy==0?source:backup;
        if(inventory==null){inventory=Files.walk(base,32);entries=inventory.iterator();}
        try {
            for(int n=0;n<64&&entries.hasNext();n++) {
                Path p=entries.next(),relative=base.relativize(p);
                if(++inventoryEntries>16384)throw new IOException("Backup inventory traversal cap");
                if(Files.isSymbolicLink(p))throw new IOException("Symlink in backup inventory");
                if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) {
                    if(relative.getNameCount()>=32)throw new IOException("Inventory depth exceeded");
                    continue;
                }
                if(relative.toString().equals("session.lock"))continue; // Ephemeral Minecraft lock, never world data.
                if(transientPaths.stream().anyMatch(relative::startsWith))continue;
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!expected.contains(relative.toString()))throw new IOException("Uninventoried backup/source file: "+relative);
            }
            if(!entries.hasNext()){inventory.close();inventory=null;entries=null;inventoryCopy++;inventoryEntries=0;}
            return inventoryCopy==2;
        } catch(IOException|RuntimeException failure) {inventory.close();inventory=null;entries=null;throw failure;}
    }
    @Override public void close(){if(inventory!=null)inventory.close();inventory=null;entries=null;}
}
