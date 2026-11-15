package fr.proutlost.worldprep.orchestration;

import com.google.gson.Gson;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Zero bytes is a native absent entity region, but only under a fully audited V2 inventory.
 * Caller must complete PagedBackupVerifier and replay its page chain. One immutable receipt
 * covers all 1024 absent slots, with no fauna/UUID fabrication and no writes to either copy.
 */
public final class EmptyNativeEntityRegion {
    public static final String EMPTY_SHA="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public record Receipt(int version,String disposition,String inventorySha256,
                          PagedBackupVerifier.Entry entry,int slots,DiskPopulationCounts counts) {}
    private static final Gson JSON=new Gson();
    public static boolean recordIfEmpty(PagedBackupVerifier.Header header,PagedBackupVerifier.Entry entry,PhysicalStore store)throws IOException {
        if(header.version()!=2)throw new IOException("Audited V2 inventory required");
        if(entry.bytes()>0&&entry.bytes()<8192)throw new IOException("Truncated entity region header");
        if(entry.bytes()!=0)return false;
        if(!EMPTY_SHA.equals(entry.sha256()))throw new IOException("Empty entity inventory SHA mismatch");
        Path relative=Path.of(entry.path());
        if(relative.getParent()==null||!relative.getParent().getFileName().toString().equals("entities")
            ||!relative.getFileName().toString().matches("r\\.-?[0-9]+\\.-?[0-9]+\\.mca"))throw new IOException("Entity region path required");
        Path source=Path.of(header.sourceRoot()).toAbsolutePath().normalize(),backup=Path.of(header.backupRoot()).toAbsolutePath().normalize();
        if(source.startsWith(backup)||backup.startsWith(source))throw new IOException("Independent copies required");
        Path a=source.resolve(relative),b=backup.resolve(relative);
        verify(a,entry.sourceModifiedMillis());verify(b,entry.backupModifiedMillis());
        if(Files.isSameFile(a,b))throw new IOException("Aliased entity region");
        var receipt=new Receipt(1,"EMPTY_NATIVE_ENTITY_REGION",PhysicalStore.sha(JSON.toJson(header)),entry,1024,DiskPopulationCounts.empty());
        store.write("empty-region-"+PhysicalStore.sha(entry.path()),receipt);
        return true;
    }
    private static void verify(Path path,long modified)throws IOException {
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink entity region");
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()!=0||before.lastModifiedTime().toMillis()!=modified)throw new IOException("Empty entity identity changed");
        // Reject hardlink aliases even when their other names are outside the inventory.
        if(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("Hardlinked entity region");
        try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(channel.size()!=0||channel.read(ByteBuffer.allocate(1),0)!=-1)throw new IOException("Entity region is not empty");
        }
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||after.size()!=0||after.lastModifiedTime().toMillis()!=modified
            ||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("Empty entity changed during read");
    }
    private EmptyNativeEntityRegion(){}
}
