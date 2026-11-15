package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.structure.StructureCapability;
import fr.proutlost.worldprep.structure.StructureInstancePlan;
import fr.proutlost.worldprep.structure.StructureSupportStatus;
import fr.proutlost.worldprep.world.ExistingRegionEntry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Retrofitted real registered Structure / StructureStart path for already FULL chunks.
 * Invokes the installed mod's generation and piece post-processors; NEVER copies NBT by hand.
 * This is an experimental COW-only adapter, not a substitute for in-game/disk certification.
 */
public final class NativeRegistryStructurePass {
    public static final String ADAPTER="native-registered-structure-neoforge-1.21.1-v1";
    private NativeRegistryStructurePass() {}
    /** Empty entries are normal vanilla registry bookkeeping, not physical structures. */
    public static boolean hasReferences(java.util.Collection<? extends it.unimi.dsi.fastutil.longs.LongSet> values) {
        return values.stream().anyMatch(refs->refs!=null&&!refs.isEmpty());
    }

    public enum Conditions {
        /** Unmodified registered biome predicate and all native placement conditions. */
        NATIVE,
        /** Override ONLY the biome predicate passed to Structure.generate. */
        OVERRIDE_BIOME,
        /** Operator provides site; bypass ONLY the public biome predicate, not native geometry checks. */
        FORCED,
        /** Explicit controlled import: override the seed-biome predicate and reanchor only the
         * four supported rigid surface models to the persisted painted-world surface. */
        IMPORT_EXISTING_TERRAIN
    }

    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Bounds {
            if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Invalid native bounds");
        }
        public boolean inside(StructureInstancePlan.Bounds reservation) {
            return minX>=reservation.minX()&&maxX<=reservation.maxX()
                &&minY>=reservation.minY()&&maxY<=reservation.maxY()
                &&minZ>=reservation.minZ()&&maxZ<=reservation.maxZ();
        }
        public static Bounds from(BoundingBox b) {
            return new Bounds(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
        }
    }
    /** Native pieces can describe a shaft to Y=maxBuildHeight; Minecraft clips placement to max-1.
     * Preserve the original StructureStart, but catalog and inspect only writable world coordinates. */
    public static Bounds writableBounds(Bounds original,int minY,int maxYExclusive)throws IOException {
        if(minY>=maxYExclusive||original.maxY()<minY||original.minY()>=maxYExclusive)
            throw new IOException("Native structure entirely outside build height");
        return new Bounds(original.minX(),Math.max(original.minY(),minY),original.minZ(),
            original.maxX(),Math.min(original.maxY(),maxYExclusive-1),original.maxZ());
    }

    /** Absolute quart coordinates of one persisted biome cell. */
    record BiomeQuart(int x,int y,int z) {}

    /**
     * Check the existing chunk biomes over the real writable footprint, including depth.
     * The generator's BiomeSource is not evidence for WorldPainter/persisted biomes.
     * This is called only during the all-chunks preflight, before any chunk intent/write.
     */
    static void requirePersistedBiomeCompatibility(Conditions mode,Bounds actual,ChunkPos pos,
            Predicate<BiomeQuart> permitted)throws IOException {
        if(mode==null||actual==null||pos==null||permitted==null)
            throw new IOException("Missing persisted-biome preflight input");
        if(mode!=Conditions.NATIVE)return; // Explicit LAB-only biome overrides remain explicit.
        int minX=Math.max(actual.minX(),pos.getMinBlockX());
        int maxX=Math.min(actual.maxX(),pos.getMaxBlockX());
        int minZ=Math.max(actual.minZ(),pos.getMinBlockZ());
        int maxZ=Math.min(actual.maxZ(),pos.getMaxBlockZ());
        if(minX>maxX||minZ>maxZ)
            throw new IOException("Native biome preflight chunk is outside actual footprint");
        for(int qz=Math.floorDiv(minZ,4);qz<=Math.floorDiv(maxZ,4);qz++)
            for(int qx=Math.floorDiv(minX,4);qx<=Math.floorDiv(maxX,4);qx++)
                for(int qy=Math.floorDiv(actual.minY(),4);qy<=Math.floorDiv(actual.maxY(),4);qy++)
                    if(!permitted.test(new BiomeQuart(qx,qy,qz)))
                        throw new IOException("Native persisted-biome mismatch in chunk=("
                            +pos.x+","+pos.z+") quart=("+qx+","+qy+","+qz+")");
    }

    public record NativeReceipt(UUID operation,String registryId,String dimension,Conditions conditions,
            Bounds actualBounds,int completedChunks,int totalChunks,long changedBlocks,String state) {}
    public record ChangedBlock(int x,int y,int z,String after) {}
    public record ChunkDelta(int chunkX,int chunkZ,long changedBlocks,List<ChangedBlock> samples) {
        public ChunkDelta {samples=List.copyOf(samples);}
    }

    public record Prepared(UUID operation,String registryId,String dimension,Conditions conditions,
            Structure structure,StructureStart start,Bounds actualBounds,List<ChunkPos> chunks,
            NativeStructureTerrainImport.Adjustment terrainAdjustment) {
        public Prepared {chunks=List.copyOf(chunks);}
    }

