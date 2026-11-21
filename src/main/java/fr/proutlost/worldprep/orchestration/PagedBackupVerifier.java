package fr.proutlost.worldprep.orchestration;

import com.google.gson.Gson;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** V2: 128 entries/page, one page load or <=1MiB payload or 64 inventory entries per step.
 * Completed page cursors are durable; a resumed run always repeats complete metadata coverage.
 * Operator must keep both copies quiescent and retain the same generation pin during the audit.
 */
public final class PagedBackupVerifier implements AutoCloseable {
    private static final Gson JSON=new Gson();
    private static final BigInteger MOD=BigInteger.ONE.shiftLeft(256);
    public static final String ZERO="0".repeat(64);
    public record Header(int version,String sourceRoot,String backupRoot,String quiescentPinPath,
                         String quiescentPinSha256,long pages,long files,String terminalPageSha256) {
        public Header {
            if(version!=2||pages<1||files<1||pages>files||!hash(quiescentPinSha256)||!hash(terminalPageSha256))throw new IllegalArgumentException("Invalid paged backup header");
            relative(quiescentPinPath);
        }
    }
    public record Entry(String path,long bytes,String sha256,long sourceModifiedMillis,long backupModifiedMillis) {
        public Entry {relative(path);if(bytes<0||!hash(sha256)||sourceModifiedMillis<0||backupModifiedMillis<0)throw new IllegalArgumentException("Invalid backup entry");}
    }
    public record Page(int version,long index,String previousSha256,List<Entry> entries) {
        public Page {entries=List.copyOf(entries);if(version!=2||index<0||!hash(previousSha256)||entries.isEmpty()||entries.size()>128)throw new IllegalArgumentException("Bounded page required");}
    }
    public record Cursor(String headerSha256,long page,String previousSha256,String lastPath,long files,String sourceSum,String backupSum) {}
    private record Checkpoint(String sha256,String payload) {}
    private final Header header;
    private final Path source,backup,pages,checkpoint;
    private final Set<Path> exclusions;
    private final String headerHash;
    private Cursor cursor;
    private Page page;
    private boolean failed;
    private static final int DEFAULT_IO_SLICE=1024*1024-16384;
    private int maxReadBytes=DEFAULT_IO_SLICE;
    private int bytesReadLastStep;
    private String pageHash;
    private int entry,copy;
    private long offset;
    private MessageDigest digest=sha256();
    private BigInteger sourceSum,backupSum;
    private long files;
    private long replayPage,replayFiles;
    private String replayHash=ZERO,replayPath="";
    private BigInteger replaySource=BigInteger.ZERO,replayBackup=BigInteger.ZERO;
    private final long resumePage;
    private String lastPath;
    private java.util.stream.Stream<Path> walk;
    private Iterator<Path> iterator;
    private int coverageCopy;
    private long covered;
    private BigInteger coverageSum=BigInteger.ZERO;
    public PagedBackupVerifier(Header header,Path expectedSource,Path pages,Path checkpoint,Set<Path> exclusions)throws IOException {
        this.header=header;this.pages=pages.toAbsolutePath().normalize();this.checkpoint=checkpoint.toAbsolutePath().normalize();
        source=Path.of(header.sourceRoot()).toRealPath();backup=Path.of(header.backupRoot()).toRealPath();
        noLinks(Path.of(header.sourceRoot()).toAbsolutePath());noLinks(Path.of(header.backupRoot()).toAbsolutePath());noLinks(this.pages);noLinks(this.checkpoint);
        if(!source.equals(expectedSource.toRealPath())||source.startsWith(backup)||backup.startsWith(source))throw new IOException("Independent source-bound backup required");
        this.exclusions=Set.copyOf(exclusions);for(var path:exclusions)relative(path.toString());
        if(excluded(Path.of(header.quiescentPinPath())))throw new IOException("Quiescent pin cannot be excluded");
        headerHash=PhysicalStore.sha(JSON.toJson(header));
        cursor=new Cursor(headerHash,0,ZERO,"",0,"0","0");
        if(Files.exists(this.checkpoint)) {
            var retained=JSON.fromJson(read(this.checkpoint,4096),Checkpoint.class);
            if(!PhysicalStore.sha(retained.payload()).equals(retained.sha256()))throw new IOException("Partial/corrupt backup cursor");
            cursor=JSON.fromJson(retained.payload(),Cursor.class);
            if(!headerHash.equals(cursor.headerSha256())||cursor.page()<0||cursor.page()>header.pages()||cursor.files()<0||cursor.files()>header.files()
                ||!hash(cursor.previousSha256()))throw new IOException("Backup cursor identity mismatch");
        }
        resumePage=cursor.page();
        sourceSum=new BigInteger(cursor.sourceSum(),16);backupSum=new BigInteger(cursor.backupSum(),16);files=cursor.files();lastPath=cursor.lastPath();
        pins();
    }
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void relative(String text) {
        var path=Path.of(text);
        if(text.isEmpty()||text.length()>2048||path.isAbsolute()||!path.normalize().equals(path)||path.startsWith("..")||path.getNameCount()>32)throw new IllegalArgumentException("Canonical relative path required");
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void noLinks(Path path)throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink refused: "+p);}
    private static String read(Path path,int limit)throws IOException {
        noLinks(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new IOException("Missing/oversized inventory metadata");
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Growing metadata");return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }
    private void pins()throws IOException {
        for(var root:List.of(source,backup))if(!PhysicalStore.sha(read(root.resolve(header.quiescentPinPath()),4096)).equals(header.quiescentPinSha256()))throw new IOException("Quiescent generation changed");
    }
    public long completedPages(){return cursor.page();}
    /** Bounded file-hash slice (64 steps / 1008KiB), plus separately bounded
     * complete-inventory coverage (8 x 64 metadata entries); no receipt is
     * considered qualified before BOTH trees have finished their coverage.
     */
    public boolean stepBounded()throws IOException {
        if(replayPage<resumePage)return replayBounded();
        if(cursor.page()==header.pages())return coverageBounded();
        if(failed)throw new IOException("Backup verifier failed; reopen from durable cursor");
        int remaining=DEFAULT_IO_SLICE;
        try {
            // A single world-owner tick is serialized: read both pins at entry and exit,
            // not 128 times for 64 individual file steps. Content/mtime checks remain
            // per file; every page checkpoint remains durable and immutable.
            pins();
            boolean completed=false;
            for(int n=0;n<64;n++) {
                if(replayPage<resumePage||cursor.page()==header.pages())break;
                maxReadBytes=remaining;
                bytesReadLastStep=0;
                if(advance(false)){completed=true;break;}
                remaining-=bytesReadLastStep;
                if(remaining<=0)break;
            }
            pins();
            return completed;
        } catch(IOException|RuntimeException failure) {
            failed=true;close();
            if(failure instanceof IOException io)throw io;
            throw new IOException("Invalid bounded backup state",failure);
        } finally {maxReadBytes=DEFAULT_IO_SLICE;bytesReadLastStep=0;}
    }
    /** Replay at most 32 retained manifest pages per tick while preserving
     * exact SHA links, source/backup additive checksums and quiescence pins.
     * Replaying is read-only and never skips an unverified durable receipt.
     */
    private boolean replayBounded()throws IOException {
        if(failed)throw new IOException("Failed verifier needs explicit durable reopening");
        try {
            pins();
            for(int n=0;n<32 && replayPage<resumePage;n++)
                advance(false);
            pins();
            return false; // Replay completion is never a full-copy PASS.
        }catch(IOException|RuntimeException failure) {
            failed=true;close();
            if(failure instanceof IOException io)throw io;
            throw new IOException("Corrupt retained backup replay",failure);
        }
    }
    private boolean coverageBounded()throws IOException {
        if(failed)throw new IOException("Backup verifier failed; reopen from durable cursor");
        try {
            pins();
            boolean complete=false;
            for(int n=0;n<8;n++) {
                if(advance(false)){complete=true;break;}
            }
            pins();
            return complete;
        } catch(IOException|RuntimeException failure) {
            failed=true;close();
            if(failure instanceof IOException io)throw io;
            throw new IOException("Invalid bounded backup coverage",failure);
        }
    }
    public boolean step()throws IOException {
        bytesReadLastStep=0;
        if(failed)throw new IOException("Backup verifier failed; reopen from durable cursor");
        try{return advance(true);}catch(IOException|RuntimeException failure){failed=true;close();if(failure instanceof IOException io)throw io;throw new IOException("Invalid backup state",failure);}
    }
    private boolean advance(boolean verifyPinNow)throws IOException {
        if(verifyPinNow)pins();
        if(replayPage<resumePage) {
            String text=read(pages.resolve("page-"+replayPage+".json"),128*1024);
            Page retained;
            try{retained=JSON.fromJson(text,Page.class);}catch(RuntimeException e){throw new IOException("Invalid retained page",e);}
            if(retained.index()!=replayPage||!retained.previousSha256().equals(replayHash))throw new IOException("Retained page chain changed");
            for(var item:retained.entries()) {
                if(item.path().compareTo(replayPath)<=0||excluded(Path.of(item.path())))throw new IOException("Retained page order/exclusion mismatch");
                replayPath=item.path();replayFiles=Math.addExact(replayFiles,1);
                replaySource=add(replaySource,item.path(),item.bytes(),item.sourceModifiedMillis());
                replayBackup=add(replayBackup,item.path(),item.bytes(),item.backupModifiedMillis());
            }
            replayHash=PhysicalStore.sha(text);replayPage++;
            if(replayPage==resumePage&&(!replayHash.equals(cursor.previousSha256())||replayFiles!=files||!replayPath.equals(lastPath)
                ||!replaySource.equals(sourceSum)||!replayBackup.equals(backupSum)))throw new IOException("Retained cursor does not match inventory chain");
            return false;
        }
        if(cursor.page()==header.pages()) {
            if(files!=header.files()||!cursor.previousSha256().equals(header.terminalPageSha256()))throw new IOException("Incomplete inventory chain");
            return coverage();
        }
        if(page==null) {
            String text=read(pages.resolve("page-"+cursor.page()+".json"),128*1024);
            try{page=JSON.fromJson(text,Page.class);}catch(RuntimeException e){throw new IOException("Partial/invalid inventory page",e);}
            if(page.index()!=cursor.page()||!page.previousSha256().equals(cursor.previousSha256()))throw new IOException("Page chain mismatch");
            String prior=lastPath;
            for(var item:page.entries()) {
                if(item.path().compareTo(prior)<=0||excluded(Path.of(item.path())))throw new IOException("Duplicate/unordered/excluded inventory entry");prior=item.path();
            }
            pageHash=PhysicalStore.sha(text);entry=0;copy=0;offset=0;digest=sha256();return false;
        }
        var item=page.entries().get(entry);Path a=source.resolve(item.path()),b=backup.resolve(item.path());
        noLinks(a);noLinks(b);if(Files.isSameFile(a,b))throw new IOException("Aliased backup file");
        Path file=copy==0?a:b;long mtime=copy==0?item.sourceModifiedMillis():item.backupModifiedMillis();
        check(file,item.bytes(),mtime);
        var buffer=ByteBuffer.allocate((int)Math.min(maxReadBytes,item.bytes()-offset));
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            while(buffer.hasRemaining())if(channel.read(buffer,offset+buffer.position())<=0)throw new IOException("Truncated backup file");
        }
        check(file,item.bytes(),mtime);digest.update(buffer.array());offset+=buffer.capacity();
        bytesReadLastStep=buffer.capacity();
        if(offset!=item.bytes())return false;
        if(!HexFormat.of().formatHex(digest.digest()).equals(item.sha256()))throw new IOException("Backup content mismatch: "+item.path());
        offset=0;if(copy==0){copy=1;return false;}copy=0;
        sourceSum=add(sourceSum,item.path(),item.bytes(),item.sourceModifiedMillis());backupSum=add(backupSum,item.path(),item.bytes(),item.backupModifiedMillis());
        files=Math.addExact(files,1);lastPath=item.path();entry++;
        if(entry==page.entries().size()) {
            var previous=cursor;
            cursor=new Cursor(headerHash,cursor.page()+1,pageHash,lastPath,files,sourceSum.toString(16),backupSum.toString(16));
            try{saveCursor();}catch(IOException failure){cursor=previous;throw failure;}page=null;
        }
        return false;
    }
    private static void check(Path path,long bytes,long mtime)throws IOException {
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        noHardLinks(path);
        if(!attrs.isRegularFile()||attrs.size()!=bytes||attrs.lastModifiedTime().toMillis()!=mtime)throw new IOException("Quiescent file identity changed: "+path);
    }
    private static void noHardLinks(Path path)throws IOException {
        // Qualification target is Linux. Unknown link-count semantics fail closed.
        try{if(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("Hard-linked inventory file");}
        catch(UnsupportedOperationException unsupported){throw new IOException("Link-count verification unavailable",unsupported);}
    }
    private static BigInteger add(BigInteger sum,String path,long bytes,long time) {
        return sum.add(new BigInteger(PhysicalStore.sha(path+"\0"+bytes+"\0"+time),16)).mod(MOD);
    }
    private boolean excluded(Path path){return path.toString().equals("session.lock")||exclusions.stream().anyMatch(path::startsWith);}
    private boolean coverage()throws IOException {
        if(coverageCopy==2)return true;
        Path root=coverageCopy==0?source:backup;
        if(walk==null){walk=Files.walk(root,33);iterator=walk.iterator();}
        try {
            for(int n=0;n<64&&iterator.hasNext();n++) {
                Path path=iterator.next(),rel=root.relativize(path);
                if(Files.isSymbolicLink(path))throw new IOException("Symlink in coverage");
                if(excluded(rel))continue;
                var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attrs.isDirectory()){if(rel.getNameCount()>=33)throw new IOException("Inventory depth exceeded");continue;}
                if(!attrs.isRegularFile())throw new IOException("Special file in coverage");
                noHardLinks(path);covered=Math.addExact(covered,1);coverageSum=add(coverageSum,rel.toString(),attrs.size(),attrs.lastModifiedTime().toMillis());
            }
            if(iterator.hasNext())return false;
            close();
            if(covered!=header.files()||!coverageSum.equals(coverageCopy==0?sourceSum:backupSum))throw new IOException("Omitted/added/modified file in complete coverage");
            coverageCopy++;covered=0;coverageSum=BigInteger.ZERO;return coverageCopy==2;
        } catch(IOException|RuntimeException e){close();throw e;}
    }
    private void saveCursor()throws IOException {
        Files.createDirectories(checkpoint.getParent());String payload=JSON.toJson(cursor);
        byte[] bytes=JSON.toJson(new Checkpoint(PhysicalStore.sha(payload),payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        new PhysicalStore(checkpoint.getParent().resolve("backup-page-receipts")).write("page-"+cursor.page(),cursor);
        Path temp=Files.createTempFile(checkpoint.getParent(),".backup-cursor-",".partial");
        try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);}
        Files.move(temp,checkpoint,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var dir=FileChannel.open(checkpoint.getParent(),StandardOpenOption.READ)){dir.force(true);}
    }
    @Override public void close(){if(walk!=null)walk.close();walk=null;iterator=null;}
}
