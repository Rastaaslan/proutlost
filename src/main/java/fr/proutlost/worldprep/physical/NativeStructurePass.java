package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.*;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.util.*;

/** Exact template cells only, never a village/jigsaw/registry structure approximation.
 * Entities, processors and randomized palettes are refused; igloo empty bed/furnace NBT is explicit.
 * All intent cells are durable before the first write; restart revalidates the whole finite plan.
 */
public final class NativeStructurePass {
    public static final String ADAPTER="exact-static-template-v1";
    /** Ordinary candidate mismatch, not a broken template or internal failure. */
    public static final class IneligibleSite extends IllegalArgumentException {
        public IneligibleSite(String reason){super(reason);}
    }
    /** Ordinary occupied/protected site; still fatal if discovered during mutation. */
    public static final class SiteConflict extends IllegalStateException {
        public SiteConflict(String reason){super(reason);}
    }
    public static final Set<String> TEMPLATES=Set.of("proutlost:qualification/platform", "proutlost:worldprep/stone_platform_v1", "minecraft:igloo/top");
    private static final Set<String> BLOCKS=Set.of("minecraft:air","minecraft:stone","minecraft:cobblestone",
        "minecraft:mossy_cobblestone","minecraft:dirt","minecraft:grass_block","minecraft:dirt_path",
        "minecraft:gravel","minecraft:oak_planks","minecraft:snow_block","minecraft:ice",
        "minecraft:crafting_table","minecraft:oak_trapdoor","minecraft:redstone_torch",
        "minecraft:white_carpet","minecraft:light_gray_carpet","minecraft:furnace","minecraft:red_bed");
    public record Cell(int x,int y,int z,String before,String after,String blockEntity,String pristineBlockEntity) {
        public Cell(int x,int y,int z,String before,String after){this(x,y,z,before,after,null,null);}
    }
    public record Plan(int version,UUID operation,String dimension,WorldPrepSavedData.Area area,String template,
                       String adapter,String templateSha256,String rotation,String mirror,String config,List<Cell> cells,List<Cell> supports) {
        public Plan { cells=List.copyOf(cells);supports=List.copyOf(supports);if(supports.size()>4096)throw new IllegalArgumentException("Support cap");if(version!=1||cells.isEmpty()||cells.size()>4096)throw new IllegalArgumentException("Finite exact plan required"); }
    }
    private final Plan plan;
    private final PhysicalStore store;
    private int cursor;
    private boolean rollback;
    public Plan plan(){return plan;}
    public int cursor(){return cursor;}
    public boolean done(){return cursor==plan.cells().size();}
    public NativeStructurePass(Plan plan,PhysicalStore store)throws IOException {
        this.plan=plan;this.store=store;
        store.write("plan",plan);
        rollback=store.exists("rollback-intent");
        // Do not trust a saved cursor as chunk durability. Reconcile from zero after restart.
    }
    /** Original allowlisted path: preserved byte-for-byte in behavior. */
    public static Plan plan(ServerLevel level,WorldPrepSavedData.Area area,String template,BlockPos origin,
                            Rotation rotation,Mirror mirror,String frozenConfig)throws IOException {
        return planChecked(level,area,template,origin,rotation,mirror,frozenConfig,null);
    }
    /** Opt-in post-ecology path. Only an EXACT_TEMPLATE capability with the pinned
     * adapter identity may extend the historic allowlist; physical safety checks remain.
     * This method never grants mutation, it only prepares the preflighted plan.
     */
    public static Plan planCatalogTemplate(ServerLevel level,WorldPrepSavedData.Area area,
                            fr.proutlost.worldprep.structure.StructureCapability capability,
                            BlockPos origin,Rotation rotation,Mirror mirror,String frozenConfig)throws IOException {
        java.util.Objects.requireNonNull(capability);
        if(capability.status()!=fr.proutlost.worldprep.structure.StructureSupportStatus.EXACT_TEMPLATE
                || !ADAPTER.equals(capability.adapterFingerprint()))
            throw new IllegalArgumentException("Unsupported catalog structure adapter");
        return planChecked(level,area,capability.structureId(),origin,rotation,mirror,frozenConfig,capability);
    }
    private static Plan planChecked(ServerLevel level,WorldPrepSavedData.Area area,String template,BlockPos origin,
                            Rotation rotation,Mirror mirror,String frozenConfig,
                            fr.proutlost.worldprep.structure.StructureCapability approved)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(!TEMPLATES.contains(template)&&approved==null)
            throw new IllegalArgumentException("UNSUPPORTED_OPAQUE or non-allowlisted exact template: "+template);
        var nativeTemplate=level.getStructureManager().get(ResourceLocation.parse(template)).orElseThrow(()->new IllegalArgumentException("Missing exact template"));
        var size=nativeTemplate.getSize();
        if(size.getX()<1||size.getY()<1||size.getZ()<1||(long)size.getX()*size.getY()*size.getZ()>4096)
            throw new IllegalArgumentException("Template envelope exceeds 4096 cells");
        if(approved!=null && (size.getX()!=approved.width()||size.getY()!=approved.height()
                ||size.getZ()!=approved.depth()))
            throw new IllegalArgumentException("Catalog dimensions differ from loaded native template");
        CompoundTag nbt=nativeTemplate.save(new CompoundTag());
        if(!nbt.getList("entities",Tag.TAG_COMPOUND).isEmpty()||nbt.contains("palettes"))throw new IllegalArgumentException("Template entities/random palettes unsupported");
        if(template.equals("minecraft:igloo/top")) {
            // Ice/snow are accepted only in their cold habitat; no thawing approximation.
            ServerExistingChunkAccess.requireBlock(level,area,origin.getX(),origin.getY(),origin.getZ());
            if(level.getBiome(origin).value().getBaseTemperature()>0.15f)
                throw new IneligibleSite("Igloo template requires a cold biome");
        }
        String digest=PhysicalStore.sha(nbt.toString());
        var palette=nbt.getList("palette",Tag.TAG_COMPOUND);
        var states=new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for(int i=0;i<palette.size();i++) {
            var state=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),palette.getCompound(i));
            String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if((state.hasBlockEntity()&&(!template.equals("minecraft:igloo/top")||(!id.equals("minecraft:furnace")&&!id.equals("minecraft:red_bed"))))||!BLOCKS.contains(id)||!state.getFluidState().isEmpty())throw new IllegalArgumentException("Unsupported palette state: "+id);
            // Falling blocks are not stable under neighbor updates. Gravel is explicitly refused.
            if(state.getBlock() instanceof FallingBlock)throw new IllegalArgumentException("Falling template block refused");
            states.add(state.mirror(mirror).rotate(rotation));
        }
        // Validate every envelope column, including template holes; never read an unloaded neighbor.
        var data=WorldPrepRuntime.data(level);
        var supports=new ArrayList<Cell>();
        for(int x=0;x<size.getX();x++)for(int z=0;z<size.getZ();z++) {
            var pos=StructureTemplate.transform(new BlockPos(x,-1,z),mirror,rotation,BlockPos.ZERO).offset(origin);
            requireSafe(level,data,area,pos);
            if(template.equals("minecraft:igloo/top")&&level.getBiome(pos.above()).value().getBaseTemperature()>0.15f)
                throw new IneligibleSite("Warm biome inside snow/ice footprint");
            var support=level.getBlockState(pos);
            if(!support.isCollisionShapeFullBlock(level,pos)||!support.getFluidState().isEmpty())throw new IneligibleSite("Exact template requires flat stable foundation");
            String state=BlockMutationRuntime.stateString(support);supports.add(new Cell(pos.getX(),pos.getY(),pos.getZ(),state,state));
        }
        for(int x=0;x<size.getX();x++)for(int y=0;y<size.getY();y++)for(int z=0;z<size.getZ();z++) {
            var pos=StructureTemplate.transform(new BlockPos(x,y,z),mirror,rotation,BlockPos.ZERO).offset(origin);
            requireSafe(level,data,area,pos);
        }
        var cells=new ArrayList<Cell>();var seen=new HashSet<BlockPos>();
        var blocks=nbt.getList("blocks",Tag.TAG_COMPOUND);
        if(blocks.size()>4096)throw new IllegalArgumentException("Oversized template block list");
        for(int i=0;i<blocks.size();i++) {
            var entry=blocks.getCompound(i);
            var coords=entry.getList("pos",Tag.TAG_INT);
            if(coords.size()!=3)throw new IllegalArgumentException("Invalid template position");
            int x=coords.getInt(0),y=coords.getInt(1),z=coords.getInt(2);
            if(x<0||y<0||z<0||x>=size.getX()||y>=size.getY()||z>=size.getZ())throw new IllegalArgumentException("Template cell outside envelope");
            var pos=StructureTemplate.transform(new BlockPos(x,y,z),mirror,rotation,BlockPos.ZERO).offset(origin);
            if(!seen.add(pos))throw new IllegalArgumentException("Duplicate template cell");
            int index=entry.getInt("state");if(index<0||index>=states.size())throw new IllegalArgumentException("Bad palette index");
            var before=level.getBlockState(pos);var after=states.get(index);
            // Conservative: no replacement of unknown blocks, fluids, block entities or solid terrain.
            if(!before.isAir())throw new IneligibleSite("Existing content in template envelope");
            String entity=null,pristine=null;
            if(entry.contains("nbt")||after.hasBlockEntity()) {
                if(!template.equals("minecraft:igloo/top")||!after.hasBlockEntity()||!entry.contains("nbt",Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Template BlockEntity NBT refused");
                entity=ExactTemplateBlockEntities.normalized(level,pos,after,entry.getCompound("nbt"));
                pristine=ExactTemplateBlockEntities.pristine(level,pos,after);
            }
            cells.add(new Cell(pos.getX(),pos.getY(),pos.getZ(),BlockMutationRuntime.stateString(before),BlockMutationRuntime.stateString(after),entity,pristine));
        }
        // Missing template cells remain untouched but are retained as clearance guards.
        for(int x=0;x<size.getX();x++)for(int y=0;y<size.getY();y++)for(int z=0;z<size.getZ();z++) {
            var pos=StructureTemplate.transform(new BlockPos(x,y,z),mirror,rotation,BlockPos.ZERO).offset(origin);
            if(seen.add(pos)) {
                var current=level.getBlockState(pos);
                if(!current.isAir())throw new IneligibleSite("Existing content in template hole");
                String state=BlockMutationRuntime.stateString(current);cells.add(new Cell(pos.getX(),pos.getY(),pos.getZ(),state,state));
            }
        }
        String identity=level.getSeed()+"/"+level.dimension().location()+"/"+area.id()+"/"+template+"/"+digest+"/"+origin+"/"+rotation+"/"+mirror+"/"+frozenConfig;
        UUID id=UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Plan(1,id,area.dimension(),area,template,ADAPTER,digest,rotation.name(),mirror.name(),frozenConfig,cells,supports);
    }
    public static void requireSafe(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockPos pos) {
        requireSite(level,data,area,pos);
        if(level.getBlockState(pos).hasBlockEntity())throw new SiteConflict("Existing BlockEntity conflict");
    }
    public static void requireSite(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockPos pos) {
        var chunk=ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ());
        if(data.protectedBlock(area.dimension(),pos.getX(),pos.getZ())||!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty()
            )throw new SiteConflict("Protection/structure/BlockEntity conflict");
        var spawn=level.getSharedSpawnPos();
        if(Math.abs((long)pos.getX()-spawn.getX())<=16&&Math.abs((long)pos.getZ()-spawn.getZ())<=16)
            throw new SiteConflict("Spawn access reservation");
    }
    private static String entityKey(Cell c){return "be-"+c.x()+"_"+c.y()+"_"+c.z();}
    private void validateEntity(ServerLevel level,Cell cell)throws IOException {
        var pos=new BlockPos(cell.x(),cell.y(),cell.z());var entity=level.getBlockEntity(pos);
        if(cell.blockEntity()==null) {if(entity!=null)throw new IOException("Unexpected BlockEntity");return;}
        if(BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(cell.before())) {
            if(entity!=null)throw new IOException("Unexpected before BlockEntity");return;
        }
        if(entity==null)throw new IOException("Missing after BlockEntity");
        var actual=entity.saveWithFullMetadata(level.registryAccess());
        if(!ExactTemplateBlockEntities.matches(cell.blockEntity(),actual)
            &&(store.exists(entityKey(cell))||!ExactTemplateBlockEntities.matches(cell.pristineBlockEntity(),actual)))throw new IOException("THIRD_STATE BlockEntity");
    }
    public static void verifyLiveCell(ServerLevel level,Cell cell)throws IOException {
        var pos=new BlockPos(cell.x(),cell.y(),cell.z());
        if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(cell.after()))throw new IOException("Live state changed");
        var entity=level.getBlockEntity(pos);
        if(cell.blockEntity()==null?entity!=null:entity==null||!ExactTemplateBlockEntities.matches(cell.blockEntity(),entity.saveWithFullMetadata(level.registryAccess())))
            throw new IOException("Live BlockEntity changed");
    }
    public void rollback()throws IOException {store.write("rollback-intent",plan.operation());rollback=true;cursor=0;}
    public void step(ServerLevel level,int budget)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(budget<1||budget>64)throw new IllegalArgumentException("Tick budget 1..64");
        var data=WorldPrepRuntime.data(level);
        for(var c:plan.supports()) {
            var pos=new BlockPos(c.x(),c.y(),c.z());requireSafe(level,data,plan.area(),pos);
            if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(c.before()))throw new IllegalStateException("Foundation changed since planning");
        }
        // Full finite footprint protection validation before any write in this tick.
        for(var c:plan.cells()) {
            var pos=new BlockPos(c.x(),c.y(),c.z());requireSite(level,data,plan.area(),pos);
            validateEntity(level,c);
            String current=BlockMutationRuntime.stateString(level.getBlockState(pos));
            if(!current.equals(c.before())&&!current.equals(c.after()))throw new IllegalStateException("THIRD_STATE at "+pos);
        }
        int end=Math.min(cursor+budget,plan.cells().size());
        for(;cursor<end;cursor++) {
            var c=plan.cells().get(rollback?plan.cells().size()-1-cursor:cursor);
            var pos=new BlockPos(c.x(),c.y(),c.z());String target=rollback?c.before():c.after();
            if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(target)) {
                if(!level.setBlock(pos,BlockMutationRuntime.decode(target),18))throw new IOException("Native block write refused");
                if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(target))throw new IOException("Native readback mismatch");
                ServerExistingChunkAccess.requireChunk(level,pos.getX()>>4,pos.getZ()>>4).setUnsaved(true);
            }
            if(!rollback&&c.blockEntity()!=null) {
                var entity=level.getBlockEntity(pos);
                if(entity==null)throw new IOException("Expected template BlockEntity missing");
                entity.loadWithComponents(ExactTemplateBlockEntities.parse(c.blockEntity()),level.registryAccess());
                entity.setChanged();
                if(!ExactTemplateBlockEntities.matches(c.blockEntity(),entity.saveWithFullMetadata(level.registryAccess())))
                    throw new IOException("BlockEntity readback mismatch");
                store.write(entityKey(c),Map.of("operation",plan.operation().toString(),"nbtSha256",PhysicalStore.sha(c.blockEntity())));
            }
        }
        // Small immutable checkpoints, no repeated cell metadata. Never called PLACED_VERIFIED.
        store.write((rollback?"rollback-":"apply-")+cursor,Map.of("cursor",cursor,"operation",plan.operation().toString(),"status",done()?(rollback?"ROLLED_BACK_MEMORY_ONLY":"MATERIALIZED_MEMORY_ONLY"):"RUNNING"));
    }
}
