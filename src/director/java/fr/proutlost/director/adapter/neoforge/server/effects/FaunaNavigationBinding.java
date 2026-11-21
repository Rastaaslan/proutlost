package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import java.util.*;
import java.util.function.*;

/** Executes bounded, captured, walkable corridors on the SAME owned animal UUID.
 * Routes are provided as immutable cell plans, not random destinations or replacement spawns.
 * Native arrival is observed separately; starting navigation is never an arrival fact.
 */
public final class FaunaNavigationBinding implements ServerEffectPort.Binding {
 public record Route(EntityOwnershipRecord owner,List<BlockPosition> cells,double speed) {
  public Route {Objects.requireNonNull(owner);cells=List.copyOf(cells);if(!FaunaRouteGeometryFence.valid(cells,owner.lastKnownChunk().dimension(),owner.lastKnownChunk())||!Double.isFinite(speed)||speed<=0||speed>1)throw new IllegalArgumentException("route topology/speed bound");}
 }
 private static final ModelCodec<FaunaNavigationRoute> CODEC=new ModelCodec<>(FaunaNavigationRoute.class);
 private static final ResourceId CODEC_ID=new ResourceId("proutlost_director:animal_route");
 private final Function<DimensionId,ServerLevel> levels;
 private final Function<EffectIntent,Route> routes;
 private final Set<ResourceId> brainSpecies;
private final Map<UUID,MoveLease> moveLeases=new HashMap<>();

private static Path nativePath(Route route){
 var nodes=route.cells().stream().map(p->new Node(p.x(),p.y(),p.z())).toList();
 var end=route.cells().getLast();
 return new Path(nodes,new BlockPos(end.x(),end.y(),end.z()),true);
}
/** Preserve the installed Brain. Only reserve idle, registered native movement memories.
 * MoveToTargetSink requires PATH absent before starting; owning PATH keeps it from
 * replacing the exact sealed corridor with its own pathfinder/random fallback.
 * Panic, temptation, breeding and any foreign memory override always win. */
static boolean idleBrain(Animal animal){
 var brain=animal.getBrain();
 return brain.checkMemory(MemoryModuleType.WALK_TARGET,MemoryStatus.VALUE_ABSENT)
   &&brain.checkMemory(MemoryModuleType.PATH,MemoryStatus.VALUE_ABSENT)
   &&!brain.getRunningBehaviors().stream().anyMatch(MoveToTargetSink.class::isInstance)
   &&!brainInterruption(animal);
}
static boolean brainInterruption(Animal animal){
 var brain=animal.getBrain();
 return brain.hasMemoryValue(MemoryModuleType.IS_PANICKING)
   ||brain.hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER)
   ||brain.hasMemoryValue(MemoryModuleType.BREED_TARGET);
}
private static final class MoveLease extends Goal {
 private final Animal animal;private final Route route;private boolean released;private Path ownedPath;
 private final boolean brainControlled;private WalkTarget ownedWalkTarget;
 MoveLease(Animal animal,Route route,boolean brainControlled){
  this.animal=animal;this.route=route;this.brainControlled=brainControlled;setFlags(EnumSet.of(Goal.Flag.MOVE));
 }
 private boolean ownsBrain(){
  if(!brainControlled||ownedWalkTarget==null)return true;
  var brain=animal.getBrain();
  return !brainInterruption(animal)
    &&brain.getMemory(MemoryModuleType.WALK_TARGET).filter(value->value==ownedWalkTarget).isPresent()
    &&brain.getMemory(MemoryModuleType.PATH).filter(value->value==ownedPath).isPresent();
 }
 private boolean active(){
  return !released&&ownsBrain()&&animal.isAlive()&&!animal.isLeashed()&&!animal.isPassenger()&&!animal.isVehicle()
    &&animal.getUUID().equals(route.owner().entityUuid())
    &&EntityDirectorAttachment.read(animal).equals(Optional.of(route.owner()));
 }
 @Override public boolean canUse(){return active();}
 @Override public boolean canContinueToUse(){return active();}
 @Override public boolean isInterruptable(){return false;}
 @Override public boolean requiresUpdateEveryTick(){return true;}
 @Override public void tick(){if(!active())stop();}
 // Keep MOVE until explicit durable transfer/cleanup; arrival alone does not release it.
 // Navigation still advances normally in Mob.serverAiStep. No setNoAI and no teleports.
 @Override public void stop(){
  released=true;
  if(brainControlled&&ownedWalkTarget!=null){
   var brain=animal.getBrain();
   if(brain.getMemory(MemoryModuleType.WALK_TARGET).filter(value->value==ownedWalkTarget).isPresent())
    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
   if(brain.getMemory(MemoryModuleType.PATH).filter(value->value==ownedPath).isPresent())
    brain.eraseMemory(MemoryModuleType.PATH);
  }
  // Do not stop a path subsequently installed by a different controller.
  if(ownedPath!=null&&animal.getNavigation().getPath()==ownedPath)animal.getNavigation().stop();
 }
 boolean begin(){
  if(!active()||brainControlled&&!idleBrain(animal))return false;
  ownedPath=nativePath(route);
  if(brainControlled){
   var end=route.cells().getLast();
   ownedWalkTarget=new WalkTarget(new BlockPos(end.x(),end.y(),end.z()),(float)route.speed(),0);
   animal.getBrain().setMemory(MemoryModuleType.PATH,ownedPath);
   animal.getBrain().setMemory(MemoryModuleType.WALK_TARGET,ownedWalkTarget);
  }
  var started=animal.getNavigation().moveTo(ownedPath,route.speed());
  return started&&animal.getNavigation().getPath()==ownedPath;
 }
}
private static void stopExistingMoveGoals(Animal animal){
 // WrappedGoal.stop() preserves the actual vanilla Goal objects/priorities.
 // GoalSelector removes stale flag locks on its next update.
 for(var wrapped:new ArrayList<>(animal.goalSelector.getAvailableGoals()))
  if(wrapped.isRunning()&&wrapped.getFlags().contains(Goal.Flag.MOVE))wrapped.stop();
}
public void releaseNavigation(UUID uuid){
 var lease=moveLeases.remove(uuid);if(lease==null)return;
 if(!lease.animal.level().getServer().isSameThread())throw new IllegalStateException("server actor required");
 lease.stop();lease.animal.goalSelector.removeGoal(lease);
}
public void releaseAllNavigation(){for(var uuid:new ArrayList<>(moveLeases.keySet()))releaseNavigation(uuid);}

 public FaunaNavigationBinding(Function<DimensionId,ServerLevel> levels,Function<EffectIntent,Route> routes){this(levels,routes,Set.of());}
 /** Exact registry profiles qualified for native Brain movement; default behavior is unchanged. */
 public FaunaNavigationBinding(Function<DimensionId,ServerLevel> levels,Function<EffectIntent,Route> routes,Set<ResourceId> brainSpecies){
  this.levels=Objects.requireNonNull(levels);this.routes=Objects.requireNonNull(routes);this.brainSpecies=Set.copyOf(brainSpecies);
 }
 private boolean brainAvailable(Animal animal,Route route){
  var existing=moveLeases.get(route.owner().entityUuid());
  return existing!=null&&existing.brainControlled&&existing.route.equals(route)&&existing.active()
    ||FaunaSpawnBinding.preparationHeld(animal,route.owner())||idleBrain(animal);
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(new PrimitiveId("proutlost:fauna_migrate"));}
 public Set<CapabilityId> capabilities(){return Set.of(new CapabilityId("entity.navigation_owned_v1"));}
 private ServerLevel level(DimensionId dim){var l=Objects.requireNonNull(levels.apply(dim));if(!l.getServer().isSameThread())throw new IllegalStateException("server actor required");if(!l.dimension().location().toString().equals(dim.value()))throw new IllegalArgumentException("dimension");return l;}
 private static BlobContent blob(Route r){return BlobContent.capture(CODEC_ID,1,CODEC.encode(new FaunaNavigationRoute(r.owner(),r.cells(),r.speed())));}
 /** A hydrated route is not an authorization until its full J04 claim and
  * original pre-state fingerprint are proven consistent with the same animal.
  * Corrupt/missing WAL data must never reach native navigation or settlement. */
 private static Optional<Route> durableRoute(PreparedEffect p,BlobBundle b){
  try{
   if(!(p.intent().typedArgs() instanceof FaunaMigrate) || p.intent().version()!=1)return Optional.empty();
   var ref=p.durableBlobs().preparedPlan();
   if(!ref.codecId().equals(CODEC_ID)||ref.codecVersion()!=1)return Optional.empty();
   var content=b.contents().stream().filter(c->c.reference().equals(ref)).findFirst().orElseThrow();
   var decoded=CODEC.decode(content.bytes());
   Route r=new Route(decoded.owner(),decoded.cells(),decoded.speed());
   var key=key(r);
   if(!FaunaNavigationWalFence.authenticated(p.durableBlobs(),p.preconditions(),
       p.expectedPost(),p.claims(),p.capabilities(),key,r.owner().generation(),content)
       || !r.owner().instanceId().equals(p.intent().instanceId())
       || !r.owner().lastKnownChunk().dimension().equals(p.intent().expectedFootprint().dimension()))
    return Optional.empty();
   return Optional.of(r);
  }catch(RuntimeException corrupt){return Optional.empty();}
 }
 private static ResourceKey key(Route r){return new ResourceKey(OwnershipDomain.ENTITY,r.owner().lastKnownChunk().dimension(),r.owner().entityUuid().toString());}
 private Animal animal(Route r){var entity=level(r.owner().lastKnownChunk().dimension()).getEntity(r.owner().entityUuid());if(!(entity instanceof Animal a))throw new IllegalArgumentException("animal unloaded or missing; never respawn");return a;}
 private boolean valid(EffectIntent intent,Route r){
  String reason;
  validation:{
   if(!(intent.typedArgs() instanceof FaunaMigrate args)||args.cohortSize()!=1||!intent.instanceId().equals(r.owner().instanceId())||r.owner().adoptedBy().isPresent()||r.owner().disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||!Set.of(EntityPhase.PRESENT,EntityPhase.MOVING).contains(r.owner().phase())){reason="ARGUMENTS_OR_CAPTURED_OWNER_STATE";break validation;}
   var level=level(intent.expectedFootprint().dimension());var a=animal(r);var attached=EntityDirectorAttachment.read(a);
   if(!attached.equals(Optional.of(r.owner()))){reason="ATTACHMENT_CHANGED expected="+r.owner()+" observed="+attached;break validation;}
   if(a.isLeashed()||a.isPassenger()||a.isVehicle()||!a.isAlive()){reason="ANIMAL_STATE leashed="+a.isLeashed()+" passenger="+a.isPassenger()+" vehicle="+a.isVehicle()+" alive="+a.isAlive();break validation;}
   if(!BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString().equals(args.species().value())){reason="SPECIES_CHANGED actual="+BuiltInRegistries.ENTITY_TYPE.getKey(a.getType());break validation;}
   if(brainSpecies.contains(args.species())&&!brainAvailable(a,r)){reason="NATIVE_BRAIN_MOVEMENT_BUSY_OR_INTERRUPTED";break validation;}
   if(!intent.expectedFootprint().cells().containsAll(r.cells())){reason="FOOTPRINT_MISMATCH";break validation;}
   var start=r.cells().getFirst();double distance=a.distanceToSqr(start.x()+.5,start.y(),start.z()+.5);
   if(distance>2.25){reason="START_DISTANCE distance_squared="+distance+" position="+a.position()+" first="+start;break validation;}
   if(!FaunaRouteGeometryFence.valid(r.cells(),intent.expectedFootprint().dimension(),r.owner().lastKnownChunk())){reason="ROUTE_GEOMETRY";break validation;}
   for(var p:r.cells()){
    if(!intent.expectedFootprint().loadedChunks().contains(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4))||level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null){reason="UNLOADED_OR_UNCAPTURED_CHUNK cell="+p;break validation;}
    var pos=new BlockPos(p.x(),p.y(),p.z());
    if(!level.getWorldBorder().isWithinBounds(pos)){reason="WORLD_BORDER cell="+p;break validation;}
    if(!level.getFluidState(pos).isEmpty()){reason="FLUID cell="+p+" state="+level.getFluidState(pos);break validation;}
    if(!level.getBlockState(pos.below()).isSolidRender(level,pos.below())){reason="FLOOR_NOT_SOLID cell="+p+" state="+level.getBlockState(pos.below());break validation;}
    var box=a.getBoundingBox().move(p.x()+.5-a.getX(),p.y()-a.getY(),p.z()+.5-a.getZ());
    if(!level.noCollision(a,box)){reason="COLLISION cell="+p+" position="+a.position()+" box="+box;break validation;}
   }
   return true;
  }
  System.out.println("DIRECTOR_FAUNA_NAV_REVALIDATE_REJECTED event="+intent.instanceId().value()+" operation="+intent.operationToken().value()
   +" entity="+r.owner().entityUuid()+" owner_revision="+r.owner().header().revision().value()+" generation="+r.owner().generation().value()+" detail="+reason);
  return false;
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  var r=Objects.requireNonNull(routes.apply(intent));if(!valid(intent,r))throw new IllegalArgumentException("unsafe navigation");
  var args=(FaunaMigrate)intent.typedArgs();
  for(var cell:r.cells()){
   var terrain=context.snapshot().context().terrain().stream().map(t->t.cells().get(cell)).filter(Objects::nonNull).findFirst().orElseThrow(()->new IllegalArgumentException("unknown corridor"));
   if(!terrain.knownContent()||terrain.protectedContent()||!terrain.hazards().isEmpty()||!terrain.tags().contains(args.corridorTag()))throw new IllegalArgumentException("unqualified corridor");
  }
  var b=blob(r);var key=key(r);var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,r.owner().generation()),Map.of(key,b.reference().hash()));
  var cost=new BudgetVector(0,0,0,0,1,1,0,0,0,r.cells().size());
  var refs=new EffectBlobs(Set.of(b.reference()),b.reference(),b.reference(),Map.of(key,b.reference()),Map.of());
  var prepared=new PreparedEffect(intent,context.snapshot(),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,cost,Optional.empty(),r.owner().generation())),read,Map.of(),cost,List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.of(b)));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  var route=durableRoute(plan,blobs);
  if(route.isEmpty())System.out.println("DIRECTOR_FAUNA_NAV_REVALIDATE_REJECTED event="+plan.intent().instanceId().value()+" operation="+plan.intent().operationToken().value()+" detail=DURABLE_ROUTE_UNAUTHENTICATED");
  return route.filter(r->valid(plan.intent(),r)).isPresent();
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  var route=durableRoute(plan,blobs).orElseThrow(()->new IllegalStateException("invalid durable route"));
  if(!valid(plan.intent(),route))throw new IllegalStateException("stale navigation");var a=animal(route);
  releaseNavigation(route.owner().entityUuid());
  FaunaSpawnBinding.releasePreparationHold(a,route.owner());
  stopExistingMoveGoals(a);
  a.getNavigation().stop();
  var lease=new MoveLease(a,route,brainSpecies.contains(((FaunaMigrate)plan.intent().typedArgs()).species()));
  a.goalSelector.addGoal(0,lease);
  // Pinned GoalSelector iterates this mutable LinkedOpenHashSet in insertion order.
  // Take MOVE first on the next update, before any stopped vanilla MOVE can restart.
  var goals=a.goalSelector.getAvailableGoals();
  var own=goals.stream().filter(g->g.getGoal()==lease).findFirst().orElseThrow();
  var rest=new ArrayList<>(goals);rest.remove(own);
  goals.clear();goals.add(own);goals.addAll(rest);
  moveLeases.put(route.owner().entityUuid(),lease);
  if(!lease.begin()){
   releaseNavigation(route.owner().entityUuid());
   throw new IllegalStateException("native navigator refused leased corridor");
  }
  // The operation confirms that navigation started; no fabricated migration-arrival fact.
  return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),Map.of(),0,Set.of(),Set.of(),List.of());
 }
 /** Observe actual arrival; unloaded/adopted/changed UUIDs cannot settle. Called by the actor
  * runner while its claims remain held. The resulting aggregate must enter the result batch.
  */
 public Optional<EntityOwnershipRecord> observeArrival(PreparedEffect plan,BlobBundle blobs,Tick now){
  var optional=durableRoute(plan,blobs);if(optional.isEmpty())return Optional.empty();
  var route=optional.orElseThrow();
  var entity=level(route.owner().lastKnownChunk().dimension()).getEntity(route.owner().entityUuid());
  if(!(entity instanceof Animal a) || !a.isAlive() ||
     !(plan.intent().typedArgs() instanceof FaunaMigrate migration) ||
     !BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString().equals(migration.species().value()))
   return Optional.empty();
  if(brainSpecies.contains(migration.species())){
   var lease=moveLeases.get(route.owner().entityUuid());
   if(lease==null||!lease.active())return Optional.empty();
  }
  var attached=EntityDirectorAttachment.read(a);if(attached.isEmpty())return Optional.empty();var old=attached.orElseThrow();
  if(!old.equals(route.owner()))return Optional.empty();var end=route.cells().getLast();
  if(!a.getNavigation().isDone()||a.distanceToSqr(end.x()+.5,end.y(),end.z()+.5)>.75)return Optional.empty();
  var next=new EntityOwnershipRecord(new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),now),old.ownerToken(),old.entityUuid(),old.instanceId(),old.cohortId(),old.slotId(),EntityPhase.SETTLED,old.disposition(),new ChunkRef(end.dimension(),end.x()>>4,end.z()>>4),now,old.persistMode(),old.cleanupPolicy(),old.adoptedBy(),old.generation());
  return Optional.of(next); // Data only: caller must durably authorize settlement before changing attachment.
 }
 /** A native attachment can prove player adoption only if it belongs to the exact
  * durable route lineage. UUID or an arbitrary adopted flag alone is not evidence.
  * A running navigation is never proof of arrival or success. Pure to regression-test. */
 static ReconcileStatus classifyRecovery(EntityOwnershipRecord durable,
      Optional<EntityOwnershipRecord> observed, boolean liveAnimalOfExpectedSpecies) {
  if(!liveAnimalOfExpectedSpecies || observed.isEmpty())return ReconcileStatus.AMBIGUOUS;
  var current=observed.orElseThrow();
  if(!current.entityUuid().equals(durable.entityUuid())
      || !current.ownerToken().equals(durable.ownerToken())
      || !current.instanceId().equals(durable.instanceId())
      || !current.cohortId().equals(durable.cohortId())
      || !current.slotId().equals(durable.slotId()))
   return ReconcileStatus.AMBIGUOUS;
  return current.adoptedBy().isPresent()
      && current.phase()==EntityPhase.ADOPTED
      && current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER
      && current.generation().compareTo(durable.generation())>0
      ? ReconcileStatus.PLAYER_OVERRIDE:ReconcileStatus.AMBIGUOUS;
 }
 public ReconcileResult reconcile(RecoveryProbe probe){
  // Recovery must use the exact J04-hydrated route, not a live planning
  // callback which can return a different entity/route after a restart.
  var authenticated=durableRoute(probe.durableIntent(),probe.blobs());
  if(authenticated.isEmpty())
   return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
  Route r=authenticated.orElseThrow();
  if(!r.owner().instanceId().equals(probe.durableIntent().intent().instanceId()) ||
     !r.owner().lastKnownChunk().dimension().equals(probe.durableIntent().intent().expectedFootprint().dimension()))
   return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
  var entity=level(r.owner().lastKnownChunk().dimension()).getEntity(r.owner().entityUuid());
  if(entity==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
  var migration=(FaunaMigrate)probe.durableIntent().intent().typedArgs();
  boolean expectedAnimal=entity instanceof Animal a && a.isAlive()
      && BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString().equals(migration.species().value());
  Optional<EntityOwnershipRecord> owner;
  try { owner=EntityDirectorAttachment.read(entity); }
  catch(RuntimeException corrupt){owner=Optional.empty();}
  // A launched route is NOT a durable arrival. A player override requires
  // a live animal of the expected species with authenticated adoption lineage.
  var status=classifyRecovery(r.owner(),owner,expectedAnimal);
  return new ReconcileResult(status,probe.loadedProofs(),List.of());
 }
}