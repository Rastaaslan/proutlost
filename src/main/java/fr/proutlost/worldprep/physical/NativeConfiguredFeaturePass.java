package fr.proutlost.worldprep.physical;

import com.mojang.serialization.JsonOps;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.TickPriority;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * Installed ConfiguredFeature execution with a finite detached world view.
 * Native features and their template processors choose the resulting cells.
 * Capture performs no world mutation, entity spawn, chunk generation or live tick scheduling.
 * Operator-sited configured features are not asserted to satisfy an outer PlacedFeature's
 * random/biome/river-placement modifiers. That policy is retained in every receipt.
 */
public final class NativeConfiguredFeaturePass {
    public static final String ADAPTER="native-configured-feature-capture-v1";
    public static final String POLICY="OPERATOR_SITED_CONFIGURED_FEATURE";
    private static final int MAX_CELLS=8192;
    private static final int MAX_READS=32768;
    private static final Set<String> FEATURE_CLASSES=Set.of(
        "com.yungnickyoung.minecraft.yungsbridges.world.feature.BridgeFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.desert.ChillzoneDesertFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.desert.DesertGiantTorchFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.desert.DesertObeliskFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.desert.DesertSmallRuinsFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.desert.DesertWellFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampArchFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampChurchFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampCubbyFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampDoubleArchFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampOgreFeature",
        "com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.SwampPillarFeature");

    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Bounds {
            if(minX>maxX||minY>maxY||minZ>maxZ
                ||(long)maxX-minX>47||(long)maxZ-minZ>47
                ||(maxX>>4)-(minX>>4)>2||(maxZ>>4)-(minZ>>4)>2
                ||(long)maxY-minY>383)
                throw new IllegalArgumentException("Feature reservation exceeds finite 3x3-center window");
        }
        public boolean contains(BlockPos p) {
            return p.getX()>=minX&&p.getX()<=maxX&&p.getY()>=minY&&p.getY()<=maxY
                &&p.getZ()>=minZ&&p.getZ()<=maxZ;
        }
    }
    public record Site(String modelId,String featureId,String templateId,int x,int y,int z,
                       Bounds reservation,Set<String> tags,String sitePolicy) {
        public Site {
            if(modelId==null||featureId==null||templateId==null||reservation==null
                ||!modelId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                ||!featureId.matches("(yungsbridges|yungsextras):[a-z0-9_./-]+")
                ||!templateId.matches("(yungsbridges|yungsextras):[a-z0-9_./-]+")
                ||!reservation.contains(new BlockPos(x,y,z))
                ||!POLICY.equals(sitePolicy)||tags==null||tags.size()>24
                ||!tags.contains("proutlost:structure"))
                throw new IllegalArgumentException("Invalid configured-feature site");
            for(var tag:tags)if(tag==null||!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid feature location tag");
            tags=Set.copyOf(tags);
        }
    }
    public record Cell(int x,int y,int z,String before,String after,String blockEntityNbt) {}
    public record ReadGuard(int x,int y,int z,String state) {}
    public record Scheduled(int x,int y,int z,String kind,String type,int delay,int priority) {}
    public record Plan(int schema,UUID operation,String dimension,Site site,String adapter,
                       String configuredFeatureSha256,String templateSha256,long randomSeed,
                       Bounds actualBounds,List<Cell> cells,List<ReadGuard> guards,
                       List<Scheduled> ticks,List<String> observedBiomes) {
        public Plan {
            cells=List.copyOf(cells);guards=List.copyOf(guards);ticks=List.copyOf(ticks);
            observedBiomes=List.copyOf(observedBiomes);
            if(schema!=1||cells.isEmpty()||cells.size()>MAX_CELLS||guards.size()>MAX_READS)
                throw new IllegalArgumentException("Invalid finite configured-feature plan");
        }
    }
    public record Receipt(int schema,UUID operation,String featureId,String planSha256,
                          int changedBlocks,int chunks,List<String> auditedChunks,String status) {}
    public record FeatureAnchor(UUID anchorId,int x,int y,int z,Set<String> tags) {}
    public record FeatureLocationRecord(int schema,UUID locationId,UUID operation,String dimension,
            String modelId,String configuredFeatureId,String templateId,String sitePolicy,
            String generationPlanSha256,String diskReceiptSha256,
            int minX,int minY,int minZ,int maxXExclusive,int maxYExclusive,int maxZExclusive,
            Set<String> tags,List<FeatureAnchor> anchors,List<String> observedBiomes,String status) {}

    private NativeConfiguredFeaturePass() {}

    public static void validateRegistry(ServerLevel level,Site site)throws IOException {
        var feature=level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
            .get(ResourceLocation.parse(site.featureId()));
        if(feature==null||!FEATURE_CLASSES.contains(feature.feature().getClass().getName()))
            throw new IOException("Missing or unaudited installed configured feature: "+site.featureId());
        var encoded=ConfiguredFeature.DIRECT_CODEC
            .encodeStart(RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),feature)
            .getOrThrow().getAsJsonObject().getAsJsonObject("config");
        String actualTemplate;
        if(encoded.has("location"))actualTemplate=encoded.get("location").getAsString();
        else actualTemplate=switch(feature.feature().getClass().getSimpleName()) {
            case "ChillzoneDesertFeature"->"yungsextras:desert/misc/chillzone";
            case "DesertGiantTorchFeature"->"yungsextras:desert/misc/giant_torch";
            case "DesertSmallRuinsFeature"->"yungsextras:desert/misc/ruins_0";
            default->throw new IOException("Cannot bind installed feature to a known native template");
        };
        if(!site.templateId().equals(actualTemplate))
            throw new IOException("Requested template does not match installed configured feature");
        var template=level.getStructureManager().get(ResourceLocation.parse(site.templateId()))
            .orElseThrow(()->new IOException("Configured feature template absent: "+site.templateId()));
        CompoundTag nbt=template.save(new CompoundTag());
        if(!nbt.getList("entities",Tag.TAG_COMPOUND).isEmpty())
            throw new IOException("Configured-feature capture does not accept mobile entities");
        var b=site.reservation();
        if(b.minY()<level.getMinBuildHeight()||b.maxY()>=level.getMaxBuildHeight()
            ||!level.getWorldBorder().isWithinBounds(b.minX(),b.minZ())
            ||!level.getWorldBorder().isWithinBounds(b.maxX(),b.maxZ()))
            throw new IOException("Configured-feature reservation outside world");
    }

    public static Plan capture(ServerLevel level,Site site,UUID batch,int index)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        validateRegistry(level,site);
        var feature=level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
            .get(ResourceLocation.parse(site.featureId()));
        String encoded=ConfiguredFeature.DIRECT_CODEC
            .encodeStart(RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),feature)
            .getOrThrow().toString();
        String templateNbt=level.getStructureManager().get(ResourceLocation.parse(site.templateId()))
            .orElseThrow().save(new CompoundTag()).toString();
        UUID operation=UUID.nameUUIDFromBytes(("worldprep-configured-feature-v1/"+batch+"/"+index+"/"
            +level.dimension().location()+"/"+site.modelId()+"/"+site.featureId()+"/"
            +site.x()+"/"+site.y()+"/"+site.z()).getBytes(StandardCharsets.UTF_8));
        long seed=level.getSeed()^operation.getMostSignificantBits()^operation.getLeastSignificantBits();
        Capture capture=new Capture(level,site.reservation(),seed);
        boolean accepted;
        try {
            accepted=feature.place(capture.world,level.getChunkSource().getGenerator(),
                RandomSource.create(seed),new BlockPos(site.x(),site.y(),site.z()));
        } catch(RuntimeException failure) {
            throw new IOException("Native configured-feature capture rejected "+site.featureId()+": "
                +failure.getMessage(),failure);
        }
        if(!accepted)throw new IOException("Native configured feature declined site: "+site.featureId());
        List<Cell> cells=new ArrayList<>();
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX;
        int maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        var biomes=new TreeSet<String>();
        for(var e:capture.after.entrySet()) {
            BlockPos pos=e.getKey();BlockState initial=capture.before.get(pos);BlockState state=e.getValue();
            BlockEntity entity=capture.entities.get(pos);
            String entityNbt=entity==null?null:entity.saveWithFullMetadata(level.registryAccess()).toString();
            if(initial.equals(state)&&entityNbt==null)continue;
            cells.add(new Cell(pos.getX(),pos.getY(),pos.getZ(),
                BlockMutationRuntime.stateString(initial),BlockMutationRuntime.stateString(state),entityNbt));
            minX=Math.min(minX,pos.getX());minY=Math.min(minY,pos.getY());minZ=Math.min(minZ,pos.getZ());
            maxX=Math.max(maxX,pos.getX());maxY=Math.max(maxY,pos.getY());maxZ=Math.max(maxZ,pos.getZ());
            biomes.add(level.getBiome(pos).unwrapKey().orElseThrow().location().toString());
        }
        if(cells.isEmpty())throw new IOException("Native configured feature produced no actual block difference");
        List<ReadGuard> guards=new ArrayList<>();
        var changed=new HashSet<BlockPos>();
        for(var c:cells)changed.add(new BlockPos(c.x(),c.y(),c.z()));
        for(var e:capture.before.entrySet())if(!changed.contains(e.getKey())) {
            var p=e.getKey();guards.add(new ReadGuard(p.getX(),p.getY(),p.getZ(),
                BlockMutationRuntime.stateString(e.getValue())));
        }
        return new Plan(1,operation,level.dimension().location().toString(),site,ADAPTER,
            PhysicalStore.sha(encoded),PhysicalStore.sha(templateNbt),seed,
            new Bounds(minX,minY,minZ,maxX,maxY,maxZ),cells,guards,capture.ticks,new ArrayList<>(biomes));
    }

    /** A finite virtual cell map. getLevel is used only by the audited native classes
     * for StructureTemplateManager/registry access; entity templates are rejected.
     * All terrain reads are required to be in existing loaded FULL chunks. */
    private static final class Capture implements InvocationHandler {
        final ServerLevel level;final Bounds bounds;final WorldGenLevel world;final RandomSource random;
        final LinkedHashMap<BlockPos,BlockState> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        final Map<BlockPos,BlockEntity> entities=new HashMap<>();
        final List<Scheduled> ticks=new ArrayList<>();
        Capture(ServerLevel level,Bounds bounds,long seed) {
            this.level=level;this.bounds=bounds;this.random=RandomSource.create(seed);
            this.world=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},this);
        }
        BlockPos require(BlockPos p) {
            BlockPos pos=p.immutable();
            if(!bounds.contains(pos))throw new IllegalStateException("Feature escaped reservation: "+pos);
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null||chunk.getPersistedStatus()!=net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                throw new IllegalStateException("Feature accessed an unloaded/non-FULL chunk: "+pos);
            return pos;
        }
        BlockState state(BlockPos p) {
            BlockPos pos=require(p);
            BlockState initial=before.get(pos);
            if(initial==null) {
                if(before.size()>=MAX_READS)throw new IllegalStateException("Feature capture read bound exceeded");
                if(level.getBlockEntity(pos)!=null)throw new IllegalStateException("Existing BlockEntity conflict at "+pos);
                initial=level.getBlockState(pos);before.put(pos,initial);
            }
            return after.getOrDefault(pos,initial);
        }
        BlockEntity entity(BlockPos p) {
            BlockPos pos=require(p);state(pos);return entities.get(pos);
        }
        boolean set(BlockPos p,BlockState target) {
            BlockPos pos=require(p);BlockState previous=state(pos);
            if(after.size()>=MAX_CELLS&&!after.containsKey(pos))
                throw new IllegalStateException("Feature capture cell bound exceeded");
            after.put(pos,target);
            if(!previous.equals(target)) {
                BlockEntity retained=entities.get(pos);
                if(retained!=null&&retained.getType().isValid(target))retained.setBlockState(target);
                else {
                    entities.remove(pos);
                    if(target.hasBlockEntity()) {
                        if(!(target.getBlock() instanceof EntityBlock factory))
                            throw new IllegalStateException("Feature block has no BlockEntity factory");
                        BlockEntity created=factory.newBlockEntity(pos,target);
                        if(created==null)throw new IllegalStateException("Native feature BlockEntity could not be captured");
                        // Keep level=null: setChanged() cannot mutate the live chunk during capture.
                        entities.put(pos,created);
                    }
                }
            }
            return true;
        }
        @SuppressWarnings({"unchecked","rawtypes"})
        @Override public Object invoke(Object proxy,Method method,Object[] incoming)throws Throwable {
            Object[] args=incoming==null?new Object[0]:incoming;
            String name=method.getName();
            if(method.getDeclaringClass()==Object.class) {
                return switch(name){case "toString"->"WorldPrepConfiguredFeatureCapture";
                    case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];
                    default->throw new IllegalStateException(name);};
            }
            for(Object arg:args)if(arg instanceof BlockPos pos)require(pos);
            switch(name) {
                case "getBlockState":return state((BlockPos)args[0]);
                case "getFluidState":return state((BlockPos)args[0]).getFluidState();
                case "isEmptyBlock":return state((BlockPos)args[0]).isAir();
                case "getBlockEntity":{
                    BlockEntity found=entity((BlockPos)args[0]);
                    if(args.length==2) return found!=null&&found.getType()==args[1]
                        ? Optional.of(found):Optional.empty();
                    return found;
                }
                case "isStateAtPosition":return ((Predicate)args[1]).test(state((BlockPos)args[0]));
                case "isFluidAtPosition":return ((Predicate)args[1]).test(state((BlockPos)args[0]).getFluidState());
                case "setBlock":return set((BlockPos)args[0],(BlockState)args[1]);
                case "removeBlock":return set((BlockPos)args[0],Blocks.AIR.defaultBlockState());
                case "ensureCanWrite":return true;
                case "getLevel":return level;
                case "getRandom":return random;
                case "isClientSide":return false;
                case "setCurrentlyGenerating":case "blockUpdated":return null;
                case "scheduleTick":{
                    if(ticks.size()>=8192)throw new IllegalStateException("Captured tick bound exceeded");
                    BlockPos pos=(BlockPos)args[0];Object type=args[1];
                    String kind=type instanceof Block?"block":type instanceof Fluid?"fluid":null;
                    if(kind==null)throw new IllegalStateException("Unsupported native scheduled tick type");
                    String id=type instanceof Block block?BuiltInRegistries.BLOCK.getKey(block).toString()
                        :BuiltInRegistries.FLUID.getKey((Fluid)type).toString();
                    TickPriority priority=args.length>=4?(TickPriority)args[3]:TickPriority.NORMAL;
                    ticks.add(new Scheduled(pos.getX(),pos.getY(),pos.getZ(),kind,id,(int)args[2],priority.getValue()));
                    return null;
                }
                case "getHeight":
                    if(args.length==3&&args[1] instanceof Integer x&&args[2] instanceof Integer z)
                        require(new BlockPos(x,bounds.minY(),z));
                    try{return method.invoke(level,args);}catch(InvocationTargetException e){throw e.getCause();}
                case "getBiome":case "registryAccess":case "dimensionType":case "enabledFeatures":
                case "getSeed":case "getSeaLevel":case "getMinBuildHeight":case "getMaxBuildHeight":
                case "getSectionsCount":case "getMinSection":case "getMaxSection":
                case "getSectionIndex":case "getSectionIndexFromSectionY":case "getSectionYFromSectionIndex":
                case "isOutsideBuildHeight":case "getWorldBorder":case "getCurrentDifficultyAt":
                case "getDifficulty":case "getShade":case "getMaxLocalRawBrightness":
                case "getBrightness":case "getRawBrightness":
                    try{return method.invoke(level,args);}catch(InvocationTargetException e){throw e.getCause();}
                default:throw new IllegalStateException("Unsupported captured WorldGenLevel call: "+method);
            }
        }
    }

    /** One finite feature commits only after its complete native output has been captured.
     * No automatic replay: an apply-intent left by a crash requires reconciliation. */
    public static void apply(ServerLevel level,Plan plan,PhysicalStore store)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(!plan.dimension().equals(level.dimension().location().toString()))
            throw new IOException("Feature world dimension changed");
        if(store.exists("feature-apply-intent"))
            throw new IOException("Feature was previously attempted; manual reconciliation required");
        for(var guard:plan.guards()) {
            var pos=new BlockPos(guard.x(),guard.y(),guard.z());
            if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(guard.state()))
                throw new IOException("Feature terrain guard changed since capture: "+pos);
        }
        for(var cell:plan.cells()) {
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());
            if(level.getBlockEntity(pos)!=null
                ||!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(cell.before()))
                throw new IOException("Feature cell changed since capture: "+pos);
        }
        store.write("feature-apply-intent",Map.of("operation",plan.operation().toString(),
            "cells",plan.cells().size(),"state","INTENT_BEFORE_MODIFICATION"));
        for(var cell:plan.cells()) {
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());
            BlockState target=BlockMutationRuntime.decode(cell.after());
            if(!level.setBlock(pos,target,18)
                &&!level.getBlockState(pos).equals(target))
                throw new IOException("Native feature materialization refused at "+pos);
        }
        for(var cell:plan.cells())if(cell.blockEntityNbt()!=null) {
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());var entity=level.getBlockEntity(pos);
            if(entity==null)throw new IOException("Missing materialized feature BlockEntity at "+pos);
            entity.loadWithComponents(ExactTemplateBlockEntities.parse(cell.blockEntityNbt()),level.registryAccess());
            entity.setChanged();
        }
        for(var tick:plan.ticks()) {
            BlockPos p=new BlockPos(tick.x(),tick.y(),tick.z());TickPriority priority=TickPriority.byValue(tick.priority());
            if(tick.kind().equals("block"))
                level.scheduleTick(p,BuiltInRegistries.BLOCK.get(ResourceLocation.parse(tick.type())),tick.delay(),priority);
            else level.scheduleTick(p,BuiltInRegistries.FLUID.get(ResourceLocation.parse(tick.type())),tick.delay(),priority);
        }
        for(var cell:plan.cells()) {
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());
            level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4).setUnsaved(true);
            if(!BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(cell.after()))
                throw new IOException("Feature immediate readback mismatch at "+pos);
        }
        store.write("feature-materialized",Map.of("operation",plan.operation().toString(),
            "changedBlocks",plan.cells().size(),"state","MATERIALIZED_NOT_DISK_VERIFIED"));
    }

    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"worldprep-feature-readonly-audit");t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    public static final class Audit {
        final Plan plan;final PhysicalStore store;
        final List<ChunkPos> chunks;final List<String> audited=new ArrayList<>();
        CompletableFuture<CompoundTag> pending;int cursor;
        public Audit(Plan plan,PhysicalStore store)throws IOException {
            this.plan=plan;this.store=store;
            if(!store.exists("feature-materialized"))throw new IOException("No complete feature materialization");
            var unique=new TreeSet<Long>();
            for(var c:plan.cells())unique.add(ChunkPos.asLong(c.x()>>4,c.z()>>4));
            this.chunks=unique.stream().map(ChunkPos::new).toList();
        }
        public boolean step(ServerLevel level)throws IOException {
            if(cursor==chunks.size())return true;
            ChunkPos pos=chunks.get(cursor);
            if(pending==null) {
                var region=DimensionType.getStorageFolder(level.dimension(),
                    level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
                pending=CompletableFuture.supplyAsync(()->{
                    try{return ReadOnlyChunkNbt.read(region,pos.x,pos.z);}
                    catch(IOException e){throw new CompletionException(e);}
                },IO);return false;
            }
            if(!pending.isDone())return false;
            CompoundTag nbt;
            try{nbt=pending.join();}catch(CompletionException e){throw new IOException("Feature disk read failed",e.getCause());}
            if(!"minecraft:full".equals(nbt.getString("Status"))&&!"full".equals(nbt.getString("Status")))
                throw new IOException("Feature disk chunk is not FULL");
            Map<Integer,PalettedContainer<BlockState>> states=new HashMap<>();
            var sections=nbt.getList("sections",Tag.TAG_COMPOUND);
            for(int i=0;i<sections.size();i++) {
                var section=sections.getCompound(i);
                if(section.contains("block_states"))states.put((int)section.getByte("Y"),
                    PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,
                        PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState())
                        .parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow());
            }
            Map<BlockPos,CompoundTag> blockEntities=new HashMap<>();
            var bes=nbt.getList("block_entities",Tag.TAG_COMPOUND);
            for(int i=0;i<bes.size();i++) {
                var be=bes.getCompound(i);blockEntities.put(new BlockPos(be.getInt("x"),be.getInt("y"),be.getInt("z")),be);
            }
            int matched=0;
            for(var cell:plan.cells())if((cell.x()>>4)==pos.x&&(cell.z()>>4)==pos.z) {
                var s=states.get(cell.y()>>4);
                BlockState state=s==null?Blocks.AIR.defaultBlockState():s.get(cell.x()&15,cell.y()&15,cell.z()&15);
                BlockPos p=new BlockPos(cell.x(),cell.y(),cell.z());
                if(!cell.after().equals(BlockMutationRuntime.stateString(state))
                    ||!cell.after().equals(BlockMutationRuntime.stateString(level.getBlockState(p))))
                    throw new IOException("Feature cell does not match disk/live: "+p);
                var entity=blockEntities.get(p);
                if(cell.blockEntityNbt()==null?entity!=null:entity==null
                    ||!ExactTemplateBlockEntities.matches(cell.blockEntityNbt(),withoutPacking(entity)))
                    throw new IOException("Feature BlockEntity does not match disk: "+p);
                matched++;
            }
            if(matched==0)throw new IOException("Feature audit chunk has no planned cells");
            audited.add(pos.x+","+pos.z);cursor++;pending=null;
            if(cursor==chunks.size())store.write("feature-disk-verified",
                new Receipt(1,plan.operation(),plan.site().featureId(),PhysicalStore.sha(store.readText("feature-plan")),
                    plan.cells().size(),chunks.size(),List.copyOf(audited),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"));
            return cursor==chunks.size();
        }
        private static CompoundTag withoutPacking(CompoundTag input) {
            CompoundTag copy=input.copy();copy.remove("keepPacked");return copy;
        }
    }

    public static FeatureLocationRecord publish(Plan plan,PhysicalStore store)throws IOException {
        var receipt=store.read("feature-disk-verified",Receipt.class);
        String planSha=PhysicalStore.sha(store.readText("feature-plan"));
        if(!receipt.operation().equals(plan.operation())||!receipt.planSha256().equals(planSha)
            ||receipt.changedBlocks()!=plan.cells().size()
            ||!receipt.status().equals("PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED"))
            throw new IOException("Feature publication has inconsistent independent evidence");
        UUID id=UUID.nameUUIDFromBytes(("worldprep-feature-location-v1/"+plan.operation()+"/"
            +plan.site().featureId()).getBytes(StandardCharsets.UTF_8));
        UUID anchor=UUID.nameUUIDFromBytes(("worldprep-feature-anchor-center-v1/"+id).getBytes(StandardCharsets.UTF_8));
        Bounds b=plan.actualBounds();
        var center=new FeatureAnchor(anchor,b.minX()+(b.maxX()-b.minX())/2,b.minY(),
            b.minZ()+(b.maxZ()-b.minZ())/2,Set.of("proutlost:anchor_center"));
        var location=new FeatureLocationRecord(1,id,plan.operation(),plan.dimension(),
            plan.site().modelId(),plan.site().featureId(),plan.site().templateId(),plan.site().sitePolicy(),
            planSha,PhysicalStore.sha(store.readText("feature-disk-verified")),
            b.minX(),b.minY(),b.minZ(),b.maxX()+1,b.maxY()+1,b.maxZ()+1,
            plan.site().tags(),List.of(center),plan.observedBiomes(),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
        store.write("director-feature-location-candidate",location);
        return location;
    }
}
