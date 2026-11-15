package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/** Opt-in physical qualification. The durable sheep are NEVER test-cleaned.
 * Re-run the SAME full gameDirectory with nativeFaunaRecoveryOnly=true.
 * First launch requires nativeFaunaFixture=x,y,z: an ALREADY LOADED grass
 * pasture outside every GameTest clearing box. Only an initially empty vanilla
 * fence ring is prepared, BEFORE START; existing cohorts never rewrite it.
 * No chunk loads, world copies, replacement spawns, or second writer.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeFauna24GameTests {
 private DirectorNativeFauna24GameTests(){}
 private static final String START="proutlost_director:lab_fauna_start";
 private static final String ARRIVAL="proutlost_director:lab_fauna_arrival";
 private static final String END="proutlost_director:lab_fauna_end";
 private static final String OBSERVED="proutlost:animal_migration_observed";
 private static final String WAITING="proutlost_director:lab_fauna_waiting_missing_entities";
 private static final ModelCodec<EntityOwnershipRecord> OWNER=new ModelCodec<>(EntityOwnershipRecord.class);
 private static final ModelCodec<FaunaNavigationRoute> PATH=new ModelCodec<>(FaunaNavigationRoute.class);
 private static String text(DirectorFact f,String key){return ((JsonValue.Text)f.payload().get(key)).value();}
 private static boolean belongs(DirectorFact f,UUID event){return f.eventId().map(EventInstanceId::value).filter(event::equals).isPresent();}

 /** No fake pass is added to the normal suite when the physical lane is off. */
 @GameTestGenerator
 public static Collection<TestFunction> nativeFaunaTests(){
  if(!Boolean.getBoolean("proutlost.director.nativeFaunaGameTest"))return List.of();
  boolean recovery=Boolean.getBoolean("proutlost.director.nativeFaunaRecoveryOnly");
  boolean restart=Boolean.getBoolean("proutlost.director.nativeFaunaRestartAfterCleanup");
  boolean held=Boolean.getBoolean("proutlost.director.nativeFaunaRestartAfterHeld");
  if((recovery?1:0)+(restart?1:0)+(held?1:0)>1)throw new IllegalArgumentException("recoveryOnly, restartAfterCleanup and restartAfterHeld are distinct qualifications");
  return List.of(new TestFunction("native_fauna_persistent",
   "proutlost_director.native_fauna24_"+(recovery?"same_world_recovery":held?"retained_reservations_then_new_event":restart?"expired_cleanup_then_new_event":"actual_arrivals_wal"),
   "proutlost_director:smoke_empty",Rotation.NONE,12000,0,true,helper->{
    var probe=new Probe(helper,recovery);
    helper.succeedWhen(()->{probe.advance();helper.assertTrue(probe.done,
     "native Fauna qualification pending phase="+probe.phase+" event="+probe.event);});
   }));
 }

 private static final class Scan {
  final DurableStore store;
  final Map<UUID,EntityOwnershipRecord> owners=new HashMap<>();
  final Map<FactId,DirectorFact> facts=new LinkedHashMap<>();
  final Map<OperationToken,PrepareRecord> prepares=new HashMap<>();
  final Map<OperationToken,ResultRecord> results=new HashMap<>();
  Optional<String> cursor=Optional.empty();
  CompletableFuture<StoreReadResult> pending;StoreQuery query;boolean done;int pages;long snapshotSequence;
  Scan(DurableStore store){this.store=store;}
  void row(EntityOwnershipRecord next){
   var old=owners.get(next.entityUuid());
   if(old==null||next.header().revision().compareTo(old.header().revision())>0)owners.put(next.entityUuid(),next);
   else if(next.header().revision().equals(old.header().revision())&&!next.equals(old))throw new IllegalStateException("conflicting J04 entity version");
  }
  void accept(DurableRecord record){
   if(record instanceof DurableAggregate a&&a.state() instanceof EntityOwnershipAggregate e)row(e.value());
   if(record instanceof DurableFact f)facts.put(f.value().factId(),f.value());
   if(record instanceof DurablePrepare p)prepares.put(p.value().operationToken(),p.value());
   if(record instanceof DurableResult r)results.put(r.value().operationToken(),r.value());
  }
  boolean poll(){
   if(done)return true;
   if(pending==null){query=new StoreQuery(store.session().worldId(),Set.of(),new Sequence(0),cursor,128);pending=store.read(query).toCompletableFuture();return false;}
   if(!pending.isDone())return false;
   if(pending.isCompletedExceptionally())throw new IllegalStateException("native Fauna J04 read failed");
   var read=pending.getNow(null);pending=null;ReadContracts.validate(read,query,store.session());
   if(read.status()==ReadStatus.STALE){owners.clear();facts.clear();prepares.clear();results.clear();cursor=Optional.empty();pages=0;snapshotSequence=0;return false;}
   if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("native Fauna J04 read "+read.status());
   var page=read.page().orElseThrow();snapshotSequence=page.lastDurableSequence().value();page.records().forEach(this::accept);
   for(var aggregate:page.aggregates())if(aggregate.state() instanceof EntityOwnershipAggregate e)row(e.value());
   if(++pages>256)throw new IllegalStateException("native Fauna qualification WAL scan bound");
   cursor=page.nextCursor();done=!page.hasMore();return done;
  }
  List<DirectorFact> facts(UUID event,String type){return facts.values().stream().filter(f->belongs(f,event)&&f.factType().value().equals(type)).toList();}
  Optional<DirectorFact> previousNativeStart(){return facts.values().stream().filter(f->f.factType().value().equals(START)&&"true".equals(text(f,"testOnly"))).reduce((a,b)->b);}
 }

 private static final class Probe {
  final GameTestHelper helper;final boolean recoveryOnly;final ServerLevel level;
  final boolean restartAfterCleanup=Boolean.getBoolean("proutlost.director.nativeFaunaRestartAfterCleanup");
  final boolean restartAfterHeld=Boolean.getBoolean("proutlost.director.nativeFaunaRestartAfterHeld");
  final MinecraftServer server;final DirectorServerLifecycle life;
  DirectorNativeGameplay gameplay;DurableStore store;Scan scan;UUID event;
  NativeFaunaHabitat.Layout layout;int phase,reconciledSpawns;long checkUntil;boolean done,launched;
  String digest;Set<UUID> expectedIds=Set.of(),retiredIds=Set.of(),heldIds=Set.of(),releasedHeldIds=Set.of();UUID retiredEvent,heldEvent;
  Map<UUID,EntityOwnershipRecord> heldSnapshot=Map.of();long heldBaseline;
  Probe(GameTestHelper helper,boolean recoveryOnly){this.helper=helper;this.recoveryOnly=recoveryOnly;level=helper.getLevel();server=level.getServer();life=DirectorStandaloneMod.directorLifecycle();}
  void require(boolean condition,String message){helper.assertTrue(condition,message);}
  boolean fixtureCanBeRearmed(){return retiredEvent!=null&&retiredIds.size()==24||heldEvent!=null&&releasedHeldIds.size()==21&&heldIds.size()==3;}
  NativeFaunaHabitat.Layout fixture(){
   String encoded=System.getProperty("proutlost.director.nativeFaunaFixture","");
   require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeFaunaFixture=x,y,z to a loaded persistent grass pasture");
   var xyz=encoded.split(",");var origin=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
   var dimension=new DimensionId(level.dimension().location().toString());
   var box=new AABB(origin.getX()-1,origin.getY()-1,origin.getZ()-1,origin.getX()+24,origin.getY()+2,origin.getZ()+11);
   require(!box.intersects(helper.getBounds().inflate(3,20,3)),"persistent sheep pasture intersects current GameTest clearing box");
   for(int cx=(origin.getX()-4)>>4;cx<=(origin.getX()+26)>>4;cx++)for(int cz=(origin.getZ()-4)>>4;cz<=(origin.getZ()+13)>>4;cz++){
    var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
    for(var be:chunk.getBlockEntities().values())if(be instanceof StructureBlockEntity structure)
     require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"persistent pasture intersects another GameTest clearing box");
   }
   for(var entity:level.getEntitiesOfClass(Entity.class,box.inflate(2),Entity::isAlive))
    require(EntityDirectorAttachment.read(entity).isEmpty(),"existing Director entity in pasture: preserve it");
   var chunks=new HashSet<ChunkRef>();var routes=new ArrayList<List<BlockPosition>>();var grassRepair=new ArrayList<BlockPos>();
   for(int z=0;z<10;z++)for(int x=0;x<23;x++){
    var nativePos=origin.offset(x,0,z);var p=new BlockPosition(dimension,nativePos.getX(),nativePos.getY(),nativePos.getZ());
    require(level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null&&level.isPositionEntityTicking(nativePos),"pasture chunk is not already loaded and entity-ticking");
    boolean grass=level.getBlockState(nativePos.below()).is(Blocks.GRASS_BLOCK);
    require((grass||fixtureCanBeRearmed()&&level.getBlockState(nativePos.below()).is(Blocks.DIRT))&&NativeFaunaHabitat.qualified(level,List.of(p),p,false),"clear vanilla pasture required; rearm only after explicit released/held qualification");
    if(!grass)grassRepair.add(nativePos.below());
    chunks.add(new ChunkRef(dimension,p.x()>>4,p.z()>>4));
   }
   // New-event fixture only, after a new world or an explicitly qualified
   // release/park transition. This never repairs or replays an old entity slot.
   var ring=new LinkedHashSet<BlockPos>();var ledger=new ServerOwnershipAttachments(64);
   for(int z=-1;z<=10;z++)for(int x=-1;x<=23;x++)if(x==-1||x==23||z==-1||z==10)ring.add(origin.offset(x,0,z));
   for(var p:ring){
    require(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&level.isPositionEntityTicking(p),"initial fence chunk unavailable");
    require((level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK)||fixtureCanBeRearmed()&&level.getBlockState(p.below()).is(Blocks.DIRT))&&(level.getBlockState(p).isAir()||level.getBlockState(p).is(Blocks.OAK_FENCE))&&level.getBlockState(p.above()).isAir(),"initial fence would replace non-fixture content");
    for(var cell:List.of(p.below(),p,p.above()))require(ledger.read(level,cell).map(o->o.disposition()==OwnershipDisposition.RELEASED).orElse(true),"initial fence has Director provenance");
    require(level.getEntitiesOfClass(Entity.class,new AABB(p),Entity::isAlive).isEmpty(),"initial fence cell occupied");
   }
   require(grassRepair.size()<=230,"native pasture repair exceeds230 cells");
   if(!grassRepair.isEmpty())require(fixtureCanBeRearmed(),"pasture repair lacks its explicit released/held qualification");
   for(var p:grassRepair)level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   for(var p:ring)if(level.getBlockState(p).isAir())level.setBlock(p,Blocks.OAK_FENCE.defaultBlockState(),3);
   for(int row=0;row<4;row++)for(int column=0;column<6;column++){
    var cells=new ArrayList<BlockPosition>();
    for(int step=0;step<3;step++){var p=origin.offset(column*4+step,0,row*3);cells.add(new BlockPosition(dimension,p.getX(),p.getY(),p.getZ()));}
    require(NativeFaunaHabitat.qualified(level,cells,cells.getFirst(),true),"native sheep habitat/spawn rules refused route");routes.add(cells);
   }
   return new NativeFaunaHabitat.Layout(routes,chunks);
  }
  ServerLevel dimension(EntityOwnershipRecord row){
   for(var candidate:server.getAllLevels())if(candidate.dimension().location().toString().equals(row.lastKnownChunk().dimension().value()))return candidate;
   throw new IllegalStateException("Fauna dimension unavailable");
  }
  boolean entitiesReady(Scan proof){
   boolean found=false;
   for(var row:proof.owners.values())if(row.instanceId().value().equals(event)){
    found=true;var current=dimension(row);var c=row.lastKnownChunk();
    if(current.getChunkSource().getChunkNow(c.x(),c.z())==null||!current.areEntitiesLoaded(ChunkPos.asLong(c.x(),c.z()))||!current.isPositionEntityTicking(new BlockPos(c.x()<<4,0,c.z()<<4)))return false;
   }
   return found;
  }
  String verify(Scan proof){
   var starts=proof.facts(event,START);var ends=proof.facts(event,END);var arrivals=proof.facts(event,ARRIVAL);
   require(starts.size()==1&&ends.size()==1,"exactly one native Fauna START and END required");
   require("true".equals(text(starts.getFirst(),"testOnly")),"fixture must stay explicitly TEST_ONLY");
   require("24".equals(text(ends.getFirst(),"settled"))&&"24_ACTUAL_ARRIVALS_TRANSFERRED".equals(text(ends.getFirst(),"reason")),"END does not confirm24 transferred actual arrivals");
   var paths=((JsonValue.Array)starts.getFirst().payload().get("paths")).values();require(paths.size()==24,"START must seal original24 UUID slots");
   var original=new HashMap<UUID,EntityOwnershipRecord>();
   for(var encoded:paths){var route=PATH.decode(Base64.getDecoder().decode(((JsonValue.Text)encoded).value()));require(route.cells().size()==3,"qualification requires real3-cell routes");require(original.put(route.owner().entityUuid(),route.owner())==null,"duplicate original spawn UUID");}
   var finalRows=new TreeMap<UUID,EntityOwnershipRecord>();
   for(var row:proof.owners.values())if(row.instanceId().value().equals(event))finalRows.put(row.entityUuid(),row);
   require(finalRows.keySet().equals(original.keySet()),"recovery replaced or lost an original slot UUID");
   if(heldEvent!=null){
    require(proof.facts(heldEvent,END).isEmpty()&&proof.facts(heldEvent,ARRIVAL).isEmpty(),"new event falsely closed or confirmed arrivals for the unresolved old event");
    require(!event.equals(heldEvent)&&Collections.disjoint(finalRows.keySet(),heldIds)&&Collections.disjoint(finalRows.keySet(),releasedHeldIds),"new event reused an old unresolved or released UUID");
    for(var entry:heldSnapshot.entrySet())require(entry.getValue().equals(proof.owners.get(entry.getKey())),"new event changed an unresolved retained owner");
    var retainedPrepares=proof.prepares.values().stream().filter(p->p.reservation().instanceId().value().equals(heldEvent)&&p.slice().operations().size()==1&&p.slice().operations().getFirst().primitive().value().equals("proutlost:fauna_spawn_owned")&&p.readSet().epochs().keySet().stream().anyMatch(k->heldIds.contains(UUID.fromString(k.key())))).toList();
    require(retainedPrepares.size()==3&&retainedPrepares.stream().allMatch(p->!proof.results.containsKey(p.operationToken())),"new event invented an outcome for a retained PREPARE");
    long occupied=proof.owners.values().stream().filter(r->r.phase()!=EntityPhase.REMOVED).count();require(occupied>=heldBaseline+24&&occupied<=128,"retained slots were not charged alongside the new24 persistent sheep");
   }

   require(arrivals.size()==24,"exactly24 durable actual-arrival facts required");
   var arrivalIds=new HashSet<UUID>();
   for(var fact:arrivals){
    require(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED,"unconfirmed arrival fact");
    var id=UUID.fromString(text(fact,"entity"));require(arrivalIds.add(id),"duplicate arrival evidence");
    var attached=OWNER.decode(Base64.getDecoder().decode(text(fact,"owner")));
    require(attached.equals(finalRows.get(id)),"arrival fact and durable owner differ");
   }
   require(arrivalIds.equals(original.keySet()),"arrival set does not match original24 UUIDs");
   var slotIds=new HashSet<String>();var cohorts=new HashSet<CohortId>();var printed=new StringBuilder();
   for(var row:finalRows.values()){
    var initial=original.get(row.entityUuid());
    require(row.ownerToken().equals(initial.ownerToken())&&row.header().id().equals(initial.header().id())&&row.slotId().equals(initial.slotId())&&row.cohortId().equals(initial.cohortId()),"entity ownership lineage changed");
    require(row.phase()==EntityPhase.SETTLED&&row.disposition()==OwnershipDisposition.DIRECTOR_DURABLE&&row.persistMode()==ArrivalPolicy.SETTLE_WORLD&&row.adoptedBy().isEmpty(),"sheep not permanently transferred to world");
    var animal=dimension(row).getEntity(row.entityUuid());
    require(animal instanceof Sheep&&animal.isAlive(),"original durable sheep unavailable: never replace "+row.entityUuid());
    require(EntityDirectorAttachment.read(animal).equals(Optional.of(row)),"Minecraft attachment differs from J04");
    require(slotIds.add(row.slotId()),"duplicate cohort slot");cohorts.add(row.cohortId());
    printed.append(row.entityUuid()).append(':').append(BlobContent.hash(OWNER.encode(row)).value()).append('\n');
   }
   require(cohorts.size()==1,"cohort identity split");
   int live=0;for(var candidate:server.getAllLevels())for(var entity:candidate.getAllEntities()){
    var attached=EntityDirectorAttachment.read(entity);if(attached.isPresent()&&attached.orElseThrow().instanceId().value().equals(event)){
     require(entity instanceof Sheep&&entity.isAlive()&&original.containsKey(entity.getUUID()),"extra or replaced cohort entity");live++;
    }
   }
   require(live==24,"exactly24 live original cohort sheep required");
   reconciledSpawns=0;
   for(String primitive:List.of("proutlost:fauna_spawn_owned","proutlost:fauna_migrate")){
    var matching=proof.prepares.values().stream().filter(p->p.reservation().instanceId().value().equals(event)&&p.slice().operations().size()==1&&p.slice().operations().getFirst().primitive().value().equals(primitive)).toList();
    require(matching.size()==24,"24 actual J04 PREPAREs required for "+primitive);
    for(var prepare:matching){
     var record=proof.results.get(prepare.operationToken());require(record!=null&&record.prepareTransactionId().equals(prepare.transactionId()),"physical operation lacks its exact durable RESULT");
     var result=record.result();boolean reconciled=primitive.equals("proutlost:fauna_spawn_owned")&&result.outcome()==Outcome.RECONCILED;
     require(result.outcome()==Outcome.SUCCEEDED||reconciled,"navigation must SUCCEED; only a proven original spawn POST may be RECONCILED");
     if(reconciled){
      var posts=prepare.durableBlobs().expectedPostStates();require(posts.size()==1,"reconciled spawn must seal one POST");
      var pair=posts.entrySet().iterator().next();var resource=pair.getKey();var id=UUID.fromString(resource.key());var initial=original.get(id);var current=finalRows.get(id);
      require(resource.domain()==OwnershipDomain.ENTITY&&initial!=null&&current!=null&&resource.dimension().equals(initial.lastKnownChunk().dimension()),"reconciled spawn is not an original cohort slot");
      require(result.appliedOperationIds().equals(Set.of(prepare.operationToken()))&&result.observedHashes().equals(Map.of(resource,pair.getValue().hash()))&&result.remainingWork()==0&&result.conflictCells().isEmpty(),"reconciled spawn must prove the exact sealed native POST and operation");
      require(prepare.readSet().fingerprints().get(resource).equals(BlobContent.hash(OWNER.encode(initial)))&&current.ownerToken().equals(initial.ownerToken())&&current.header().id().equals(initial.header().id())&&current.cohortId().equals(initial.cohortId())&&current.slotId().equals(initial.slotId())&&current.header().revision().value()==initial.header().revision().value()+2+proof.facts(event,"proutlost_director:lab_fauna_metadata").stream().filter(f->id.toString().equals(text(f,"entity"))&&"OBSERVED_CURRENT_CHUNK".equals(text(f,"reason"))).count()&&current.generation().value()==initial.generation().value()+1,"reconciled spawn ownership lineage differs from sealed RESERVED slot");
      reconciledSpawns++;
     }
    }
   }
   var observed=proof.facts(event,OBSERVED);require(observed.size()==1&&"24".equals(text(observed.getFirst(),"count")),"aggregate migration observation missing");
   var status=gameplay.labFaunaStatus();
   require(event.equals(status.event())&&!status.failed()&&!status.recovering()&&!status.active()&&status.ended()&&status.spawned()==24&&status.arrived()==24&&status.settled()==24&&status.live()==24&&status.removedSlots()==0,"coordinator status disagrees with physical/J04 evidence");
   if(!expectedIds.isEmpty())require(expectedIds.equals(finalRows.keySet()),"UUID set changed during stability window");
   expectedIds=Set.copyOf(finalRows.keySet());return BlobContent.hash(printed.toString().getBytes(StandardCharsets.UTF_8)).value();
  }
  boolean successfulEnd(Scan proof){
   var ends=proof.facts(event,END);require(ends.size()==1,"exactly one native END required");
   return "24".equals(text(ends.getFirst(),"settled"))&&"24_ACTUAL_ARRIVALS_TRANSFERRED".equals(text(ends.getFirst(),"reason"));
  }
  void verifyExpiredCleanup(Scan proof){
   require(restartAfterCleanup&&!recoveryOnly,"expired cohort was aborted, not successful; explicit restartAfterCleanup is required");
   var starts=proof.facts(event,START);var ends=proof.facts(event,END);require(starts.size()==1&&ends.size()==1,"old cohort dossier incomplete");
   require("0".equals(text(ends.getFirst(),"settled"))&&"ABORTED_NO_REPLACEMENT_SPAWNS".equals(text(ends.getFirst(),"reason"))&&proof.facts(event,ARRIVAL).isEmpty(),"old cohort is not an entirely cleaned aborted event");
   long deadline=Long.parseLong(text(starts.getFirst(),"deadline"));require(life.labAnchorClock(server)>=deadline,"restart qualification must not change an unexpired deadline");
   var original=new HashMap<UUID,EntityOwnershipRecord>();
   for(var encoded:((JsonValue.Array)starts.getFirst().payload().get("paths")).values()){var row=PATH.decode(Base64.getDecoder().decode(((JsonValue.Text)encoded).value())).owner();require(original.put(row.entityUuid(),row)==null,"old cohort duplicate UUID");}
   require(original.size()==24,"old cohort must retain its24 original identities");
   var retired=proof.owners.values().stream().filter(r->r.instanceId().value().equals(event)).toList();require(retired.size()==24,"old cleanup lost a slot");
   for(var row:retired){
    var initial=original.get(row.entityUuid());require(initial!=null&&row.phase()==EntityPhase.REMOVED&&row.disposition()==OwnershipDisposition.RELEASED&&row.adoptedBy().isEmpty()&&row.ownerToken().equals(initial.ownerToken())&&row.header().id().equals(initial.header().id())&&row.cohortId().equals(initial.cohortId())&&row.slotId().equals(initial.slotId())&&row.generation().value()==initial.generation().value()+1,"old slot was not durably released under its original lineage");
    require(dimension(row).getEntity(row.entityUuid())==null,"old original sheep is still physically present");
   }
   for(var current:server.getAllLevels())for(var entity:current.getAllEntities())require(EntityDirectorAttachment.read(entity).filter(r->r.instanceId().value().equals(event)).isEmpty(),"old cohort still has a native attachment");
   for(String primitive:List.of("proutlost:fauna_spawn_owned","proutlost:fauna_remove_owned")){
    var matching=proof.prepares.values().stream().filter(p->p.reservation().instanceId().value().equals(event)&&p.slice().operations().size()==1&&p.slice().operations().getFirst().primitive().value().equals(primitive)).toList();require(matching.size()==24,"old cohort requires24 durable spawn and24 exact-owner removal PREPAREs");
    for(var prepare:matching){
     var record=proof.results.get(prepare.operationToken());require(record!=null&&record.prepareTransactionId().equals(prepare.transactionId()),"old cleanup has an unresolved PREPARE");var result=record.result();boolean reconciled=primitive.equals("proutlost:fauna_spawn_owned")&&result.outcome()==Outcome.RECONCILED;
     require(result.outcome()==Outcome.SUCCEEDED||reconciled,"old removal was not actually confirmed");
     var posts=prepare.durableBlobs().expectedPostStates();require(posts.size()==1,"old physical operation must seal one POST");var pair=posts.entrySet().iterator().next();var id=UUID.fromString(pair.getKey().key());require(original.containsKey(id)&&result.appliedOperationIds().equals(Set.of(prepare.operationToken()))&&result.observedHashes().equals(Map.of(pair.getKey(),pair.getValue().hash()))&&result.remainingWork()==0&&result.conflictCells().isEmpty(),"old operation lacks exact original UUID and POST evidence");
     if(primitive.equals("proutlost:fauna_remove_owned"))require(pair.getValue().hash().equals(BlobContent.hash(OWNER.encode(proof.owners.get(id)))),"removed slot differs from its sealed durable POST");
     else require(prepare.readSet().fingerprints().get(pair.getKey()).equals(BlobContent.hash(OWNER.encode(original.get(id)))),"old spawn did not bind its original RESERVED owner");
    }
   }
   var status=gameplay.labFaunaStatus();require(event.equals(status.event())&&!status.failed()&&!status.active()&&status.ended()&&status.removedSlots()==24&&status.live()==0&&status.settled()==0,"old cleanup status is not terminal and empty");
   retiredEvent=event;retiredIds=Set.copyOf(original.keySet());
   System.out.println("DIRECTOR_NATIVE_FAUNA_EXPIRED_OLD_COHORT_CLEANUP_VERIFIED event="+event+" deadline="+deadline+" clock="+life.labAnchorClock(server)+" original_removed=24 physical_absent=24");
  }
  void verifyWaitingMissing(Scan proof){
   require(restartAfterHeld&&!recoveryOnly&&!restartAfterCleanup,"unresolved old reservations require explicit restartAfterHeld mode");
   var starts=proof.facts(event,START);var parked=proof.facts(event,WAITING);require(starts.size()==1&&parked.size()==1&&proof.facts(event,END).isEmpty()&&proof.facts(event,ARRIVAL).isEmpty(),"parked cohort must remain unclosed and have no claimed arrivals");
   var waiting=parked.getFirst();require(waiting.durable()&&waiting.evidenceStatus()==EvidenceStatus.CONFIRMED&&"21".equals(text(waiting,"released")),"missing durable21-release waiting fact");
   var missing=new HashSet<UUID>();for(var value:((JsonValue.Array)waiting.payload().get("missing")).values())require(missing.add(UUID.fromString(((JsonValue.Text)value).value())),"duplicate held UUID");require(missing.size()==3,"this qualification requires exactly3 unresolved original UUIDs");
   require(life.labAnchorClock(server)>=Long.parseLong(text(starts.getFirst(),"deadline")),"do not bypass the old deadline");
   var original=new HashMap<UUID,EntityOwnershipRecord>();for(var value:((JsonValue.Array)starts.getFirst().payload().get("paths")).values()){var row=PATH.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value())).owner();require(original.put(row.entityUuid(),row)==null,"duplicate original parked-cohort slot");}require(original.size()==24,"parked cohort lost an original identity");
   var spawn=new HashMap<UUID,PrepareRecord>();var removal=new ArrayList<PrepareRecord>();
   for(var prepare:proof.prepares.values())if(prepare.reservation().instanceId().value().equals(event)){
    require(prepare.slice().operations().size()==1,"unexpected parked-cohort slice");String primitive=prepare.slice().operations().getFirst().primitive().value();
    if(primitive.equals("proutlost:fauna_spawn_owned")){require(prepare.readSet().epochs().size()==1,"spawn must bind one original UUID");var resource=prepare.readSet().epochs().keySet().iterator().next();var id=UUID.fromString(resource.key());require(resource.domain()==OwnershipDomain.ENTITY&&original.containsKey(id)&&spawn.put(id,prepare)==null,"parked cohort rebound a spawn slot");require(prepare.readSet().fingerprints().get(resource).equals(BlobContent.hash(OWNER.encode(original.get(id)))),"held PREPARE does not seal the original RESERVED owner");}
    else if(primitive.equals("proutlost:fauna_remove_owned"))removal.add(prepare);
    else throw new IllegalStateException("parked cohort unexpectedly began navigation");
   }
   require(spawn.size()==4&&removal.size()==1&&spawn.keySet().containsAll(missing),"expected4 original spawn PREPAREs and1 actual removal");
   var released=new HashSet<UUID>();var unused=new HashSet<UUID>();var heldRows=new HashMap<UUID,EntityOwnershipRecord>();UUID knownSpawn=null;
   for(var entry:original.entrySet()){
    UUID id=entry.getKey();var initial=entry.getValue();var row=proof.owners.get(id);require(row!=null&&row.instanceId().value().equals(event)&&row.header().id().equals(initial.header().id())&&row.ownerToken().equals(initial.ownerToken())&&row.cohortId().equals(initial.cohortId())&&row.slotId().equals(initial.slotId()),"parked/released slot lineage changed");
    if(missing.contains(id)){
     require(row.equals(initial)&&row.phase()==EntityPhase.RESERVED&&!proof.results.containsKey(spawn.get(id).operationToken()),"uncertain spawn was released, advanced or given a fabricated result");
     require(dimension(row).getEntity(id)==null,"an original held UUID is currently loaded: let its old coordinator resume");heldRows.put(id,row);
    }else{
     require(row.phase()==EntityPhase.REMOVED&&row.disposition()==OwnershipDisposition.RELEASED&&row.generation().value()==initial.generation().value()+1&&dimension(row).getEntity(id)==null,"old released slot remains owned or physically present");released.add(id);
     if(!spawn.containsKey(id)){require(row.header().revision().equals(initial.header().revision().plus(1)),"unused slot unexpectedly advanced");unused.add(id);}
     else{require(knownSpawn==null,"more than one original spawn succeeded");knownSpawn=id;var result=proof.results.get(spawn.get(id).operationToken());require(result!=null&&result.prepareTransactionId().equals(spawn.get(id).transactionId())&&result.result().outcome()==Outcome.SUCCEEDED,"known original physical spawn lacks its real durable result");}
    }
   }
   require(released.size()==21&&unused.size()==20&&heldRows.size()==3&&knownSpawn!=null,"park must retain3, release20 unused slots and remove1 real sheep");
   var unusedFacts=new HashSet<UUID>();for(var fact:proof.facts(event,"proutlost_director:lab_fauna_metadata"))if("UNUSED_RESERVATION_NO_SPAWN".equals(text(fact,"reason")))unusedFacts.add(UUID.fromString(text(fact,"entity")));require(unusedFacts.equals(unused),"20 unused releases lack their explicit no-PREPARE metadata");
   var prepare=removal.getFirst();var result=proof.results.get(prepare.operationToken());var posts=prepare.durableBlobs().expectedPostStates();require(posts.size()==1,"known removal must seal one POST");var post=posts.entrySet().iterator().next();
   require(UUID.fromString(post.getKey().key()).equals(knownSpawn)&&result!=null&&result.prepareTransactionId().equals(prepare.transactionId())&&result.result().outcome()==Outcome.SUCCEEDED&&result.result().appliedOperationIds().equals(Set.of(prepare.operationToken()))&&result.result().observedHashes().equals(Map.of(post.getKey(),post.getValue().hash()))&&result.result().remainingWork()==0&&result.result().conflictCells().isEmpty()&&post.getValue().hash().equals(BlobContent.hash(OWNER.encode(proof.owners.get(knownSpawn)))),"known sheep was not removed under its exact sealed physical POST");
   for(var current:server.getAllLevels())for(var entity:current.getAllEntities())require(EntityDirectorAttachment.read(entity).filter(r->r.instanceId().value().equals(event)).isEmpty(),"old original entity is currently loaded: preserve/resume its old event");
   var status=gameplay.labFaunaStatus();require(event.equals(status.event())&&!status.failed()&&!status.active()&&!status.ended()&&status.removedSlots()==21&&status.live()==0,"old event is not durably parked and unclosed");
   long occupied=proof.owners.values().stream().filter(r->r.phase()!=EntityPhase.REMOVED).count();require(occupied>=3&&occupied+24<=128,"retained3 must still occupy persistent capacity");
   heldEvent=event;heldIds=Set.copyOf(missing);releasedHeldIds=Set.copyOf(released);heldSnapshot=Map.copyOf(heldRows);heldBaseline=occupied;
   System.out.println("DIRECTOR_NATIVE_FAUNA_OLD_EVENT_WAITING_UNRESOLVED_VERIFIED event="+event+" known_spawn_removed=1 unused_unprepared_released=20 held_unresolved=3 persistent_slots_used="+occupied+" closed=false");
  }
  void afterExistingWaiting(){
   if(life.observationView(server).isEmpty()||!entitiesReady(scan))return;verifyWaitingMissing(scan);layout=fixture();phase=2;
  }
  void afterExistingEnd(){
   if(life.observationView(server).isEmpty()||!entitiesReady(scan))return;
   if(successfulEnd(scan)){digest=verify(scan);checkUntil=server.getTickCount()+200L;phase=4;return;}
   verifyExpiredCleanup(scan);long occupied=scan.owners.values().stream().filter(r->r.phase()!=EntityPhase.REMOVED).count();require(occupied+24<=128,"persistent capacity remains occupied after old cleanup");
   layout=fixture();phase=2;
  }
  void advance(){
   require(server.isSameThread(),"Fauna qualification server actor");
   if(phase==0){gameplay=life.labGameplay(server).orElse(null);if(gameplay==null)return;var status=gameplay.labFaunaStatus();require(!status.failed(),"Fauna bootstrap failed");if(status.recovering())return;store=life.labReadOnlyStoreSnapshot(server).orElse(null);if(store==null)return;scan=new Scan(store);phase=1;return;}
   if(phase==1){
    if(!scan.poll())return;var previous=scan.previousNativeStart();
    if(previous.isPresent()){
     event=previous.orElseThrow().eventId().orElseThrow().value();
     if(scan.facts(event,END).isEmpty()){if(!scan.facts(event,WAITING).isEmpty()){afterExistingWaiting();return;}scan=new Scan(store);phase=11;return;}
     require(scan.facts(event,END).size()==1,"conflicting existing native END records");
     afterExistingEnd();return;
    }
    require(!recoveryOnly,"recovery-only run requires an already committed native cohort on this exact world/WAL");
    long occupied=scan.owners.values().stream().filter(r->r.phase()!=EntityPhase.REMOVED).count();require(occupied+24<=128,"persistent128-slot capacity does not admit another24 sheep");
    layout=fixture();phase=2;return;
   }
   if(phase==11){
    var status=gameplay.labFaunaStatus();require(!status.failed(),"existing native cohort recovery failed; preserve original UUIDs");
    if(!scan.poll())return;
    if(scan.facts(event,END).isEmpty()){if(!scan.facts(event,WAITING).isEmpty()){afterExistingWaiting();return;}scan=new Scan(store);return;}
    require(scan.facts(event,END).size()==1,"conflicting recovered native END records");
    afterExistingEnd();return;
   }
   if(phase==2){event=gameplay.labTestBeginFauna(layout);if(event==null)return;launched=true;phase=3;return;}
   if(phase==3){var status=gameplay.labFaunaStatus();require(!status.failed(),"actual native Fauna saga failed");if(!event.equals(status.event())||status.active()||!status.ended())return;scan=new Scan(store);phase=31;return;}
   if(phase==31){if(!scan.poll()||!entitiesReady(scan))return;digest=verify(scan);checkUntil=server.getTickCount()+200L;phase=4;return;}
   if(phase==4){if(server.getTickCount()<checkUntil)return;scan=new Scan(store);phase=5;return;}
   if(phase==5){
    if(!scan.poll()||!entitiesReady(scan))return;require(digest.equals(verify(scan)),"durable owners changed or were cleaned after END");
    DirectorNativeLabSaveHarness.saveFaunaCheckpoint(server,scan.store.session(),scan.snapshotSequence,event,expectedIds,digest);
    String marker=recoveryOnly?"DIRECTOR_NATIVE_FAUNA24_SAME_WORLD_RECOVERY_NO_RESPAWN_PASS":heldEvent!=null&&launched?"DIRECTOR_NATIVE_FAUNA24_NEW_EVENT_WITH_UNRESOLVED_ORIGINAL_SLOTS_RETAINED_PASS":retiredEvent!=null&&launched?"DIRECTOR_NATIVE_FAUNA24_NEW_EVENT_AFTER_EXPIRED_CLEANUP_PASS":launched?"DIRECTOR_NATIVE_FAUNA24_ACTUAL_ARRIVALS_WAL_ATTACHMENTS_PASS":"DIRECTOR_NATIVE_FAUNA24_EXISTING_COHORT_PRESERVED_PASS";
    System.out.println(marker+" event="+event+" boot_epoch="+store.session().bootEpoch().value()+" owner_sha256="+digest+" spawn_reconciled="+reconciledSpawns+" retired_event="+retiredEvent+" retired_count="+retiredIds.size()+" held_event="+heldEvent+" held_count="+heldIds.size()+" released_held_event="+releasedHeldIds.size()+" persistent_slots_used="+scan.owners.values().stream().filter(r->r.phase()!=EntityPhase.REMOVED).count()+" original_uuids="+new TreeSet<>(expectedIds));done=true;phase=6;
   }
  }
 }
}
