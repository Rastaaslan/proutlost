package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.EventHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Entity-only initial population. Every irreversible add has a forced immutable
 * intent. An issued or ambiguous animal is never silently recreated.
 * Native variants are finalized as a group and retained before the first add. */
public final class NativeFaunaModulePopulation {
    public static final String BATCH_TAG="proutlost_fauna_batch";
    public static final String OPERATION_TAG="proutlost_fauna_operation";
    public static final String REQUEST_TAG="proutlost_fauna_request";
    public static final String GROUP_TAG="proutlost_fauna_group";
    private static final Gson JSON=new Gson();

    public record Observed(UUID uuid,String species,String biome,double x,double y,double z) {}
    public record Animal(UUID uuid,WorldPrepFaunaModule.Position position,
                         NativeFaunaHabitatPolicy.HabitatEvidence habitat) {}
    public record Plan(UUID batch,String requestSha,int groupIndex,UUID operation,
                       WorldPrepFaunaModule.Group group,NativeFaunaHabitatPolicy.Profile profile,
                       List<Observed> baseline,List<Animal> animals) {
        public Plan {
            Objects.requireNonNull(batch);Objects.requireNonNull(operation);
            Objects.requireNonNull(group);Objects.requireNonNull(profile);
            baseline=List.copyOf(baseline);animals=List.copyOf(animals);
            if(groupIndex<0||baseline.size()>4096||animals.size()>64
                ||requestSha==null||!requestSha.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid bounded fauna plan");
        }
    }
    public record ReadyAnimal(UUID uuid,String nbt,String nbtSha) {}
    public record Initialization(UUID operation,String requestSha,List<ReadyAnimal> animals) {
        public Initialization {animals=List.copyOf(animals);}
    }
    public record Intent(UUID batch,UUID operation,String requestSha,String group,
                         String species,UUID uuid,WorldPrepFaunaModule.Position position,String nbtSha) {}
    public enum Retained { NEW, INTENT_ONLY, ISSUED }
    public static final class GroupNotReadyException extends IOException {
        public GroupNotReadyException(String message){super(message);}
    }

    static UUID operation(UUID batch,String group) {
        return UUID.nameUUIDFromBytes(("worldprep-fauna-v1/"+batch+"/"+group).getBytes(StandardCharsets.UTF_8));
    }
    static UUID animalId(UUID operation,WorldPrepFaunaModule.Position p) {
        return UUID.nameUUIDFromBytes((operation+"/"+p.x()+"/"+p.y()+"/"+p.z()).getBytes(StandardCharsets.UTF_8));
    }
    static long seed(Plan plan,Animal animal) {
        return plan.operation().getMostSignificantBits()^plan.operation().getLeastSignificantBits()
            ^animal.uuid().getMostSignificantBits()^animal.uuid().getLeastSignificantBits();
    }
    private static long seed(UUID operation,UUID animal) {
        return operation.getMostSignificantBits()^operation.getLeastSignificantBits()
            ^animal.getMostSignificantBits()^animal.getLeastSignificantBits();
    }

    public static Plan prepare(ServerLevel level,WorldPrepFaunaModule.Request request,String requestSha,
                               int index,List<Plan> earlier)throws IOException {
        var group=request.groups().get(index);
        var profile=WorldPrepFaunaModule.profile(request,group.species());
        var baseline=census(level,group,profile);
        var present=baseline.stream().filter(o->matches(group,o)).toList();
        int deficit=Math.max(0,group.targetCount()-present.size());
        if(baseline.size()+deficit>profile.localTotalLimit()
            ||baseline.stream().filter(o->o.species().equals(group.species())).count()+deficit>profile.localSpeciesLimit())
            throw new GroupNotReadyException("FAUNA_LOCAL_BUDGET: "+group.id());
        UUID operation=operation(request.batch(),group.id());
        List<Animal> chosen=new ArrayList<>();
        List<String> refused=new ArrayList<>();
        for(var p:group.candidates()) {
            if(chosen.size()==deficit)break;
            if(excluded(request,p,profile.species())||!separated(p,baseline,chosen,profile.minimumSeparation()))continue;
            boolean overlaps=false;
            for(var a:chosen)
                if(bodyOverlap(profile.species(),p,profile.species(),a.position()))overlaps=true;
            for(var old:earlier)for(var a:old.animals())
                if(distanceSquared(p,a.position())<square(Math.max(profile.minimumSeparation(),old.profile().minimumSeparation()))
                    ||bodyOverlap(profile.species(),p,old.profile().species(),a.position()))overlaps=true;
            if(overlaps)continue;
            UUID uuid=animalId(operation,p);
            if(level.getEntity(uuid)!=null)throw new IOException("FAUNA_UUID_ALREADY_PRESENT_BEFORE_INTENT: "+uuid);
            try {
                var habitat=NativeFaunaHabitatPolicy.validate(level,profile,p.block(),group.expectedBiome(),seed(operation,uuid));
                chosen.add(new Animal(uuid,p,habitat));
            } catch(IllegalArgumentException unsuitable) {
                if(refused.size()<8)refused.add(unsuitable.getMessage());
            }
        }
        if(chosen.size()!=deficit)
            throw new GroupNotReadyException("FAUNA_GROUP_NOT_READY "+group.id()+" need="+deficit+" eligible="+chosen.size()
                +" reasons="+refused+"; retained plans may resume after native conditions improve");
        return new Plan(request.batch(),requestSha,index,operation,group,profile,baseline,chosen);
    }

    public static void validateRetained(Plan p,WorldPrepFaunaModule.Request r,String sha,int index)throws IOException {
        var group=r.groups().get(index);var profile=WorldPrepFaunaModule.profile(r,group.species());
        if(!p.batch().equals(r.batch())||!p.requestSha().equals(sha)||p.groupIndex()!=index
            ||!p.operation().equals(operation(r.batch(),group.id()))||!p.group().equals(group)||!p.profile().equals(profile))
            throw new IOException("FAUNA_RETAINED_PLAN_IDENTITY_MISMATCH");
        Set<UUID> ids=new HashSet<>();
        for(var o:p.baseline())
            if(o==null||o.uuid()==null||o.species()==null||o.biome()==null
                ||!Double.isFinite(o.x())||!Double.isFinite(o.y())||!Double.isFinite(o.z())
                ||!ids.add(o.uuid()))throw new IOException("FAUNA_INVALID_BASELINE");
        int deficit=Math.max(0,group.targetCount()-(int)p.baseline().stream().filter(o->matches(group,o)).count());
        if(p.animals().size()!=deficit)throw new IOException("FAUNA_RETAINED_DEFICIT_MISMATCH");
        for(var a:p.animals())
            if(a==null||!group.candidates().contains(a.position())
                ||!animalId(p.operation(),a.position()).equals(a.uuid())||!ids.add(a.uuid())
                ||!a.habitat().species().equals(group.species())||!a.habitat().biome().equals(group.expectedBiome()))
                throw new IOException("FAUNA_INVALID_RETAINED_ANIMAL");
    }

    static List<Observed> census(ServerLevel level,WorldPrepFaunaModule.Group group,
                                 NativeFaunaHabitatPolicy.Profile profile)throws IOException {
        int radius=profile.localRadius();var center=group.center();
        List<Mob> mobs=new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(Mob.class),
            m->m.isAlive()&&NativeFaunaHabitatPolicy.isFaunaCategory(m.getType().getCategory())
                &&Math.abs(m.getX()-(center.x()+0.5))<=radius
                &&Math.abs(m.getZ()-(center.z()+0.5))<=radius,mobs,4097);
        if(mobs.size()>4096)throw new IOException("FAUNA_LOCAL_CENSUS_LIMIT");
        List<Observed> result=new ArrayList<>();
        for(var mob:mobs) {
            String biome=level.getBiome(mob.blockPosition()).unwrapKey().orElseThrow().location().toString();
            result.add(new Observed(mob.getUUID(),BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(),
                biome,mob.getX(),mob.getY(),mob.getZ()));
        }
        result.sort(Comparator.comparing(o->o.uuid().toString()));
        return List.copyOf(result);
    }
    static boolean matches(WorldPrepFaunaModule.Group group,Observed observed) {
        return group.species().equals(observed.species())&&group.expectedBiome().equals(observed.biome());
    }
    private static double square(double d){return d*d;}
    private static double distanceSquared(WorldPrepFaunaModule.Position a,WorldPrepFaunaModule.Position b) {
        return square((double)a.x()-b.x())+square((double)a.y()-b.y())+square((double)a.z()-b.z());
    }
    private static boolean separated(WorldPrepFaunaModule.Position p,List<Observed> existing,
                                      List<Animal> planned,double separation) {
        double sq=square(separation);
        for(var o:existing)
            if(square(p.x()+0.5-o.x())+square(p.y()-o.y())+square(p.z()+0.5-o.z())<sq)return false;
        for(var a:planned)if(distanceSquared(p,a.position())<sq)return false;
        return true;
    }
    private static boolean bodyOverlap(String species,WorldPrepFaunaModule.Position p,
                                       String otherSpecies,WorldPrepFaunaModule.Position q) {
        var a=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(species)).getSpawnAABB(p.x()+0.5,p.y(),p.z()+0.5);
        var b=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(otherSpecies)).getSpawnAABB(q.x()+0.5,q.y(),q.z()+0.5);
        return a.intersects(b);
    }
    private static boolean excluded(WorldPrepFaunaModule.Request r,WorldPrepFaunaModule.Position p,String species) {
        EntityType<?> type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(species));
        var box=type.getSpawnAABB(p.x()+0.5,p.y(),p.z()+0.5);
        for(var b:r.exclusions())
            if(box.maxX>b.minX()&&box.minX<b.maxX()+1.0&&box.maxY>b.minY()
                &&box.minY<b.maxY()+1.0&&box.maxZ>b.minZ()&&box.minZ<b.maxZ()+1.0)return true;
        return false;
    }

    /** Finalize all members once, retaining native group data and variants before any add. */
    public static Initialization initialize(ServerLevel level,Plan plan,PhysicalStore journal)throws IOException {
        if(journal.exists("native-finalized")) {
            var retained=journal.read("native-finalized",Initialization.class);
            validateInitialization(plan,retained);return retained;
        }
        SpawnGroupData groupData=null;List<ReadyAnimal> initialized=new ArrayList<>();
        for(var animal:plan.animals()) {
            NativeFaunaHabitatPolicy.validate(level,plan.profile(),animal.position().block(),
                plan.group().expectedBiome(),seed(plan,animal));
            Entity entity=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(plan.profile().species())).create(level);
            if(!(entity instanceof Mob mob))throw new IOException("FAUNA_FACTORY_NOT_MOB");
            var p=animal.position();mob.moveTo(p.x()+0.5,p.y(),p.z()+0.5,0,0);
            mob.getRandom().setSeed(seed(plan,animal));
            groupData=EventHooks.finalizeMobSpawn(mob,level,level.getCurrentDifficultyAt(p.block()),
                NativeFaunaHabitatPolicy.SPAWN_REASON,groupData);
            if(mob.isSpawnCancelled()||mob.isAddedToLevel()||mob.isRemoved()||mob.isPassenger()||!mob.getPassengers().isEmpty()
                ||!mob.blockPosition().equals(p.block()))
                throw new IOException("FAUNA_NATIVE_FINALIZATION_UNPLANNED_ENTITY_OR_POSITION");
            mob.setUUID(animal.uuid());
            if(plan.profile().persistent())mob.setPersistenceRequired();
            var data=mob.getPersistentData();
            data.putString(BATCH_TAG,plan.batch().toString());
            data.putString(OPERATION_TAG,plan.operation().toString());
            data.putString(REQUEST_TAG,plan.requestSha());
            data.putString(GROUP_TAG,plan.group().id());
            NativeFaunaHabitatPolicy.validateFinalizedBody(level,plan.profile(),mob,p.block());
            CompoundTag nbt=new CompoundTag();
            if(!mob.save(nbt))throw new IOException("FAUNA_NATIVE_ENTITY_NOT_SAVEABLE");
            String snbt=nbt.toString();
            if(snbt.length()>65536)throw new IOException("FAUNA_ENTITY_NBT_LIMIT");
            initialized.add(new ReadyAnimal(animal.uuid(),snbt,PhysicalStore.sha(snbt)));
        }
        var ready=new Initialization(plan.operation(),plan.requestSha(),initialized);
        validateInitialization(plan,ready);
        journal.write("native-finalized",ready);
        return ready;
    }
    static void validateInitialization(Plan plan,Initialization ready)throws IOException {
        if(!plan.operation().equals(ready.operation())||!plan.requestSha().equals(ready.requestSha())
            ||ready.animals().size()!=plan.animals().size())throw new IOException("FAUNA_INITIALIZATION_IDENTITY_MISMATCH");
        for(int i=0;i<ready.animals().size();i++) {
            var a=ready.animals().get(i);
            if(!a.uuid().equals(plan.animals().get(i).uuid())||a.nbt()==null||a.nbt().length()>65536
                ||!PhysicalStore.sha(a.nbt()).equals(a.nbtSha()))throw new IOException("FAUNA_INITIALIZATION_NBT_MISMATCH");
        }
    }
    static Intent intent(Plan plan,Animal animal,ReadyAnimal ready) {
        return new Intent(plan.batch(),plan.operation(),plan.requestSha(),plan.group().id(),
            plan.profile().species(),animal.uuid(),animal.position(),ready.nbtSha());
    }
    public static Retained retained(PhysicalStore journal,Intent expected)throws IOException {
        boolean intent=journal.exists("intent-"+expected.uuid()),issued=journal.exists("issued-"+expected.uuid());
        if(issued&&!intent)throw new IOException("FAUNA_ISSUED_WITHOUT_INTENT");
        if(intent&&!journal.read("intent-"+expected.uuid(),Intent.class).equals(expected))
            throw new IOException("FAUNA_RETAINED_INTENT_MISMATCH");
        if(issued&&!journal.read("issued-"+expected.uuid(),Intent.class).equals(expected))
            throw new IOException("FAUNA_RETAINED_ISSUED_MISMATCH");
        return issued?Retained.ISSUED:intent?Retained.INTENT_ONLY:Retained.NEW;
    }
    public static boolean allIssued(Plan plan,PhysicalStore journal)throws IOException {
        if(plan.animals().isEmpty())return true;
        if(!journal.exists("native-finalized"))return false;
        var ready=journal.read("native-finalized",Initialization.class);validateInitialization(plan,ready);
        boolean all=true;
        for(int i=0;i<plan.animals().size();i++)
            all&=retained(journal,intent(plan,plan.animals().get(i),ready.animals().get(i)))==Retained.ISSUED;
        return all;
    }

    /** One member, including recovery. Returns true only if addFreshEntity was actually called successfully. */
    public static boolean issue(ServerLevel level,WorldPrepFaunaModule.Request request,Plan plan,
                                Initialization ready,int index,PhysicalStore journal)throws IOException {
        Animal animal=plan.animals().get(index);ReadyAnimal finalized=ready.animals().get(index);
        Intent intent=intent(plan,animal,finalized);Retained state=retained(journal,intent);
        Entity existing=level.getEntity(animal.uuid());
        if(state==Retained.ISSUED) {
            if(existing!=null)requireOwned(existing,intent);
            return false; // issued is never a permission to resurrect a missing animal
        }
        if(state==Retained.INTENT_ONLY) {
            if(existing==null)throw new IOException("FAUNA_AMBIGUOUS_INTENT_MISSING_ENTITY "+animal.uuid()
                +"; audit persisted UUID before any explicit resume; no respawn");
            requireOwned(existing,intent);
            journal.write("issued-"+animal.uuid(),intent);return false;
        }
        if(existing!=null)throw new IOException("FAUNA_UUID_CONFLICT_BEFORE_INTENT "+animal.uuid());
        var current=census(level,plan.group(),plan.profile());
        long species=current.stream().filter(o->o.species().equals(plan.profile().species())).count();
        long habitat=current.stream().filter(o->matches(plan.group(),o)).count();
        if(current.size()+1>plan.profile().localTotalLimit()||species+1>plan.profile().localSpeciesLimit()
            ||habitat+1>plan.group().targetCount())
            throw new IOException("FAUNA_CURRENT_LOCAL_TARGET_OR_BUDGET_CHANGED "+plan.group().id());
        if(excluded(request,animal.position(),plan.profile().species())
            ||!separated(animal.position(),current,List.of(),plan.profile().minimumSeparation()))
            throw new IOException("FAUNA_CURRENT_SEPARATION_OR_EXCLUSION_REFUSED "+animal.uuid());
        NativeFaunaHabitatPolicy.validate(level,plan.profile(),animal.position().block(),
            plan.group().expectedBiome(),seed(plan,animal));
        Mob mob;
        try {
            CompoundTag nbt=TagParser.parseTag(finalized.nbt());
            Entity entity=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(plan.profile().species())).create(level);
            if(!(entity instanceof Mob created))throw new IOException("FAUNA_FACTORY_NOT_MOB");
            created.load(nbt);mob=created;
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException malformed) {
            throw new IOException("FAUNA_FINALIZED_NBT_MALFORMED",malformed);
        }
        requireOwned(mob,intent);
        if(!mob.blockPosition().equals(animal.position().block())||mob.isPassenger()||!mob.getPassengers().isEmpty())
            throw new IOException("FAUNA_FINALIZED_POSITION_OR_PASSENGER_MISMATCH");
        mob.getRandom().setSeed(seed(plan,animal));
        NativeFaunaHabitatPolicy.validateFinalizedBody(level,plan.profile(),mob,animal.position().block());
        if(mob.isSpawnCancelled())throw new IOException("FAUNA_FINALIZED_SPAWN_CANCELLED");
        journal.write("intent-"+animal.uuid(),intent);
        if(!level.addFreshEntity(mob))
            throw new IOException("FAUNA_NATIVE_ADD_REFUSED_WITH_RETAINED_INTENT "+animal.uuid());
        journal.write("issued-"+animal.uuid(),intent);
        return true;
    }
    static void requireOwned(Entity entity,Intent intent)throws IOException {
        var nbt=entity.getPersistentData();
        if(!(entity instanceof Mob mob)||!mob.isAlive()||!entity.getUUID().equals(intent.uuid())
            ||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(intent.species())
            ||!nbt.getString(BATCH_TAG).equals(intent.batch().toString())
            ||!nbt.getString(OPERATION_TAG).equals(intent.operation().toString())
            ||!nbt.getString(REQUEST_TAG).equals(intent.requestSha())||!nbt.getString(GROUP_TAG).equals(intent.group()))
            throw new IOException("FAUNA_UUID_OWNERSHIP_OR_LIFE_MISMATCH "+intent.uuid());
    }

    public static List<NativeFaunaModuleAudit.Expected> expected(Plan plan,PhysicalStore journal,
                                                                boolean requireAllIssued)throws IOException {
        List<NativeFaunaModuleAudit.Expected> result=new ArrayList<>();
        plan.baseline().stream().filter(o->matches(plan.group(),o)).limit(plan.group().targetCount())
            .forEach(o->result.add(new NativeFaunaModuleAudit.Expected(o.uuid(),o.species(),null,
                plan.group().id(),false,false)));
        if(plan.animals().isEmpty())return result;
        if(!journal.exists("native-finalized")) {
            if(requireAllIssued)throw new IOException("FAUNA_EXPECTED_INITIALIZATION_MISSING");
            return result;
        }
        var ready=journal.read("native-finalized",Initialization.class);validateInitialization(plan,ready);
        for(int i=0;i<plan.animals().size();i++) {
            var a=plan.animals().get(i);Retained state=retained(journal,intent(plan,a,ready.animals().get(i)));
            if(requireAllIssued&&state!=Retained.ISSUED)throw new IOException("FAUNA_EXPECTED_NOT_ISSUED "+a.uuid());
            if(state!=Retained.NEW)result.add(new NativeFaunaModuleAudit.Expected(a.uuid(),plan.profile().species(),
                plan.operation(),plan.group().id(),true,plan.profile().persistent()));
        }
        return List.copyOf(result);
    }
    private NativeFaunaModulePopulation() {}
}
