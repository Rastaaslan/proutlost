package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.entity.EntityTypeTest;
import java.io.IOException;
import java.util.*;

/** One immutable initial population plan. Missing issued entities are NEVER respawned.
 * Crash between intent and receipt quarantines a missing UUID: absence cannot distinguish
 * lost chunk save, migration, unloading or death. Reconciliation is deliberately conservative.
 */
public final class NativeFaunaPass {
    public record Rule(String species,Set<String> biomes,Set<String> floors,int minY,int maxY,
                       float minTemperature,float maxTemperature,int waterDepth,int globalLimit,
                       int speciesLimit,int chunkLimit,int separation,boolean persistent,int regionLimit,int habitatLimit) {
        public Rule(String species,Set<String> biomes,Set<String> floors,int minY,int maxY,float minTemperature,float maxTemperature,
                    int waterDepth,int globalLimit,int speciesLimit,int chunkLimit,int separation,boolean persistent) {
            this(species,biomes,floors,minY,maxY,minTemperature,maxTemperature,waterDepth,globalLimit,speciesLimit,chunkLimit,separation,persistent,speciesLimit,globalLimit);
        }
        public Rule {
            // Missing fields in retained v1 plans keep the original conservative global/species limits.
            if(regionLimit==0)regionLimit=speciesLimit;if(habitatLimit==0)habitatLimit=globalLimit;
            biomes=Collections.unmodifiableSortedSet(new TreeSet<>(biomes));floors=Collections.unmodifiableSortedSet(new TreeSet<>(floors));
            if(biomes.isEmpty()||biomes.size()>128||floors.isEmpty()||floors.size()>128||minY>maxY
                ||!Float.isFinite(minTemperature)||!Float.isFinite(maxTemperature)||minTemperature>maxTemperature
                ||waterDepth<0||waterDepth>32||globalLimit<1||globalLimit>256||speciesLimit<1||speciesLimit>globalLimit
                ||regionLimit<1||regionLimit>globalLimit||habitatLimit<1||habitatLimit>globalLimit
                ||chunkLimit<1||chunkLimit>speciesLimit||separation<1||separation>128)
                throw new IllegalArgumentException("Invalid finite fauna rule");
            ResourceLocation.parse(species);
        }
    }
    /** Candidate-only mismatch. Never use for malformed rules, corrupt state or runtime failures. */
    public static final class UnsuitableHabitat extends IllegalArgumentException {
        public UnsuitableHabitat(String reason){super(reason);}
    }
    public record Animal(UUID uuid,int x,int y,int z) {}
    public record Plan(int version,UUID operation,WorldPrepSavedData.Area area,String config,Rule rule,List<Animal> animals) {
        public Plan {animals=List.copyOf(animals);if(version!=1||animals.isEmpty()||animals.size()>64)throw new IllegalArgumentException("Population group cap 64");}
    }
    private final PhysicalStore store;
    private final Plan plan;
    private int cursor;
    public NativeFaunaPass(Plan plan,PhysicalStore store)throws IOException {this.plan=plan;this.store=store;store.write("plan",plan);}
    public Plan plan(){return plan;}
    public boolean done(){return cursor==plan.animals().size();}
    public int cursor(){return cursor;}
    public static Plan plan(ServerLevel level,WorldPrepSavedData.Area area,Rule rule,List<BlockPos> positions,String config) {
        NativePhysicalRuntime.requireThread(level);
        if(positions.isEmpty()||positions.size()>64||new HashSet<>(positions).size()!=positions.size())throw new IllegalArgumentException("Invalid finite group");
        if(positions.size()>rule.globalLimit()||positions.size()>rule.speciesLimit())throw new IllegalArgumentException("Initial group exceeds density budget");
        var chunkCounts=new HashMap<Long,Integer>();
        for(var position:positions) {
            long chunk=((long)(position.getX()>>4)<<32)^((position.getZ()>>4)&0xffffffffL);
            if(chunkCounts.merge(chunk,1,Integer::sum)>rule.chunkLimit())throw new IllegalArgumentException("Initial chunk density exceeded");
        }
        var sorted=positions.stream().sorted(Comparator.<BlockPos>comparingInt(pos -> pos.getX()).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY)).toList();
        UUID operation=UUID.nameUUIDFromBytes((level.getSeed()+"/"+area+"/"+config+"/"+rule+"/"+sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var animals=new ArrayList<Animal>();
        for(var pos:sorted) {
            validateHabitat(level,area,rule,pos);
            for(var prior:animals)if(distance(pos,prior.x(),prior.z())<(long)rule.separation()*rule.separation())throw new IllegalArgumentException("Group spacing conflict");
            animals.add(new Animal(UUID.nameUUIDFromBytes((operation+"/"+pos).getBytes(java.nio.charset.StandardCharsets.UTF_8)),pos.getX(),pos.getY(),pos.getZ()));
        }
        return new Plan(1,operation,area,config,rule,animals);
    }
    private static double distance(BlockPos p,int x,int z){double dx=(double)p.getX()-x,dz=(double)p.getZ()-z;return dx*dx+dz*dz;}
    private static EntityType<?> type(Rule rule) {
        var id=ResourceLocation.parse(rule.species());
        if(!BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw new IllegalArgumentException("Missing entity registry entry "+id);
        var type=BuiltInRegistries.ENTITY_TYPE.get(id);
        if(!Set.of(MobCategory.CREATURE,MobCategory.WATER_CREATURE,MobCategory.WATER_AMBIENT).contains(type.getCategory()))
            throw new IllegalArgumentException("Non-fauna entity category refused");
        return type;
    }
    public static void validateHabitat(ServerLevel level,WorldPrepSavedData.Area area,Rule rule,BlockPos pos) {
        type(rule);
        // All census chunks must already be loaded FULL; this pass never creates tickets.
        long chunks=(long)((area.maxX()>>4)-(area.minX()>>4)+1)*((area.maxZ()>>4)-(area.minZ()>>4)+1);
        if(chunks<1||chunks>64)throw new IllegalArgumentException("Fauna census area exceeds 64 chunks");
        if(!NativeHabitatOrigin.verticalEnvelopeFits(pos.getY(),level.getMinBuildHeight(),
                level.getMaxBuildHeight(),rule.waterDepth()))
            throw new UnsuitableHabitat("Native build-height habitat envelope unavailable");
        for(int x=area.minX()>>4;x<=area.maxX()>>4;x++)for(int z=area.minZ()>>4;z<=area.maxZ()>>4;z++)ServerExistingChunkAccess.requireChunk(level,x,z);
        NativeStructurePass.requireSafe(level,WorldPrepRuntime.data(level),area,pos);
        ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY()-1,pos.getZ());
        ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY()+2,pos.getZ());
        if(pos.getY()<rule.minY()||pos.getY()>rule.maxY())throw new UnsuitableHabitat("Altitude unsuitable");
        var biome=level.getBiome(pos);
        String biomeId=biome.unwrapKey().orElseThrow().location().toString();
        float temperature=biome.value().getBaseTemperature();
        if(!rule.biomes().contains(biomeId)||temperature<rule.minTemperature()||temperature>rule.maxTemperature())throw new UnsuitableHabitat("Biome/temperature unsuitable");
        if(rule.waterDepth()==0) {
            if(!rule.floors().contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock()).toString())
                ||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir())throw new UnsuitableHabitat("Ground/clearance unsuitable");
        } else {
            for(int i=0;i<rule.waterDepth();i++) {
                var water=pos.below(i);ServerExistingChunkAccess.requireBlock(level,area,water.getX(),water.getY(),water.getZ());
                if(!level.getFluidState(water).is(net.minecraft.tags.FluidTags.WATER))throw new UnsuitableHabitat("Water depth unsuitable");
            }
            var floor=pos.below(rule.waterDepth());ServerExistingChunkAccess.requireBlock(level,area,floor.getX(),floor.getY(),floor.getZ());
            if(!rule.floors().contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(floor).getBlock()).toString()))throw new UnsuitableHabitat("Aquatic substrate/depth unsuitable");
        }
        // Reuse the bounded habitat classifier and registry-driven suitability policy on actual terrain.
        int floorY=pos.getY()-(rule.waterDepth()==0?1:rule.waterDepth());
        var floorPos=new BlockPos(pos.getX(),floorY,pos.getZ());
        String floorId=BuiltInRegistries.BLOCK.getKey(level.getBlockState(floorPos).getBlock()).toString();
        boolean forest=false;
        for(int y=pos.getY()+2;y<Math.min(level.getMaxBuildHeight(),pos.getY()+9);y++) {
            var canopy=new BlockPos(pos.getX(),y,pos.getZ());ServerExistingChunkAccess.requireBlock(level,area,canopy.getX(),canopy.getY(),canopy.getZ());
            if(level.getBlockState(canopy).is(net.minecraft.tags.BlockTags.LEAVES))forest=true;
        }
        try {
            var habitat=fr.proutlost.worldprep.ecology.HabitatDiscovery.classify(List.of(
                new fr.proutlost.worldprep.ecology.HabitatDiscovery.Sample(pos.getX(),pos.getZ(),floorY,rule.waterDepth(),forest,false)),false).getFirst();
            var evidence=new fr.proutlost.worldprep.ecology.FaunaSuitability.Evidence(pos.getX(),pos.getY(),pos.getZ(),habitat.habitat(),biomeId,floorId,rule.waterDepth(),rule.waterDepth()==0?2:0,false);
            var policy=new fr.proutlost.worldprep.ecology.FaunaSuitability.InitialPolicy(rule.species(),
                rule.waterDepth()==0?Set.of("OPEN_GRASSLAND"):Set.of("SHALLOW_MARINE","MARINE","DEEP_MARINE"),
                rule.biomes(),rule.floors(),rule.waterDepth(),rule.waterDepth(),rule.waterDepth()==0?2:0);
            if(!fr.proutlost.worldprep.ecology.FaunaSuitability.evaluateInitial(evidence,Set.of(rule.species()),policy).equals("SUITABLE_BY_RULE"))
                throw new UnsuitableHabitat("Discovered habitat unsuitable");
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException("Habitat discovery interrupted",interrupted);}
    }
    private void density(ServerLevel level,Animal animal) {
        // Bounded live census, including pre-existing entities. Refuse excessive loaded populations.
        var all=new ArrayList<Entity>();
        level.getEntities(EntityTypeTest.forClass(Entity.class),e->true,all,4097);
        if(all.size()>4096)throw new IllegalStateException("Live census cap exceeded");
        int total=0,species=0,chunk=0;
        for(var e:all) {
            if(!(e instanceof Mob)||e.getUUID().equals(animal.uuid()))continue;
            if(!Set.of(MobCategory.CREATURE,MobCategory.WATER_CREATURE,MobCategory.WATER_AMBIENT).contains(e.getType().getCategory()))continue;
            // Global cap is conservative across all loaded entities, not just requested species.
            total++;
            if(e.getType()==type(plan.rule()))species++;
            if((e.blockPosition().getX()>>4)==(animal.x()>>4)&&(e.blockPosition().getZ()>>4)==(animal.z()>>4))chunk++;
            if(distance(new BlockPos(animal.x(),animal.y(),animal.z()),e.blockPosition().getX(),e.blockPosition().getZ())
                    <(long)plan.rule().separation()*plan.rule().separation())throw new IllegalStateException("Pre-existing entity spacing conflict");
        }
        if(total>=plan.rule().globalLimit()||species>=plan.rule().speciesLimit()||chunk>=plan.rule().chunkLimit())throw new IllegalStateException("Population density cap");
    }
    /** Crash-safe per-animal WAL fence, independently checkable after a fresh JVM.
     * An ISSUED receipt means the animal was once emitted, NOT that it is
     * still alive. Never re-create an issued animal when it is absent.
     * The corresponding intent must exist and name the exact pinned animal.
     */
    enum RetainedSpawn { NEW, INTENT_ONLY, ISSUED }
    static RetainedSpawn retainedSpawn(PhysicalStore store,Animal animal)throws IOException {
        String key=animal.uuid().toString();
        boolean intent=store.exists("intent-"+key);
        boolean issued=store.exists("issued-"+key);
        if(issued&&!intent)
            throw new IOException("ORPHAN_ISSUED_FAUNA_RECEIPT: missing write-ahead intent "+key);
        if(intent&&!animal.equals(store.read("intent-"+key,Animal.class)))
            throw new IOException("FOREIGN_FAUNA_INTENT: animal identity changed "+key);
        if(issued&&!animal.equals(store.read("issued-"+key,Animal.class)))
            throw new IOException("FOREIGN_FAUNA_ISSUED: animal identity changed "+key);
        return issued?RetainedSpawn.ISSUED:intent?RetainedSpawn.INTENT_ONLY:RetainedSpawn.NEW;
    }
    public void step(ServerLevel level,int budget)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(budget<1||budget>4)throw new IllegalArgumentException("Spawn budget 1..4");
        for(int n=0;n<budget&&!done();n++) {
            var animal=plan.animals().get(cursor);String key=animal.uuid().toString();
            var entity=level.getEntity(animal.uuid());
            if(entity!=null&&(!entity.getPersistentData().getString("proutlost_initial_operation").equals(plan.operation().toString())||entity.getType()!=type(plan.rule())))
                throw new IllegalStateException("Entity UUID ownership conflict");
            var retained=retainedSpawn(store,animal);
            if(retained==RetainedSpawn.ISSUED) {cursor++;continue;} // issued != currently alive
            if(retained==RetainedSpawn.INTENT_ONLY) {
                if(entity==null)throw new IllegalStateException("AMBIGUOUS_MISSING_ENTITY: no automatic respawn "+key);
                store.write("issued-"+key,animal);cursor++;continue;
            }
            if(entity!=null)throw new IllegalStateException("Entity exists without owned spawn intent");
            var pos=new BlockPos(animal.x(),animal.y(),animal.z());
            validateHabitat(level,plan.area(),plan.rule(),pos);density(level,animal);
            var created=type(plan.rule()).create(level);
            if(!(created instanceof Mob mob))throw new IllegalStateException("Registered fauna is not a Mob");
            mob.setUUID(animal.uuid());mob.moveTo(animal.x()+0.5,animal.y(),animal.z()+0.5,0,0);
            // Collision query may inspect neighboring chunks; preflight entire entity AABB first.
            var box=mob.getBoundingBox();
            if(!Double.isFinite(box.minX)||!Double.isFinite(box.maxX)||!Double.isFinite(box.minY)||!Double.isFinite(box.maxY)
                ||!Double.isFinite(box.minZ)||!Double.isFinite(box.maxZ)||box.getXsize()>4||box.getYsize()>4||box.getZsize()>4)
                throw new IllegalStateException("Unbounded/oversized entity collision envelope");
            ServerExistingChunkAccess.requireBlock(level,plan.area(),animal.x(),(int)Math.floor(box.maxY),animal.z());
            for(int x=(int)Math.floor(box.minX);x<=(int)Math.floor(box.maxX);x++)
                for(int z=(int)Math.floor(box.minZ);z<=(int)Math.floor(box.maxZ);z++)
                    ServerExistingChunkAccess.requireBlock(level,plan.area(),x,animal.y(),z);
            if(!level.noCollision(mob))throw new IllegalStateException("Entity collision");
            if(plan.rule().persistent())mob.setPersistenceRequired();
            mob.getPersistentData().putString("proutlost_initial_operation",plan.operation().toString());
            store.write("intent-"+key,animal); // forced to disk BEFORE native addFreshEntity
            if(!level.addFreshEntity(mob))throw new IOException("Native spawn refused after intent; reconcile explicitly");
            store.write("issued-"+key,animal);cursor++;
        }
        if(done())store.write("population-issued",Map.of("operation",plan.operation().toString(),"count",cursor,"status","INITIAL_ISSUED_NOT_DISK_VERIFIED"));
    }
}
