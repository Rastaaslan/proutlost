package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import fr.proutlost.worldprep.orchestration.ContraV5SegmentedCensusPages;
import fr.proutlost.worldprep.orchestration.DiskPopulationCounts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.dimension.DimensionType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Census of the already verified, quiescent independent backup, across every dimension.
 * One bounded inventory page OR one region slot per step. The complete backup inventory must
 * have passed PagedBackupVerifier first. Every retained chunk receipt is replayed one per step;
 * no loaded-entity census substitutes for disk coverage. More than 256 animals refuses new
 * population outright, matching the maximum supported finite global policy.
 */
public final class NativeBackupFaunaCensus {
    public record Baseline(int version,String inventorySha,String registrySha,DiskPopulationCounts counts,String status) {}
    record Chunk(String path,int slot,String inventorySha,DiskPopulationCounts counts) {}
    private static final Gson JSON=new Gson();
    private final PagedBackupVerifier.Header header;
    private final ContraV5SegmentedCensusPages segmented;
    private ContraV5SegmentedCensusPages.Item segmentedEntry;
    private final Path backup,pages;
    private final PhysicalStore store;
    private final String inventorySha,registrySha;
    private final Map<String,String> dimensions=new HashMap<>();
    private final Map<String,Integer> species=new TreeMap<>(),regions=new TreeMap<>(),chunks=new TreeMap<>();
    private int total,entry,slot;
    private long pageIndex,files;
    private String previous="0".repeat(64),lastPath="";
    private PagedBackupVerifier.Page page;
    public NativeBackupFaunaCensus(ServerLevel level,PagedBackupVerifier.Header header,Path pages,PhysicalStore store)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        this.header=Objects.requireNonNull(header);
        this.pages=Objects.requireNonNull(pages);
        this.store=Objects.requireNonNull(store);
        this.segmented=null;
        backup=Path.of(header.backupRoot()).toAbsolutePath().normalize();
        inventorySha=PhysicalStore.sha(JSON.toJson(header));
        registrySha=registryDigest();
        initializeDimensions(level);
    }
    /** RUN68: census all immutable A/B V5 pages, never just segment 0.
     * Must be invoked ONLY after an independent full cold-content SHA audit
     * and an exact native V5 segmented binding. No admission is implied here.
     */
    public NativeBackupFaunaCensus(ServerLevel level,Path manifestFile,Path world,
             Path backupA,Path backupB,UUID qualification,PhysicalStore store)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        this.header=null;
        this.pages=null;
        this.store=Objects.requireNonNull(store);
        this.segmented=new ContraV5SegmentedCensusPages(manifestFile,world,backupA,backupB,qualification);
        backup=backupA.toRealPath(LinkOption.NOFOLLOW_LINKS);
        inventorySha=segmented.inventorySha256();
        registrySha=registryDigest();
        initializeDimensions(level);
    }
    private void initializeDimensions(ServerLevel level)throws IOException {
        for(var dimension:level.getServer().getAllLevels()) {
            if(dimensions.size()>=128)throw new IOException("Dimension census bound exceeded");
            String relative=backup.relativize(DimensionType.getStorageFolder(dimension.dimension(),backup).resolve("entities").toAbsolutePath().normalize()).toString();
            dimensions.put(relative,dimension.dimension().location().toString());
        }
        store.write("identity",Map.of("inventory",inventorySha,"registry",registrySha,"dimensions",dimensions));
    }
    static String registryDigest()throws IOException {
        var registry=new TreeMap<String,String>();
        for(var type:BuiltInRegistries.ENTITY_TYPE) {
            String id=BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if(registry.size()>=4096||id.length()>256)throw new IOException("Bounded entity registry inventory required");
            registry.put(id,type.getCategory().name());
        }
        return PhysicalStore.sha(JSON.toJson(registry));
    }
    /** Bound retained backup-census work per server tick instead of forcing
     * one 47k-file inventory entry per 50 ms tick. Each interior step still
     * validates the complete immutable page chain and backup pin.
     *
     * A batch limit is a maximum, NOT a completion proof.
     */
    public Optional<Baseline> stepBounded(int maxEntries)throws IOException {
        if(maxEntries<1||maxEntries>32)
            throw new IOException("Quiescent census work budget must be 1..32");
        for(int i=0;i<maxEntries;i++) {
            var result=step();
            if(result.isPresent())return result;
        }
        return Optional.empty();
    }
    Optional<Baseline> step()throws IOException {
        pin();
        if(segmented!=null) {
            if(segmentedEntry==null) {
                var more=segmented.next();
                if(more.isEmpty()){
                    if(!segmented.complete()||files!=segmented.filesRead())
                        throw new IOException("Incomplete native fauna census across segments");
                    var result=new Baseline(1,inventorySha,registrySha,counts(),
                        "COMPLETE_QUIESCENT_SEGMENTED_BACKUP_CENSUS");
                    store.write("baseline",result);
                    return Optional.of(result);
                }
                segmentedEntry=more.orElseThrow();
            }
            return processFile(segmentedEntry.first(),segmentedEntry.header());
        }
        if(page==null) {
            if(pageIndex==header.pages()) {
                if(files!=header.files()||!previous.equals(header.terminalPageSha256()))throw new IOException("Incomplete census inventory");
                var result=new Baseline(1,inventorySha,registrySha,counts(),"COMPLETE_QUIESCENT_BACKUP_CENSUS");
                store.write("baseline",result);return Optional.of(result);
            }
            Path file=pages.resolve("page-"+pageIndex+".json");String text=bounded(file,128*1024);
            try{page=JSON.fromJson(text,PagedBackupVerifier.Page.class);}catch(RuntimeException e){throw new IOException("Malformed census inventory page",e);}
            if(page==null||page.index()!=pageIndex||!previous.equals(page.previousSha256()))throw new IOException("Census page chain mismatch");
            previous=PhysicalStore.sha(text);entry=0;return Optional.empty();
        }
        if(entry==page.entries().size()){page=null;pageIndex++;return Optional.empty();}
        var file=page.entries().get(entry);
        return processFile(file,header);
    }
    private Optional<Baseline> processFile(PagedBackupVerifier.Entry file,
                                            PagedBackupVerifier.Header currentHeader)throws IOException {
        if(slot==0) {
            if(file.path().compareTo(lastPath)<=0)throw new IOException("Census inventory order/duplicate");
        }
        Path relative=Path.of(file.path()),parent=relative.getParent();
        if(parent==null||!parent.getFileName().toString().equals("entities")){advance(file.path());return Optional.empty();}
        String dimension=dimensions.get(parent.toString());
        if(dimension==null)throw new IOException("Unregistered dimension entity storage in inventory: "+parent);
        var matcher=java.util.regex.Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca").matcher(relative.getFileName().toString());
        if(!matcher.matches())throw new IOException("Unsupported entity storage entry: "+relative);
        Path path=backup.resolve(relative);noLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=file.bytes()
            ||Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()!=file.backupModifiedMillis())throw new IOException("Quiescent census file changed");
        int rx,rz;
        try{rx=Integer.parseInt(matcher.group(1));rz=Integer.parseInt(matcher.group(2));}catch(NumberFormatException e){throw new IOException("Entity region coordinate overflow",e);}
        long cx=(long)rx*32+slot%32,cz=(long)rz*32+slot/32;
        if(cx<Integer.MIN_VALUE||cx>Integer.MAX_VALUE||cz<Integer.MIN_VALUE||cz>Integer.MAX_VALUE)throw new IOException("Entity chunk coordinate overflow");
        if(fr.proutlost.worldprep.orchestration.EmptyNativeEntityRegion.recordIfEmpty(currentHeader,file,store)) {
            if(slot!=0)throw new IOException("Empty entity region substituted during slot census");
            advance(file.path());return Optional.empty(); // one durable disposition, all 1024 slots absent
        }
        String key="chunk-"+PhysicalStore.sha(file.path()+"/"+slot);
        if(store.exists(key)) {
            var retained=store.read(key,Chunk.class);
            if(!retained.path().equals(file.path())||retained.slot()!=slot||!retained.inventorySha().equals(inventorySha))throw new IOException("Census receipt identity changed");
            add(retained.counts());
        } else {
            var offset=ByteBuffer.allocate(4);
            try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                if(channel.size()<8192)throw new IOException("Truncated entity region header");
                while(offset.hasRemaining())if(channel.read(offset,slot*4L+offset.position())<=0)throw new IOException("Partial entity region header");
            }
            offset.flip();int location=offset.getInt();
            if(location!=0) {
                var tag=ReadOnlyChunkNbt.entitiesBounded(path.getParent(),(int)cx,(int)cz);
                var found=new ArrayList<CompoundTag>();collect(tag.getList("Entities",Tag.TAG_COMPOUND),found,0);
                var s=new TreeMap<String,Integer>();var r=new TreeMap<String,Integer>();var c=new TreeMap<String,Integer>();int count=0;
                var uuids=new HashSet<UUID>();
                for(var entity:found) {
                    var id=ResourceLocation.tryParse(entity.getString("id"));
                    if(id==null||id.toString().length()>256||!BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw new IOException("Census missing entity registry entry");
                    var type=BuiltInRegistries.ENTITY_TYPE.get(id);
                    if(!Set.of(MobCategory.CREATURE,MobCategory.WATER_CREATURE,MobCategory.WATER_AMBIENT).contains(type.getCategory()))continue;
                    if(!entity.hasUUID("UUID"))throw new IOException("Census fauna UUID missing");
                    var position=entity.getList("Pos",Tag.TAG_DOUBLE);
                    if(position.size()!=3||!Double.isFinite(position.getDouble(0))||!Double.isFinite(position.getDouble(1))||!Double.isFinite(position.getDouble(2))
                        ||Math.floor(position.getDouble(0)/16)!=cx||Math.floor(position.getDouble(2)/16)!=cz)
                        throw new IOException("Fauna position does not match persisted entity chunk");
                    if(!uuids.add(entity.getUUID("UUID")))throw new IOException("Duplicate fauna UUID within entity chunk");
                    store.write("uuid-"+entity.getUUID("UUID"),Map.of("regionSha",PhysicalStore.sha(file.path()),"slot",slot,"species",id.toString()));
                    if(++count+total>256)throw new IOException("Existing disk fauna already exhausts maximum global budget");
                    s.merge(id.toString(),1,Integer::sum);r.merge(dimension+":"+(cx>>5)+":"+(cz>>5),1,Integer::sum);c.merge(dimension+":"+cx+":"+cz,1,Integer::sum);
                }
                var counts=new DiskPopulationCounts(count,s,r,c);store.write(key,new Chunk(file.path(),slot,inventorySha,counts));add(counts);
            }
        }
        if(++slot==1024){slot=0;advance(file.path());}return Optional.empty();
    }
    private void advance(String path){
        lastPath=path;files++;entry++;
        if(segmented!=null)segmentedEntry=null;
    }
    private DiskPopulationCounts counts(){return new DiskPopulationCounts(total,Map.copyOf(species),Map.copyOf(regions),Map.copyOf(chunks));}
    private void add(DiskPopulationCounts counts)throws IOException {
        if(counts==null||counts.total()<0||counts.total()>256||counts.species().size()>256||counts.regions().size()>256||counts.chunks().size()>256)throw new IOException("Invalid bounded census counts");
        for(var map:List.of(counts.species(),counts.regions(),counts.chunks())) {
            long sum=0;for(int n:map.values()){if(n<1||n>256)throw new IOException("Invalid census count");sum+=n;}
            if(sum!=counts.total())throw new IOException("Census count totals disagree");
        }
        if(total+counts.total()>256)throw new IOException("Existing disk fauna exhausts global budget");total+=counts.total();
        counts.species().forEach((k,v)->species.merge(k,v,Integer::sum));counts.regions().forEach((k,v)->regions.merge(k,v,Integer::sum));counts.chunks().forEach((k,v)->chunks.merge(k,v,Integer::sum));
    }
    private void pin()throws IOException {
        String pin=segmented==null?header.quiescentPinPath():"worldprep-qualification-pin.json";
        String expected=segmented==null?header.quiescentPinSha256():segmented.pinSha256();
        if(!PhysicalStore.sha(bounded(backup.resolve(pin),4096)).equals(expected))
            throw new IOException("Census quiescent pin changed");
    }
    private static void collect(ListTag list,List<CompoundTag> result,int depth)throws IOException {
        if(depth>16||list.size()>4096)throw new IOException("Entity census nesting/count bound");
        for(int i=0;i<list.size();i++){if(result.size()>=4096)throw new IOException("Entity census count bound");var tag=list.getCompound(i);result.add(tag);collect(tag.getList("Passengers",Tag.TAG_COMPOUND),result,depth+1);}
    }
    private static void noLinks(Path path)throws IOException {for(Path p=path.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink census input");}
    private static String bounded(Path path,int max)throws IOException {
        noLinks(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw new IOException("Missing/bounded census input");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(max+1);if(bytes.length>max)throw new IOException("Census input grew beyond bound");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