    /**
     * Build a real mod StructureStart from live dynamic registries.
     * The native generator chooses its real Y; no fabricated dependent pieces/blocks are supplied.
     * A mismatched planner envelope is rejected *before any block mutation*.
     */
    public static Prepared prepare(ServerLevel level,StructureInstancePlan allocation,
                                   StructureCapability capability,Conditions mode)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(allocation==null||capability==null||mode==null
                ||!allocation.structureId().equals(capability.structureId()))
            throw new IOException("Native allocation identity mismatch");
        if(capability.status()!=StructureSupportStatus.EXACT_JIGSAW
                &&capability.status()!=StructureSupportStatus.CUSTOM_SAFE_ADAPTER)
            throw new IOException("Native generator capability must be explicitly opted in");
        ResourceLocation key=ResourceLocation.tryParse(allocation.structureId());
        if(key==null||!key.toString().equals(allocation.structureId()))
            throw new IOException("Invalid native structure registry key");
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure structure=registry.get(key);
        if(structure==null)throw new IOException("Installed native structure missing: "+key);
        var source=level.getChunkSource();
        Predicate<Holder<Biome>> biome=mode==Conditions.NATIVE
                ? structure.biomes()::contains : holder->true;
        ChunkPos origin=new ChunkPos(allocation.x()>>4,allocation.z()>>4);
        StructureStart start;
        try {
            start=structure.generate(level.registryAccess(),source.getGenerator(),
                source.getGenerator().getBiomeSource(),source.randomState(),
                level.getStructureManager(),level.getSeed(),origin,0,level,biome);
        } catch(RuntimeException invalid) {
            throw new IOException("Native YUNG's generator rejected proposed site: "+key,invalid);
        }
        if(start==null||!start.isValid()||start.getPieces().isEmpty())
            throw new IOException("Native generator declined placement (conditions/terrain): "+key);
        var terrainImport=NativeStructureTerrainImport.apply(level,structure,start,key.toString(),mode);
        start=terrainImport.start();
        Bounds actual=writableBounds(Bounds.from(start.getBoundingBox()),
            level.getMinBuildHeight(),level.getMaxBuildHeight());
        if(!actual.inside(allocation.reservation()))
            throw new IOException("Real generator footprint exceeds reserved envelope: "+key+" "+actual);
        if(actual.minY()<level.getMinBuildHeight()||actual.maxY()>=level.getMaxBuildHeight())
            throw new IOException("Native structure crosses world height limits");
        int lowX=actual.minX()>>4,highX=actual.maxX()>>4;
        int lowZ=actual.minZ()>>4,highZ=actual.maxZ()>>4;
        long count=((long)highX-lowX+1)*((long)highZ-lowZ+1);
        if(count<=0||count>192)
            throw new IOException("Native structure exceeds bounded 192-chunk COW budget");
        if(origin.x<lowX||origin.x>highX||origin.z<lowZ||origin.z>highZ)
            throw new IOException("Native structure start chunk outside real generated bounds");
        var world=level.getServer().getWorldPath(LevelResource.ROOT);
        Path region=DimensionType.getStorageFolder(level.dimension(),world).resolve("region");
        List<ChunkPos> chunks=new ArrayList<>();
        for(int z=lowZ;z<=highZ;z++)for(int x=lowX;x<=highX;x++) {
            ChunkPos pos=new ChunkPos(x,z);
            if(!level.getWorldBorder().isWithinBounds(pos.getMinBlockX(),pos.getMinBlockZ())
                ||!level.getWorldBorder().isWithinBounds(pos.getMaxBlockX(),pos.getMaxBlockZ()))
                throw new IOException("Native placement crosses world border: "+pos);
            ExistingRegionEntry.requirePresent(region,x,z);
            chunks.add(pos);
        }
        UUID operation=UUID.nameUUIDFromBytes(("worldprep-native-registry-v1/"
                +level.dimension().location()+"/"+allocation.instanceId()+"/"+key).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        return new Prepared(operation,key.toString(),level.dimension().location().toString(),
                mode,structure,start,actual,chunks,terrainImport.adjustment());
    }

