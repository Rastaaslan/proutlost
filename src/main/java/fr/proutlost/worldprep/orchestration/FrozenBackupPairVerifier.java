package fr.proutlost.worldprep.orchestration;

import com.google.gson.*;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** V5 cold-snapshot audit for a safely rebooted world.
 * Independent sealed A/B backup trees remain immutable. The SOURCE is allowed
 * to have Minecraft-only runtime changes only when a separately retained,
 * strict, complete pre/post semantic certificate cryptographically binds
 * EVERY differing live path. All unchanged paths must match the exact
 * original inventory bytes. No weakening of legacy PagedBackupVerifier.
 *
 * This verifier validates every backup byte and live SHA as a bounded stream,
 * then performs full non-symlink coverage of both independent backup trees.
 * Admission is a snapshot-continuity result, NOT a Director runtime import.
 */
public final class FrozenBackupPairVerifier implements AutoCloseable {
    private static final Gson JSON=new Gson();
    private static final int MAX_PAGE=128*1024, SLICE=64*1024;
    private static final int MAX_STEP_BYTES=1024*1024;
    private final Path world,a,b,pagesA,pagesB;
    private final PagedBackupVerifier.Header ha,hb;
    private final Map<String,String> expectedRuntime=new HashMap<>();
    private final Map<String,Long> expectedRuntimeBytes=new HashMap<>();
    private final Map<String,String> originalFrozenSha=new HashMap<>();
    private final HashSet<String> observed=new HashSet<>();
    private final Set<String> extra=new HashSet<>();
    private final String proofDigest;
    private long page,count;
    private String last="",hashA="0".repeat(64),hashB="0".repeat(64);
    private PagedBackupVerifier.Page pageA,pageB;
    private int entry,copy;
    private long offset;
    private MessageDigest digest=sha();
    private boolean verified,failed;
    private FileChannel channel;
    private Path path;
    private long expectedBytes,expectedMtime;

