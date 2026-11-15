package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Opt-in real FIRE/J04 provider qualification, NOT a human/J06/DisasterInstance test.
 * First process proves finite-energy expiry, then leaves a second real two-wave checkpoint.
 * External second process reads the SAME saved cells/WAL and executes cleanup only.
 * nativeFixture has an EMPTY audience; player damage and player fire tail are NOT_RUN. */
@GameTestHolder("proutlost_director_fire_provider")
@PrefixGameTestTemplate(false)
public final class NativeFireProviderGameTests {
 private NativeFireProviderGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<BlockPosition> POSITION=new ModelCodec<>(BlockPosition.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final FactType START=new FactType("proutlost_director:native_fire_provider_start"),END=new FactType("proutlost_director:native_fire_provider_end");
 private static final FactType CHECKPOINT=new FactType("proutlost_director:native_fire_provider_checkpoint"),WAVE=new FactType("proutlost_director:controlled_fire_wave");
 private static final ResourceId RECIPE=new ResourceId("proutlost_director:native_fire_fixture"),PROFILE=new ResourceId("proutlost_director:native_fire_fixture_damage");
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,100);
 private static final Map<MinecraftServer,Probe> ACTIVE=Collections.synchronizedMap(new IdentityHashMap<>());
 static {if(Boolean.getBoolean("proutlost.director.nativeGameTests"))NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e)->{var p=ACTIVE.get(e.getServer());if(p!=null)p.close();});}
 public static boolean active(MinecraftServer server){return ACTIVE.containsKey(server);}
 @GameTestGenerator public static Collection<TestFunction> nativeFireTests(){
  if(!Boolean.getBoolean("proutlost.director.nativeFireProviderGameTest"))return List.of();
  boolean recovery=Boolean.getBoolean("proutlost.director.nativeFireProviderRecoveryOnly");
  boolean partial=Boolean.getBoolean("proutlost.director.nativeFireProviderPartialRecovery");
  if(recovery&&partial)throw new IllegalArgumentException("choose checkpoint recovery OR explicit partial recovery");
  return List.of(new TestFunction("native_fire_provider_persistent","proutlost_director_fire_provider.native_fire_provider_"+(partial?"partial_cleanup_then_expiry_checkpoint":recovery?"same_world_cleanup":"expiry_then_checkpoint"),
   "proutlost_director_fire_provider:smoke_empty",Rotation.NONE,12000,0,true,h->{
    var level=h.getLevel();var server=level.getServer();
    var rule=level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);boolean previous=rule.get();
    rule.set(true,server); // Explicit isolated LAB setup, before any probe/fixture/WAL campaign.
    Probe p;
    try{p=new Probe(h,recovery,partial,previous);}
    catch(RuntimeException failed){rule.set(previous,server);throw failed;}
    ACTIVE.put(server,p);
    System.out.println("DIRECTOR_NATIVE_FIRE_TEST_RULE_ENABLED previous="+previous+" current="+rule.get()+" scope=isolated_provider_fixture");
    h.succeedWhen(()->{
     if(p.failure!=null){h.assertTrue(false,"native FIRE failed: "+p.failure);return;}
     try{p.poll();}
     catch(RuntimeException failure){
      p.failure=failure;System.err.println("DIRECTOR_NATIVE_FIRE_FIRST_FAILURE phase="+p.phase+" event="+p.event);failure.printStackTrace();
      try{p.close();}catch(RuntimeException closingFailure){failure.addSuppressed(closingFailure);closingFailure.printStackTrace();}
      h.assertTrue(false,"native FIRE failed: "+failure);return;
     }
     h.assertTrue(p.done,"native FIRE pending phase="+p.phase);
    });
   }));
 }
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static Map<String,JsonValue> object(byte[] bytes){return ((JsonValue.ObjectValue)CanonicalJsonV1.decode(bytes)).values();}
 private static String text(Map<String,JsonValue> value,String k){return ((JsonValue.Text)value.get(k)).value();}
 private record Energy(Map<BlockPosition,Long> cells,long tick,long cursor,boolean started){}
 private static final class Probe {
  final GameTestHelper h;final boolean recovery,partial;final ServerLevel level;final MinecraftServer server;final WorldId world;final Path path;
  final boolean originalFireTick;boolean fireRuleRestored,worldSaveAttempted;
  final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
  final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>(),initial=new HashMap<>();
  final Map<BlockPosition,Fingerprint> sentries=new HashMap<>();
  final CompletableFuture<JournalStore> opening;JournalStore store;
  List<BlockPosition> cells=List.of();Footprint footprint;BlobContent mask;ControlledFire args;ControlledFireWaveBinding binding;
  UUID event;String campaign;long firstBoot,generationFloor;RuntimeException failure;OperationToken withheldOperation;int phase,waves,readPart,stales;boolean done,storeClosing;
  Energy energy;PreparedEffect firstPlan,lastPlan;BlobBundle lastBlobs;Execution work;
  DurableBatch metadataBatch;CompletableFuture<CommitAck> ack;CompletableFuture<Void> closing;CompletableFuture<JournalStore> reopening;
  StoreQuery query;CompletableFuture<StoreReadResult> reading;ReadStamp chronologyStamp;List<DurableRecord> records=List.of(),pending=List.of();
  ArrayDeque<PrepareRecord> oldPlans=new ArrayDeque<>();PrepareRecord hydratingPlan;CompletableFuture<BlobReadResult> hydrating;
  Set<BlobRef> expectedHydration=Set.of();
  Probe(GameTestHelper h,boolean recovery,boolean partial,boolean originalFireTick){
   this.h=h;this.recovery=recovery;this.partial=partial;this.originalFireTick=originalFireTick;level=h.getLevel();server=level.getServer();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-fire-provider-test-only");
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();
  }
  void require(boolean ok,String why){h.assertTrue(ok,why);}
  Tick now(){return new Tick(server.overworld().getGameTime());}
  Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level.getBlockState(pos(p))).reference().hash();}
  void publish(BlockPosition p,OwnershipRecord row){owners.put(key(p),row);attached.write(level,pos(p),row);}
  DirectorFact fact(FactType type,Map<String,JsonValue> payload){return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
   Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());}
  void append(List<DurableRecord> rows,List<BlobContent> blobs){metadataBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);ack=store.append(metadataBatch).toCompletableFuture();}
  boolean exactAck(DurableBatch batch,CompletableFuture<CommitAck> future){
   if(future==null||!future.isDone())return false;require(!future.isCompletedExceptionally(),"actual J04 ACK IO failed");var a=future.getNow(null);
   require(a!=null&&a.ackStatus()==AckStatus.DURABLE&&a.transactionId().equals(batch.transactionId())&&a.transactionHash().equals(batch.transactionHash())
    &&a.storeGeneration().equals(store.session().storeGeneration())&&a.durableSequence().value()>0&&a.permitAttempt().equals(batch.permitAttempt())
    &&a.forcedBlobs().equals(DurableBlobReferences.collect(batch.records())),"exact J04 transaction/hash/generation/refs ACK required");return true;
  }
  boolean acked(){return exactAck(metadataBatch,ack);}
  void read(){stales=0;chronology();}
  void chronology(){readPart=0;query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);reading=store.read(query).toCompletableFuture();}
  void retryRead(){require(++stales<=3,"private WAL repeatedly STALE");chronology();}
  boolean readDone(){
   if(!reading.isDone())return false;require(!reading.isCompletedExceptionally(),"WAL read failed");var result=reading.getNow(null);ReadContracts.validate(result,query,store.session());
   if(result.status()==ReadStatus.STALE){retryRead();return false;}require(result.status()==ReadStatus.AVAILABLE&&!result.page().orElseThrow().hasMore(),"bounded WAL page unavailable/too large");
   var page=result.page().orElseThrow();
   if(readPart==0){
    require(page.aggregates().isEmpty(),"chronology is not aggregate hydration");pending=page.records();chronologyStamp=result.stamp().orElseThrow();
    var keys=new HashSet<AggregateKey>();for(var row:pending){if(row instanceof DurableOwnership o)keys.add(new AggregateKey(AggregateKind.OWNERSHIP,o.value().header().id()));if(row instanceof DurablePrepare p)generationFloor=Math.max(generationFloor,p.value().reservationGeneration().value());}
    if(keys.isEmpty()){records=pending;return true;}require(keys.size()==2,"two actual owner projection IDs required");
    readPart=1;query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),4);reading=store.read(query).toCompletableFuture();return false;
   }
   var stamp=result.stamp().orElseThrow();if(!stamp.session().equals(chronologyStamp.session())||!stamp.snapshotSequence().equals(chronologyStamp.snapshotSequence())){retryRead();return false;}
   require(page.aggregates().size()==2,"point read requires two owner projections");owners.clear();
   for(var row:page.aggregates()){require(row.state() instanceof OwnershipAggregate,"foreign point projection");var o=((OwnershipAggregate)row.state()).value();owners.put(o.resourceKey(),o);}
   require(owners.size()==2,"distinct physical owner keys required");records=pending;return true;
  }
  List<DirectorFact> facts(FactType type){return records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).filter(f->f.factType().equals(type)).toList();}
  List<PrepareRecord> eventPrepares(){return records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value).filter(p->p.reservation().instanceId().value().equals(event)).toList();}
  List<DirectorFact> eventWaves(){return facts(WAVE).stream().filter(f->f.eventId().orElseThrow().value().equals(event)).toList();}
  DamageProfile profile(){return new DamageProfile(PROFILE,Set.of(new ResourceId("minecraft:in_fire")),1,6,new DurationTicks(20),List.of(),Set.of(fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING),ControlledFireWaveBinding.HIT_CODEC);}
  boolean nativeSafe(){
   if(footprint==null)return false;for(var c:footprint.loadedChunks())if(level.getChunkSource().getChunkNow(c.x(),c.z())==null)return false;
   var b=footprint.bounds().getFirst();return level.getEntities(null,new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())).isEmpty();
  }
  Map<BlockPosition,Long> seed(){var s=new HashMap<BlockPosition,Long>();for(var p:cells)s.put(p,p.equals(cells.getFirst())?args.energy().value():0L);return s;}
  void bind(){
   var original=new HashMap<BlockPosition,OwnershipRecord>();cells.forEach(p->original.put(p,initial.get(key(p))));
   boolean denied=false;ControlledFireWaveBinding forbidden=null;
   try{forbidden=new ControlledFireWaveBinding(server,world,new EventInstanceId(event),args.recipe(),mask,seed(),DirectorControlledFireBlocks.CONTROLLED_FIRE::get,
    ()->new OwnershipView(owners),this::publish,this::now,profile(),Set.of(),this::nativeSafe,1.0,original);}catch(IllegalArgumentException absentAudience){denied=true;}
   if(forbidden!=null)forbidden.close();require(denied,"production constructor must still reject empty actual audience");
   binding=ControlledFireWaveBinding.nativeFixture(server,world,new EventInstanceId(event),args.recipe(),mask,seed(),DirectorControlledFireBlocks.CONTROLLED_FIRE::get,
    ()->new OwnershipView(owners),this::publish,this::now,profile(),this::nativeSafe,1.0,original);
  }
  void fixture(){
   var encoded=System.getProperty("proutlost.director.nativeFireProviderFixture","");require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeFireProviderFixture=x,y,z to loaded empty disposable space");
   var a=encoded.split(",");var origin=new BlockPos(Integer.parseInt(a[0]),Integer.parseInt(a[1]),Integer.parseInt(a[2]));
   require(origin.getY()>level.getMinBuildHeight()+1&&origin.getY()<level.getMaxBuildHeight()-3,"fixture height");
   var box=new AABB(origin.getX()-2,origin.getY()-1,origin.getZ()-2,origin.getX()+4,origin.getY()+3,origin.getZ()+3);
   require(!box.intersects(h.getBounds().inflate(3,20,3))&&level.getEntities(null,box).isEmpty(),"fixture intersects current clearing/entities");
   var chunks=new HashSet<ChunkRef>();var dimension=new DimensionId(level.dimension().location().toString());
   for(int x=-2;x<=3;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++){
    var p=origin.offset(x,y,z);require(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null,"fixture must already be loaded");
    chunks.add(new ChunkRef(dimension,p.getX()>>4,p.getZ()>>4));require(attached.read(level,p).isEmpty(),"never overwrite native ownership");
    require(level.getBlockState(p).isAir()||(y==-1&&level.getBlockState(p).is(Blocks.STONE)),"never overwrite a checkpoint or existing content");
   }
   require(chunks.size()==1,"fixture and guards must fit one loaded chunk");
   for(int cx=(origin.getX()-66)>>4;cx<=(origin.getX()+68)>>4;cx++)for(int cz=(origin.getZ()-66)>>4;cz<=(origin.getZ()+67)>>4;cz++){
    var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
    for(var be:chunk.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
     require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"fixture intersects another clearing box");
   }
   int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
   for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++){
    var floor=origin.offset(x,-1,z);level.setBlock(floor,Blocks.STONE.defaultBlockState(),flags);require(level.getBlockState(floor).is(Blocks.STONE),"native test floor");
    if(x==-1||x==2||z==-1||z==1){var wall=origin.offset(x,0,z);require(level.setBlock(wall,Blocks.STONE.defaultBlockState(),flags),"native test wall");}
   }
   cells=List.of(new BlockPosition(dimension,origin.getX(),origin.getY(),origin.getZ()),new BlockPosition(dimension,origin.getX()+1,origin.getY(),origin.getZ()));
   // Real flammable sentries OUTSIDE the admitted mask; they must never burn or become fire.
   for(var p:cells)require(level.setBlock(pos(p).above(),Blocks.OAK_PLANKS.defaultBlockState(),flags),"outside-mask flammable sentry");
   footprint=new Footprint(dimension,chunks,Set.copyOf(cells),List.of(new Aabb(dimension,origin.getX()-2,origin.getY()-1,origin.getZ()-2,origin.getX()+4,origin.getY()+3,origin.getZ()+3)));
   mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,FOOTPRINT.encode(footprint));sentries.clear();
   for(int x=-2;x<=3;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++){
    var p=new BlockPosition(dimension,origin.getX()+x,origin.getY()+y,origin.getZ()+z);if(!cells.contains(p))sentries.put(p,hash(p));
   }
  }
  void assertSentries(){for(var entry:sentries.entrySet()){
   require(hash(entry.getKey()).equals(entry.getValue()),"outside-mask fuel/wall/air changed: "+entry.getKey());
   require(!(level.getBlockState(pos(entry.getKey())).getBlock() instanceof BaseFireBlock),"residual/spread fire outside mask");
  }}
  void assertPhysical(){
   for(var p:cells){require(level.getBlockState(pos(p)).equals(energy.cells().get(p)>0?DirectorControlledFireBlocks.CONTROLLED_FIRE.get().defaultBlockState():Blocks.AIR.defaultBlockState()),"actual fire cells differ from durable energy proposal");
    require(hash(p).equals(owners.get(key(p)).expectedPostHash())&&attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"native owner/hash mismatch");}
   assertSentries();
  }
  void assertSavedOwnerPost(){
   for(var p:cells){var o=owners.get(key(p));require(o!=null&&o.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE
    &&attached.read(level,pos(p)).equals(Optional.of(o))&&hash(p).equals(o.expectedPostHash()),"saved exact active owner POST changed during real native clock wait");}
  }
  void assertFrozenPost(){for(var entry:lastPlan.expectedPost().entrySet()){
   var p=cells.stream().filter(c->key(c).equals(entry.getKey())).findFirst().orElseThrow();require(hash(p).equals(entry.getValue()),"autonomous fire mutation before next authorized wave");
  }assertSentries();}
  void beginCampaign(String name,long total){
   if(binding!=null){binding.close();binding=null;}campaign=name;event=UUID.randomUUID();firstBoot=store.session().bootEpoch().value();waves=0;initial.clear();
   args=new ControlledFire(RECIPE,mask.reference(),new DurationTicks(total),PROFILE);var rows=new ArrayList<DurableRecord>();var token=new OwnerToken(UUID.randomUUID());
   for(var p:cells){var old=owners.get(key(p));if(old!=null)require(old.disposition()==OwnershipDisposition.RELEASED&&level.getBlockState(pos(p)).is(Blocks.AIR),"reuse only actually cleaned owned fixture");
    var o=new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),now()),key(p),Set.of(token),old==null?new Epoch(1):old.epoch().plus(1),
     OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty());
    initial.put(key(p),o);rows.add(new DurableOwnership(o));
   }append(rows,List.of());
  }
  PlanningSnapshot snapshot(){
   var actual=new HashMap<BlockPosition,TerrainCellSnapshot>();for(var p:cells){var o=owners.get(key(p));actual.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
   var terrain=new TerrainSnapshot(footprint,actual,List.of(),Map.of(),new Revision(1));var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
   return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL),new OwnershipView(owners),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of(mask)));
  }
  BlobContent blob(PreparedEffect p,BlobBundle b,ResourceId codec,String stage){return blob(p.durableBlobs(),b,codec,stage);}
  BlobContent blob(EffectBlobs refs,BlobBundle b,ResourceId codec,String stage){
   var matches=refs.snapshots().stream().filter(r->r.codecId().equals(codec)).map(r->b.contents().stream().filter(c->c.reference().equals(r)).findFirst().orElseThrow())
    .filter(c->text(object(c.bytes()),"stage").equals(stage)).toList();require(matches.size()==1,"one exact "+codec.value()+" "+stage+" blob");return matches.getFirst();
  }
  Energy decodeEnergy(BlobContent b){
   var data=object(b.bytes());require(text(data,"event").equals(event.toString())&&text(data,"mask").equals(mask.reference().hash().value()),"engine event/mask lineage");
   var values=((JsonValue.ObjectValue)data.get("energy")).values();require(values.size()==2,"two energy slots");var parsed=new HashMap<BlockPosition,Long>();
   for(var p:cells){long n=Long.parseLong(((JsonValue.Text)values.get(p.x()+","+p.y()+","+p.z())).value());require(n>=0,"nonnegative remaining energy");parsed.put(p,n);}
   return new Energy(Map.copyOf(parsed),Long.parseLong(text(data,"tick")),Long.parseLong(text(data,"cursor")),((JsonValue.Bool)data.get("started")).value());
  }
  void ledgerEmpty(BlobContent b){var v=object(b.bytes());require(text(v,"event").equals(event.toString())&&text(v,"profile").equals(PROFILE.value())
   &&((JsonValue.ObjectValue)v.get("players")).values().isEmpty(),"empty audience must produce no fabricated hit ledger/player");}
  Execution capture(boolean cleanup){
   var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),cleanup?ControlledFireWaveBinding.CLEANUP:ControlledFireWaveBinding.FIRE,1,args,footprint,new EventInstanceId(event));
   var captured=binding.capture(intent,snapshot());var p=captured.prepared().orElseThrow();
   var before=decodeEnergy(blob(p,captured.blobs(),ControlledFireWaveBinding.STATE_CODEC,"before"));var after=decodeEnergy(blob(p,captured.blobs(),ControlledFireWaveBinding.STATE_CODEC,"after"));
   require(before.equals(energy),"PRE energy/cursor/tick must match current acknowledged engine");
   long elapsed=before.started()?after.tick()-before.tick():0;
   require(elapsed>=0&&after.cursor()==before.cursor()+1&&after.tick()==p.snapshot().capturedTick().value(),"real monotone clock/cursor required");
   if(before.started()&&!cleanup)require(elapsed>=20,"actual native wave cadence must be at least20 ticks");
   long expected=cleanup?0:before.cells().values().stream().mapToLong(v->Math.max(0,v-Math.min(v,elapsed))).sum();
   require(after.cells().values().stream().mapToLong(Long::longValue).sum()==expected,"energy must decay by ACTUAL elapsed time; ignition cannot create energy");
   ledgerEmpty(blob(p,captured.blobs(),ControlledFireWaveBinding.HIT_CODEC,"before"));ledgerEmpty(blob(p,captured.blobs(),ControlledFireWaveBinding.HIT_CODEC,"after"));
   var proposal=object(captured.blobs().contents().stream().filter(b->b.reference().equals(p.durableBlobs().preparedPlan())).findFirst().orElseThrow().bytes());
   require(text(proposal,"rng_seed").equals(Long.toString(event.getMostSignificantBits()^after.cursor())),"forced RNG/cursor identity");
   for(var entry:p.preconditions().fingerprints().entrySet()){var cell=cells.stream().filter(c->key(c).equals(entry.getKey())).findFirst().orElseThrow();require(hash(cell).equals(entry.getValue()),"capture mutated the native PRE");}
   return new Execution(captured,after,cleanup);
  }
  final class Execution {
   final EffectPreparation captured;final PreparedEffect plan;final Energy after;final boolean cleanup;final StoreSession session;
   final ResourceArbiter arbiter;final ReservationToken reservation;final PrepareRecord prepare;final PreparedSlice slice;final EventRunner runner;final ServerEffectPort port;
   DurableBatch submittedPrepare,resultBatch;CompletableFuture<CommitAck> prepareAck,resultAck;LiveValidationSnapshot live;boolean finished;
   Execution(EffectPreparation captured,Energy after,boolean cleanup){
    this.captured=captured;plan=captured.prepared().orElseThrow();this.after=after;this.cleanup=cleanup;session=store.session();
    var i=plan.intent();var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
    arbiter=new ResourceArbiter(world,new Epoch(Math.max(generationFloor,session.bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){
     public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}});
    var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),
     now().plus(4000),plan.claims(),Map.of(scope,plan.budgetEstimate()));
    reservation=arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();generationFloor=reservation.generation().value();
    slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),footprint,plan.preconditions(),List.of(i),plan.budgetEstimate());
    prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),reservation.generation(),plan.preconditions(),reservation.expiryTick(),slice,reservation,captured.validation(),List.of(),
     cleanup?Set.of():Set.of(NoninvertibleEffect.DAMAGE),List.of(),plan.durableBlobs());
    live=new LiveValidationSnapshot(plan.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,now(),reservation.generation());
    var tracked=new DurableStore(){
     public CompletionStage<CommitAck> append(DurableBatch b){
      var future=store.append(b).toCompletableFuture();
      if(b.transactionId().equals(prepare.transactionId())){submittedPrepare=b;prepareAck=future;}
      else if(resultBatch!=null&&b.transactionId().equals(resultBatch.transactionId()))resultAck=future;
      return future;
     }
     public StoreReadResult view(StoreQuery q){return store.view(q);}public CompletionStage<StoreReadResult> read(StoreQuery q){return store.read(q);}
     public StoreSession session(){return store.session();}public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest q){return store.readBlobs(q);}
    };
    var authority=new CommitPermit.Authority(tracked,4);
    port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},x->live,Map.of(i.primitive(),binding),binding.capabilities(),4,
     x->new StabilizationLiveSnapshot(store.session(),live,new OwnershipView(owners)),m->{},(m,r)->{});
    port.hydrate(plan,captured.blobs());runner=new EventRunner(authority,tracked,port,arbiter,4);
   }
   void submit(){
    require(binding.revalidate(plan,captured.blobs())&&nativeSafe(),"native exact PRE/ownership before submit");
    boolean refused=false;try{port.apply(slice,null);}catch(RuntimeException noPermit){refused=true;}
    require(refused,"port without permit must reject");assertPreUnchanged();
    var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
    runner.submit(batch,plan.expectedPost(),live,result->{
     require(result.outcome()==Outcome.SUCCEEDED&&result.appliedOperationIds().equals(Set.of(plan.intent().operationToken()))&&result.observedHashes().equals(plan.expectedPost()),"only exact real physical success may be recorded");
     var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),result,Set.of(),List.of(),List.of())));
     for(var row:binding.resultRecords()){
      rows.add(row);if(row instanceof DurableFact d&&d.value().factType().equals(WAVE))
       require(((JsonValue.ObjectValue)d.value().payload().get("damage")).values().isEmpty(),"no fake/native fixture damage result");
     }
     resultBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());return resultBatch;
    });
    assertPreUnchanged(); // Submit/IO completion alone cannot execute a native effect.
   }
   void assertPreUnchanged(){for(var p:cells)require(hash(p).equals(plan.preconditions().fingerprints().get(key(p))),"native effect before permitted actor poll");assertSentries();}
   boolean poll(){
    require(session.equals(store.session()),"live J04 session changed during effect");live=new LiveValidationSnapshot(live.readSet(),live.loadedChunks(),live.health(),now(),reservation.generation());
    var status=runner.poll(prepare.transactionId(),live);
    require(status.stage()!=EventRunner.Stage.REJECTED&&status.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native FIRE requires reconciliation: "+status.failure());
    if(status.stage()!=EventRunner.Stage.COMMITTED)return false;
    require(exactAck(submittedPrepare,prepareAck)&&exactAck(resultBatch,resultAck),"both exact forced PREPARE and RESULT ACK required");
    if(!finished){binding.committed(plan.intent().operationToken());require(arbiter.release(reservation),"release after exact RESULT only");energy=after;lastPlan=plan;lastBlobs=captured.blobs();if(!cleanup)waves++;finished=true;}
    assertPhysical();return true;
   }
  }
  DirectorFact startFact(){
   var payload=new TreeMap<String,JsonValue>();payload.put("campaign",text(campaign));payload.put("plan",text(Base64.getEncoder().encodeToString(PLAN.encode(firstPlan))));
   payload.put("boot",text(firstBoot));payload.put("pid",text(ProcessHandle.current().pid()));payload.put("energy",text(args.energy().value()));
   payload.put("initial_owners",new JsonValue.Array(cells.stream().map(p->(JsonValue)text(Base64.getEncoder().encodeToString(OWNER.encode(initial.get(key(p)))))).toList()));
   var guards=new TreeMap<String,JsonValue>();sentries.forEach((p,hash)->guards.put(Base64.getEncoder().encodeToString(POSITION.encode(p)),text(hash.value())));payload.put("sentries",new JsonValue.ObjectValue(guards));
   payload.put("audience",new JsonValue.Array(List.of()));payload.put("damage",text("NOT_RUN"));payload.put("j06",new JsonValue.Bool(false));payload.put("full_disaster",new JsonValue.Bool(false));return fact(START,payload);
  }
  void closeBinding(){if(binding!=null){binding.close();binding=null;}}
  void closeStore(){require(!storeClosing,"private WAL close already started");storeClosing=true;closing=store.closeAsync().toCompletableFuture();}
  boolean closedStore(){if(!closing.isDone())return false;require(!closing.isCompletedExceptionally(),"private WAL close/flush failed");return true;}
  void save(String reason){
   for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"saving actual owner NBT differs from WAL projection");
   worldSaveAttempted=true;boolean saved=server.saveEverything(false,true,true);
   System.out.println("DIRECTOR_NATIVE_FIRE_PROVIDER_WORLD_SAVE reason="+reason+" event="+event+" world_id="+world.value()+" world="+server.getWorldPath(LevelResource.ROOT)
    +" boot="+store.session().bootEpoch().value()+" pid="+ProcessHandle.current().pid()+" save_return="+saved+" fresh_nbt_requires_external_reboot=true");
  }
  void restoreFireRule(){
   if(fireRuleRestored)return;
   var rule=level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);rule.set(originalFireTick,server);
   require(rule.get()==originalFireTick,"restore original LAB doFireTick");fireRuleRestored=true;
   // A checkpoint was saved while the actual rule was true: persist its restoration too.
   boolean flushed=!worldSaveAttempted||server.saveEverything(false,true,true);
   System.out.println("DIRECTOR_NATIVE_FIRE_TEST_RULE_RESTORED original="+originalFireTick+" current="+rule.get()+" world_save_attempted="+worldSaveAttempted+" save_return="+flushed);
  }
  void close(){ACTIVE.remove(server);try{closeBinding();}finally{try{if(store!=null&&!storeClosing)closeStore();}finally{restoreFireRule();}}}
  void verifyJournal(boolean cleaned){
   var all=eventPrepares();var replies=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value)
    .filter(r->all.stream().anyMatch(p->p.transactionId().equals(r.prepareTransactionId()))).toList();
   boolean ended=facts(END).stream().anyMatch(f->f.eventId().orElseThrow().value().equals(event));
   require(all.size()==(cleaned?4:3)&&replies.size()==all.size()-(ended?0:1),"two applied forwards, one forced unapplied PREPARE, new cleanup only after recovery");
   require(all.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.FIRE)).count()==3,"recovery must never replay/create a forward FIRE operation");
   require(all.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.CLEANUP)).count()==(cleaned?1:0),"one new cleanup only");
   for(var p:all){
    var reply=replies.stream().filter(r->r.prepareTransactionId().equals(p.transactionId())&&r.operationToken().equals(p.operationToken())).findFirst().orElse(null);
    if(p.operationToken().equals(withheldOperation)){
     if(ended)require(reply!=null&&reply.result().outcome()==Outcome.DELIVERY_UNCERTAIN&&reply.result().appliedOperationIds().isEmpty()&&reply.result().observedHashes().isEmpty(),"unmatched old PREPARE may close only uncertain, never fabricated success/absence");
     else require(reply==null,"withheld physical operation must have no fabricated RESULT");continue;
    }
    var expected=new HashMap<ResourceKey,Fingerprint>();p.durableBlobs().expectedPostStates().forEach((k,v)->expected.put(k,v.hash()));
    require(reply!=null&&reply.result().outcome()==Outcome.SUCCEEDED&&reply.result().appliedOperationIds().equals(Set.of(p.operationToken()))&&reply.result().observedHashes().equals(expected),"exact actual physical RESULT required");
    require(p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.CLEANUP)?p.noninvertibleEffects().isEmpty():p.noninvertibleEffects().equals(Set.of(NoninvertibleEffect.DAMAGE)),"potential damage must be declared and never replayed");
   }
   var observed=eventWaves();require(observed.size()==(cleaned?3:2),"only actual applied wave RESULT facts may persist");
   var cursors=observed.stream().map(f->Long.parseLong(text(f,"burn_cursor"))).sorted().toList();
   require(cursors.equals(cleaned?List.of(1L,2L,4L):List.of(1L,2L)),"reserved cursor3 is consumed but never claimed as an applied wave");
   for(var fact:observed)require(((JsonValue.ObjectValue)fact.payload().get("damage")).values().isEmpty(),"damage/human qualification remains NOT_RUN");
   if(cleaned)for(var p:cells)require(owners.get(key(p)).disposition()==OwnershipDisposition.RELEASED,"durable ownership release required");
  }
  void loadCheckpoint(boolean external){
   var starts=facts(START).stream().filter(f->text(f,"campaign").equals("PERSISTENT")).toList();
   require(starts.size()==1&&facts(CHECKPOINT).size()==1,"one real persistent checkpoint required");var start=starts.getFirst();var checkpoint=facts(CHECKPOINT).getFirst();
   event=start.eventId().orElseThrow().value();campaign="PERSISTENT";firstBoot=Long.parseLong(text(start,"boot"));withheldOperation=new OperationToken(UUID.fromString(text(checkpoint,"unapplied_operation")));
   require(checkpoint.eventId().equals(start.eventId())&&store.session().bootEpoch().value()>firstBoot,"same event and new J04 boot fence required");
   if(external)require(ProcessHandle.current().pid()!=Long.parseLong(text(start,"pid")),"recoveryOnly requires a genuinely new process, not just a reopened store");
   require(facts(END).stream().noneMatch(f->f.eventId().equals(start.eventId())),"persistent checkpoint already cleaned; never recreate forward gameplay");
   firstPlan=PLAN.decode(Base64.getDecoder().decode(text(start,"plan")));lastPlan=PLAN.decode(Base64.getDecoder().decode(text(checkpoint,"last_plan")));
   require(lastPlan.intent().instanceId().value().equals(event)&&firstPlan.intent().instanceId().value().equals(event)&&firstPlan.intent().typedArgs().equals(lastPlan.intent().typedArgs()),"sealed plans/event/typed recipe differ");
   args=(ControlledFire)firstPlan.intent().typedArgs();footprint=firstPlan.intent().expectedFootprint();
   require(footprint.equals(lastPlan.intent().expectedFootprint())&&footprint.cells().size()==2&&footprint.dimension().value().equals(level.dimension().location().toString()),"two exact same-world fire slots required");
   cells=footprint.cells().stream().sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::z)).toList();
   mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,FOOTPRINT.encode(footprint));require(mask.reference().equals(args.propagationMask()),"real captured propagation mask hash");
   initial.clear();for(var value:((JsonValue.Array)start.payload().get("initial_owners")).values()){var o=OWNER.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));initial.put(o.resourceKey(),o);}
   require(initial.size()==2,"original forced owner lineage required");sentries.clear();
   for(var entry:((JsonValue.ObjectValue)start.payload().get("sentries")).values().entrySet())sentries.put(POSITION.decode(Base64.getDecoder().decode(entry.getKey())),new Fingerprint(((JsonValue.Text)entry.getValue()).value()));
   for(var p:cells){level.getChunk(p.x()>>4,p.z()>>4);require(attached.read(level,pos(p)).equals(Optional.ofNullable(owners.get(key(p)))),"fresh saved chunk/ownership must match actual WAL POST");}
   require(nativeSafe(),"checkpoint fixture has a real entity/player or unloaded chunk");assertSentries();verifyJournal(false);
   var reserved=eventPrepares().stream().filter(p->p.operationToken().equals(withheldOperation)).findFirst().orElseThrow();
   require(reserved.readSet().fingerprints().equals(lastPlan.expectedPost()),"forced unapplied PREPARE must retain the actually saved last native POST as its PRE");
   for(var entry:lastPlan.expectedPost().entrySet()){var p=cells.stream().filter(c->key(c).equals(entry.getKey())).findFirst().orElseThrow();require(hash(p).equals(entry.getValue()),"saved native POST differs from sealed last physical plan");}
   var last=eventWaves().stream().max(Comparator.comparingLong((DirectorFact f)->Long.parseLong(text(f,"burn_cursor")))).orElseThrow();
   var bytes=Base64.getDecoder().decode(text(last,"state_base64"));energy=decodeEnergy(BlobContent.capture(ControlledFireWaveBinding.STATE_CODEC,1,bytes));
   ledgerEmpty(BlobContent.capture(ControlledFireWaveBinding.HIT_CODEC,1,Base64.getDecoder().decode(text(last,"hit_ledger_base64"))));
   require(energy.cursor()==2&&energy.cells().values().stream().mapToLong(Long::longValue).sum()>0,"checkpoint requires real positive acknowledged energy/cursor2");assertPhysical();
  }
  /** Exact recovery of the observed one-result EXPIRY failure. Never scans orphan blobs. */
  void loadPartial(){
   var starts=facts(START);require(starts.size()==1&&facts(CHECKPOINT).isEmpty()&&facts(END).isEmpty(),"partial recovery requires one unended START, no fabricated checkpoint");
   var start=starts.getFirst();require(text(start,"campaign").equals("EXPIRY"),"partial mode requires the original failed EXPIRY campaign");
   event=start.eventId().orElseThrow().value();campaign="EXPIRY_PARTIAL_RECOVERY";
   var pin=System.getProperty("proutlost.director.nativeFireProviderPartialEvent","");
   require(!pin.isEmpty()&&UUID.fromString(pin).equals(event),"pin exact observed nativeFireProviderPartialEvent before partial cleanup");
   firstBoot=Long.parseLong(text(start,"boot"));
   require(store.session().bootEpoch().value()>firstBoot&&ProcessHandle.current().pid()!=Long.parseLong(text(start,"pid")),"partial cleanup requires real reopened WAL and a new process");
   firstPlan=PLAN.decode(Base64.getDecoder().decode(text(start,"plan")));lastPlan=firstPlan;
   require(firstPlan.intent().primitive().equals(ControlledFireWaveBinding.FIRE)&&firstPlan.intent().instanceId().value().equals(event),"original forced fire identity");
   args=(ControlledFire)firstPlan.intent().typedArgs();footprint=firstPlan.intent().expectedFootprint();
   require(footprint.cells().size()==2&&footprint.dimension().value().equals(level.dimension().location().toString()),"exact saved two-cell fire scope");
   cells=footprint.cells().stream().sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::z)).toList();
   mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,FOOTPRINT.encode(footprint));
   require(mask.reference().equals(args.propagationMask()),"original mask, never a new fixture");
   initial.clear();for(var value:((JsonValue.Array)start.payload().get("initial_owners")).values()){
    var owner=OWNER.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));initial.put(owner.resourceKey(),owner);
   }
   require(initial.size()==2,"two original admitted owner rows");sentries.clear();
   for(var entry:((JsonValue.ObjectValue)start.payload().get("sentries")).values().entrySet())
    sentries.put(POSITION.decode(Base64.getDecoder().decode(entry.getKey())),new Fingerprint(((JsonValue.Text)entry.getValue()).value()));
   for(var p:cells){
    level.getChunk(p.x()>>4,p.z()>>4);var local=attached.read(level,pos(p)).orElseThrow();var durable=owners.get(key(p));var original=initial.get(key(p));
    require(local.equals(durable)&&local.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&local.header().id().equals(original.header().id())
     &&local.ownerTokens().equals(original.ownerTokens())&&local.epoch().equals(original.epoch())&&local.playerOverrideEpoch().equals(original.playerOverrideEpoch())
     &&local.creationMutationId().equals(original.creationMutationId()),"saved exact original-lineage active POST, never adopt a foreign/player owner");
    require(hash(p).equals(firstPlan.expectedPost().get(key(p)))&&hash(p).equals(local.expectedPostHash()),"actual saved POST differs from original successful result");
   }
   require(nativeSafe(),"saved partial fixture unavailable/occupied");assertSentries();verifyPartial(false,false);
   var wave=eventWaves().getFirst();require(text(wave,"operation").equals(firstPlan.intent().operationToken().value().toString()),"committed wave/prepare identity");
   energy=decodeEnergy(BlobContent.capture(ControlledFireWaveBinding.STATE_CODEC,1,Base64.getDecoder().decode(text(wave,"state_base64"))));
   require(energy.cursor()==1&&energy.cells().values().stream().mapToLong(Long::longValue).sum()>0,"one actually committed positive-energy wave");
   ledgerEmpty(BlobContent.capture(ControlledFireWaveBinding.HIT_CODEC,1,Base64.getDecoder().decode(text(wave,"hit_ledger_base64"))));assertPhysical();
   bind();binding.restoreCommittedState(Base64.getDecoder().decode(text(wave,"state_base64")),Base64.getDecoder().decode(text(wave,"hit_ledger_base64")));
   var old=eventPrepares().getFirst();require(old.operationToken().equals(firstPlan.intent().operationToken())&&old.durableBlobs().equals(firstPlan.durableBlobs())
    &&old.readSet().equals(firstPlan.preconditions())&&old.slice().operations().equals(List.of(firstPlan.intent())),"dossier must match the actual durable PREPARE exactly");
   requestBlobs(old);phase=40;
  }
  void verifyPartial(boolean cleaned,boolean ended){
   var ps=eventPrepares();var rs=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value)
    .filter(r->ps.stream().anyMatch(p->p.transactionId().equals(r.prepareTransactionId()))).toList();
   require(ps.size()==(cleaned?2:1)&&rs.size()==ps.size(),"partial WAL permits one original success and at most one NEW cleanup");
   require(ps.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.FIRE)).count()==1,"partial recovery must never replay forward FIRE");
   require(ps.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.CLEANUP)).count()==(cleaned?1:0),"exactly one fresh inverse when cleaned");
   for(var p:ps){
    var r=rs.stream().filter(x->x.prepareTransactionId().equals(p.transactionId())&&x.operationToken().equals(p.operationToken())).findFirst().orElseThrow();
    var expected=new HashMap<ResourceKey,Fingerprint>();p.durableBlobs().expectedPostStates().forEach((k,v)->expected.put(k,v.hash()));
    require(r.result().outcome()==Outcome.SUCCEEDED&&r.result().appliedOperationIds().equals(Set.of(p.operationToken()))&&r.result().observedHashes().equals(expected),"partial journal needs exact actual physical results");
   }
   var waves=eventWaves();require(waves.size()==(cleaned?2:1)&&waves.stream().map(f->Long.parseLong(text(f,"burn_cursor"))).sorted().toList().equals(cleaned?List.of(1L,2L):List.of(1L)),"only actual old cursor1 and new cleanup cursor2");
   for(var f:waves)require(((JsonValue.ObjectValue)f.payload().get("damage")).values().isEmpty(),"partial human damage remains NOT_RUN");
   require(facts(END).stream().filter(f->f.eventId().orElseThrow().value().equals(event)).count()==(ended?1:0),"partial END must be real durable metadata");
   if(cleaned)for(var p:cells){
    var o=owners.get(key(p));var original=initial.get(key(p));require(o.disposition()==OwnershipDisposition.RELEASED&&o.ownerTokens().equals(original.ownerTokens())&&o.epoch().equals(original.epoch())
     &&o.playerOverrideEpoch().equals(original.playerOverrideEpoch())&&level.getBlockState(pos(p)).is(Blocks.AIR)&&attached.read(level,pos(p)).equals(Optional.of(o)),"exact AIR/RELEASED before any new event");
   }
  }
  void requestBlobs(PrepareRecord p){hydratingPlan=p;expectedHydration=DurableBlobReferences.collect(p);hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),expectedHydration)).toCompletableFuture();}
  BlobBundle hydrated(){
   require(!hydrating.isCompletedExceptionally(),"forced original blob IO failed");var loaded=hydrating.getNow(null);
   require(loaded!=null&&loaded.status()==AckStatus.DURABLE&&loaded.errors().isEmpty()&&loaded.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(expectedHydration),"exact original forced PREPARE bytes required");
   return new BlobBundle(loaded.contents());
  }
  void poll(){
   require(server.isSameThread(),"native FIRE actor");if(done)return;
   require(!fireRuleRestored&&level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK),"actual doFireTick must remain TRUE throughout native FIRE qualification");
   switch(phase){
    case 0->{if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"private J04 open");store=opening.getNow(null);require(!store.readOnlySafe(),"private J04 unavailable");read();phase=1;}
    case 1->{
     if(!readDone())return;
     if(partial){loadPartial();return;}
     if(recovery){
      loadCheckpoint(true);bind();var last=eventWaves().stream().max(Comparator.comparingLong((DirectorFact f)->Long.parseLong(text(f,"burn_cursor")))).orElseThrow();
      binding.restoreCommittedState(Base64.getDecoder().decode(text(last,"state_base64")),Base64.getDecoder().decode(text(last,"hit_ledger_base64")));
      oldPlans.addAll(eventPrepares());require(oldPlans.size()==3,"three original forward PREPAREs, only two applied");requestBlobs(oldPlans.removeFirst());phase=20;return;
     }
     require(records.isEmpty(),"fresh native test must not overwrite an existing WAL; use recoveryOnly");fixture();beginCampaign("EXPIRY",80);phase=2;
    }
    case 2->{
     if(!acked())return;for(var p:cells)publish(p,initial.get(key(p)));bind();energy=new Energy(Map.copyOf(seed()),now().value(),0,false);
     work=capture(false);firstPlan=work.plan;append(List.of(new DurableFact(startFact())),List.of());phase=3;
    }
    case 3->{if(!acked())return;work.submit();phase=4;}
    case 4->{
     if(!work.poll())return;
     if(waves==1)require(cells.stream().allMatch(p->level.getBlockState(pos(p)).is(DirectorControlledFireBlocks.CONTROLLED_FIRE.get())),"initial directed ignition must physically light BOTH cells");
     if(campaign.equals("EXPIRY")){
      if(energy.cells().values().stream().allMatch(v->v==0)){require(waves>=2,"expiry requires actual elapsed waves");work=capture(true);work.submit();phase=6;}
      else phase=5;
     }else if(waves<2)phase=5;
     else{
      require(energy.cells().values().stream().mapToLong(Long::longValue).sum()>0,"fsync latency consumed all energy; cannot fabricate an active checkpoint");
      phase=30;
     }
    }
    case 5->{
     assertFrozenPost();if(now().value()-energy.tick()<20)return;work=capture(false);work.submit();phase=4;
    }
    case 6->{
     if(!work.poll())return;require(energy.cells().values().stream().allMatch(v->v==0)&&cells.stream().allMatch(p->level.getBlockState(pos(p)).is(Blocks.AIR)),"real finite-energy extinction/cleanup");
     for(var p:cells)require(owners.get(key(p)).disposition()==OwnershipDisposition.RELEASED&&owners.get(key(p)).epoch().equals(initial.get(key(p)).epoch())&&owners.get(key(p)).playerOverrideEpoch().equals(initial.get(key(p)).playerOverrideEpoch()),"cleanup must release exact original lineage without player override fiction");
     append(List.of(new DurableFact(fact(END,Map.of("campaign",text("EXPIRY"),"finite_energy_extinguished",new JsonValue.Bool(true),"damage",text("NOT_RUN"),"cursor",text(energy.cursor()))))),List.of());phase=7;
    }
    case 7->{
     if(!acked())return;System.out.println("DIRECTOR_NATIVE_FIRE_FINITE_ENERGY_EXPIRY_PROVIDER_PASS waves="+waves+" cursor="+energy.cursor()+" damage=NOT_RUN j06=false full_disaster=false");
     beginCampaign("PERSISTENT",400);phase=2;
    }
    case 8->{if(!acked())return;closeBinding();closeStore();phase=9;}
    case 9->{if(!closedStore())return;reopening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=10;}
    case 10->{if(!reopening.isDone())return;require(!reopening.isCompletedExceptionally(),"same WAL reopen");store=reopening.getNow(null);storeClosing=false;read();phase=11;}
    case 11->{if(!readDone())return;loadCheckpoint(false);save("ACTIVE_TWO_WAVE_CHECKPOINT");closeStore();phase=12;}
    case 12->{
     if(!closedStore())return;restoreFireRule();System.out.println("DIRECTOR_NATIVE_FIRE_WAVES_WAL_CHECKPOINT_PASS event="+event+" cursor="+energy.cursor()+" damage=NOT_RUN player_fire_tail=NOT_RUN j06=false full_disaster=false fresh_nbt=false forced_unapplied_prepare=true g5=false");
     done=true;ACTIVE.remove(server);phase=99;
    }
    case 20->{
     if(!hydrating.isDone())return;var bytes=hydrated();
     if(hydratingPlan.operationToken().equals(lastPlan.intent().operationToken())){
      lastBlobs=bytes;var evidence=new ArrayList<RecoveryEvidence>();
      for(var p:cells){var o=owners.get(key(p));evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),Optional.of(o.ownerTokens().iterator().next()),o.epoch(),o.playerOverrideEpoch(),false));}
      require(binding.reconcile(new RecoveryProbe(lastPlan,evidence,bytes)).status()==ReconcileStatus.SAME_POST,"fresh saved native POST must exactly reconcile, without invented absence");
     }
     binding.recoverCleanupBudget(hydratingPlan,bytes);
     var reserved=decodeEnergy(blob(hydratingPlan.durableBlobs(),bytes,ControlledFireWaveBinding.STATE_CODEC,"after"));
     if(reserved.cursor()>energy.cursor())energy=reserved; // Conservative budget floor, NOT a claim of native application.
     if(!oldPlans.isEmpty()){requestBlobs(oldPlans.removeFirst());return;}
     require(lastBlobs!=null,"last POST bytes missing");
     boolean rejected=false;try{binding.capture(new EffectIntent(new OperationToken(UUID.randomUUID()),ControlledFireWaveBinding.FIRE,1,args,footprint,new EventInstanceId(event)),snapshot());}catch(IllegalArgumentException noReplay){rejected=true;}
     require(rejected,"recovered binding must be cleanup-only, not a new fire/damage authorization");require(energy.cursor()==3,"forced unapplied energy/cursor reservation must be consumed");assertFrozenPost();
     System.out.println("DIRECTOR_NATIVE_FIRE_CLOCK_WAIT mode=cold current="+now().value()+" required="+energy.tick());phase=26;
    }
    case 21->{if(!work.poll())return;require(energy.cells().values().stream().allMatch(v->v==0),"recovered cleanup energy zero");read();phase=22;}
    case 22->{
     if(!readDone())return;verifyJournal(true);assertPhysical();
     var withheld=eventPrepares().stream().filter(p->p.operationToken().equals(withheldOperation)).findFirst().orElseThrow();
     var uncertain=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
     append(List.of(new DurableResult(new ResultRecord(withheld.transactionId(),withheld.operationToken(),uncertain,Set.of(),List.of(),List.of())),
      new DurableFact(fact(END,Map.of("campaign",text("PERSISTENT"),"cleanup",text("OWNED_AIR_RELEASED"),"forward_replayed",new JsonValue.Bool(false),"damage_replayed",new JsonValue.Bool(false),"damage",text("NOT_RUN"))))),List.of());phase=23;
    }
    case 23->{if(!acked())return;read();phase=25;}
    case 25->{if(!readDone())return;verifyJournal(true);assertPhysical();save("SAME_WORLD_CLEANUP_ONLY");closeBinding();closeStore();phase=24;}
    case 24->{
     if(!closedStore())return;restoreFireRule();System.out.println("DIRECTOR_NATIVE_FIRE_SAME_WORLD_CLEANUP_NO_DAMAGE_REPLAY_PASS event="+event+" damage=NOT_RUN player_fire_tail=NOT_RUN j06=false full_disaster=false fresh_nbt=true g5=false");
     done=true;ACTIVE.remove(server);phase=99;
    }
    case 30->{assertFrozenPost();if(now().value()-energy.tick()<20)return;work=capture(false);withheldOperation=work.prepare.operationToken();work.submit();phase=31;}
    case 31->{
     if(!exactAck(work.submittedPrepare,work.prepareAck))return;work.assertPreUnchanged();
     // Intentionally DO NOT poll EventRunner: the real fsync PREPARE has no application/RESULT.
     append(List.of(new DurableFact(fact(CHECKPOINT,Map.of("last_plan",text(Base64.getEncoder().encodeToString(PLAN.encode(lastPlan))),
      "unapplied_operation",text(withheldOperation.value()),"cursor",text(energy.cursor()),"damage",text("NOT_RUN"),"client",new JsonValue.Bool(false))))),List.of());phase=8;
    }
    case 40->{
     if(!hydrating.isDone())return;var bytes=hydrated();lastBlobs=bytes;var evidence=new ArrayList<RecoveryEvidence>();
     for(var p:cells){var o=owners.get(key(p));evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),Optional.of(o.ownerTokens().iterator().next()),o.epoch(),o.playerOverrideEpoch(),false));}
     require(binding.reconcile(new RecoveryProbe(firstPlan,evidence,bytes)).status()==ReconcileStatus.SAME_POST,"partial saved POST must reconcile exactly with legacy original blobs, no false absence proof");
     binding.recoverCleanupBudget(hydratingPlan,bytes);energy=decodeEnergy(blob(hydratingPlan.durableBlobs(),bytes,ControlledFireWaveBinding.STATE_CODEC,"after"));
     boolean rejected=false;try{binding.capture(new EffectIntent(new OperationToken(UUID.randomUUID()),ControlledFireWaveBinding.FIRE,1,args,footprint,new EventInstanceId(event)),snapshot());}catch(IllegalArgumentException cleanupOnly){rejected=true;}
     require(rejected,"partial continuation cannot acquire a forward damage/ignition authorization");assertPhysical();
     System.out.println("DIRECTOR_NATIVE_FIRE_CLOCK_WAIT mode=partial current="+now().value()+" required="+energy.tick());phase=45;
    }
    case 41->{if(!work.poll())return;require(energy.cursor()==2&&energy.cells().values().stream().allMatch(v->v==0),"new actual partial cleanup cursor2/energy0");read();phase=42;}
    case 42->{
     if(!readDone())return;verifyPartial(true,false);assertPhysical();
     append(List.of(new DurableFact(fact(END,Map.of("campaign",text("EXPIRY_PARTIAL_RECOVERY"),"cleanup",text("EXACT_SAVED_POST_TO_AIR_RELEASED"),
      "damage_replayed",new JsonValue.Bool(false),"forward_replayed",new JsonValue.Bool(false),"fixture_rebuilt",new JsonValue.Bool(false),"damage",text("NOT_RUN"))))),List.of());phase=43;
    }
    case 43->{if(!acked())return;read();phase=44;}
    case 44->{
     if(!readDone())return;verifyPartial(true,true);assertPhysical();save("PARTIAL_OLD_EVENT_CLEANED_NO_REPLAY");
     System.out.println("DIRECTOR_NATIVE_FIRE_PARTIAL_SAME_WORLD_CLEANUP_NO_REPLAY_PASS event="+event+" old_cursor=1 cleanup_cursor=2 fixture_rebuilt=false damage=NOT_RUN");
     // New event only AFTER exact physical AIR + durable RELEASED + durable old END.
     // Reuses the same native cells/sentries/owners/WAL; fixture() is NEVER called in partial mode.
     beginCampaign("EXPIRY",80);phase=2;
    }
    case 26->{
     assertFrozenPost();assertSavedOwnerPost();if(now().value()<energy.tick())return;
     work=capture(true);work.submit();phase=21;
    }
    case 45->{
     assertPhysical();assertSavedOwnerPost();if(now().value()<energy.tick())return;
     work=capture(true);work.submit();phase=41;
    }
    case 99->{}
    default->throw new IllegalStateException("native FIRE phase");
   }
  }
 }
}
