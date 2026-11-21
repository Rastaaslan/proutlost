package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;

/** Producer-side immutable message publication. These files are transport only:
 * neither an export pointer nor a successful write is a native J04 receipt.
 * Call on the producer's IO executor, never on a Minecraft actor.
 */
public final class LocationTransferFiles {
    public record Published(Path directory,Fingerprint manifestSha256,
                            Fingerprint sourceSha256,int pages,boolean written) {}
    private static final ModelCodec<LocationTransferManifest> MANIFEST=new ModelCodec<>(LocationTransferManifest.class);
    private static final ModelCodec<LocationTransferPage> PAGE=new ModelCodec<>(LocationTransferPage.class);
    private LocationTransferFiles(){}

    /** The target is generation-specific and must never be replaced. A repeated
     * call verifies every existing byte, including absence of extra messages.
     */
    public static Published writeImmutable(Path target,LocationImport source)throws IOException {
        Objects.requireNonNull(target);Objects.requireNonNull(source);
        Path directory=target.toAbsolutePath().normalize(),parent=directory.getParent();
        if(parent==null)throw new IOException("transfer parent required");
        noLinks(directory);Files.createDirectories(parent);noLinks(parent);
        var prepared=LocationTransfer.prepare(source);
        byte[] manifest=MANIFEST.encode(prepared.manifest());
        if(manifest.length>LocationTransfer.WIRE_BYTES)throw new IOException("manifest wire bound");
        Path lockFile=parent.resolve(".location-transfer-files.lock");noLinks(lockFile);
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=lock(channel)) {
            if(!lock.isValid())throw new IOException("transfer publication lock unavailable");
            if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) {
                verify(directory,prepared,manifest);
                return published(directory,prepared,false);
            }
            Path temporary=Files.createTempDirectory(parent,".location-transfer-");
            // An interrupted unpublished directory is retained for diagnosis.
            // It never ends in _transfer and is never selected by native ingress.
            writeNew(temporary.resolve("manifest.json"),manifest);
            for(var page:prepared.pages()) {
                byte[] bytes=PAGE.encode(page);
                if(bytes.length>LocationTransfer.WIRE_BYTES)throw new IOException("page wire bound");
                writeNew(temporary.resolve(pageName(page.pageIndex())),bytes);
            }
            sync(temporary);
            // Same-parent rename publishes all messages together; the parent lock
            // serializes cooperating exporters. No replacement option is used.
            Files.move(temporary,directory,StandardCopyOption.ATOMIC_MOVE);
            sync(parent);verify(directory,prepared,manifest);
            return published(directory,prepared,true);
        }
    }
    private static FileLock lock(FileChannel channel)throws IOException {
        try {var lock=channel.tryLock();if(lock==null)throw new IOException("transfer publication already owned");return lock;}
        catch(OverlappingFileLockException busy){throw new IOException("transfer publication already owned",busy);}
    }
    private static Published published(Path path,LocationTransfer.Prepared prepared,boolean written) {
        return new Published(path,LocationTransfer.identity(prepared.manifest()),prepared.manifest().sourceSha256(),prepared.pages().size(),written);
    }
    private static String pageName(int index){return String.format(Locale.ROOT,"page-%04d.json",index);}
    private static void verify(Path directory,LocationTransfer.Prepared prepared,byte[] manifest)throws IOException {
        noLinks(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("transfer target is not a directory");
        Set<String> expected=new HashSet<>();expected.add("manifest.json");
        for(var page:prepared.pages())expected.add(pageName(page.pageIndex()));
        Set<String> found=new HashSet<>();
        try(var files=Files.list(directory)) {
            for(var path:files.limit(expected.size()+1L).toList())found.add(path.getFileName().toString());
        }
        if(!found.equals(expected))throw new IOException("existing transfer message set differs; retained");
        equal(directory.resolve("manifest.json"),manifest);
        for(var page:prepared.pages())equal(directory.resolve(pageName(page.pageIndex())),PAGE.encode(page));
    }
    private static void equal(Path path,byte[] expected)throws IOException {
        noLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=expected.length)
            throw new IOException("immutable transfer file type/length changed");
        byte[] actual;
        try(var input=Files.newInputStream(path)){actual=input.readNBytes(LocationTransfer.WIRE_BYTES+1);}
        if(!Arrays.equals(actual,expected))throw new IOException("immutable transfer file differs; retained");
    }
    private static void noLinks(Path path)throws IOException {
        for(var p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("symlinked transfer publication path");
    }
    private static void writeNew(Path path,byte[] bytes)throws IOException {
        try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
        }
    }
    private static void sync(Path directory)throws IOException {
        try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
    }
}