    private static MessageDigest sha(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String digest(byte[] data){
        return HexFormat.of().formatHex(sha().digest(data));
    }
    private static void safe(Path path)throws IOException {
        for(Path p=path;p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Symlink in V5 cold snapshot path: "+p);
    }
    private static void ordinary(Path path,long bytes,long mtime)throws IOException {
        safe(path);
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()!=bytes
            ||(mtime>=0&&attrs.lastModifiedTime().toMillis()!=mtime))
            throw new IOException("Changed frozen snapshot file: "+path);
        try{
            if(((Number)Files.getAttribute(path,"unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue()!=1)
                throw new IOException("Hardlinked frozen snapshot file: "+path);
        }catch(UnsupportedOperationException failure) {
            throw new IOException("Cannot verify hardlink count",failure);
        }
    }
    /** Exact single allowed new NVMe Contra candidate; no broader directory prefix. */
    static boolean exactScaledContraCandidate(Path path) {
        return path!=null&&path.toString().equals(
            "/home/damien/contra-ecology-run6/Contra-worldprep-modern-run6");
    }
    public FrozenBackupPairVerifier(
            Path expectedWorld,PagedBackupVerifier.Header first,
            PagedBackupVerifier.Header second,Path firstPages,Path secondPages,
            Path continuation,UUID expectedBatch)throws IOException {
        world=expectedWorld.toRealPath();
        ha=first;hb=second;
        a=Path.of(first.backupRoot()).toRealPath();
        b=Path.of(second.backupRoot()).toRealPath();
        pagesA=firstPages.toRealPath();pagesB=secondPages.toRealPath();
        for(var path:List.of(world,a,b,pagesA,pagesB,continuation)){
            safe(path);
        }
        if(a.equals(b)||a.startsWith(world)||b.startsWith(world)
            ||world.startsWith(a)||world.startsWith(b)
            ||a.startsWith(b)||b.startsWith(a))
            throw new IOException("Non-independent frozen world or backup paths");
        // Larger V5 inventory only for the pinned, isolated Contra candidate;
        // other worlds retain their original finite bounds unchanged.
        boolean isolatedContra=exactScaledContraCandidate(world);
        if(isolatedContra) {
            // The OLD prototype at /srv/storage/.../Contra-worldprep-modern is
            // a different world and must never inherit the new RUN6/RUN7 caps.
            var marker=JSON.fromJson(read(world.resolve("worldprep-candidate.json"),4096),JsonObject.class);
            if(marker==null||marker.get("schemaVersion").getAsInt()!=1
                ||!marker.get("offlineBaseline").getAsBoolean()
                ||!world.toString().equals(marker.get("candidateDirectory").getAsString())
                ||!"CONTRA_NVME_ECOLOGY_RUN6_SHA256_INDEPENDENT".equals(
                    marker.get("lineage").getAsString()))
                throw new IOException("Foreign/changed live Contra scaled backup lineage");
        }
        long maxFiles=isolatedContra?2_500_000L:100_000L;
        long maxPages=isolatedContra?20_000L:1000L;
        if(!world.toString().equals(first.sourceRoot())
            ||!world.toString().equals(second.sourceRoot())
            ||first.version()!=2||second.version()!=2
            ||first.files()!=second.files()||first.pages()!=second.pages()
            ||first.files()<1||first.files()>maxFiles
            ||first.pages()<1||first.pages()>maxPages
            ||first.files()>first.pages()*128L
            ||!first.quiescentPinPath().equals(second.quiescentPinPath())
            ||!first.quiescentPinSha256().equals(second.quiescentPinSha256()))
            throw new IOException("Different or foreign sealed backup generation");
        if(!"worldprep-qualification-pin.json".equals(first.quiescentPinPath()))
            throw new IOException("Unexpected cold source generation pin path");
        for(var root:List.of(world,a,b)) {
            var pin=root.resolve(first.quiescentPinPath());
            if(!PhysicalStore.sha(read(pin,4096)).equals(first.quiescentPinSha256()))
                throw new IOException("Changed actual source/backup generation pin");
        }
        var receipt=JSON.fromJson(read(continuation,65536),JsonObject.class);
        if(receipt==null||!"S2_V5_FROZEN_TWO_BACKUPS_AND_LIVE_SEMANTIC_EQUIVALENCE_PASS_NOT_ADMITTED"
                .equals(receipt.get("status").getAsString())
            ||!world.toString().equals(receipt.get("world").getAsString())
            ||!a.toString().equals(receipt.get("frozenRootA").getAsString())
            ||!b.toString().equals(receipt.get("frozenRootB").getAsString())
            ||!expectedBatch.toString().equals(receipt.get("expectedBatch").getAsString())
            ||receipt.get("inventoryFilesVerifiedTwice").getAsLong()!=first.files()
            ||!receipt.get("sha256A").getAsString().equals(
                 digest(read(Path.of(firstPages.toString()).getParent().resolve(
                     firstPages.getFileName().toString().replace(".pages","")),65536)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            ||!receipt.get("sha256B").getAsString().equals(
                 digest(read(Path.of(secondPages.toString()).getParent().resolve(
                     secondPages.getFileName().toString().replace(".pages","")),65536)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            throw new IOException("Wrong or stale strict semantic snapshot certificate");
        var changed=receipt.getAsJsonArray("mutableFilesStrictlyClassified");
        if(changed==null||changed.size()>128)
            throw new IOException("Unbounded or missing source continuity classification");
        for(var item:changed) {
            var entry=item.getAsJsonObject();
            String relative=entry.get("path").getAsString();
            Path normalized=Path.of(relative).normalize();
            if(normalized.isAbsolute()||normalized.startsWith("..")
                ||!normalized.toString().equals(relative)||relative.length()>2048
                ||expectedRuntime.putIfAbsent(relative,
                     entry.get("liveSha256").getAsString())!=null)
                throw new IOException("Invalid/duplicate changed source path");
            if(!entry.get("frozenSha256").getAsString().matches("[0-9a-f]{64}")
                ||!entry.get("liveSha256").getAsString().matches("[0-9a-f]{64}"))
                throw new IOException("Changed path requires exact SHA hashes");
            long liveSize=entry.get("liveBytes").getAsLong();
            if(liveSize<0||liveSize>96L*1024*1024)
                throw new IOException("Oversized recorded post-boot source");
            expectedRuntimeBytes.put(relative,liveSize);
            originalFrozenSha.put(relative,entry.get("frozenSha256").getAsString());
        }
        var unexpected=receipt.getAsJsonArray("unlistedNonWorldConfigHistory");
        if(unexpected==null||unexpected.size()>16)
            throw new IOException("Unexpected source inventory additions");
        for(var item:unexpected){
            String rel=item.getAsString();
            if(!rel.matches("serverconfig/proutlost-server-[1-9][0-9]{0,2}\\.toml\\.bak")
                ||!extra.add(rel))throw new IOException("Foreign runtime file");
            var file=world.resolve(rel);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(file)>4096)throw new IOException("Unsafe config backup history");
        }
        proofDigest=PhysicalStore.sha(read(continuation,65536));
    }
    private static String read(Path file,int max)throws IOException {
        safe(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
            ||Files.size(file)>max)throw new IOException("Unsafe bounded certificate/manifest");
        return Files.readString(file);
    }
    private void nextPage()throws IOException {
        if(page>=ha.pages())return;
        var rawA=read(pagesA.resolve("page-"+page+".json"),MAX_PAGE);
        var rawB=read(pagesB.resolve("page-"+page+".json"),MAX_PAGE);
        var nextA=JSON.fromJson(rawA,PagedBackupVerifier.Page.class);
        var nextB=JSON.fromJson(rawB,PagedBackupVerifier.Page.class);
        if(nextA==null||nextB==null||nextA.index()!=page||nextB.index()!=page
            ||nextA.version()!=2||nextB.version()!=2
            ||!nextA.previousSha256().equals(hashA)
            ||!nextB.previousSha256().equals(hashB)
            ||nextA.entries().size()<1||nextA.entries().size()>128
            ||nextA.entries().size()!=nextB.entries().size())
            throw new IOException("Corrupted V5 independent backup page link");
        pageA=nextA;pageB=nextB;
        hashA=PhysicalStore.sha(rawA);hashB=PhysicalStore.sha(rawB);
        entry=0;
    }
    private void preflightFile()throws IOException {
        var xa=pageA.entries().get(entry);
        var xb=pageB.entries().get(entry);
        String rel=xa.path();
        if(!rel.equals(xb.path())||rel.compareTo(last)<=0
            ||!xa.sha256().equals(xb.sha256())||xa.bytes()!=xb.bytes()
            ||rel.equals("session.lock")||rel.equals("worldprep-qualification.json")
            ||rel.startsWith("worldprep-v2/physical/"))
            throw new IOException("Conflicting or unbounded backup inventory entry");
        if(!observed.add(rel))throw new IOException("Duplicated original snapshot path");
        var originallyPinned=originalFrozenSha.get(rel);
        if(originallyPinned!=null&&!originallyPinned.equals(xa.sha256()))
            throw new IOException("Source proof not bound to original frozen inventory: "+rel);
        for(var pair:List.of(a.resolve(rel),b.resolve(rel)))safe(pair);
    }
    private void startFile()throws IOException {
        var item=copy==0?pageA.entries().get(entry):pageB.entries().get(entry);
        Path root=copy==0?a:copy==1?b:world;
        path=root.resolve(item.path()).normalize();
        if(!path.startsWith(root))throw new IOException("Escaped sealed snapshot");
        expectedBytes=copy==2?expectedRuntimeBytes.getOrDefault(item.path(),item.bytes())
            :item.bytes();
        expectedMtime=copy==0?item.backupModifiedMillis()
            :copy==1?pageB.entries().get(entry).backupModifiedMillis():-1;
        ordinary(path,expectedBytes,expectedMtime);
        channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        offset=0;
        digest=sha();
    }
    private int consume(int budget)throws IOException {
        if(channel==null)startFile();
        if(budget<=0)throw new IllegalArgumentException("Non-positive slice budget");
        int slice=(int)Math.min(Math.min((long)budget,(long)SLICE),expectedBytes-offset);
        if(slice>0) {
            var buffer=ByteBuffer.allocate(slice);
            while(buffer.hasRemaining()) {
                if(channel.read(buffer,offset+buffer.position())<=0)
                    throw new IOException("Truncated snapshot file midstream");
            }
            digest.update(buffer.array());
            offset+=slice;
        }
        if(offset==expectedBytes) {
            ordinary(path,expectedBytes,expectedMtime);
            var recorded=pageA.entries().get(entry);
            String hash=HexFormat.of().formatHex(digest.digest());
            String expected=copy==2?expectedRuntime.getOrDefault(recorded.path(),recorded.sha256())
                :recorded.sha256();
            if(!hash.equals(expected))
                throw new IOException("Frozen/live source content changed: "+recorded.path());
            channel.close();channel=null;
            if(copy==2) {
                last=recorded.path();count++;entry++;copy=0;
                if(entry==pageA.entries().size()) {
                    pageA=null;pageB=null;page++;
                }
            }else copy++;
        }
        return slice;
    }
    public long verifiedFiles(){return count;}
    public String semanticProofSha256(){return proofDigest;}
    public boolean stepBounded()throws IOException {
        if(failed)throw new IOException("Frozen V5 verification failed closed");
        if(verified)return true;
        try {
            int remaining=MAX_STEP_BYTES,entries=0;
            while(remaining>0&&entries<64) {
                if(pageA==null) {
                    if(page==ha.pages()) {
                        if(count!=ha.files()
                            ||!ha.terminalPageSha256().equals(hashA)
                            ||!hb.terminalPageSha256().equals(hashB)
                            ||!expectedRuntime.keySet().stream().allMatch(observed::contains))
                            throw new IOException("Incomplete frozen backup pair");
                        // Verify exact independent tree membership, not just all known entries.
                        requireCoverage(a);requireCoverage(b);requireLiveCoverage();
                        verified=true;return true;
                    }
                    nextPage();
                }
                if(copy==0 && channel==null)preflightFile();
                remaining-=consume(remaining);
                entries++;
            }
            return false;
        }catch(IOException|RuntimeException failure){
            failed=true;close();
            if(failure instanceof IOException io)throw io;
            throw new IOException("Frozen backup pair refused",failure);
        }
    }
    private void requireCoverage(Path root)throws IOException {
        long counted=0;
        try(var stream=Files.walk(root,33)) {
            var paths=stream.iterator();
            while(paths.hasNext()){
                var file=paths.next();
                if(Files.isSymbolicLink(file))throw new IOException("Symlink in backup coverage");
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                String name=root.relativize(file).toString();
                if(!observed.contains(name))throw new IOException("Uninventoried backup file: "+name);
                if(++counted>ha.files())throw new IOException("Unbounded frozen backup tree");
            }
        }
        if(counted!=ha.files())throw new IOException("Incomplete frozen backup full coverage");
    }
    private void requireLiveCoverage()throws IOException {
        long checked=0;
        var realExtras=new HashSet<String>();
        try(var stream=Files.walk(world,33)){
            var iterator=stream.iterator();
            while(iterator.hasNext()){
                Path path=iterator.next();
                if(Files.isSymbolicLink(path))
                    throw new IOException("Symlink in live V5 source continuity");
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                String rel=world.relativize(path).toString();
                if(rel.equals("session.lock")||rel.equals("worldprep-qualification.json")
                    ||rel.startsWith("worldprep-v2/physical/"))continue;
                if(extra.contains(rel)){realExtras.add(rel);continue;}
                if(!observed.contains(rel))
                    throw new IOException("Uninventoried live file after cold snapshot: "+rel);
                if(++checked>ha.files())
                    throw new IOException("Unbounded live source coverage");
            }
        }
        if(checked!=ha.files()||!extra.equals(realExtras))
            throw new IOException("Incomplete live source continuity coverage");
    }
    @Override public void close()throws IOException {
        if(channel!=null){channel.close();channel=null;}
    }
}
