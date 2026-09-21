package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Append-only durable ownership journal.  The manifest is the atomic commit
 * record for a complete prefix of pages; pages outside that prefix confer no
 * ownership and are never replayed.
 */
public final class PagedJournalStore {
    private static final int MAGIC = 0x574a4d46;
    public static final int FORMAT = 1;

    public record PageReference(long sequence, int entryCount, String checksum) {
        public PageReference { if(sequence<0||entryCount<1)throw new IllegalArgumentException("Invalid journal page reference");new PlanFingerprint(checksum); }
    }
    public record Manifest(int format,UUID journalId,UUID planId,String planRoot,PassId pass,String dimension,
            String area,List<PageReference> pages,long entryCount,String root) {
        public Manifest { pages=List.copyOf(pages);Objects.requireNonNull(journalId);Objects.requireNonNull(planId);Objects.requireNonNull(pass);if(format!=FORMAT||dimension.isBlank()||area.isBlank())throw new IllegalArgumentException("Invalid journal manifest");new PlanFingerprint(planRoot);new PlanFingerprint(root);long count=0,sequence=0;for(var page:pages){if(page.sequence()!=sequence++)throw new IllegalArgumentException("Journal page sequence gap");count+=page.entryCount();}if(count!=entryCount||!computeRoot(format,journalId,planId,planRoot,pass,dimension,area,pages,entryCount).equals(root))throw new IllegalArgumentException("Journal manifest root mismatch"); }
    }
    @FunctionalInterface public interface PageConsumer<T>{void accept(long sequence,List<T> entries)throws IOException;}
    public record Metrics(long pages,long entries,int maximumResidentPages,int maximumResidentEntries){}

    public static Manifest empty(UUID journalId,UUID planId,String planRoot,PassId pass,String dimension,String area){return create(journalId,planId,planRoot,pass,dimension,area,List.of());}
    public static Manifest initialize(Path directory,Manifest expected)throws IOException{Files.createDirectories(directory);Path target=manifestPath(directory);if(Files.exists(target)){var existing=readManifest(directory);requireIdentity(existing,expected);return existing;}publishManifest(target,expected);return readManifest(directory);}

