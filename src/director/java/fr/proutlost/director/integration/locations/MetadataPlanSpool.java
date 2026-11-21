package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Bounded private plan preparation, never a store or an admission receipt.
 * J04 revalidates every encoded page and every revision before activation.
 * Call only on the native import owner's private IO executor. Files are kept
 * after interruptions; only exact immutable bytes can be reused.
 */
public final class MetadataPlanSpool {
    private static final ModelCodec<MetadataGenerationPage> CODEC=new ModelCodec<>(MetadataGenerationPage.class);
    public record FileRef(Path path,Fingerprint sha256,int bytes) {
        public FileRef{Objects.requireNonNull(path);Objects.requireNonNull(sha256);Checks.require(bytes>0&&bytes<=1_048_576,"plan spool file bound");}
    }
    private MetadataPlanSpool(){}
    /** Recover a previously prepared clock only as untrusted proposal data.
     * The caller still checks the current native clock, every head and J04 CAS.
     * This also covers a crash after IO preparation but before the first stage.
     */
    public static Optional<Tick> preparedTick(Path inputs,LocationTransferManifest transfer)throws IOException {
        Path directory=inputs.resolve(".generation-plan-"+LocationTransfer.identity(transfer).value());noLinks(directory);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        Checks.require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"plan spool directory required");
        List<Path> files;try(var entries=Files.list(directory)){
            files=entries.filter(p->p.getFileName().toString().matches("(?:group-[0-9]{4}-chunk-[0-9]{3}|attempt-0-group-[0-9]{4}-chunk-[0-9]{3}-[0-9a-f]{64})\\.json")).limit(1025).sorted().toList();
        }
        Checks.require(files.size()<=1024,"plan spool file count bound");
        for(var file:files){
            byte[] bytes=bounded(file,1_048_576);var saved=CODEC.decode(bytes);
            Checks.require(Arrays.equals(bytes,CODEC.encode(saved))&&saved.generation().equals(LocationTransfer.identity(transfer))
                &&saved.sourceNamespace().equals(transfer.sourceNamespace())&&saved.exportRevision().equals(transfer.exportRevision())
                &&saved.exportDigest().equals(transfer.exportDigest())&&saved.pageIndex()==1023&&saved.pageCount()==1024,"prepared clock identity");
            if(!saved.states().isEmpty())return Optional.of(saved.states().getFirst().header().updatedTick());
        }
        return Optional.empty();
    }
    public static List<FileRef> prepare(Path inputs,LocationTransferManifest transfer,int group,
            List<AggregateState> states,Map<AggregateKey,Revision> before)throws IOException {
        return prepare(inputs,transfer,group,states,before,64L*1024*1024,1024,0);
    }
    public static List<FileRef> prepare(Path inputs,LocationTransferManifest transfer,int group,
            List<AggregateState> states,Map<AggregateKey,Revision> before,
            long remainingBytes,int remainingPages,long minimumFreeBytes)throws IOException {
        return prepare0(inputs,transfer,Optional.empty(),group,states,before,remainingBytes,remainingPages,minimumFreeBytes);
    }
    public static List<FileRef> prepare(Path inputs,LocationTransferManifest transfer,Epoch attempt,int group,
            List<AggregateState> states,Map<AggregateKey,Revision> before,
            long remainingBytes,int remainingPages,long minimumFreeBytes)throws IOException {
        return prepare0(inputs,transfer,Optional.of(attempt),group,states,before,remainingBytes,remainingPages,minimumFreeBytes);
    }
    private static List<FileRef> prepare0(Path inputs,LocationTransferManifest transfer,Optional<Epoch> attempt,int group,
            List<AggregateState> states,Map<AggregateKey,Revision> before,
            long remainingBytes,int remainingPages,long minimumFreeBytes)throws IOException {
        Objects.requireNonNull(attempt);
        Checks.require(group>=0&&group<1024,"plan spool group bound");
        Checks.require(remainingBytes>=0&&remainingBytes<=64L*1024*1024&&remainingPages>=0&&remainingPages<=1024
            &&minimumFreeBytes>=0,"plan spool capacity contract");
        var identity=LocationTransfer.identity(transfer);
        var chunks=MetadataGenerationPacking.pack(identity,transfer.sourceNamespace(),transfer.exportRevision(),transfer.exportDigest(),states,before);
        Path directory=inputs.resolve(".generation-plan-"+identity.value());noLinks(directory);
        Checks.require(chunks.size()<=remainingPages,"plan spool page capacity");
        // Check the whole group before any filesystem mutation. Do not retain
        // a catalogue-sized encoded cache just to compute these counters.
        long total=0,newBytes=0;int candidateIndex=0,newFiles=0;
        for(var chunk:chunks){
            byte[] bytes=CODEC.encode(chunk.page(identity,transfer.sourceNamespace(),transfer.exportRevision(),transfer.exportDigest(),1023,1024));
            total=Math.addExact(total,bytes.length);Checks.require(total<=remainingBytes,"plan spool byte capacity");
            Path path=directory.resolve(name(attempt,group,candidateIndex++,bytes));noLinks(path);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){newBytes=Math.addExact(newBytes,bytes.length);newFiles++;}
        }
        // All immutable attempts share one private spool budget. Historical
        // files remain evidence; reprepare never silently frees or overwrites them.
        long retainedBytes=0;int retainedFiles=0;
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){
            Checks.require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"plan spool directory required");
            List<Path> saved;try(var entries=Files.list(directory)){saved=entries.limit(1025).toList();}
            Checks.require(saved.size()<=1024,"retained plan spool file count bound");
            for(var file:saved){noLinks(file);Checks.require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"plan spool retained file type");
                long size=Files.size(file);Checks.require(size>=0&&size<=1_048_576,"plan spool retained file bytes");
                retainedBytes=Math.addExact(retainedBytes,size);retainedFiles++;}
        }
        Checks.require(Math.addExact(retainedBytes,newBytes)<=64L*1024*1024&&retainedFiles+newFiles<=1024,"retained plan spool capacity");
        Path existing=inputs.toAbsolutePath().normalize();while(existing!=null&&!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        Checks.require(existing!=null&&Files.getFileStore(existing).getUsableSpace()>=Math.addExact(newBytes,minimumFreeBytes),"plan spool disk reserve");
        Files.createDirectories(directory);noLinks(directory);List<FileRef> result=new ArrayList<>();int index=0;
        for(var chunk:chunks){
            var placeholder=chunk.page(identity,transfer.sourceNamespace(),transfer.exportRevision(),transfer.exportDigest(),1023,1024);
            byte[] bytes=CODEC.encode(placeholder);Path path=directory.resolve(name(attempt,group,index++,bytes));
            immutable(path,bytes);result.add(new FileRef(path,BlobContent.hash(bytes),bytes.length));
        }
        forceDirectory(directory);return List.copyOf(result);
    }
    public static MetadataGenerationPage read(FileRef reference,LocationTransferManifest transfer,int index,int count)throws IOException {
        byte[] bytes=bounded(reference.path(),reference.bytes());
        Checks.require(bytes.length==reference.bytes()&&BlobContent.hash(bytes).equals(reference.sha256()),"plan spool bytes changed");
        var saved=CODEC.decode(bytes);var identity=LocationTransfer.identity(transfer);
        Checks.require(Arrays.equals(bytes,CODEC.encode(saved))&&saved.generation().equals(identity)
            &&saved.sourceNamespace().equals(transfer.sourceNamespace())&&saved.exportRevision().equals(transfer.exportRevision())
            &&saved.exportDigest().equals(transfer.exportDigest())&&saved.pageIndex()==1023&&saved.pageCount()==1024,"plan spool identity");
        var page=new MetadataGenerationPage(identity,transfer.sourceNamespace(),transfer.exportRevision(),transfer.exportDigest(),index,count,saved.states(),saved.beforeRevisions());
        Checks.require(CODEC.encode(page).length<=reference.bytes(),"final plan counters exceeded frozen packing");return page;
    }
    private static String name(Optional<Epoch> attempt,int group,int chunk,byte[] bytes){
        if(attempt.isEmpty())return String.format(Locale.ROOT,"group-%04d-chunk-%03d.json",group,chunk);
        // Content identity permits exact replay under the same epoch without
        // treating an uncommitted private plan as an authoritative old read set.
        return "attempt-"+attempt.orElseThrow().value()+String.format(Locale.ROOT,"-group-%04d-chunk-%03d-",group,chunk)+BlobContent.hash(bytes).value()+".json";
    }
    private static void immutable(Path target,byte[] bytes)throws IOException {
        noLinks(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            Checks.require(Arrays.equals(bytes,bounded(target,1_048_576)),"immutable plan changed; keep staged generation and reconcile");return;
        }
        Path temporary=Files.createTempFile(target.getParent(),".plan-",".tmp");
        try{
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static byte[] bounded(Path path,int maximum)throws IOException {
        noLinks(path);Checks.require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=maximum,"plan file type/size");
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(maximum+1);Checks.require(bytes.length<=maximum,"plan spool grew");return bytes;}
    }
    private static void noLinks(Path path)throws IOException{for(var p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("symlinked plan spool");}
    private static void forceDirectory(Path path)throws IOException{try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}}
}
