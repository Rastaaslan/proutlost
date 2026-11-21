package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.structure.*;
import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.util.*;

/** Adaptive two-stage post-ecology candidate survey, one per tick, at most 64 existing chunks and one exact site per area.
 * The catalogue key is an exact template resource, never an invented registry structure relation.
 */
public final class NativeTemplateSitePlanner implements AutoCloseable {
    private NativeChunkWindow window;
    private final WorldPrepSavedData.Area area;
    private final String template,config;
    private final StructureCapability approved;
    private final List<BlockPos> candidates;
    private int cursor;
    public NativeTemplateSitePlanner(WorldPrepSavedData.Area area,String template,String config,long seed) {
        this(area,template,config,seed,null);
    }
    /** Opt-in catalog path; existing V5 remains unchanged. */
    public NativeTemplateSitePlanner(WorldPrepSavedData.Area area,String template,String config,long seed,
                                     StructureCatalog catalog) {
        this.area=area;this.template=template;this.config=config;
        this.approved=catalog==null?null:catalog.requireProductionSupported(template);
        if(approved!=null&&(approved.status()!=StructureSupportStatus.EXACT_TEMPLATE
                || !NativeStructurePass.ADAPTER.equals(approved.adapterFingerprint())))
            throw new IllegalArgumentException("Catalog adapter does not support exact native template");
        if(approved==null&&!NativeStructurePass.TEMPLATES.contains(template))
            throw new IllegalArgumentException("Unsupported automatic template");
        long count=(long)((area.maxX()>>4)-(area.minX()>>4)+1)*((area.maxZ()>>4)-(area.minZ()>>4)+1);
        if(count<1||count>64)throw new IllegalArgumentException("Automatic site survey limited to 64 existing chunks");
        // Two finite deterministic passes: first the previous 3x3 positions
        // (9 per chunk), then a 27-position fallback ONLY after all primary
        // sites are rejected. Max 64*36=2304. One bounded candidate per tick,
        // no implicit terrain generation and no cross-group tickets.
        var primary=new LinkedHashSet<BlockPos>();
        var fallback=new LinkedHashSet<BlockPos>();
        for(int x=area.minX()>>4;x<=area.maxX()>>4;x++)for(int z=area.minZ()>>4;z<=area.maxZ()>>4;z++) {
            for(int dx:new int[]{2,4,6,8,10,12})for(int dz:new int[]{2,4,6,8,10,12}) {
                long px=(long)x*16+dx,pz=(long)z*16+dz;
                if(px<area.minX()||px>area.maxX()||pz<area.minZ()||pz>area.maxZ())continue;
                if(px<(long)area.minX()+16||px>(long)area.maxX()-18
                    ||pz<(long)area.minZ()+16||pz>(long)area.maxZ()-18)continue;
                var point=new BlockPos((int)px,0,(int)pz);
                if(dx%4==0&&dz%4==0)primary.add(point);
                else fallback.add(point);
            }
        }
        // Keep all previous primary and fallback positions and deterministic
        // seed/config ordering, but group each pass by chunk before considering
        // the per-position salted rank. This lets NativeChunkWindow reuse the
        // exact verified 3x3 halo across the nine primary sites in that chunk,
        // rather than releasing/re-pinning it after virtually every candidate.
        // No heightmap, collision, rarity, or template verification is skipped.
        var chunkRanks=new HashMap<Long,String>();
        Comparator<BlockPos> order=Comparator
            .comparing((BlockPos p)->chunkRanks.computeIfAbsent(
                WorldPrepSavedData.cellKey(p.getX()>>4,p.getZ()>>4),
                key->PhysicalStore.sha(seed+"/"+config+"/structure-chunk-v1/"
                    +(p.getX()>>4)+","+(p.getZ()>>4))))
            .thenComparingInt(p->p.getX()>>4)
            .thenComparingInt(p->p.getZ()>>4)
            .thenComparing(p->PhysicalStore.sha(seed+"/"+config+"/"+p))
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);
        var ordered=new ArrayList<BlockPos>(primary.size()+fallback.size());
        ordered.addAll(primary.stream().sorted(order).toList());
        ordered.addAll(fallback.stream().sorted(order).toList());
        candidates=List.copyOf(ordered);
    }
    /** Immutable positions for audit/tests; construction performs no world I/O. */
    public List<BlockPos> auditCandidates(){return candidates;}
    /** Read-only diagnostics: number of exact candidate positions actually checked.
     * Never advances a chunk lease or changes the deterministic search order.
     */
    public int auditCheckedCandidates(){return cursor;}
    public void close(){if(window!=null)window.close();}
    public boolean exhausted(){return cursor==candidates.size();}
    public Optional<NativeStructurePass.Plan> step(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(cursor==candidates.size())throw new IllegalStateException("No eligible exact site in finite post-ecology survey");
        // Same exact v3 allocator rarity gate, which depends only on seed,
        // template and X/Z. Fast-forward deterministic rejected coordinates
        // BEFORE obtaining any chunk lease. Native terrain, template, protection
        // and disk checks remain mandatory for every rarity-admitted site.
        while(cursor<candidates.size()) {
            var p=candidates.get(cursor);
            if(AutomaticStructurePlanner.rarityAdmitted(level.getSeed(),template,
                    p.getX(),p.getZ(),approved==null?4:approved.rarityDivisor()))break;
            cursor++;
        }
        if(cursor==candidates.size())return Optional.empty();
        var point=candidates.get(cursor);
        if(window==null)window=new NativeChunkWindow(level);
        var templateSize=level.getStructureManager().get(ResourceLocation.parse(template)).orElseThrow().getSize();
        int radius=Math.max(templateSize.getX(),templateSize.getZ());
        if(radius<1||radius>16)throw new IllegalArgumentException("REFUSED: automatic footprint loading radius");
        if(approved!=null&&(approved.width()!=templateSize.getX()
                ||approved.height()!=templateSize.getY()||approved.depth()!=templateSize.getZ()))
            throw new IllegalArgumentException("Catalog/native size mismatch");
        if(!window.blocks(point.getX()-radius,point.getZ()-radius,point.getX()+radius,point.getZ()+radius))return Optional.empty();
        cursor++;
        var chunk=ServerExistingChunkAccess.requireChunk(level,point.getX()>>4,point.getZ()>>4);
        // LevelChunk.getHeight() returns the highest MOTION_BLOCKING block Y,
        // unlike the Anvil NBT heightmap encoding (first free Y). Its next Y
        // is the first air block and must be used as template origin.
        int y=foundationY(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            point.getX()&15,point.getZ()&15));
        var size=level.getStructureManager().get(ResourceLocation.parse(template)).orElseThrow(()->new IllegalArgumentException("Missing exact template")).getSize();
        if(size.getX()<1||size.getY()<1||size.getZ()<1||(long)size.getX()*size.getY()*size.getZ()>4096)throw new IllegalArgumentException("Finite template required");
        var terrain=y>=100?TerrainSemantic.HIGHLAND:TerrainSemantic.LOWLAND;
        var effectiveCatalog=approved==null
            ?new StructureCatalog(List.of(new StructureCapability(template,StructureSupportStatus.EXACT_TEMPLATE,
                NativeStructurePass.ADAPTER,size.getX(),size.getY(),size.getZ(),2,32,4,
                Set.of(TerrainSemantic.LOWLAND,TerrainSemantic.HIGHLAND))))
            :new StructureCatalog(List.of(approved));
        var allocations=new AutomaticStructurePlanner().plan(
            List.of(new AutomaticStructurePlanner.Candidate(template,point.getX(),y,point.getZ(),100,terrain,0)),
            effectiveCatalog,level.getSeed(),1,1,PhysicalStore.sha(config));
        if(allocations.isEmpty())return Optional.empty();
        var allocation=allocations.getFirst();var rotation=Rotation.valueOf(allocation.rotation().name());
        int minX=0,minZ=0;
        for(int x:new int[]{0,size.getX()-1})for(int z:new int[]{0,size.getZ()-1}) {
            var corner=StructureTemplate.transform(new BlockPos(x,0,z),Mirror.NONE,rotation,BlockPos.ZERO);
            minX=Math.min(minX,corner.getX());minZ=Math.min(minZ,corner.getZ());
        }
        var origin=new BlockPos(allocation.footprint().minX()-minX,y,allocation.footprint().minZ()-minZ);
        try{return Optional.of(approved==null
            ?NativeStructurePass.plan(level,area,template,origin,rotation,Mirror.NONE,config)
            :NativeStructurePass.planCatalogTemplate(level,area,approved,origin,rotation,Mirror.NONE,config));}
        catch(fr.proutlost.worldprep.world.ServerExistingChunkAccess.Refused refused){
            // A template overlapping the exact candidate perimeter is a site rejection.
            // Missing/incomplete chunks, invalid dimensions and loading failures must halt.
            if(isOutsideCandidateArea(refused))return Optional.empty();
            throw refused;
        }
        catch(NativeStructurePass.IneligibleSite|NativeStructurePass.SiteConflict unsuitable){return Optional.empty();}
    }
    /** Native LevelChunk.getHeight returns the TOP solid Y; convert to first
     * free position. Distinct from the NBT encoded 'firstAvailable' heightmap.
     */
    static int foundationY(int nativeTopSolidY){
        return Math.addExact(nativeTopSolidY,1);
    }
    static boolean isOutsideCandidateArea(fr.proutlost.worldprep.world.ServerExistingChunkAccess.Refused refusal){
        return refusal.refusal()==fr.proutlost.worldprep.world.ServerExistingChunkAccess.Refusal.OUTSIDE_AREA;
    }
}