    /**
     * One FULL chunk per server tick. ExistingChunkLoader pins a verified 3x3 halo.
     * The immutable chunk intent prevents silent replay after a crash: COW must be reconciled.
     */
    public static final class Runner implements AutoCloseable {
        private final Prepared p;
        private final PhysicalStore store;
        private NativeChunkWindow leases;
        private int cursor;
        private int preflightCursor;
        private long changedBlocks;
        public Runner(Prepared prepared,PhysicalStore store)throws IOException {
            this.p=prepared;this.store=store;
            store.write("native-plan",Map.of("operation",p.operation().toString(),
                "registry",p.registryId(),"dimension",p.dimension(),"conditions",p.conditions().toString(),
                "bounds",p.actualBounds(),"nativeGeneratorBounds",p.terrainAdjustment().originalBounds(),
                "terrainAdjustment",p.terrainAdjustment(),
                "nativePieces",p.start().getPieces().size(),"totalChunks",p.chunks().size()));
        }
        public int completedChunks(){return cursor;}
        public String progress(){return "checked="+preflightCursor+"/"+p.chunks().size()+" placedChunks="+cursor+"/"+p.chunks().size();}
        private boolean conflicts(ChunkAccess chunk) {
            return chunk.getAllStarts().values().stream().anyMatch(start->start!=null&&start.isValid()
                &&start.getBoundingBox().intersects(p.start().getBoundingBox()))
                ||hasReferences(chunk.getAllReferences().values());
        }
        public boolean done(){return cursor==p.chunks().size();}
        public NativeReceipt receipt(){
            return new NativeReceipt(p.operation(),p.registryId(),p.dimension(),p.conditions(),
                p.actualBounds(),cursor,p.chunks().size(),changedBlocks,
                done()?"PLACED_UNVERIFIED_DISK":"PARTIAL_COW_NO_AUTOREPLAY");
        }
        public boolean step(ServerLevel level)throws IOException {
            NativePhysicalRuntime.requireThread(level);
            if(!level.dimension().location().toString().equals(p.dimension()))
                throw new IOException("Native placement dimension changed");
            if(done())return true;
            if(leases==null)leases=new NativeChunkWindow(level);
            boolean checking=preflightCursor<p.chunks().size();
            ChunkPos pos=p.chunks().get(checking?preflightCursor:cursor);
            if(!leases.blocks(pos.getMinBlockX(),pos.getMinBlockZ(),pos.getMaxBlockX(),pos.getMaxBlockZ()))
                return false;
            ChunkAccess chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);
            if(chunk==null||!chunk.getPos().equals(pos)
                ||chunk.getPersistedStatus()!=net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                throw new IOException("Existing FULL chunk lease missing: "+pos);
            // A pre-existing start/reference indicates overlap uncertainty; NEVER overwrite it.
            if(conflicts(chunk))
                throw new IOException("Existing native structure overlaps target chunk: "+pos);
            // Inspect the ENTIRE footprint before the first mutation. Empty registry keys are not structures.
            if(checking){
                requirePersistedBiomeCompatibility(p.conditions(),p.actualBounds(),pos,
                    cell->p.structure().biomes().contains(
                        chunk.getNoiseBiome(cell.x(),cell.y(),cell.z())));
                preflightCursor++;leases.close();leases=null;return false;
            }
            var real=p.actualBounds();
            int x0=Math.max(real.minX(),pos.getMinBlockX()),x1=Math.min(real.maxX(),pos.getMaxBlockX());
            int z0=Math.max(real.minZ(),pos.getMinBlockZ()),z1=Math.min(real.maxZ(),pos.getMaxBlockZ());
            int y0=real.minY(),y1=real.maxY();
            var cursorBlock=new net.minecraft.core.BlockPos.MutableBlockPos();
            var before=new ArrayList<net.minecraft.world.level.block.state.BlockState>();
            for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)
                before.add(level.getBlockState(cursorBlock.set(x,y,z)));
            String intent="native-chunk-intent-"+cursor;
            if(store.exists(intent))
                throw new IOException("Native chunk was previously attempted; manual COW reconciliation required");
            store.write(intent,Map.of("chunkX",pos.x,"chunkZ",pos.z,
                "operation",p.operation().toString(),"state","INTENT_BEFORE_MODIFICATION"));
            BoundingBox clip=new BoundingBox(pos.getMinBlockX(),level.getMinBuildHeight(),
                pos.getMinBlockZ(),pos.getMaxBlockX(),level.getMaxBuildHeight()-1,pos.getMaxBlockZ());
            RandomSource random=RandomSource.create(level.getSeed() ^ p.operation().getMostSignificantBits()
                    ^ ((long)pos.x*341873128712L) ^ ((long)pos.z*132897987541L));
            p.start().placeInChunk(level,level.structureManager(),level.getChunkSource().getGenerator(),
                random,clip,pos);
            if(pos.equals(p.start().getChunkPos()))
                chunk.setStartForStructure(p.structure(),p.start());
            chunk.addReferenceForStructure(p.structure(),p.start().getChunkPos().toLong());
            chunk.setUnsaved(true);
            int index=0;long delta=0;
            var samples=new ArrayList<ChangedBlock>();
            for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++) {
                var after=level.getBlockState(cursorBlock.set(x,y,z));
                if(!before.get(index).equals(after)) {
                    delta++;
                    if(samples.size()<16)samples.add(new ChangedBlock(x,y,z,
                        fr.proutlost.worldprep.runtime.BlockMutationRuntime.stateString(after)));
                }
                index++;
            }
            changedBlocks+=delta;
            store.write("native-chunk-placed-"+cursor,new ChunkDelta(pos.x,pos.z,delta,samples));
            cursor++;
            leases.close();leases=null;
            if(done())store.write("native-all-chunks-placed",receipt());
            return done();
        }
        @Override public void close(){
            if(leases!=null){leases.close();leases=null;}
        }
    }
}
