package fr.proutlost.worldprep.orchestration;

import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/**
 * RUN68 — READ-ONLY streaming of every A/B segment for the native backup
 * fauna census. This does NOT replace the mandatory pre-boot full cold
 * SHA/content audit, nor certify V5. It must be bound to the exact UUID,
 * world and two independent roots by the eventual native qualification gate.
 * Legacy V2 backup readers remain unchanged.
 */
public final class ContraV5SegmentedCensusPages {
    public record Item(PagedBackupVerifier.Header header,
                       PagedBackupVerifier.Entry first,
                       PagedBackupVerifier.Entry second) {}
    private static final String ZERO="0".repeat(64);
    private final ContraV5SegmentedBackupAudit.Manifest manifest;
    private final Path bundle,manifestPath,world,a,b;
    private final String manifestSha256;
    private final MessageDigest global;
    private int segmentNo;
    private long pageNo,localFiles,totalFiles,totalPages;
    private int offset;
    private String last="",linkA=ZERO,linkB=ZERO;
    private PagedBackupVerifier.Header ha,hb;
    private PagedBackupVerifier.Page pa,pb;
    private boolean finished,failed;

    private static MessageDigest sha(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String digest(String s){
        return HexFormat.of().formatHex(sha().digest(
            s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void noLinks(Path path)throws IOException{
        for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Linked census input: "+p);
    }
    private static String read(Path path,int max)throws IOException{
        noLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
            ||Files.size(path)>max)
            throw new IOException("Missing/oversized segmented census page: "+path);
        return QualificationBinding.read(path,max);
    }
    private static void rel(String name)throws IOException{
        if(name==null||name.isBlank()||name.length()>2048
            ||name.indexOf('\\')>=0)
            throw new IOException("Unsafe census inventory name");
        Path p=Path.of(name);
        if(p.isAbsolute()||p.startsWith("..")||!p.normalize().equals(p)
            ||p.getNameCount()>32||name.equals("session.lock")
            ||name.equals("worldprep-qualification.json")
            ||name.startsWith("worldprep-v2/physical/"))
            throw new IOException("Uninventoried native physical or unsafe path: "+name);
    }
    private static String fileSha(Path file)throws IOException {
        byte[] raw=Files.readAllBytes(file);
        if(raw.length>4096)throw new IOException("Oversized qualification generation pin");
        return HexFormat.of().formatHex(sha().digest(raw));
    }

    public ContraV5SegmentedCensusPages(Path manifestFile,Path expectedWorld,
           Path expectedA,Path expectedB,UUID exactQualification)throws IOException {
        if(exactQualification==null)throw new IOException("Explicit native V5 UUID mandatory");
        noLinks(manifestFile);noLinks(expectedWorld);noLinks(expectedA);noLinks(expectedB);
        manifestPath=manifestFile.toRealPath(LinkOption.NOFOLLOW_LINKS);
        bundle=manifestPath.getParent();
        world=expectedWorld.toRealPath(LinkOption.NOFOLLOW_LINKS);
        a=expectedA.toRealPath(LinkOption.NOFOLLOW_LINKS);
        b=expectedB.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if(world.equals(a)||world.equals(b)||a.equals(b)
            ||world.startsWith(a)||world.startsWith(b)||a.startsWith(world)
            ||b.startsWith(world)||a.startsWith(b)||b.startsWith(a))
            throw new IOException("Overlapping native V5 source/copy roots");
        String raw=read(manifestFile,65536);
        manifest=QualificationBinding.decode(raw,ContraV5SegmentedBackupAudit.Manifest.class);
        if(manifest.version()!=3||!manifest.qualification().equals(exactQualification.toString())
            ||!manifest.world().equals(world.toString())
            ||!manifest.backupA().equals(a.toString())
            ||!manifest.backupB().equals(b.toString())
            ||!"worldprep-qualification-pin.json".equals(manifest.pinPath())
            ||manifest.pinSha256()==null||!manifest.pinSha256().matches("[0-9a-f]{64}")
            ||manifest.inventorySha256()==null||!manifest.inventorySha256().matches("[0-9a-f]{64}")
            ||manifest.segments()==null||manifest.segments().isEmpty()
            ||manifest.segments().size()>ContraV5SegmentedBackupAudit.MAX_SEGMENTS
            ||manifest.totalFiles()<1||manifest.totalPages()<1)
            throw new IOException("Wrong V5 segmented ledger envelope");
        for(Path root:List.of(world,a,b))
            if(!fileSha(root.resolve(manifest.pinPath())).equals(manifest.pinSha256()))
                throw new IOException("V5 generation pin changed");
        manifestSha256=digest(raw);
        global=sha();
    }

    public String inventorySha256(){return manifest.inventorySha256();}
    public String pinSha256(){return manifest.pinSha256();}
    public String manifestSha256(){return manifestSha256;}
    public long filesRead(){return totalFiles;}
    public boolean complete(){return finished;}

    private ContraV5SegmentedBackupAudit.Segment segment() {
        return manifest.segments().get(segmentNo);
    }
    private void openSegment()throws IOException{
        var segment=segment();
        if(segment.ordinal()!=segmentNo
            ||segment.files()<1||segment.files()>ContraV5SegmentedBackupAudit.MAX_FILES_PER_SEGMENT
            ||segment.pages()<1||segment.pages()>ContraV5SegmentedBackupAudit.MAX_PAGES_PER_SEGMENT
            ||segment.files()>segment.pages()*128L||segment.pages()>segment.files()
            ||segment.firstPath()==null||segment.lastPath()==null)
            throw new IOException("Exceeded existing native V5 segment bounds");
        rel(segment.firstPath());rel(segment.lastPath());
        if(segment.firstPath().compareTo(segment.lastPath())>0
            ||(!last.isEmpty()&&segment.firstPath().compareTo(last)<=0))
            throw new IOException("Cross-segment order/overlap");
        Path dir=bundle.resolve(String.format(Locale.ROOT,"segments/segment-%02d",segmentNo));
        String xa=read(dir.resolve("backup-a.json"),65536);
        String xb=read(dir.resolve("backup-b.json"),65536);
        if(!digest(xa).equals(segment.headerShaA())||!digest(xb).equals(segment.headerShaB()))
            throw new IOException("Changed native V5 segment header SHA");
        ha=QualificationBinding.decode(xa,PagedBackupVerifier.Header.class);
        hb=QualificationBinding.decode(xb,PagedBackupVerifier.Header.class);
        if(ha.version()!=2||hb.version()!=2
            ||!ha.sourceRoot().equals(world.toString())||!hb.sourceRoot().equals(world.toString())
            ||!ha.backupRoot().equals(a.toString())||!hb.backupRoot().equals(b.toString())
            ||ha.files()!=segment.files()||hb.files()!=segment.files()
            ||ha.pages()!=segment.pages()||hb.pages()!=segment.pages()
            ||!ha.terminalPageSha256().equals(segment.lastPageShaA())
            ||!hb.terminalPageSha256().equals(segment.lastPageShaB())
            ||!ha.quiescentPinPath().equals(manifest.pinPath())
            ||!hb.quiescentPinPath().equals(manifest.pinPath())
            ||!ha.quiescentPinSha256().equals(manifest.pinSha256())
            ||!hb.quiescentPinSha256().equals(manifest.pinSha256()))
            throw new IOException("Wrong native source/backup/header identity");
    }
    private void openPage()throws IOException{
        var segment=segment();
        if(pageNo>=segment.pages())throw new IOException("Unbounded native census pages");
        Path dir=bundle.resolve(String.format(Locale.ROOT,"segments/segment-%02d",segmentNo));
        String xa=read(dir.resolve("backup-a.json.pages/page-"+pageNo+".json"),128*1024);
        String xb=read(dir.resolve("backup-b.json.pages/page-"+pageNo+".json"),128*1024);
        pa=QualificationBinding.decode(xa,PagedBackupVerifier.Page.class);
        pb=QualificationBinding.decode(xb,PagedBackupVerifier.Page.class);
        if(pa.version()!=2||pb.version()!=2||pa.index()!=pageNo||pb.index()!=pageNo
            ||!pa.previousSha256().equals(linkA)||!pb.previousSha256().equals(linkB)
            ||pa.entries()==null||pb.entries()==null||pa.entries().isEmpty()
            ||pa.entries().size()>128||pa.entries().size()!=pb.entries().size())
            throw new IOException("Truncated/foreign V5 segment census page");
        linkA=digest(xa);linkB=digest(xb);offset=0;
        totalPages=Math.addExact(totalPages,1);
    }
    private void endSegment()throws IOException{
        var s=segment();
        if(localFiles!=s.files()||!last.equals(s.lastPath())
            ||!linkA.equals(s.lastPageShaA())||!linkB.equals(s.lastPageShaB()))
            throw new IOException("Incomplete native V5 segment census");
        segmentNo++;pageNo=0;localFiles=0;linkA=ZERO;linkB=ZERO;
        ha=null;hb=null;pa=null;pb=null;offset=0;
    }
    private void endAll()throws IOException {
        if(!digest(read(manifestPath,65536)).equals(manifestSha256))
            throw new IOException("Segmented manifest mutated during census");
        for(Path root:List.of(world,a,b))
            if(!fileSha(root.resolve(manifest.pinPath())).equals(manifest.pinSha256()))
                throw new IOException("V5 pin mutated during census");
        if(totalFiles!=manifest.totalFiles()||totalPages!=manifest.totalPages()
            ||!HexFormat.of().formatHex(global.digest()).equals(manifest.inventorySha256()))
            throw new IOException("Incomplete global native V5 multi-segment census SHA");
        finished=true;
    }

    /** One immutable entry per step; end is returned ONLY after all segment terminal links. */
    public Optional<Item> next()throws IOException{
        if(failed)throw new IOException("RUN68 census failed closed; no retry on same cursor");
        if(finished)return Optional.empty();
        try{
        while(true){
            if(segmentNo==manifest.segments().size()){endAll();return Optional.empty();}
            if(ha==null)openSegment();
            if(pa==null){
                if(pageNo==segment().pages()){endSegment();continue;}
                openPage();
            }
            if(offset==pa.entries().size()) {
                offset=0;pa=null;pb=null;pageNo++;
                continue;
            }
            var x=pa.entries().get(offset);var y=pb.entries().get(offset);
            if(x==null||y==null)throw new IOException("Null segmented census entry");
            rel(x.path());
            if(x.path().compareTo(last)<=0||!x.path().equals(y.path())
                ||x.bytes()<0||x.bytes()!=y.bytes()
                ||x.sourceModifiedMillis()!=y.sourceModifiedMillis()
                ||x.sha256()==null||!x.sha256().matches("[0-9a-f]{64}")
                ||!x.sha256().equals(y.sha256()))
                throw new IOException("Cross-copy divergent or unsorted V5 census entry");
            if(localFiles==0&&!x.path().equals(segment().firstPath()))
                throw new IOException("Segment first file mismatch");
            global.update((x.path()+"\u0000"+x.bytes()+"\u0000"+x.sha256()+"\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            offset++;localFiles++;totalFiles=Math.addExact(totalFiles,1);last=x.path();
            if(localFiles>segment().files()||totalFiles>manifest.totalFiles())
                throw new IOException("Census file bound exceeded");
            return Optional.of(new Item(ha,x,y));
        }
        }catch(IOException|RuntimeException failure){
            failed=true;
            if(failure instanceof IOException io)throw io;
            throw new IOException("Invalid or corrupted V5 segmented census",failure);
        }
    }
}
