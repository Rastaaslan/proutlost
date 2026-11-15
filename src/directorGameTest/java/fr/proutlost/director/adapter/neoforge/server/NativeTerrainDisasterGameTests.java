package fr.proutlost.director.adapter.neoforge.server;

import com.mojang.authlib.GameProfile;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/** Native provider qualification, NOT a full disaster/client/G5 qualification.
 * First boot leaves exact physical POST in a saved persistent fixture outside
 * every GameTest clearing box. Re-run the SAME gameDirectory with recoveryOnly.
 * No fake network client, receipt, warning evidence, natural eligibility or hurt.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class NativeTerrainDisasterGameTests {
 private NativeTerrainDisasterGameTests(){}
 private static final Set<net.minecraft.server.MinecraftServer> ACTIVE=Collections.synchronizedSet(
  Collections.newSetFromMap(new IdentityHashMap<>()));
 /** Test-only wall-clock pacing is needed only while this physical probe exists. */
 public static boolean active(net.minecraft.server.MinecraftServer server){return ACTIVE.contains(server);}
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static final PrimitiveId ID=new PrimitiveId("proutlost:landslide");
 private static final ResourceId RECIPE=new ResourceId("proutlost_director:native_collapse_fixture");
 private static final CapabilityId TERRAIN=new CapabilityId("mutation.terrain_v1");
 private static final FactType START=new FactType("proutlost_director:native_terrain_checkpoint");
 private static final FactType END=new FactType("proutlost_director:native_terrain_cleanup");
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,4096);

 @GameTestGenerator
 public static Collection<TestFunction> nativeTerrainTests(){
  if(!Boolean.getBoolean("proutlost.director.nativeTerrainGameTest"))return List.of();
  boolean recovery=Boolean.getBoolean("proutlost.director.nativeTerrainRecoveryOnly");
  return List.of(new TestFunction("native_terrain_persistent",
   "proutlost_director.native_collapse_"+(recovery?"saved_verified_post_resume_then_inverse":"physical_checkpoint"),
   "proutlost_director:smoke_empty",Rotation.NONE,12000,0,true,helper->{
    var p=new Probe(helper,recovery);
    helper.succeedWhen(()->{try{p.poll();}catch(RuntimeException failed){System.out.println("DIRECTOR_NATIVE_TERRAIN_PROBE_FAILURE phase="+p.phase+" reason="+failed);p.close();throw failed;}
     helper.assertTrue(p.done,"native terrain pending phase="+p.phase);});
   }));
 }
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static JsonValue.Text text(Object v){return new JsonValue.Text(v.toString());}
 private static final class Probe {
  final GameTestHelper h;final boolean recovery;final ServerLevel level;
  final net.minecraft.server.MinecraftServer server;final WorldId world;final Path path;
  final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
  final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>(),initial=new HashMap<>();
  final Map<MutationId,MutationRecord> mutations=new HashMap<>();
  final Map<MutationId,RestoredAggregate> restored=new HashMap<>();
  final CompletableFuture<JournalStore> opening;
  JournalStore store;UUID event;PreparedEffect original;BlobBundle originalBlobs;
  List<BlockPosition> cells=List.of();Set<BlockPosition> sources=Set.of();
  CompletableFuture<CommitAck> ack;CompletableFuture<Void> closing;
  CompletableFuture<JournalStore> reopening;CompletableFuture<StoreReadResult> reading;
  CompletableFuture<BlobReadResult> hydrating;StoreQuery query;
  StoreSession readSession;Sequence readCut;int readPhase,readRetries;
  List<DurableRecord> records=List.of();long firstBoot;int phase;boolean done;
  Execution work;BlockTransformBinding base;
  Probe(GameTestHelper h,boolean recovery){
   this.h=h;this.recovery=recovery;level=h.getLevel();server=level.getServer();ACTIVE.add(server);
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-terrain-test-only");
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();
  }
  void require(boolean ok,String why){h.assertTrue(ok,why);}
  Tick now(){return new Tick(server.overworld().getGameTime());}
  Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level.getBlockState(pos(p))).reference().hash();}
  void publish(BlockPosition p,OwnershipRecord owner){owners.put(key(p),owner);attached.write(level,pos(p),owner);}
  void append(List<DurableRecord> rows,List<BlobContent> blobs){
   ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs)).toCompletableFuture();
  }
  boolean acked(){
   if(!ack.isDone())return false;
   require(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE,"real J04 metadata ACK required");
   return true;
  }
  void read(){readRetries=0;beginRead();}
  void beginRead(){
   readSession=store.session();readCut=null;readPhase=0;
   query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);
   reading=store.read(query).toCompletableFuture();
  }
  boolean retryRead(){
   require(++readRetries<=8,"native terrain coherent-read retry bound");
   beginRead();return false;
  }
  boolean readDone(){
   if(!reading.isDone())return false;
   require(!reading.isCompletedExceptionally()&&store.session().equals(readSession),"J04 read/session failed");
   var r=reading.getNow(null);ReadContracts.validate(r,query,readSession);
   if(r.status()==ReadStatus.STALE)return retryRead();
   require(r.status()==ReadStatus.AVAILABLE&&!r.page().orElseThrow().hasMore(),"bounded fixture J04 page unavailable");
   var page=r.page().orElseThrow();
   if(readPhase==0){
    // Empty key-set requests chronology ONLY. Derive actual aggregate identities
    // from those durable rows before asking for the explicit memory projections.
    records=page.records();readCut=page.lastDurableSequence();
    var keys=new HashSet<AggregateKey>();
    for(var row:records){
     if(row instanceof DurableOwnership o)keys.add(new OwnershipAggregate(o.value()).key());
     if(row instanceof DurableMutation m)keys.add(new MutationAggregate(m.value()).key());
    }
    require(keys.size()<=8,"native fixture aggregate bound");
    if(keys.isEmpty()){owners.clear();restored.clear();return true;}
    readPhase=1;query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),128);
    reading=store.read(query).toCompletableFuture();return false;
   }
   if(!page.lastDurableSequence().equals(readCut))return retryRead();
   var projectedOwners=new HashMap<ResourceKey,OwnershipRecord>();var projectedMutations=new HashMap<MutationId,RestoredAggregate>();
   for(var row:page.aggregates()){
    require(row.committedSequence().compareTo(readCut)<=0,"aggregate beyond coherent WAL cut");
    if(row.state() instanceof MutationAggregate m)projectedMutations.put(m.value().mutationId(),row);
    if(row.state() instanceof OwnershipAggregate o)projectedOwners.put(o.value().resourceKey(),o.value());
   }
   require(page.aggregates().size()==query.aggregateKeys().size(),"explicit aggregate projection incomplete");
   owners.clear();owners.putAll(projectedOwners);restored.clear();restored.putAll(projectedMutations);
   return true;
  }
  void setCells(PreparedEffect plan){
   cells=plan.intent().expectedFootprint().cells().stream().sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();
   require(cells.size()==4,"exactly four collapse cells");
   var stone=BlockTransformBinding.stateBlob(Blocks.STONE.defaultBlockState()).reference().hash();
   sources=cells.stream().filter(p->plan.preconditions().fingerprints().get(key(p)).equals(stone)).collect(java.util.stream.Collectors.toSet());
   require(sources.size()==2,"two actual original STONE source cells");
  }
  void fixture(){
   String encoded=System.getProperty("proutlost.director.nativeTerrainFixture","");
   require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeTerrainFixture=x,y,z to an already loaded clear supported persistent area");
   var xyz=encoded.split(",");var origin=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
   var box=new AABB(origin.getX()-3,origin.getY()-3,origin.getZ()-3,origin.getX()+5,origin.getY()+2,origin.getZ()+4);
   require(!box.intersects(h.getBounds().inflate(3,20,3)),"persistent terrain fixture intersects current clearing box");
   var dimension=new DimensionId(level.dimension().location().toString());
   var selected=new ArrayList<BlockPosition>();
   for(int x=-3;x<=4;x++)for(int z=-3;z<=3;z++){
    var floor=origin.offset(x,-3,z);
    require(level.getChunkSource().getChunkNow(floor.getX()>>4,floor.getZ()>>4)!=null,"fixture must already be loaded");
    require(level.getBlockState(floor).is(Blocks.STONE)||level.getBlockState(floor).isAir(),"fixture support may only reuse STONE or explicit empty test space");
    require(attached.read(level,floor).isEmpty(),"new test fixture cannot overwrite existing floor provenance");
    for(int y=1;y<=4;y++){
     var p=floor.above(y);require(level.getBlockState(p).isAir(),"new fixture must be empty; never overwrite saved checkpoint");
     require(attached.read(level,p).isEmpty(),"new fixture cannot overwrite any saved provenance");
    }
   }
   for(int cx=(origin.getX()-4)>>4;cx<=(origin.getX()+5)>>4;cx++)for(int cz=(origin.getZ()-4)>>4;cz<=(origin.getZ()+4)>>4;cz++){
    var loaded=level.getChunkSource().getChunkNow(cx,cz);if(loaded==null)continue;
    for(var be:loaded.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
     require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"persistent fixture intersects another clearing box");
   }
   // Explicit initial GameTest fixture floor, before any START/admission; never executed in recoveryOnly.
   for(int x=-3;x<=4;x++)for(int z=-3;z<=3;z++)if(level.getBlockState(origin.offset(x,-3,z)).isAir())
    level.setBlock(origin.offset(x,-3,z),Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
   for(int x=0;x<=1;x++){
    var source=origin.east(x);var dest=source.below(2);
    require(level.getEntities(null,new AABB(source).minmax(new AABB(dest))).isEmpty(),"fixture occupied");
    level.setBlock(source,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
    selected.add(new BlockPosition(dimension,source.getX(),source.getY(),source.getZ()));
    selected.add(new BlockPosition(dimension,dest.getX(),dest.getY(),dest.getZ()));
   }
   cells=List.copyOf(selected);sources=Set.of(cells.get(0),cells.get(2));
   // Explicit disposable test setup only. No gameplay command constructs these cells.
  }
  void noClientGate(){
   var source=sources.iterator().next();
   var mock=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"TerrainNoClient"),ClientInformation.createDefault()){
    @Override public boolean hasPermissions(int permission){return permission<=4;}
   };
   mock.moveTo(source.x()+.5,source.y()-2.,source.z()+.5,0F,-90F);
   var layout=NativeTerrainDisasterLayout.survey(mock,DisasterRunner.Family.COLLAPSE);
   require(layout.sources().size()==2&&layout.destinations().size()==2,"actual ray/survey must see two ceiling pairs");
   var coordinator=new DirectorNativeTerrainDisasterGameplay(server,world,store,attached,new HashMap<>());
   coordinator.useSharedRecoveryScan();coordinator.acceptRecoveryRows(List.of(),true);
   boolean refused=false;
   try{coordinator.begin(mock,DisasterRunner.Family.COLLAPSE);}catch(IllegalArgumentException expected){refused=true;}
   finally{coordinator.close();}
   require(refused,"manual coordinator accepted mock without real connected audience");
   boolean warningDenied=false;
   try{new NativeWarningBinding(server,new PlayerId(mock.getUUID()),new PerceptionTransform(source.x()+.5,source.y(),source.z()+.5,0,0,2),1200);}
   catch(IllegalStateException expected){warningDenied=true;}
   require(warningDenied,"real J06 bootstrap accepted a renderer/session that does not exist");
   for(var p:cells)require(level.getBlockState(pos(p)).is(sources.contains(p)?Blocks.STONE:Blocks.AIR),"no-client request mutated terrain");
   System.out.println("DIRECTOR_NATIVE_TERRAIN_NO_CLIENT_REFUSED_NO_ACK no_human_authorization=true full_warn=false g5=false");
  }
  Footprint footprint(){
   var dim=cells.getFirst().dimension();var chunks=new HashSet<ChunkRef>();cells.forEach(p->chunks.add(new ChunkRef(dim,p.x()>>4,p.z()>>4)));
   require(chunks.size()==1,"persistent physical fixture must fit one chunk");
   return new Footprint(dim,chunks,new HashSet<>(cells),List.of());
  }
  PlanningSnapshot snapshot(){
   var fp=footprint();var observed=new HashMap<BlockPosition,TerrainCellSnapshot>();
   for(var p:cells){var o=Objects.requireNonNull(owners.get(key(p)));observed.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
   var terrain=new TerrainSnapshot(fp,observed,List.of(),Map.of(),new Revision(1));
   var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
   return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),fp.loadedChunks(),HealthMode.NORMAL),
    new OwnershipView(owners),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
  }
  LandslideBinding binding(boolean inverse){
   var replacements=inverse?Map.of(new ResourceId("minecraft:air"),new ResourceId("minecraft:stone"),new ResourceId("minecraft:cobblestone"),new ResourceId("minecraft:air")):
    Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:air"),new ResourceId("minecraft:air"),new ResourceId("minecraft:cobblestone"));
   base=new BlockTransformBinding(d->level,()->new OwnershipView(owners),this::publish,this::now,
    Map.of(RECIPE,new BlockTransformBinding.Recipe(replacements,false)),4,ID,Set.of(TERRAIN));
   return new LandslideBinding(base,server,new PlayerId(UUID.nameUUIDFromBytes("NO_NATIVE_CLIENT".getBytes(java.nio.charset.StandardCharsets.UTF_8))),!inverse);
  }
  EffectPreparation capture(boolean inverse){
   var b=binding(inverse);var fp=footprint();
   var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),ID,1,
    new Landslide(RECIPE,4,new ResourceId("proutlost:test_debris_damage")),fp,new EventInstanceId(event));
   var captured=b.capture(intent,snapshot());work=new Execution(b,captured,inverse);return captured;
  }
  final class Execution {
   final LandslideBinding binding;final EffectPreparation captured;final PreparedEffect plan;final boolean inverse;
   final ResourceArbiter arbiter;final ReservationToken reservation;final PrepareRecord prepare;
   final EventRunner runner;LiveValidationSnapshot live;boolean finished;SliceResult actual;
   Execution(LandslideBinding binding,EffectPreparation captured,boolean inverse){
    this.binding=binding;this.captured=captured;this.plan=captured.prepared().orElseThrow();this.inverse=inverse;
    var i=plan.intent();var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();
    plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
    arbiter=new ResourceArbiter(world,new Epoch(Math.max(store.session().bootEpoch().value(),1)),1000,4,new ResourceArbiter.Compatibility(){
     public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}
    });
    var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),now().plus(4000),plan.claims(),Map.of(scope,plan.budgetEstimate()));
    reservation=arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
    var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),plan.preconditions(),List.of(i),plan.budgetEstimate());
    prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),reservation.generation(),plan.preconditions(),reservation.expiryTick(),slice,reservation,captured.validation(),List.of(),
     inverse?Set.of():Set.of(NoninvertibleEffect.DAMAGE),List.of(),plan.durableBlobs());
    live=new LiveValidationSnapshot(plan.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),reservation.generation());
    var authority=new CommitPermit.Authority(store,4);
    var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},x->live,Map.of(ID,binding),binding.capabilities(),4,
     x->new StabilizationLiveSnapshot(store.session(),live,new OwnershipView(owners)),m->{},(m,r)->{});
    port.hydrate(plan,captured.blobs());runner=new EventRunner(authority,store,port,arbiter,4);
   }
   void submit(){
    var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
    runner.submit(batch,plan.expectedPost(),live,result->{
     actual=result;require(result.outcome()==Outcome.SUCCEEDED&&result.appliedOperationIds().equals(Set.of(plan.intent().operationToken()))&&result.observedHashes().equals(plan.expectedPost()),"only exact physical success may write VERIFIED mutations");
     var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),result,Set.of(),List.of(),List.of())));
     for(var p:cells){rows.add(new DurableOwnership(owners.get(key(p))));var m=mutation(p,this);mutations.put(m.mutationId(),m);rows.add(new DurableMutation(m));}
     return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
    });
   }
   boolean poll(){
    live=new LiveValidationSnapshot(live.readSet(),live.loadedChunks(),live.health(),now(),reservation.generation());
    var status=runner.poll(prepare.transactionId(),live);
    require(status.stage()!=EventRunner.Stage.REJECTED&&status.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native EventRunner failed "+status.failure());
    if(status.stage()!=EventRunner.Stage.COMMITTED)return false;
    if(!finished){require(arbiter.release(reservation),"release after real durable result");finished=true;}return true;
   }
  }
  MutationRecord mutation(BlockPosition p,Execution e){
   var o=initial.get(key(p));var old=mutations.get(o.creationMutationId());
   if(e.inverse){require(old!=null,"inverse needs original mutation");var header=old.header();
    return new MutationRecord(new AggregateHeader(1,header.id(),header.revision().plus(1),header.lastSequence(),now()),old.mutationId(),old.instanceId(),old.sliceId(),MutationState.ROLLED_BACK,
     old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),old.durableSeq());}
   var k=key(p);var id=o.creationMutationId();
   return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),now()),id,new EventInstanceId(event),e.prepare.slice().sliceId(),MutationState.VERIFIED,ID,1,
    new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of()),
    e.plan.durableBlobs().preStates().get(k),e.plan.durableBlobs().expectedPostStates().get(k),o.ownerTokens().iterator().next(),o.epoch(),e.plan.preconditions(),Set.of(NoninvertibleEffect.DAMAGE),List.of(),Optional.empty(),new Sequence(0));
  }
  void validatePost(){
   for(var p:cells)require(level.getBlockState(pos(p)).is(sources.contains(p)?Blocks.AIR:Blocks.COBBLESTONE),"actual collapse POST missing");
  }
  void verifyJournal(boolean inverse){verifyJournal(inverse,false);}
  void verifyJournal(boolean inverse,boolean allowVerified){
   var prepares=records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value).toList();
   var results=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value).toList();
   require(prepares.size()==(inverse?2:1)&&results.size()==prepares.size(),"no extra forward/damage replay PREPARE/RESULT");
   require(prepares.stream().filter(p->p.operationToken().equals(original.intent().operationToken())
    &&p.slice().operations().equals(List.of(original.intent()))&&p.durableBlobs().equals(original.durableBlobs())).count()==1,
    "original forward PREPARE identity must remain exact");
   for(var p:prepares){
    var matching=results.stream().filter(r->r.prepareTransactionId().equals(p.transactionId())&&r.operationToken().equals(p.operationToken())).toList();
    require(matching.size()==1,"physical PREPARE needs its exact existing RESULT");var result=matching.getFirst().result();
    var expected=new HashMap<ResourceKey,Fingerprint>();p.durableBlobs().expectedPostStates().forEach((k,v)->expected.put(k,v.hash()));
    require(result.outcome()==Outcome.SUCCEEDED&&result.appliedOperationIds().equals(Set.of(p.operationToken()))
     &&result.observedHashes().equals(expected)&&result.remainingWork()==0&&result.conflictCells().isEmpty(),"durable RESULT must prove exact physical success");
   }
   require(mutations.size()==4&&restored.size()==4,"exactly four physical mutation rows");
   for(var m:mutations.values()){
    require(m.instanceId().value().equals(event)&&m.footprint().cells().size()==1,"mutation physical lineage");
    var resource=key(m.footprint().cells().iterator().next());var sealedOwner=initial.get(resource);
    require(m.preStateRef().equals(original.durableBlobs().preStates().get(resource))
     &&m.postStateRef().equals(original.durableBlobs().expectedPostStates().get(resource))
     &&m.readSetVersions().equals(original.preconditions())&&m.ownerToken().equals(sealedOwner.ownerTokens().iterator().next())
     &&m.ownerEpoch().equals(sealedOwner.epoch()),"mutation references must be actual original PRE/POST and ownership");
    boolean verified=allowVerified&&!inverse&&m.state()==MutationState.VERIFIED&&m.durableSeq().value()==0&&m.header().revision().value()==1;
    boolean committed=m.state()==(inverse?MutationState.ROLLED_BACK:MutationState.COMMITTED)&&m.durableSeq().value()>0
     &&m.header().revision().value()==(inverse?3:2);
    require(verified||committed,"mutation must be original VERIFIED or retain real nonzero WAL sequence/revision");
    var durable=restored.get(m.mutationId());require(durable!=null&&((MutationAggregate)durable.state()).value().equals(m)
     &&durable.committedSequence().value()>0&&durable.committedSequence().value()>=m.durableSeq().value(),"durable mutation projection differs");
   }
   if(work!=null)for(var value:work.binding.observedDamageFacts().values())require(value instanceof JsonValue.ObjectValue o
    &&o.values().get("attempted") instanceof JsonValue.Bool attempted&&!attempted.value(),"native provider fixture must not claim a client hurt");

  }
  /** Read-only POST proof before any metadata promotion or inverse. No forward provider is invoked. */
  void proveSavedPost(){
   validatePost();for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"saved owner no longer equals exact J04 POST owner");
   binding(true);var evidence=new ArrayList<RecoveryEvidence>();
   for(var p:cells){var o=owners.get(key(p));evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),
    Optional.of(o.ownerTokens().iterator().next()),o.epoch(),o.playerOverrideEpoch(),false));}
   require(base.reconcile(new RecoveryProbe(original,evidence,originalBlobs)).status()==ReconcileStatus.SAME_POST,
    "fresh saved physical POST is not exact under original owner lineage");
  }
  boolean promoteVerified(){
   verifyJournal(false,true);var rows=new ArrayList<DurableRecord>();
   for(var restoredRow:restored.values()){
    var old=((MutationAggregate)restoredRow.state()).value();if(old.state()==MutationState.COMMITTED)continue;
    require(old.state()==MutationState.VERIFIED&&old.durableSeq().value()==0&&restoredRow.committedSequence().value()>0,
     "resume promotion requires actual durable VERIFIED row and original exact RESULT");
    var header=old.header();var next=new MutationRecord(new AggregateHeader(1,header.id(),header.revision().plus(1),restoredRow.committedSequence(),now()),
     old.mutationId(),old.instanceId(),old.sliceId(),MutationState.COMMITTED,old.primitiveId(),old.primitiveVersion(),old.footprint(),
     old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),
     old.reconciliationEvidence(),old.compensationRef(),restoredRow.committedSequence());
    mutations.put(next.mutationId(),next);rows.add(new DurableMutation(next));
   }
   if(rows.isEmpty())return false;
   append(rows,List.of());
   System.out.println("DIRECTOR_NATIVE_COLLAPSE_RESUMING_SAVED_VERIFIED_POST count="+rows.size()+" no_new_start=true no_forward=true no_damage=true");
   return true;
  }
  void startRecoveredInverse(){proveSavedPost();verifyJournal(false);capture(true);work.submit();phase=11;}
  public void close(){ACTIVE.remove(server);if(store!=null)store.closeAsync();}
  void poll(){
   require(server.isSameThread(),"native terrain actor");
   switch(phase){
    case 0->{if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"J04 opening failed");store=opening.getNow(null);require(!store.readOnlySafe(),"J04 durability unavailable");read();phase=1;}
    case 1->{
     if(!readDone())return;
     var starts=records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).filter(f->f.factType().equals(START)).toList();
     if(recovery){
      require(starts.size()==1,"recovery-only requires this same WAL checkpoint");var start=starts.getFirst();event=start.eventId().orElseThrow().value();
      original=PLAN.decode(Base64.getDecoder().decode(text(start,"plan")));firstBoot=Long.parseLong(text(start,"boot"));require(store.session().bootEpoch().value()>firstBoot,"new J04 boot fence required");setCells(original);
      require(original.intent().expectedFootprint().dimension().value().equals(level.dimension().location().toString()),"sealed checkpoint dimension mismatch");
      for(var p:cells){level.getChunk(p.x()>>4,p.z()>>4);var local=attached.read(level,pos(p)).orElseThrow(()->new IllegalStateException("fresh saved ownership missing"));
       require(local.equals(owners.get(key(p))),"native saved ownership and WAL differ");}
      require(records.stream().noneMatch(r->r instanceof DurableFact f&&f.value().factType().equals(END)),"checkpoint already cleaned; never construct new forward event");
      var refs=CommitPermit.Authority.references(original.durableBlobs());
      hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();phase=10;return;
     }
     require(starts.isEmpty()&&records.stream().noneMatch(DurablePrepare.class::isInstance),"fresh run cannot overwrite existing checkpoint; use recoveryOnly");
     fixture();noClientGate();event=UUID.randomUUID();firstBoot=store.session().bootEpoch().value();
     var rows=new ArrayList<DurableRecord>();
     for(var p:cells){var o=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),key(p),Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),
       OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),new Epoch(0),Optional.empty());initial.put(key(p),o);owners.put(key(p),o);rows.add(new DurableOwnership(o));}
     var captured=capture(false);original=captured.prepared().orElseThrow();originalBlobs=captured.blobs();
     rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),START,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,
      Map.of("plan",text(Base64.getEncoder().encodeToString(PLAN.encode(original))),"boot",text(firstBoot),"testOnly",text(true)),true,Optional.empty())));
     append(rows,List.of());phase=2;
    }
    case 2->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));work.submit();phase=3;}
    case 3->{if(!work.poll())return;validatePost();read();phase=4;}
    case 4->{
     if(!readDone())return;require(restored.size()==4,"four actual mutation aggregates required");var rows=new ArrayList<DurableRecord>();
     for(var restoredRow:restored.values()){
      var old=((MutationAggregate)restoredRow.state()).value();require(old.state()==MutationState.VERIFIED&&restoredRow.committedSequence().value()>0,"physical VERIFIED requires actual committed sequence");
      var header=old.header();var next=new MutationRecord(new AggregateHeader(1,header.id(),header.revision().plus(1),restoredRow.committedSequence(),now()),old.mutationId(),old.instanceId(),old.sliceId(),MutationState.COMMITTED,
       old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),restoredRow.committedSequence());
      mutations.put(next.mutationId(),next);rows.add(new DurableMutation(next));
     }append(rows,List.of());phase=5;
    }
    case 5->{if(!acked())return;closing=store.closeAsync().toCompletableFuture();phase=6;}
    case 6->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"J04 checkpoint flush failed");reopening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=7;}
    case 7->{if(!reopening.isDone())return;require(!reopening.isCompletedExceptionally(),"same WAL reopen failed");store=reopening.getNow(null);read();phase=8;}
    case 8->{if(!readDone())return;validatePost();verifyJournal(false);checkpoint("COLLAPSE_POST");closing=store.closeAsync().toCompletableFuture();phase=9;}
    case 9->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"final WAL close failed");System.out.println("DIRECTOR_NATIVE_COLLAPSE_PHYSICAL_CHECKPOINT_PASS event="+event+" full_warn=false client=false g5=false");done=true;ACTIVE.remove(server);phase=99;}
    case 10->{
     if(!hydrating.isDone())return;require(!hydrating.isCompletedExceptionally(),"recovery blob IO failed");var loaded=hydrating.getNow(null);
     require(loaded.status()==AckStatus.DURABLE&&loaded.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(CommitPermit.Authority.references(original.durableBlobs())),"exact original refs required");
     originalBlobs=new BlobBundle(loaded.contents());
     for(var blob:loaded.contents())if(blob.reference().codecId().value().equals("proutlost_director:owner_v1")){var o=OWNER.decode(blob.bytes());initial.put(o.resourceKey(),o);}
     require(initial.size()==4,"original owner snapshots missing");mutations.clear();restored.values().forEach(a->{var m=((MutationAggregate)a.state()).value();mutations.put(m.mutationId(),m);});
     proveSavedPost();verifyJournal(false,true);
     if(promoteVerified()){phase=15;return;}
     startRecoveredInverse();
    }
    case 15->{if(!acked())return;read();phase=16;}
    case 16->{if(!readDone())return;mutations.clear();restored.values().forEach(a->{var m=((MutationAggregate)a.state()).value();mutations.put(m.mutationId(),m);});startRecoveredInverse();}
    case 11->{if(!work.poll())return;for(var p:cells)require(hash(p).equals(original.preconditions().fingerprints().get(key(p))),"inverse did not restore exact original STONE/AIR");read();phase=12;}
    case 12->{
     if(!readDone())return;verifyJournal(true);var rows=new ArrayList<DurableRecord>();
     for(var p:cells){var old=owners.get(key(p));var header=old.header();var released=new OwnershipRecord(new AggregateHeader(1,header.id(),header.revision().plus(1),header.lastSequence(),now()),old.resourceKey(),old.ownerTokens(),old.epoch(),OwnershipDisposition.RELEASED,old.creationMutationId(),hash(p),old.playerOverrideEpoch(),old.exceptionContractId());owners.put(key(p),released);rows.add(new DurableOwnership(released));}
     rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),END,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of("damage_replayed",new JsonValue.Bool(false),"cleanup",text("EXACT_NATIVE_PRE")),true,Optional.empty())));
     append(rows,List.of());phase=13;
    }
    case 13->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));checkpoint("COLLAPSE_RECOVERED_INVERSE");closing=store.closeAsync().toCompletableFuture();phase=14;}
    case 14->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"recovery final WAL flush failed");System.out.println("DIRECTOR_NATIVE_COLLAPSE_SAME_WORLD_INVERSE_NO_FORWARD_DAMAGE_REPLAY_PASS event="+event+" full_warn=false client=false g5=false");done=true;ACTIVE.remove(server);phase=99;}
    case 99->{}
    default->throw new IllegalStateException("terrain test phase");
   }
  }
  void checkpoint(String reason){
   require(cells.stream().allMatch(p->attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p))))),"checkpoint owner attachment mismatch");
   boolean saved=server.saveEverything(false,true,true);
   System.out.println("DIRECTOR_NATIVE_TERRAIN_WORLD_SAVE reason="+reason+" event="+event+" world="+server.getWorldPath(LevelResource.ROOT)+" world_id="+world.value()+" boot_epoch="+store.session().bootEpoch().value()+" save_return="+saved+" cells="+cells+" fresh_nbt_requires_external_reboot=true");
  }
 }
}