    /** Publish an exact page, reread it, then atomically advance the manifest. */
    public static <T> Manifest append(Path directory,Manifest expected,List<T> entries,PagedPlanStore.EntryCodec<T> codec)throws IOException{
        if(entries.isEmpty())throw new IllegalArgumentException("Empty journal page");Files.createDirectories(directory);
        Manifest current=Files.exists(manifestPath(directory))?readManifest(directory):empty(expected.journalId(),expected.planId(),expected.planRoot(),expected.pass(),expected.dimension(),expected.area());
        requireIdentity(current,expected);
        long sequence=current.pages().size();Path target=pagePath(directory,sequence);byte[] payload=encode(entries,codec);var page=DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL,current.journalId(),current.pass(),current.dimension(),(int)(sequence>>32),(int)sequence,sequence,entries.size(),payload);
        if(Files.exists(target)){var durable=DurablePageStore.read(target);requirePage(current,new PageReference(sequence,entries.size(),page.checksum()),durable);if(!Arrays.equals(durable.payload(),payload))throw new IOException("Conflicting duplicate journal page");}
        else DurablePageStore.publish(target,page);
        var durable=DurablePageStore.read(target);var reference=new PageReference(sequence,durable.entryCount(),durable.checksum());requirePage(current,reference,durable);
        var refs=new ArrayList<>(current.pages());refs.add(reference);var advanced=create(current.journalId(),current.planId(),current.planRoot(),current.pass(),current.dimension(),current.area(),refs);publishManifest(manifestPath(directory),advanced);return readManifest(directory);
    }

    public static Manifest readManifest(Path directory)throws IOException{try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(manifestPath(directory))))){if(in.readInt()!=MAGIC||in.readInt()!=FORMAT)throw new IOException("Invalid journal manifest format");UUID journal=new UUID(in.readLong(),in.readLong()),plan=new UUID(in.readLong(),in.readLong());String planRoot=in.readUTF();PassId pass=enumValue(in.readUTF());String dimension=in.readUTF(),area=in.readUTF();int count=in.readInt();if(count<0||count>10_000_000)throw new IOException("Invalid journal page count");var pages=new ArrayList<PageReference>(count);for(int i=0;i<count;i++)pages.add(new PageReference(in.readLong(),in.readInt(),in.readUTF()));long entries=in.readLong();String root=in.readUTF();if(in.read()!=-1)throw new IOException("Trailing journal manifest data");try{return new Manifest(FORMAT,journal,plan,planRoot,pass,dimension,area,pages,entries,root);}catch(IllegalArgumentException e){throw new IOException("Invalid journal manifest",e);}}catch(EOFException e){throw new IOException("Truncated journal manifest",e);}}

    public static void validateAll(Path directory,Manifest expected)throws IOException{var durable=readManifest(directory);if(!durable.equals(expected))throw new IOException("Exact journal manifest mismatch");for(var ref:durable.pages())requirePage(durable,ref,DurablePageStore.read(pagePath(directory,ref.sequence())));}

    public static <T> Metrics visitPages(Path directory,Manifest expected,PagedPlanStore.EntryCodec<T> codec,PageConsumer<T> consumer)throws IOException{var durable=readManifest(directory);if(!durable.equals(expected))throw new IOException("Exact journal manifest mismatch");long pages=0,entries=0;int maximum=0;for(var ref:durable.pages()){var page=DurablePageStore.read(pagePath(directory,ref.sequence()));requirePage(durable,ref,page);var decoded=decode(page,codec);pages++;entries+=decoded.size();maximum=Math.max(maximum,decoded.size());consumer.accept(ref.sequence(),decoded);decoded.clear();}return new Metrics(pages,entries,pages==0?0:1,maximum);}

    public static Path manifestPath(Path directory){return directory.resolve("manifest.wjm");}
    public static Path pagePath(Path directory,long sequence){return directory.resolve(String.format("%020d.wpp",sequence));}
    private static Manifest create(UUID journal,UUID plan,String planRoot,PassId pass,String dimension,String area,List<PageReference> pages){long entries=pages.stream().mapToLong(PageReference::entryCount).sum();return new Manifest(FORMAT,journal,plan,planRoot,pass,dimension,area,pages,entries,computeRoot(FORMAT,journal,plan,planRoot,pass,dimension,area,pages,entries));}
    private static String computeRoot(int format,UUID journal,UUID plan,String planRoot,PassId pass,String dimension,String area,List<PageReference> pages,long entries){var d=CanonicalDigest.sha256().putString("worldprep-journal-manifest").putInt(format).putUuid(journal).putUuid(plan).putString(planRoot).putString(pass.name()).putString(dimension).putString(area).putLong(entries).putInt(pages.size());for(var p:pages)d.putLong(p.sequence()).putInt(p.entryCount()).putString(p.checksum());return d.finish();}
    private static void requireIdentity(Manifest a,Manifest b)throws IOException{if(!a.journalId().equals(b.journalId())||!a.planId().equals(b.planId())||!a.planRoot().equals(b.planRoot())||a.pass()!=b.pass()||!a.dimension().equals(b.dimension())||!a.area().equals(b.area()))throw new IOException("Journal ownership identity mismatch");}
    private static void requirePage(Manifest m,PageReference r,DurablePageStore.Page p)throws IOException{if(p.storageKind()!=DurablePageStore.StorageKind.JOURNAL||!p.ownerId().equals(m.journalId())||p.passId()!=m.pass()||!p.dimension().equals(m.dimension())||p.sequence()!=r.sequence()||p.entryCount()!=r.entryCount()||!p.checksum().equals(r.checksum()))throw new IOException("Journal page identity mismatch at "+r.sequence());}
    private static <T>byte[] encode(List<T> entries,PagedPlanStore.EntryCodec<T> codec)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(entries.size());for(var e:entries)codec.write(out,e);}return bytes.toByteArray();}
    private static <T>ArrayList<T> decode(DurablePageStore.Page page,PagedPlanStore.EntryCodec<T> codec)throws IOException{try(var in=new DataInputStream(new ByteArrayInputStream(page.payload()))){int count=in.readInt();if(count!=page.entryCount())throw new IOException("Journal entry count mismatch");var result=new ArrayList<T>(count);for(int i=0;i<count;i++)result.add(codec.read(in));if(in.read()!=-1)throw new IOException("Trailing journal page payload");return result;}catch(EOFException e){throw new IOException("Truncated journal page",e);}}
    private static void publishManifest(Path target,Manifest m)throws IOException{Path tmp=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());try{try(var channel=FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);var raw=java.nio.channels.Channels.newOutputStream(channel);var out=new DataOutputStream(new BufferedOutputStream(raw))){out.writeInt(MAGIC);out.writeInt(FORMAT);out.writeLong(m.journalId().getMostSignificantBits());out.writeLong(m.journalId().getLeastSignificantBits());out.writeLong(m.planId().getMostSignificantBits());out.writeLong(m.planId().getLeastSignificantBits());out.writeUTF(m.planRoot());out.writeUTF(m.pass().name());out.writeUTF(m.dimension());out.writeUTF(m.area());out.writeInt(m.pages().size());for(var p:m.pages()){out.writeLong(p.sequence());out.writeInt(p.entryCount());out.writeUTF(p.checksum());}out.writeLong(m.entryCount());out.writeUTF(m.root());out.flush();channel.force(true);}try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}}
    private static PassId enumValue(String value)throws IOException{try{return PassId.valueOf(value);}catch(IllegalArgumentException e){throw new IOException("Unknown persisted journal pass "+value,e);}}
    private PagedJournalStore(){}
}
