package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.phys.shapes.Shapes;
import java.util.function.*;

/** Actual one-slot animal spawn, for wave scheduling by the cohort runner.
 * Does not advertise completed migration/navigation. Existing or ambiguous slots never respawn.
 */
public final class FaunaSpawnBinding implements ServerEffectPort.Binding {
    private static final ModelCodec<EntityOwnershipRecord> CODEC=new ModelCodec<>(EntityOwnershipRecord.class);
    private static final ResourceId BLOB_CODEC=new ResourceId("proutlost_director:entity_slot");
    private final Function<DimensionId,ServerLevel> levels;
    private final Function<EffectIntent,EntityOwnershipRecord> slots;
    private final Consumer<EntityOwnershipRecord> changed;
    private final BooleanSupplier capsAvailable;
    private final Set<ResourceId> compatibleSpecies;
    private final boolean prepareForNavigation,prepareBrain;
    private final Set<OperationToken> capturedHere=new HashSet<>(),writeAttempted=new HashSet<>();
    /** Current-boot provider proof only; never a historical absence proof. */
    public boolean definitivelyNeverWritten(OperationToken op){return capturedHere.contains(op)&&!writeAttempted.contains(op);}
    /** Owner-bound MOVE lease from authorized SPAWN until the first granted NAV. */
    private static final class PreparationHold extends net.minecraft.world.entity.ai.goal.Goal {
        private final Animal animal;private final EntityOwnershipRecord owner;private boolean released;
        private final boolean brainControlled;private final WalkTarget ownedTarget;private final Path ownedPath;
        PreparationHold(Animal animal,EntityOwnershipRecord owner,boolean brainControlled){
            this.animal=Objects.requireNonNull(animal);this.owner=Objects.requireNonNull(owner);this.brainControlled=brainControlled;
            setFlags(EnumSet.of(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE));
            if(brainControlled){
                if(!FaunaNavigationBinding.idleBrain(animal))throw new IllegalStateException("native Brain not idle for prepared birth");
                var at=animal.blockPosition();ownedTarget=new WalkTarget(at,.1F,0);
                ownedPath=new Path(List.of(new Node(at.getX(),at.getY(),at.getZ())),at,true);
                animal.getBrain().setMemory(MemoryModuleType.PATH,ownedPath);
                animal.getBrain().setMemory(MemoryModuleType.WALK_TARGET,ownedTarget);
            }else{ownedTarget=null;ownedPath=null;}
        }
        private boolean ownsBrain(){
            return !brainControlled||!FaunaNavigationBinding.brainInterruption(animal)
                &&animal.getBrain().getMemory(MemoryModuleType.WALK_TARGET).filter(value->value==ownedTarget).isPresent()
                &&animal.getBrain().getMemory(MemoryModuleType.PATH).filter(value->value==ownedPath).isPresent();
        }
        private boolean active(){
            return !released&&ownsBrain()&&owner.phase()==EntityPhase.PRESENT
                &&owner.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&owner.adoptedBy().isEmpty()
                &&animal.isAlive()&&!animal.isLeashed()&&!animal.isPassenger()&&!animal.isVehicle()
                &&animal.getUUID().equals(owner.entityUuid())
                &&animal.level().dimension().location().toString().equals(owner.lastKnownChunk().dimension().value())
                &&EntityDirectorAttachment.read(animal).equals(Optional.of(owner));
        }
        @Override public boolean canUse(){return active();}
        @Override public boolean canContinueToUse(){return active();}
        @Override public boolean isInterruptable(){return false;}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void start(){if(active())animal.getNavigation().stop();}
        @Override public void tick(){if(!active())stop();}
        // Never stop a path installed after this hold lost ownership.
        @Override public void stop(){
            released=true;
            if(brainControlled){
                var brain=animal.getBrain();
                if(brain.getMemory(MemoryModuleType.WALK_TARGET).filter(value->value==ownedTarget).isPresent())brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                if(brain.getMemory(MemoryModuleType.PATH).filter(value->value==ownedPath).isPresent())brain.eraseMemory(MemoryModuleType.PATH);
            }
        }
    }
    private static void installPreparationHold(Animal animal,EntityOwnershipRecord owner,boolean brainControlled){
        var hold=new PreparationHold(animal,owner,brainControlled);animal.goalSelector.addGoal(0,hold);
        // Same pinned selector ordering as the navigation MoveLease: MOVE is held
        // before a vanilla MOVE goal can start on the first native AI update.
        var goals=animal.goalSelector.getAvailableGoals();
        var own=goals.stream().filter(g->g.getGoal()==hold).findFirst().orElseThrow();
        var rest=new ArrayList<>(goals);rest.remove(own);goals.clear();goals.add(own);goals.addAll(rest);
    }
    static boolean preparationHeld(Animal animal,EntityOwnershipRecord owner){
        return animal.goalSelector.getAvailableGoals().stream().anyMatch(wrapped->
            wrapped.getGoal() instanceof PreparationHold hold&&hold.brainControlled&&hold.owner.equals(owner)&&hold.active());
    }
    /** Release this precise captured preparation lease only; no attachment/WAL/entity mutation. */
    public static void releasePreparationHold(Animal animal,EntityOwnershipRecord expectedHoldOwner){
        Objects.requireNonNull(animal);Objects.requireNonNull(expectedHoldOwner);
        if(animal.level().getServer()==null||!animal.level().getServer().isSameThread())throw new IllegalStateException("server actor required");
        if(!animal.getUUID().equals(expectedHoldOwner.entityUuid()))return;
        for(var wrapped:new ArrayList<>(animal.goalSelector.getAvailableGoals()))
            if(wrapped.getGoal() instanceof PreparationHold hold&&hold.owner.equals(expectedHoldOwner)){
                hold.stop();animal.goalSelector.removeGoal(hold);
            }
    }
    private boolean positionSafe(ServerLevel level,EffectIntent intent){
        var a=args(intent);var p=position(intent);
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(a.species().value())).orElseThrow();
        var box=type.getDimensions().makeBoundingBox(p.x()+.5,p.y(),p.z()+.5);
        return level.getWorldBorder().isWithinBounds(box)&&level.noCollision(box)
            &&!level.containsAnyLiquid(box)&&level.isUnobstructed(null,Shapes.create(box));
    }
    public FaunaSpawnBinding(Function<DimensionId,ServerLevel> levels,Function<EffectIntent,EntityOwnershipRecord> slots,
                              Consumer<EntityOwnershipRecord> changed,BooleanSupplier capsAvailable,Set<ResourceId> compatibleSpecies){
        this(levels,slots,changed,capsAvailable,compatibleSpecies,true,false);
    }
    /** Spawn-only profiles preserve their mod's native Brain/AI. They must not claim navigation. */
    public FaunaSpawnBinding(Function<DimensionId,ServerLevel> levels,Function<EffectIntent,EntityOwnershipRecord> slots,
                              Consumer<EntityOwnershipRecord> changed,BooleanSupplier capsAvailable,
                              Set<ResourceId> compatibleSpecies,boolean prepareForNavigation){
        this(levels,slots,changed,capsAvailable,compatibleSpecies,prepareForNavigation,false);
    }
    /** Existing cohort preparation, using idle owned Brain memories for explicit Brain profiles. */
    public FaunaSpawnBinding(Function<DimensionId,ServerLevel> levels,Function<EffectIntent,EntityOwnershipRecord> slots,
                              Consumer<EntityOwnershipRecord> changed,BooleanSupplier capsAvailable,
                              Set<ResourceId> compatibleSpecies,boolean prepareForNavigation,boolean prepareBrain){
        this.levels=Objects.requireNonNull(levels);this.slots=Objects.requireNonNull(slots);this.changed=Objects.requireNonNull(changed);this.capsAvailable=Objects.requireNonNull(capsAvailable);this.compatibleSpecies=Set.copyOf(compatibleSpecies);
        if(this.compatibleSpecies.isEmpty())throw new IllegalArgumentException("explicit spawn species profile required");
        this.prepareForNavigation=prepareForNavigation;this.prepareBrain=prepareBrain;
        if(prepareBrain&&!prepareForNavigation)throw new IllegalArgumentException("Brain preparation requires a navigation hold");
    }
    public static void register(IEventBus bus){EntityDirectorAttachment.register(bus);}
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(new PrimitiveId("proutlost:fauna_spawn_owned"));}
    public Set<CapabilityId> capabilities(){return Set.of(new CapabilityId("entity.spawn_owned_v1"));}
    private ServerLevel level(DimensionId d){ServerLevel l=Objects.requireNonNull(levels.apply(d));if(!l.getServer().isSameThread())throw new IllegalStateException("server actor required");if(!l.dimension().location().toString().equals(d.value()))throw new IllegalArgumentException("dimension");return l;}
    private FaunaMigrate args(EffectIntent i){if(!i.primitive().equals(new PrimitiveId("proutlost:fauna_spawn_owned"))||!(i.typedArgs() instanceof FaunaMigrate a)||i.version()!=1||a.cohortSize()!=1||!compatibleSpecies.contains(a.species()))throw new IllegalArgumentException("one registered spawn slot required");return a;}
    private BlockPosition position(EffectIntent i){if(i.expectedFootprint().cells().size()!=1)throw new IllegalArgumentException("one spawn cell");return i.expectedFootprint().cells().iterator().next();}
    private static ResourceKey key(EntityOwnershipRecord slot){return new ResourceKey(OwnershipDomain.ENTITY,slot.lastKnownChunk().dimension(),slot.entityUuid().toString());}
    private static BlobContent blob(EntityOwnershipRecord slot){return BlobContent.capture(BLOB_CODEC,1,CODEC.encode(slot));}
    private static EntityOwnershipRecord read(BlobBundle blobs,BlobRef ref){if(!ref.codecId().equals(BLOB_CODEC)||ref.codecVersion()!=1)throw new IllegalArgumentException("slot codec");return CODEC.decode(blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow().bytes());}
    public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
        var args=args(intent);var p=position(intent);var level=level(p.dimension());var slot=Objects.requireNonNull(slots.apply(intent),"slot must be durably reserved");
        if(slot.phase()!=EntityPhase.RESERVED || slot.persistMode()!=args.arrivalPolicy() || !slot.instanceId().equals(intent.instanceId()) || slot.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE || slot.adoptedBy().isPresent()
                || !slot.lastKnownChunk().equals(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)) || !capsAvailable.getAsBoolean()
                || level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null || level.getEntity(slot.entityUuid())!=null)throw new IllegalArgumentException("spawn slot unavailable/uncertain");
        var terrain=context.snapshot().context().terrain().stream().map(t->t.cells().get(p)).filter(Objects::nonNull).findFirst().orElseThrow(()->new IllegalArgumentException("unknown habitat"));
        if(!terrain.knownContent()||terrain.protectedContent()||!terrain.hazards().isEmpty()||!terrain.tags().contains(args.corridorTag()))throw new IllegalArgumentException("unsafe habitat corridor");
        var after=new EntityOwnershipRecord(new AggregateHeader(1,slot.header().id(),slot.header().revision().plus(1),slot.header().lastSequence(),context.snapshot().capturedTick()),slot.ownerToken(),slot.entityUuid(),slot.instanceId(),slot.cohortId(),slot.slotId(),EntityPhase.PRESENT,slot.disposition(),slot.lastKnownChunk(),context.snapshot().capturedTick(),slot.persistMode(),slot.cleanupPolicy(),Optional.empty(),slot.generation());
        var pre=blob(slot);var post=blob(after);var key=key(slot);var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,slot.generation()),Map.of(key,pre.reference().hash()));
        var cost=new BudgetVector(0,0,0,1,1,1,0,0,0,0);var refs=new EffectBlobs(Set.of(pre.reference()),post.reference(),pre.reference(),Map.of(key,pre.reference()),Map.of(key,post.reference()));
        var plan=new PreparedEffect(intent,context.snapshot(),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,cost,Optional.empty(),slot.generation())),read,Map.of(key,post.reference().hash()),cost,List.of(),capabilities(),refs);
        capturedHere.add(intent.operationToken());
        return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.of(pre,post)));
    }
    public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
        args(plan.intent());var p=position(plan.intent());var level=level(p.dimension());var slot=slots.apply(plan.intent());
        // Rehydrated PRE/POST records must still be exactly the captured
        // durable pair. A matching live fingerprint alone does not authorize
        // a different child entity to be attached to the physical world.
        var recovered=durableSpawn(new RecoveryProbe(plan,List.of(),blobs));
        return recovered.isPresent() && slot!=null
                && slot.equals(recovered.orElseThrow().reserved())
                && capsAvailable.getAsBoolean()
                && level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null
                && level.getEntity(slot.entityUuid())==null&&positionSafe(level,plan.intent());
    }
    public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
        if(!revalidate(plan,blobs))throw new IllegalStateException("spawn changed before write");var args=args(plan.intent());var p=position(plan.intent());var level=level(p.dimension());var before=slots.apply(plan.intent());var key=key(before);
        EntityType<?> type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(args.species().value())).orElseThrow();
        Entity entity=type.create(level);if(!(entity instanceof Animal animal))throw new IllegalArgumentException("species lacks registered animal adapter");
        var durable=durableSpawn(new RecoveryProbe(plan,List.of(),blobs))
            .orElseThrow(()->new IllegalArgumentException("invalid durable spawn PRE/POST"));
        if(!durable.resource().equals(key) || !durable.reserved().equals(before))
            throw new IllegalArgumentException("spawn reserved identity substitution");
        var after=durable.present();
        animal.setUUID(after.entityUuid());animal.moveTo(p.x()+.5,p.y(),p.z()+.5,0,0);animal.setPersistenceRequired();
        if(!level.getWorldBorder().isWithinBounds(animal.getBoundingBox()) || !level.noCollision(animal) || !animal.checkSpawnRules(level,MobSpawnType.EVENT) || !animal.checkSpawnObstruction(level))throw new IllegalArgumentException("live spawn safety");
        EntityDirectorAttachment.initialize(animal,after);
        try{
            if(prepareForNavigation)installPreparationHold(animal,after,prepareBrain);
            writeAttempted.add(plan.intent().operationToken());
            if(!level.addFreshEntity(animal))throw new IllegalStateException("entity addition rejected; slot remains reserved for reconciliation");
            changed.accept(after);return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),Map.of(key,blob(after).reference().hash()),0,Set.of(),Set.of(),List.of());
        }catch(RuntimeException|Error failure){
            // An uncertain add is not erased or respawned. Release only our transient hold.
            releasePreparationHold(animal,after);throw failure;
        }
    }
    /** Reconciliation must never query the live slot provider. After a crash
     * that provider can be empty, advanced to another revision, or point at a
     * different animal. The immutable J04 PREPARE blobs are the only source
     * of the reserved UUID and expected attached post-state.
     */
    private record DurableSpawn(EntityOwnershipRecord reserved,
                                EntityOwnershipRecord present,ResourceKey resource) {}

    private static Optional<DurableSpawn> durableSpawn(RecoveryProbe probe) {
        try {
            var plan=probe.durableIntent();
            var refs=plan.durableBlobs();
            if(!(plan.intent().typedArgs() instanceof FaunaMigrate args)
                    || !plan.intent().primitive().equals(new PrimitiveId("proutlost:fauna_spawn_owned"))
                    || plan.intent().version()!=1 || args.cohortSize()!=1
                    || refs.preStates().size()!=1
                    || !refs.preStates().keySet().equals(refs.expectedPostStates().keySet())
                    || !refs.preStates().keySet().equals(plan.expectedPost().keySet())
                    || !refs.preStates().keySet().equals(plan.preconditions().fingerprints().keySet()))
                return Optional.empty();
            ResourceKey resource=refs.preStates().keySet().iterator().next();
            var before=probe.blobs().contents().stream()
                    .filter(b->b.reference().equals(refs.preStates().get(resource)))
                    .findFirst().orElseThrow();
            var after=probe.blobs().contents().stream()
                    .filter(b->b.reference().equals(refs.expectedPostStates().get(resource)))
                    .findFirst().orElseThrow();
            var reserved=read(probe.blobs(),before.reference());
            var present=read(probe.blobs(),after.reference());
            if(!FaunaSpawnWalFence.authenticated(refs,plan.preconditions(),
                    plan.expectedPost(),plan.claims(),plan.capabilities(),
                    resource,reserved.generation(),probe.blobs(),before,after))
                return Optional.empty();
            var position=plan.intent().expectedFootprint().cells();
            if(position.size()!=1 || reserved.persistMode()!=args.arrivalPolicy()
                    || !resource.equals(key(reserved))
                    || !resource.equals(key(present))
                    || !reserved.instanceId().equals(plan.intent().instanceId())
                    || !reserved.lastKnownChunk().dimension().equals(plan.intent().expectedFootprint().dimension())
                    || !reserved.lastKnownChunk().equals(new ChunkRef(
                        position.iterator().next().dimension(),
                        position.iterator().next().x()>>4,
                        position.iterator().next().z()>>4))
                    || !FaunaSpawnWalFence.validTransition(
                        reserved,present,plan.snapshot().capturedTick()))
                return Optional.empty();
            return Optional.of(new DurableSpawn(reserved,present,resource));
        } catch(RuntimeException invalidWalData) {
            return Optional.empty();
        }
    }

    public ReconcileResult reconcile(RecoveryProbe probe){
        try{args(probe.durableIntent().intent());}catch(RuntimeException unqualified){
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        }
        var durable=durableSpawn(probe);
        if(durable.isEmpty())
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var recovered=durable.orElseThrow();
        var p=position(probe.durableIntent().intent());
        // An unloaded original UUID is unknown, never proof of absence and
        // never permission to spawn a replacement animal.
        Entity entity=level(p.dimension()).getEntity(recovered.reserved().entityUuid());
        if(entity==null)
            return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
        // UUID/attachment alone is insufficient: an unrelated native type
        // cannot become a recovered one-slot animal spawn.
        var args=(FaunaMigrate)probe.durableIntent().intent().typedArgs();
        if(!(entity instanceof Animal animal) || !animal.isAlive()
                || !BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString()
                    .equals(args.species().value()))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var marker=EntityDirectorAttachment.read(entity);
        if(marker.isEmpty())
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var local=marker.orElseThrow();
        if(!local.header().id().equals(recovered.reserved().header().id())
                || local.persistMode()!=recovered.reserved().persistMode()
                || local.cleanupPolicy()!=recovered.reserved().cleanupPolicy()
                || !local.entityUuid().equals(recovered.reserved().entityUuid())
                || !local.ownerToken().equals(recovered.reserved().ownerToken())
                || !local.instanceId().equals(recovered.reserved().instanceId())
                || !local.cohortId().equals(recovered.reserved().cohortId())
                || !local.slotId().equals(recovered.reserved().slotId()))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        if(local.adoptedBy().isPresent()&&local.phase()==EntityPhase.ADOPTED
                &&local.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER
                &&local.generation().compareTo(recovered.present().generation())>0
                &&local.header().revision().compareTo(recovered.present().header().revision())>0)
            return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
        // Exact native attachment identity and full durable post-state are
        // required; an unrelated animal or just a matching token is not enough.
        boolean matches=entity.getUUID().equals(recovered.present().entityUuid())
                && local.equals(recovered.present());
        return new ReconcileResult(
            matches?ReconcileStatus.SAME_POST:ReconcileStatus.AMBIGUOUS,
            probe.loadedProofs(),List.of());
    }
}
