package fr.proutlost.worldprep.orchestration;

import com.google.gson.Gson;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/**
 * Isolated V5 DEV compatibility gate for cold, multi-segment A/B inventories.
 * Never substitutes for native V5 nine-phase qualification. Legacy V2 stays unchanged.
 * Each independently SHA-chained segment obeys the existing 2.5M-file/20k-page caps.
 * This is a COLD standalone preflight (not a server-tick verifier).
 */
public final class ContraV5SegmentedBackupAudit {
    private static final Gson JSON = new Gson();
    private static final String ZERO = "0".repeat(64);
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);
    public static final int MAX_SEGMENTS=16,MAX_FILES_PER_SEGMENT=2_500_000,MAX_PAGES_PER_SEGMENT=20_000;
    private static final int MAX_PAGE_BYTES=128*1024;
    private static final int MAX_DEPTH=33;
    public record Segment(int ordinal,long files,long pages,String firstPath,String lastPath,
                          String headerShaA,String headerShaB,String lastPageShaA,String lastPageShaB) {}
    public record Manifest(int version,String world,String backupA,String backupB,String qualification,
                           String pinPath,String pinSha256,long totalFiles,long totalPages,
                           String inventorySha256,List<Segment> segments) {}
    public record Verified(long files,long pages,int segments,String inventorySha256,
                           String headerManifestSha256,String status) {}
    private record Checksum(long count,BigInteger sum) {}
    private static MessageDigest digest(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static boolean sha(String text){return text!=null&&text.matches("[0-9a-f]{64}");}
    private static void safe(Path path)throws IOException{
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink denied: "+p);
    }
    private static Path real(Path path)throws IOException{safe(path);return path.toRealPath(LinkOption.NOFOLLOW_LINKS);}
    private static void rel(String path)throws IOException{
        if(path==null||path.isEmpty()||path.length()>2048)throw new IOException("Missing/large relative path");
        Path v=Path.of(path);
        if(v.isAbsolute()||v.startsWith("..")||!v.normalize().equals(v)
            ||v.getNameCount()>32||path.indexOf('\\')>=0)throw new IOException("Noncanonical inventory path");
    }
    private static String read(Path f,int max)throws IOException{
        safe(f);
        if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||Files.size(f)>max)
            throw new IOException("Missing/oversized sealed metadata: "+f);
        return QualificationBinding.read(f,max);
    }
    private static BasicFileAttributes ordinary(Path path,long bytes,long millis)throws IOException{
        safe(path);
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()!=bytes||(millis>=0&&a.lastModifiedTime().toMillis()!=millis))
            throw new IOException("Frozen file size/mtime/type mismatch: "+path);
        try {
            if(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)
                throw new IOException("Hardlink refused: "+path);
        }catch(UnsupportedOperationException ex){throw new IOException("Cannot verify link count",ex);}
        return a;
    }
    private static String shaFile(Path path,long bytes,long millis)throws IOException{
        ordinary(path,bytes,millis);
        MessageDigest md=digest();
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] buf=new byte[65536];long left=bytes;
            while(left>0){
                int n=in.read(buf,0,(int)Math.min(left,buf.length));
                if(n<1)throw new IOException("Truncated backup file "+path);
                md.update(buf,0,n);left-=n;
            }
            if(in.read()!=-1)throw new IOException("Growing backup file "+path);
        }
        ordinary(path,bytes,millis);
        return HexFormat.of().formatHex(md.digest());
    }
    private static BigInteger addition(BigInteger existing,String rel,long bytes,long millis){
        String field=rel+"\u0000"+bytes+"\u0000"+millis;
        return existing.add(new BigInteger(1,digest().digest(field.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).mod(MOD);
    }
    private static Checksum coverage(Path root,boolean source)throws IOException{
        BigInteger sum=BigInteger.ZERO;long count=0;
        try(var walk=Files.walk(root,MAX_DEPTH)){
            var itr=walk.iterator();
            while(itr.hasNext()){
                Path path=itr.next();
                safe(path);
                Path rel=root.relativize(path);
                if(rel.toString().isEmpty())continue;
                String name=rel.toString().replace('\\','/');
                if(source&&(name.equals("session.lock")||name.equals("worldprep-qualification.json")
                    ||name.startsWith("worldprep-v2/physical/")))continue;
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!a.isRegularFile())throw new IOException("Unexpected special entry in coverage "+path);
                ordinary(path,a.size(),a.lastModifiedTime().toMillis());
                rel(name);
                count=Math.addExact(count,1);
                if(count>(long)MAX_SEGMENTS*MAX_FILES_PER_SEGMENT)throw new IOException("Global coverage cap");
                sum=addition(sum,name,a.size(),a.lastModifiedTime().toMillis());
            }
        }
        return new Checksum(count,sum);
    }
    /**
     * Exact V5 qualification identity is mandatory at the integration boundary.
     * The legacy DEV verify() remains an offline-only content audit: neither
     * overload alone certifies native V5 or admits a running Minecraft server.
     */
    public static Verified verifyForQualification(Path manifestFile,Path source,
                                                  Path a,Path b,UUID expectedQualification)throws IOException{
        if(expectedQualification==null)throw new IOException("Expected V5 qualification UUID required");
        return verifyInternal(manifestFile,source,a,b,expectedQualification);
    }
    /** Caller already owns the exact source session.lock while sealing the native
     * V5 WAL. Never fall back to a stale standalone report or an unlocked window.
     * Package-private: only the native binder within this package may use it.
     */
    static Verified verifyWithHeldColdLock(Path manifestFile,Path source,
                                           Path a,Path b,UUID qualification,
                                           FileLock held)throws IOException {
        if(qualification==null||held==null||!held.isValid()||held.isShared())
            throw new IOException("Exact exclusive cold Minecraft lock required");
        Path world=real(source);
        if(!Files.isRegularFile(world.resolve("session.lock"),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Actual native session.lock missing");
        return verifyLocked(manifestFile,world,a,b,qualification);
    }
    public static Verified verify(Path manifestFile,Path source,Path a,Path b)throws IOException{
        return verifyInternal(manifestFile,source,a,b,null);
    }
    private static Verified verifyInternal(Path manifestFile,Path source,Path a,
                                           Path b,UUID expectedQualification)throws IOException{
        Path world=real(source),lockFile=world.resolve("session.lock");
        safe(lockFile);
        if(!Files.isRegularFile(lockFile,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Minecraft cold source session lock is mandatory");
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.WRITE)) {
            try(FileLock lock=channel.tryLock()) {
                if(lock==null)throw new IOException("Live world locked by Minecraft");
                return verifyLocked(manifestFile,world,a,b,expectedQualification);
            }
        }catch(OverlappingFileLockException e){throw new IOException("Active Minecraft session",e);}
    }
    private static Verified verifyLocked(Path manifestFile,Path source,Path a,
                                         Path b,UUID expectedQualification)throws IOException{
        Path world=real(source),left=real(a),right=real(b),manifestPath=real(manifestFile);
        if(left.equals(right)||world.equals(left)||world.equals(right)||left.startsWith(right)
           ||right.startsWith(left)||world.startsWith(left)||world.startsWith(right)
           ||left.startsWith(world)||right.startsWith(world))
            throw new IOException("Independent source and two physical copies mandatory");
        String raw=read(manifestPath,65536);
        Manifest manifest=QualificationBinding.decode(raw,Manifest.class);
        if(manifest==null||manifest.version()!=3||!world.toString().equals(manifest.world())
            ||!left.toString().equals(manifest.backupA())||!right.toString().equals(manifest.backupB())
            ||!sha(manifest.pinSha256())||!sha(manifest.inventorySha256())
            ||manifest.totalFiles()<1||manifest.totalPages()<1
            ||manifest.segments()==null||manifest.segments().isEmpty()
            ||manifest.segments().size()>MAX_SEGMENTS)
            throw new IOException("Foreign, invalid or unbounded V5 segmented envelope");
        try{
            if(!UUID.fromString(manifest.qualification()).toString().equals(manifest.qualification()))
                throw new IllegalArgumentException("noncanonical UUID");
        }catch(RuntimeException ex){throw new IOException("Invalid qualification UUID",ex);}
        if(expectedQualification!=null&&!expectedQualification.toString().equals(manifest.qualification()))
            throw new IOException("Foreign V5 qualification UUID in frozen segment envelope");
        rel(manifest.pinPath());
        if(!manifest.pinPath().equals("worldprep-qualification-pin.json"))
            throw new IOException("Unexpected snapshot pin");
        for(Path p:List.of(world.resolve(manifest.pinPath()),left.resolve(manifest.pinPath()),right.resolve(manifest.pinPath()))) {
            if(!shaFile(p,Files.size(p),-1).equals(manifest.pinSha256()))
                throw new IOException("Changed frozen generation pin "+p);
        }
        long files=0,pages=0;
        String last="",allSha;
        MessageDigest inventory=digest();
        BigInteger sourceSum=BigInteger.ZERO,aSum=BigInteger.ZERO,bSum=BigInteger.ZERO;
        Path parent=manifestPath.getParent();
        for(int i=0;i<manifest.segments().size();i++){
            Segment segment=manifest.segments().get(i);
            if(segment.ordinal()!=i||segment.files()<1||segment.files()>MAX_FILES_PER_SEGMENT
                ||segment.pages()<1||segment.pages()>MAX_PAGES_PER_SEGMENT
                ||segment.files()>segment.pages()*128
                ||segment.pages()>segment.files()
                ||!sha(segment.headerShaA())||!sha(segment.headerShaB())
                ||!sha(segment.lastPageShaA())||!sha(segment.lastPageShaB()))
                throw new IOException("Segment cap/identity failed: "+i);
            rel(segment.firstPath());rel(segment.lastPath());
            if(segment.firstPath().compareTo(segment.lastPath())>0
                ||(!last.isEmpty()&&last.compareTo(segment.firstPath())>=0))
                throw new IOException("Overlapping or unordered segments");
            Path dir=parent.resolve("segments/segment-"+String.format(Locale.ROOT,"%02d",i));
            String rawA=read(dir.resolve("backup-a.json"),65536),rawB=read(dir.resolve("backup-b.json"),65536);
            if(!PhysicalStore.sha(rawA).equals(segment.headerShaA())
               ||!PhysicalStore.sha(rawB).equals(segment.headerShaB()))
                throw new IOException("Changed segment header SHA");
            var ha=QualificationBinding.decode(rawA,PagedBackupVerifier.Header.class);
            var hb=QualificationBinding.decode(rawB,PagedBackupVerifier.Header.class);
            if(!ha.sourceRoot().equals(world.toString())||!hb.sourceRoot().equals(world.toString())
                ||!ha.backupRoot().equals(left.toString())||!hb.backupRoot().equals(right.toString())
                ||ha.files()!=segment.files()||hb.files()!=segment.files()
                ||ha.pages()!=segment.pages()||hb.pages()!=segment.pages()
                ||!ha.terminalPageSha256().equals(segment.lastPageShaA())
                ||!hb.terminalPageSha256().equals(segment.lastPageShaB())
                ||!ha.quiescentPinPath().equals(manifest.pinPath())
                ||!hb.quiescentPinPath().equals(manifest.pinPath())
                ||!ha.quiescentPinSha256().equals(manifest.pinSha256())
                ||!hb.quiescentPinSha256().equals(manifest.pinSha256()))
                throw new IOException("Segment source/copy pin/header identity mismatch");
            String linkA=ZERO,linkB=ZERO;
            long local=0;
            for(long pageNo=0;pageNo<segment.pages();pageNo++){
                Path fa=dir.resolve("backup-a.json.pages/page-"+pageNo+".json");
                Path fb=dir.resolve("backup-b.json.pages/page-"+pageNo+".json");
                String textA=read(fa,MAX_PAGE_BYTES),textB=read(fb,MAX_PAGE_BYTES);
                var pa=QualificationBinding.decode(textA,PagedBackupVerifier.Page.class);
                var pb=QualificationBinding.decode(textB,PagedBackupVerifier.Page.class);
                if(pa.version()!=2||pb.version()!=2||pa.index()!=pageNo||pb.index()!=pageNo
                    ||!pa.previousSha256().equals(linkA)||!pb.previousSha256().equals(linkB)
                    ||pa.entries().size()!=pb.entries().size()||pa.entries().isEmpty()
                    ||pa.entries().size()>128)
                    throw new IOException("Corrupt/alien page link in segment "+i+" page "+pageNo);
                linkA=PhysicalStore.sha(textA);linkB=PhysicalStore.sha(textB);
                for(int k=0;k<pa.entries().size();k++){
                    var x=pa.entries().get(k);var y=pb.entries().get(k);
                    String name=x.path();
                    rel(name);
                    if(name.compareTo(last)<=0||name.equals("session.lock")
                       ||name.equals("worldprep-qualification.json")
                       ||name.startsWith("worldprep-v2/physical/")
                       ||!name.equals(y.path())||x.bytes()!=y.bytes()||!x.sha256().equals(y.sha256())
                       ||x.sourceModifiedMillis()!=y.sourceModifiedMillis())
                        throw new IOException("Duplicate/foreign/divergent file in segmented chain "+name);
                    if(i==0&&pageNo==0&&k==0&&!name.equals(segment.firstPath())
                        ||local==0&&!name.equals(segment.firstPath()))
                        throw new IOException("Segment first path changed");
                    String sourceHash=shaFile(world.resolve(name),x.bytes(),x.sourceModifiedMillis());
                    String aHash=shaFile(left.resolve(name),x.bytes(),x.backupModifiedMillis());
                    String bHash=shaFile(right.resolve(name),y.bytes(),y.backupModifiedMillis());
                    if(!sourceHash.equals(x.sha256())||!aHash.equals(x.sha256())||!bHash.equals(x.sha256()))
                        throw new IOException("Content mismatch in A/B/source "+name);
                    inventory.update((name+"\u0000"+x.bytes()+"\u0000"+x.sha256()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    sourceSum=addition(sourceSum,name,x.bytes(),x.sourceModifiedMillis());
                    aSum=addition(aSum,name,x.bytes(),x.backupModifiedMillis());
                    bSum=addition(bSum,name,y.bytes(),y.backupModifiedMillis());
                    last=name;local++;files++;
                }
            }
            if(local!=segment.files()||!last.equals(segment.lastPath())
               ||!linkA.equals(segment.lastPageShaA())||!linkB.equals(segment.lastPageShaB()))
                throw new IOException("Missing/out-of-order terminal segment "+i);
            pages+=segment.pages();
        }
        allSha=HexFormat.of().formatHex(inventory.digest());
        if(files!=manifest.totalFiles()||pages!=manifest.totalPages()
           ||!allSha.equals(manifest.inventorySha256()))
            throw new IOException("Incomplete global SHA inventory");
        var cs=coverage(world,true);var ca=coverage(left,false);var cb=coverage(right,false);
        if(cs.count()!=files||ca.count()!=files||cb.count()!=files
           ||!cs.sum().equals(sourceSum)||!ca.sum().equals(aSum)||!cb.sum().equals(bSum))
            throw new IOException("Extra/omitted/changed source or backup path in exhaustive cold coverage");
        return new Verified(files,pages,manifest.segments().size(),allSha,
            PhysicalStore.sha(raw),"COLD_SEGMENTED_A_B_CONTENT_AND_FULL_COVERAGE_SHA_VERIFIED_NOT_V5_QUALIFIED");
    }
    private ContraV5SegmentedBackupAudit(){}
}
