package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Bounded, immutable plan publication and traversal.  A reader owns at most one
 * decoded page; callers never receive a collection representing the whole plan.
 */
public final class PagedPlanStore {
    private static final int MANIFEST_MAGIC = 0x57504d46;
    private static final int MANIFEST_FORMAT = 1;

    public interface EntryCodec<T> {
        void write(DataOutput out, T value) throws IOException;
        T read(DataInput in) throws IOException;
    }

    public record Publication(Path directory, PlanManifest manifest) {}
    public record ReadMetrics(long pagesRead, long entriesRead, int maximumResidentPages,
                              int maximumResidentEntries) {}
    public record LifecycleDiagnostics(List<Path> temporaryFiles, List<Path> unreferencedPages,
                                       List<Path> missingReferencedPages) {
        public LifecycleDiagnostics {
            temporaryFiles = List.copyOf(temporaryFiles);
            unreferencedPages = List.copyOf(unreferencedPages);
            missingReferencedPages = List.copyOf(missingReferencedPages);
        }
        public boolean healthy() { return temporaryFiles.isEmpty() && missingReferencedPages.isEmpty(); }
    }

    public static <T> Publication publish(Path root, UUID planId, PassId pass, String area,
            String dimension, String profile, String semanticInputDigest, String engineVersion,
            String plannerVersion, int entriesPerPage, Iterable<T> entries, EntryCodec<T> codec)
            throws IOException {
        Objects.requireNonNull(entries, "entries"); Objects.requireNonNull(codec, "codec");
        if (entriesPerPage < 1) throw new IllegalArgumentException("entriesPerPage must be positive");
        Path directory = root.resolve(planId.toString());
        if (Files.exists(manifestPath(directory))) throw new IOException("Plan is already published");
        Files.createDirectories(directory);
        var references = new ArrayList<PlanManifest.PageReference>();
        var batch = new ArrayList<T>(entriesPerPage);
        long sequence = 0;
        for (T entry : entries) {
            batch.add(Objects.requireNonNull(entry, "entry"));
            if (batch.size() == entriesPerPage) {
                references.add(writePage(directory, planId, pass, dimension, sequence++, batch, codec));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) references.add(writePage(directory, planId, pass, dimension, sequence, batch, codec));
        var manifest = PlanManifest.create(DurablePageStore.FORMAT_VERSION, planId, pass, area,
                dimension, profile, semanticInputDigest, engineVersion, plannerVersion,
                PlanManifest.State.SEALED, references);
        // The manifest is the commit record and is published only after every page rereads cleanly.
        validatePages(directory, manifest);
        publishManifest(manifestPath(directory), manifest);
        return new Publication(directory, readManifest(directory));
    }

    public static PlanManifest readManifest(Path directory) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(manifestPath(directory))))) {
            if (in.readInt() != MANIFEST_MAGIC || in.readInt() != MANIFEST_FORMAT) throw new IOException("Invalid plan manifest format");
            int pageFormat = in.readInt(); UUID id = new UUID(in.readLong(), in.readLong());
            PassId pass = enumValue(PassId.class, in.readUTF()); String area = in.readUTF();
            String dimension = in.readUTF(), profile = in.readUTF(), semantic = in.readUTF();
            String engine = in.readUTF(), planner = in.readUTF();
            PlanManifest.State state = enumValue(PlanManifest.State.class, in.readUTF());
            int count = in.readInt();
            if (count < 0 || count > 10_000_000) throw new IOException("Invalid manifest page count");
            var pages = new ArrayList<PlanManifest.PageReference>(count);
            for (int i=0;i<count;i++) pages.add(new PlanManifest.PageReference(in.readLong(), in.readInt(), in.readInt(), in.readInt(), in.readUTF()));
            String root = in.readUTF();
            if (in.read() != -1) throw new IOException("Trailing plan manifest data");
            try { return new PlanManifest(pageFormat,id,pass,area,dimension,profile,semantic,engine,planner,state,pages,root); }
            catch (IllegalArgumentException exception) { throw new IOException("Invalid plan manifest", exception); }
        } catch (EOFException exception) { throw new IOException("Truncated plan manifest", exception); }
    }

    /** Validates every page incrementally before invoking the consumer. */
    public static <T> ReadMetrics read(Path directory, PlanManifest expected, EntryCodec<T> codec,
            Consumer<T> consumer) throws IOException {
        PlanManifest durable = readManifest(directory);
        if (!durable.equals(expected)) throw new IOException("Exact sealed manifest mismatch");
        durable.requireApplicable();
        long pagesRead=0, entriesRead=0; int maximumEntries=0;
        for (var reference : durable.pages()) {
            var page = requirePage(directory, durable, reference);
            var decoded = decode(page, codec);
            maximumEntries = Math.max(maximumEntries, decoded.size()); pagesRead++; entriesRead += decoded.size();
            for (T entry : decoded) consumer.accept(entry);
            decoded.clear(); // make the one-page residency explicit before the next disk read
        }
        return new ReadMetrics(pagesRead,entriesRead,pagesRead==0?0:1,maximumEntries);
    }

    public static void validatePages(Path directory, PlanManifest manifest) throws IOException {
        manifest.requireApplicable();
        for (var reference : manifest.pages()) requirePage(directory, manifest, reference);
    }

    /** Reports suspicious files but deliberately never deletes recovery material. */
    public static LifecycleDiagnostics diagnose(Path directory, PlanManifest manifest) throws IOException {
        var temporary = new ArrayList<Path>(); var unreferenced = new ArrayList<Path>(); var missing = new ArrayList<Path>();
        var referenced = new HashSet<Path>();
        for (var page : manifest.pages()) { Path path=pagePath(directory,page.sequence()); referenced.add(path); if(!Files.isRegularFile(path)) missing.add(path); }
        if (Files.isDirectory(directory)) try (var stream=Files.list(directory)) { stream.forEach(path->{
            String name=path.getFileName().toString();
            if(name.contains(".tmp-")) temporary.add(path);
            else if(name.endsWith(".wpp")&&!referenced.contains(path)) unreferenced.add(path);
        }); }
        temporary.sort(Comparator.naturalOrder()); unreferenced.sort(Comparator.naturalOrder()); missing.sort(Comparator.naturalOrder());
        return new LifecycleDiagnostics(temporary,unreferenced,missing);
    }

    public static Path pagePath(Path directory,long sequence){return directory.resolve(String.format("%020d.wpp",sequence));}
    public static Path manifestPath(Path directory){return directory.resolve("manifest.wpm");}

    private static <T> PlanManifest.PageReference writePage(Path directory, UUID owner, PassId pass,
            String dimension,long sequence,List<T> values,EntryCodec<T> codec)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(values.size());for(T value:values)codec.write(out,value);}
        int chunkX=(int)(sequence>>32),chunkZ=(int)sequence;
        var page=DurablePageStore.page(DurablePageStore.StorageKind.PLAN,owner,pass,dimension,chunkX,chunkZ,sequence,values.size(),bytes.toByteArray());
        DurablePageStore.publish(pagePath(directory,sequence),page);
        var durable=DurablePageStore.read(pagePath(directory,sequence));
        return new PlanManifest.PageReference(sequence,chunkX,chunkZ,durable.entryCount(),durable.checksum());
    }

    private static DurablePageStore.Page requirePage(Path directory,PlanManifest manifest,PlanManifest.PageReference reference)throws IOException{
        var page=DurablePageStore.read(pagePath(directory,reference.sequence()));
        if(page.storageKind()!=DurablePageStore.StorageKind.PLAN||!page.ownerId().equals(manifest.planId())||page.passId()!=manifest.passId()
                ||!page.dimension().equals(manifest.dimension())||page.sequence()!=reference.sequence()||page.chunkX()!=reference.chunkX()
                ||page.chunkZ()!=reference.chunkZ()||page.entryCount()!=reference.entryCount()||!page.checksum().equals(reference.checksum()))
            throw new IOException("Plan page identity mismatch at sequence "+reference.sequence());
        return page;
    }

    private static <T> ArrayList<T> decode(DurablePageStore.Page page,EntryCodec<T> codec)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(page.payload()))){int count=in.readInt();if(count!=page.entryCount())throw new IOException("Plan page entry count mismatch");
            var values=new ArrayList<T>(count);for(int i=0;i<count;i++)values.add(codec.read(in));if(in.read()!=-1)throw new IOException("Trailing plan page payload");return values;}
        catch(EOFException exception){throw new IOException("Truncated plan page payload",exception);}
    }

    private static void publishManifest(Path target,PlanManifest manifest)throws IOException{
        Path temporary=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());
        try{try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);var raw=java.nio.channels.Channels.newOutputStream(channel);var out=new DataOutputStream(new BufferedOutputStream(raw))){
                out.writeInt(MANIFEST_MAGIC);out.writeInt(MANIFEST_FORMAT);out.writeInt(manifest.planFormatVersion());out.writeLong(manifest.planId().getMostSignificantBits());out.writeLong(manifest.planId().getLeastSignificantBits());
                out.writeUTF(manifest.passId().name());out.writeUTF(manifest.areaId());out.writeUTF(manifest.dimension());out.writeUTF(manifest.profile());out.writeUTF(manifest.semanticInputDigest());out.writeUTF(manifest.engineVersion());out.writeUTF(manifest.plannerVersion());out.writeUTF(manifest.state().name());out.writeInt(manifest.pages().size());
                for(var page:manifest.pages()){out.writeLong(page.sequence());out.writeInt(page.chunkX());out.writeInt(page.chunkZ());out.writeInt(page.entryCount());out.writeUTF(page.checksum());}out.writeUTF(manifest.rootFingerprint());out.flush();channel.force(true);}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        }catch(AtomicMoveNotSupportedException exception){Files.move(temporary,target);}finally{Files.deleteIfExists(temporary);}
    }
    private static <E extends Enum<E>>E enumValue(Class<E> type,String value)throws IOException{try{return Enum.valueOf(type,value);}catch(IllegalArgumentException exception){throw new IOException("Unknown persisted enum "+value,exception);}}
    private PagedPlanStore(){}
}
