package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/** Opt-in native provider test only: real fixture -> IMPORT -> one PULSE -> TRANSFER.
 * First boot leaves actual controlled POST plus J04 WAL; external second boot restores exact
 * original native LEVEL0/1/2/3. No J06, player receipt, natural flood or full disaster claim.
 * Fixture construction is explicit disposable test setup BEFORE START, never recovery behavior. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class NativeFloodProviderGameTests {
 private NativeFloodProviderGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final FactType START=new FactType("proutlost_director:native_flood_provider_start");
 private static final FactType CHECKPOINT=new FactType("proutlost_director:native_flood_provider_checkpoint");
 private static final FactType END=new FactType("proutlost_director:native_flood_provider_cleanup");
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,4096);
 private static Probe running;
 /** Root may include this in its native IO pacer; it is false unless this opt-in test is running. */
 public static boolean active(MinecraftServer server){return running!=null&&running.server==server&&!running.done;}
 @GameTestGenerator
 public static Collection<TestFunction> nativeFloodTests(){
  if(Boolean.getBoolean("proutlost.director.nativeFloodDepthGameTest"))
   return List.of(new TestFunction("native_flood_depth","proutlost_director.native_flood_depth_import_restore",
    "proutlost_director_water_depth:smoke_empty",Rotation.NONE,12000,0,true,h->{
     var deep=new DepthProbe(h);running=deep.probe;
     h.succeedWhen(()->{
      if(deep.failedMessage!=null)h.fail(deep.failedMessage);
      try{deep.poll();}catch(GameTestAssertException assertion){throw assertion;}
      catch(RuntimeException unexpected){deep.failedMessage=unexpected.toString();h.fail(deep.failedMessage);}
      h.assertTrue(deep.done,"native deep import/restore phase="+deep.phase);
     });
    }));
  if(!Boolean.getBoolean("proutlost.director.nativeFloodProviderGameTest"))return List.of();
  boolean recovery=Boolean.getBoolean("proutlost.director.nativeFloodProviderRecoveryOnly");
  return List.of(new TestFunction("native_flood_provider_persistent",
   "proutlost_director.native_flood_provider_"+(recovery?"same_world_restore":"physical_checkpoint"),
   "proutlost_director:smoke_empty",Rotation.NONE,12000,0,true,h->{
    var p=new Probe(h,recovery);running=p;
    h.succeedWhen(()->{p.poll();h.assertTrue(p.done,"native flood provider phase="+p.phase);});
   }));
 }
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static final class Probe {
  final GameTestHelper h;final boolean recovery;final ServerLevel level;final MinecraftServer server;
  final WorldId world;final Path path;final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
  final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>(),initial=new HashMap<>();
  final Map<MutationId,MutationRecord> mutations=new HashMap<>();
  final Map<MutationId,RestoredAggregate> restored=new HashMap<>();
  final CompletableFuture<JournalStore> opening;
  JournalStore store;UUID event;long firstBoot,observedUnits=26;int phase;boolean done;
  List<BlockPosition> cells=List.of();Footprint footprint;BlobContent mask;
  PreparedEffect original,lastPlan;BlobBundle originalBlobs,recoveryBlobs;
  CompletableFuture<CommitAck> ack;CompletableFuture<Void> closing;CompletableFuture<JournalStore> reopening;
  CompletableFuture<StoreReadResult> reading;CompletableFuture<BlobReadResult> hydrating;StoreQuery query;
  List<DurableRecord> records=List.of(),pendingRecords=List.of();ReadStamp chronologyStamp;int readPart,stales;
  Set<AggregateKey> projectionKeys=Set.of();Execution work;
  Probe(GameTestHelper h,boolean recovery){this(h,recovery,"director-native-flood-provider-test-only");}
  Probe(GameTestHelper h,boolean recovery,String directory){
   this.h=h;this.recovery=recovery;level=h.getLevel();server=level.getServer();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   path=server.getWorldPath(LevelResource.ROOT).resolve(directory);
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();
  }
  void require(boolean ok,String why){h.assertTrue(ok,why);}
  Tick now(){return new Tick(server.overworld().getGameTime());}
  Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level.getBlockState(pos(p))).reference().hash();}
  void publish(BlockPosition p,OwnershipRecord row){owners.put(key(p),row);attached.write(level,pos(p),row);}
  void append(List<DurableRecord> rows,List<BlobContent> blobs){
   ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs)).toCompletableFuture();
  }
  boolean acked(){
   if(!ack.isDone())return false;
   require(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE,"actual J04 metadata ACK required");return true;
  }
  void read(){stales=0;chronology();}
  void chronology(){
   readPart=0;pendingRecords=List.of();projectionKeys=Set.of();
   query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);reading=store.read(query).toCompletableFuture();
  }
  void retryRead(){require(++stales<=3,"same-cut read repeatedly STALE");chronology();}
  boolean readDone(){
   if(!reading.isDone())return false;require(!reading.isCompletedExceptionally(),"J04 read IO failed");
   var result=reading.getNow(null);ReadContracts.validate(result,query,store.session());
   if(result.status()==ReadStatus.STALE){retryRead();return false;}
   require(result.status()==ReadStatus.AVAILABLE&&!result.page().orElseThrow().hasMore(),"bounded native WAL query unavailable/too large");
   var page=result.page().orElseThrow();
   if(readPart==0){
    require(page.aggregates().isEmpty(),"chronology query must not masquerade as aggregate hydration");
    pendingRecords=page.records();chronologyStamp=result.stamp().orElseThrow();var keys=new HashSet<AggregateKey>();
    // Bootstrap exact IDs from actual chronology BEFORE cells/initial/blob hydration exist.
    for(var row:pendingRecords){
     if(row instanceof DurableOwnership o)keys.add(new AggregateKey(AggregateKind.OWNERSHIP,o.value().header().id()));
     if(row instanceof DurableMutation m)keys.add(new AggregateKey(AggregateKind.MUTATION,m.value().mutationId().value()));
    }
    if(keys.isEmpty()){records=pendingRecords;return true;}
    require(keys.size()==8,"exactly four owner and four creation-mutation projection keys");
    projectionKeys=Set.copyOf(keys);readPart=1;
    query=new StoreQuery(world,projectionKeys,new Sequence(0),Optional.empty(),16);reading=store.read(query).toCompletableFuture();return false;
   }
   var stamp=result.stamp().orElseThrow();
   if(!stamp.session().equals(chronologyStamp.session())||!stamp.snapshotSequence().equals(chronologyStamp.snapshotSequence())){retryRead();return false;}
   require(page.aggregates().size()==8,"point query must hydrate eight actual projections");
   var got=new HashSet<AggregateKey>();owners.clear();mutations.clear();restored.clear();
   for(var row:page.aggregates()){
    if(row.state() instanceof OwnershipAggregate o){owners.put(o.value().resourceKey(),o.value());got.add(new AggregateKey(AggregateKind.OWNERSHIP,o.value().header().id()));}
    if(row.state() instanceof MutationAggregate m){mutations.put(m.value().mutationId(),m.value());restored.put(m.value().mutationId(),row);got.add(new AggregateKey(AggregateKind.MUTATION,m.value().mutationId().value()));}
   }
   require(got.equals(projectionKeys)&&owners.size()==4&&mutations.size()==4,"point projections must match requested actual IDs");
   records=pendingRecords;return true;
  }
  List<DirectorFact> facts(FactType type){return records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).filter(f->f.factType().equals(type)).toList();}
  DirectorFact fact(FactType type,Map<String,JsonValue> payload){
   return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
    Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
  }
  void fixture(){
   String encoded=System.getProperty("proutlost.director.nativeFloodProviderFixture","");
   require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeFloodProviderFixture=x,y,z to loaded empty persistent test space");
   var a=encoded.split(",");var origin=new BlockPos(Integer.parseInt(a[0]),Integer.parseInt(a[1]),Integer.parseInt(a[2]));
   require(origin.getY()>level.getMinBuildHeight()+1&&origin.getY()<level.getMaxBuildHeight()-3,"fixture height");
   var box=new AABB(origin.getX()-1,origin.getY()-1,origin.getZ()-1,origin.getX()+5,origin.getY()+2,origin.getZ()+2);
   require(!box.intersects(h.getBounds().inflate(3,20,3)),"persistent basin intersects current clearing box");
   require(level.getEntities(null,box).isEmpty(),"test basin occupied");
   var chunks=new HashSet<String>();
   for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++){
    var floor=origin.offset(x,-1,z);chunks.add((floor.getX()>>4)+","+(floor.getZ()>>4));
    require(level.getChunkSource().getChunkNow(floor.getX()>>4,floor.getZ()>>4)!=null,"fixture must already be loaded");
    require(level.getBlockState(floor).isAir()||level.getBlockState(floor).is(Blocks.STONE),"test floor may only reuse STONE or AIR");
    for(int y=-1;y<=1;y++){
     var p=origin.offset(x,y,z);require(attached.read(level,p).isEmpty(),"never overwrite saved ownership");
     if(y>=0)require(level.getBlockState(p).isAir(),"never overwrite checkpoint: test basin/headroom must be empty");
    }
   }
   require(chunks.size()==1,"persistent basin/guards must fit one chunk");
   // Native structure blocks have bounded sizes; inspect nearby LOADED chunks without loading test space.
   for(int cx=(origin.getX()-65)>>4;cx<=(origin.getX()+69)>>4;cx++)for(int cz=(origin.getZ()-65)>>4;cz<=(origin.getZ()+66)>>4;cz++){
    var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
    for(var be:chunk.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
     require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"persistent basin intersects another clearing box");
   }
   // Explicit disposable test setup only, all before START/admission. Recovery never executes it.
   int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
   for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++){
    require(level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),flags)||level.getBlockState(origin.offset(x,-1,z)).is(Blocks.STONE),"test floor");
    if(x==-1||x==4||z==-1||z==1)require(level.setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),flags),"test wall");
   }
   var dimension=new DimensionId(level.dimension().location().toString());var selected=new ArrayList<BlockPosition>();
   for(int i=0;i<4;i++){var p=origin.east(i);require(level.setBlock(p,Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,i),flags),"test native water fixture");
    selected.add(new BlockPosition(dimension,p.getX(),p.getY(),p.getZ()));}
   cells=List.copyOf(selected);mask=ControlledWaterBinding.captureMask(level,cells,attached);footprint=FOOTPRINT.decode(mask.bytes());validateNativeOriginal();
  }
  void validateNativeOriginal(){
   int sum=0;for(int i=0;i<4;i++){
    var state=level.getBlockState(pos(cells.get(i)));require(state.equals(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,i)),"exact original native LEVEL"+i);
    int v=ControlledWaterBinding.nativeVolume(state);require(v==8-i,"actual native amount/source/falling mismatch");sum+=v;
   }require(sum==26,"actual native reservoir contains26");
  }
  void validateControlled(int[] expected){
   int sum=0;for(int i=0;i<4;i++){var p=cells.get(i);var state=level.getBlockState(pos(p));int v=DirectorControlledWater.volume(state);
    require(v==expected[i]&&state.getFluidState().getAmount()==v,"actual controlled amount "+i);sum+=v;
    require(hash(p).equals(owners.get(key(p)).expectedPostHash()),"actual native state differs from current WAL owner post");
   }require(sum==26,"physical controlled volume must remain26");
  }
  PlanningSnapshot snapshot(){
   var observed=new HashMap<BlockPosition,TerrainCellSnapshot>();
   for(var p:cells){var o=Objects.requireNonNull(owners.get(key(p)));observed.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
   var terrain=new TerrainSnapshot(footprint,observed,List.of(),Map.of(),new Revision(1));
   var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
   return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL),
    new OwnershipView(owners),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of(mask)));
  }
  ControlledWaterBinding binding(){return new ControlledWaterBinding(d->{require(d.equals(footprint.dimension()),"native fixture dimension");return level;},()->new OwnershipView(owners),this::publish,this::now,attached);}
  Execution capture(ResourceId recipe){
   var b=binding();var instance=new EventInstanceId(event);long units=recipe.equals(ControlledWaterBinding.IMPORT)?observedUnits:recipe.equals(ControlledWaterBinding.PULSE)?1:recipe.equals(ControlledWaterBinding.TRANSFER)?32:0;
   var op=recipe.equals(ControlledWaterBinding.PULSE)?ControlledWaterBinding.pulseToken(instance):new OperationToken(UUID.randomUUID());
   var intent=new EffectIntent(op,ControlledWaterBinding.ID,1,new ControlledWater(recipe,mask.reference(),units,8),footprint,instance);
   if(recipe.equals(ControlledWaterBinding.PULSE))b.registerPulse(intent,original,originalBlobs);
   if(recipe.equals(ControlledWaterBinding.RESTORE))b.registerRestore(intent,original,originalBlobs);
   work=new Execution(b,b.capture(intent,snapshot()),recipe);return work;
  }
  final class Execution {
   final ControlledWaterBinding binding;final EffectPreparation captured;final PreparedEffect plan;final ResourceId recipe;
   final ResourceArbiter arbiter;final ReservationToken reservation;final PrepareRecord prepare;final EventRunner runner;
   LiveValidationSnapshot live;boolean finished;
   Execution(ControlledWaterBinding binding,EffectPreparation captured,ResourceId recipe){
    this.binding=binding;this.captured=captured;this.plan=captured.prepared().orElseThrow();this.recipe=recipe;
    var i=plan.intent();var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
    arbiter=new ResourceArbiter(world,new Epoch(Math.max(store.session().bootEpoch().value(),1)),1000,4,new ResourceArbiter.Compatibility(){
     public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}});
    var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),now().plus(4000),plan.claims(),Map.of(scope,plan.budgetEstimate()));
    reservation=arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
    var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),plan.preconditions(),List.of(i),plan.budgetEstimate());
    prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),reservation.generation(),plan.preconditions(),reservation.expiryTick(),slice,reservation,captured.validation(),List.of(),Set.of(),List.of(),plan.durableBlobs());
    live=new LiveValidationSnapshot(plan.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),reservation.generation());
    var authority=new CommitPermit.Authority(store,4);
    var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},x->live,Map.of(ControlledWaterBinding.ID,binding),binding.capabilities(),4,
     x->new StabilizationLiveSnapshot(store.session(),live,new OwnershipView(owners)),m->{},(m,r)->{});
    port.hydrate(plan,captured.blobs());runner=new EventRunner(authority,store,port,arbiter,4);
   }
   void submit(){
    require(binding.revalidate(plan,captured.blobs()),"native PRE and owner CAS must be exact before submit");
    // Pure stale-owner probe only: no world/attachment/player override is fabricated.
    var resource=plan.claims().getFirst().resource();var old=owners.remove(resource);
    require(!binding.revalidate(plan,captured.blobs()),"missing exact owner must fail CAS");owners.put(resource,old);
    require(binding.revalidate(plan,captured.blobs()),"exact owner restoration must recover PRE validation");
    var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
    runner.submit(batch,plan.expectedPost(),live,result->{
     require(result.outcome()==Outcome.SUCCEEDED&&result.appliedOperationIds().equals(Set.of(plan.intent().operationToken()))&&result.observedHashes().equals(plan.expectedPost()),"only exact actual physical success may be recorded");
     var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),result,Set.of(),List.of(),List.of())));
     binding.updatedOwners().values().forEach(o->rows.add(new DurableOwnership(o)));
     if(recipe.equals(ControlledWaterBinding.IMPORT))for(var p:cells){var m=importMutation(p,this);mutations.put(m.mutationId(),m);rows.add(new DurableMutation(m));}
     if(recipe.equals(ControlledWaterBinding.RESTORE))for(var oldMutation:List.copyOf(mutations.values())){
      var m=withState(oldMutation,MutationState.ROLLED_BACK,oldMutation.durableSeq());mutations.put(m.mutationId(),m);rows.add(new DurableMutation(m));}
     return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
    });
   }
   boolean poll(){
    live=new LiveValidationSnapshot(live.readSet(),live.loadedChunks(),live.health(),now(),reservation.generation());
    var status=runner.poll(prepare.transactionId(),live);require(status.stage()!=EventRunner.Stage.REJECTED&&status.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native water EventRunner "+status.failure());
    if(status.stage()!=EventRunner.Stage.COMMITTED)return false;
    if(!finished){require(arbiter.release(reservation),"release only after real durable RESULT");finished=true;}return true;
   }
  }
  MutationRecord importMutation(BlockPosition p,Execution e){
   var o=initial.get(key(p));var k=key(p);var id=o.creationMutationId();
   return new MutationRecord(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),now()),id,new EventInstanceId(event),e.prepare.slice().sliceId(),MutationState.VERIFIED,ControlledWaterBinding.ID,1,
    new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of()),
    e.plan.durableBlobs().preStates().get(k),e.plan.durableBlobs().expectedPostStates().get(k),o.ownerTokens().iterator().next(),o.epoch(),e.plan.preconditions(),Set.of(),List.of(),Optional.empty(),new Sequence(0));
  }
  MutationRecord withState(MutationRecord old,MutationState state,Sequence sequence){
   var h=old.header();return new MutationRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),sequence,now()),old.mutationId(),old.instanceId(),old.sliceId(),state,
    old.primitiveId(),old.primitiveVersion(),old.footprint(),old.preStateRef(),old.postStateRef(),old.ownerToken(),old.ownerEpoch(),old.readSetVersions(),old.noninvertibleEffects(),old.reconciliationEvidence(),old.compensationRef(),sequence);
  }
  void commitImportMutations(){
   var rows=new ArrayList<DurableRecord>();for(var restoredRow:restored.values()){
    var old=((MutationAggregate)restoredRow.state()).value();require(old.state()==MutationState.VERIFIED&&restoredRow.committedSequence().value()>0,"actual committed VERIFIED projection required");
    var next=withState(old,MutationState.COMMITTED,restoredRow.committedSequence());mutations.put(next.mutationId(),next);rows.add(new DurableMutation(next));
   }require(rows.size()==4,"four imported creation mutations");append(rows,List.of());
  }
  void verifyJournal(boolean cleaned){
   var prepares=records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value).toList();
   var results=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value).toList();
   require(prepares.size()==(cleaned?4:3)&&results.size()==prepares.size(),"IMPORT/PULSE/TRANSFER only; recovery adds only RESTORE");
   require(prepares.stream().filter(p->p.operationToken().equals(ControlledWaterBinding.pulseToken(new EventInstanceId(event)))).count()==1,"one stable pulse operation only");
   require(prepares.stream().anyMatch(p->p.operationToken().equals(lastPlan.intent().operationToken()))&&results.stream().anyMatch(r->r.operationToken().equals(lastPlan.intent().operationToken())
    &&r.result().outcome()==Outcome.SUCCEEDED&&r.result().observedHashes().equals(lastPlan.expectedPost())),"sealed last physical POST must have exact actual RESULT");
   for(var m:mutations.values()){
    var p=m.footprint().cells().iterator().next();var k=key(p);var o=initial.get(k);var aggregate=restored.get(m.mutationId());
    require(m.instanceId().value().equals(event)&&m.preStateRef().equals(original.durableBlobs().preStates().get(k))
     &&m.postStateRef().equals(original.durableBlobs().expectedPostStates().get(k))&&m.readSetVersions().equals(original.preconditions())
     &&m.ownerToken().equals(o.ownerTokens().iterator().next())&&m.ownerEpoch().equals(o.epoch()),"creation mutation must retain actual original IMPORT PRE/POST refs");
    require(m.state()==(cleaned?MutationState.ROLLED_BACK:MutationState.COMMITTED)&&m.header().revision().value()==(cleaned?3:2)
     &&m.durableSeq().value()>0&&aggregate!=null&&((MutationAggregate)aggregate.state()).value().equals(m)
     &&aggregate.committedSequence().value()>=m.durableSeq().value(),"actual nonzero WAL sequence/projection required");
   }
  }
  void checkpoint(String reason){
   for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"saved attachment and WAL owner differ");
   boolean saved=server.saveEverything(false,true,true);
   System.out.println("DIRECTOR_NATIVE_FLOOD_PROVIDER_WORLD_SAVE reason="+reason+" event="+event+" world="+server.getWorldPath(LevelResource.ROOT)+" world_id="+world.value()+" boot="+store.session().bootEpoch().value()+" save_return="+saved+" cells="+cells+" external_reboot_required=true");
  }
  void poll(){
   require(server.isSameThread(),"native flood actor");
   switch(phase){
    case 0->{if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"J04 open failed");store=opening.getNow(null);require(!store.readOnlySafe(),"J04 unavailable");read();phase=1;}
    case 1->{
     if(!readDone())return;
     if(recovery){
      require(facts(START).size()==1&&facts(CHECKPOINT).size()==1&&facts(END).isEmpty(),"same WAL checkpoint required; never reseed/replay forward");
      var start=facts(START).getFirst();var check=facts(CHECKPOINT).getFirst();event=start.eventId().orElseThrow().value();
      original=PLAN.decode(Base64.getDecoder().decode(text(start,"plan")));lastPlan=PLAN.decode(Base64.getDecoder().decode(text(check,"last_plan")));
      firstBoot=Long.parseLong(text(start,"boot"));require(store.session().bootEpoch().value()>firstBoot,"new J04 boot fence required");
      footprint=original.intent().expectedFootprint();cells=footprint.cells().stream().sorted(Comparator.comparingInt(BlockPosition::x)).toList();
      require(cells.size()==4&&footprint.dimension().value().equals(level.dimension().location().toString()),"four sealed same-dimension water cells");
      for(var p:cells){level.getChunk(p.x()>>4,p.z()>>4);require(attached.read(level,pos(p)).equals(Optional.ofNullable(owners.get(key(p)))),"actual fresh NBT ownership must match WAL");}
      var refs=new HashSet<>(CommitPermit.Authority.references(original.durableBlobs()));refs.addAll(CommitPermit.Authority.references(lastPlan.durableBlobs()));
      hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();phase=20;return;
     }
     require(records.isEmpty(),"fresh run cannot overwrite an existing checkpoint; use recoveryOnly");fixture();event=UUID.randomUUID();firstBoot=store.session().bootEpoch().value();
     var rows=new ArrayList<DurableRecord>();for(var p:cells){
      var o=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),key(p),Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),new Epoch(0),Optional.empty());
      initial.put(key(p),o);owners.put(key(p),o);rows.add(new DurableOwnership(o));}
     capture(ControlledWaterBinding.IMPORT);original=work.plan;originalBlobs=work.captured.blobs();
     rows.add(new DurableFact(fact(START,Map.of("plan",text(Base64.getEncoder().encodeToString(PLAN.encode(original))),"boot",text(firstBoot),"test_only_fixture",new JsonValue.Bool(true)))));
     append(rows,List.of());phase=2;
    }
    case 2->{if(!acked())return;validateNativeOriginal();for(var p:cells)publish(p,owners.get(key(p)));work.submit();phase=3;}
    case 3->{if(!work.poll())return;validateControlled(new int[]{8,7,6,5});read();phase=4;}
    case 4->{if(!readDone())return;commitImportMutations();phase=5;}
    case 5->{if(!acked())return;capture(ControlledWaterBinding.PULSE).submit();phase=6;}
    case 6->{if(!work.poll())return;validateControlled(new int[]{7,8,6,5});capture(ControlledWaterBinding.TRANSFER).submit();lastPlan=work.plan;phase=7;}
    case 7->{if(!work.poll())return;validateControlled(new int[]{7,7,6,6});
     append(List.of(new DurableFact(fact(CHECKPOINT,Map.of("last_plan",text(Base64.getEncoder().encodeToString(PLAN.encode(lastPlan))),"units",text(26),"full_warn",new JsonValue.Bool(false))))),List.of());phase=8;}
    case 8->{if(!acked())return;closing=store.closeAsync().toCompletableFuture();phase=9;}
    case 9->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"checkpoint WAL close");reopening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=10;}
    case 10->{if(!reopening.isDone())return;require(!reopening.isCompletedExceptionally(),"same WAL reopen");store=reopening.getNow(null);read();phase=11;}
    case 11->{if(!readDone())return;validateControlled(new int[]{7,7,6,6});verifyJournal(false);checkpoint("CONTROLLED_POST26");closing=store.closeAsync().toCompletableFuture();phase=12;}
    case 12->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"final WAL close");System.out.println("DIRECTOR_NATIVE_FLOOD_IMPORT_PULSE_TRANSFER_CHECKPOINT_PASS event="+event+" units=26 full_warn=false client=false g5=false");done=true;phase=99;}
    case 20->{
     if(!hydrating.isDone())return;require(!hydrating.isCompletedExceptionally(),"original+last POST blob IO");var loaded=hydrating.getNow(null);
     var refs=new HashSet<>(CommitPermit.Authority.references(original.durableBlobs()));refs.addAll(CommitPermit.Authority.references(lastPlan.durableBlobs()));
     require(loaded.status()==AckStatus.DURABLE&&loaded.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(refs),"exact original and last-plan bytes required");
     recoveryBlobs=new BlobBundle(loaded.contents());originalBlobs=new BlobBundle(loaded.contents().stream().filter(b->CommitPermit.Authority.references(original.durableBlobs()).contains(b.reference())).toList());
     mask=originalBlobs.contents().stream().filter(b->b.reference().equals(((ControlledWater)original.intent().typedArgs()).propagationMask())).findFirst().orElseThrow();
     for(var b:originalBlobs.contents())if(b.reference().codecId().value().equals("proutlost_director:owner_v1")){var o=OWNER.decode(b.bytes());initial.put(o.resourceKey(),o);}
     require(initial.size()==4,"original owner credit missing");validateControlled(new int[]{7,7,6,6});verifyJournal(false);
     var evidence=new ArrayList<RecoveryEvidence>();for(var p:cells){var o=owners.get(key(p));evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),Optional.of(o.ownerTokens().iterator().next()),o.epoch(),o.playerOverrideEpoch(),false));}
     require(binding().reconcile(new RecoveryProbe(lastPlan,evidence,recoveryBlobs)).status()==ReconcileStatus.SAME_POST,"fresh actual POST must reconcile against LAST TRANSFER, not obsolete IMPORT POST");
     capture(ControlledWaterBinding.RESTORE).submit();phase=21;
    }
    case 21->{if(!work.poll())return;validateNativeOriginal();for(var p:cells)require(hash(p).equals(original.preconditions().fingerprints().get(key(p))),"restore must reproduce exact original native bytes");read();phase=22;}
    case 22->{
     if(!readDone())return;verifyJournal(true);var rows=new ArrayList<DurableRecord>();
     for(var p:cells){var old=owners.get(key(p));var h=old.header();var release=new OwnershipRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),h.lastSequence(),now()),key(p),old.ownerTokens(),old.epoch(),OwnershipDisposition.RELEASED,old.creationMutationId(),hash(p),old.playerOverrideEpoch(),old.exceptionContractId());owners.put(key(p),release);rows.add(new DurableOwnership(release));}
     rows.add(new DurableFact(fact(END,Map.of("cleanup",text("EXACT_ORIGINAL_NATIVE_LEVELS"),"forward_replayed",new JsonValue.Bool(false)))));append(rows,List.of());phase=23;
    }
    case 23->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));checkpoint("RECOVERED_EXACT_NATIVE_LEVELS");closing=store.closeAsync().toCompletableFuture();phase=24;}
    case 24->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"cleanup WAL flush");System.out.println("DIRECTOR_NATIVE_FLOOD_SAME_WORLD_EXACT_RESTORE_NO_FORWARD_REPLAY_PASS event="+event+" units=26 full_warn=false client=false g5=false");done=true;phase=99;}
    case 99->{}
    default->throw new IllegalStateException("native flood phase");
   }
  }
 }
 /** Opt-in actual 4-cell/32-unit kernel regression, in loaded disposable
  * space of the SAME test world. It does not claim real client swimming. */
 private static final class DepthProbe {
  final Probe probe;int phase;boolean done;String failedMessage;
  DepthProbe(GameTestHelper h){
   h.assertTrue(Boolean.getBoolean("proutlost.director.nativeLab")&&Boolean.getBoolean("proutlost.director.nativeGameTests"),
    "explicit native LAB/depth fixture required");
   probe=new Probe(h,false,"director-native-flood-depth-test-only-"+UUID.randomUUID());
   probe.observedUnits=32;
  }
  void fixture(){
   var p=probe;String encoded=System.getProperty("proutlost.director.nativeFloodDepthFixture","");
   p.require(encoded.matches("-?[0-9]+,-?[0-9]+,-?[0-9]+"),"set nativeFloodDepthFixture=x,y,z to NEW loaded empty test space");
   var a=encoded.split(",");var origin=new BlockPos(Integer.parseInt(a[0]),Integer.parseInt(a[1]),Integer.parseInt(a[2]));
   p.require(origin.getY()>p.level.getMinBuildHeight()+1&&origin.getY()<p.level.getMaxBuildHeight()-4,"depth fixture height");
   var box=new AABB(origin.getX()-1,origin.getY()-1,origin.getZ()-1,origin.getX()+3,origin.getY()+3,origin.getZ()+2);
   p.require(!box.intersects(p.h.getBounds().inflate(3,20,3))&&p.level.getEntities(null,box).isEmpty(),"depth fixture intersects test clearing or entity");
   var chunks=new HashSet<String>();
   for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=2;y++){
    var at=origin.offset(x,y,z);chunks.add((at.getX()>>4)+","+(at.getZ()>>4));
    p.require(p.level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null,"depth fixture must already be loaded");
    p.require(p.attached.read(p.level,at).isEmpty(),"depth fixture must never overwrite saved ownership");
    var state=p.level.getBlockState(at);
    p.require(y==-1?(state.isAir()||state.is(Blocks.STONE)):state.isAir(),"depth fixture may only replace AIR and a STONE/AIR floor");
   }
   p.require(chunks.size()==1,"depth fixture including guards must fit one loaded chunk");
   for(int cx=(origin.getX()-65)>>4;cx<=(origin.getX()+67)>>4;cx++)
    for(int cz=(origin.getZ()-65)>>4;cz<=(origin.getZ()+66)>>4;cz++){
     var chunk=p.level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
     for(var be:chunk.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
      p.require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"depth fixture intersects another test clearing");
    }
   int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
   for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++){
    var floor=origin.offset(x,-1,z);p.level.setBlock(floor,Blocks.STONE.defaultBlockState(),flags);
    if(x==-1||x==2||z==-1||z==1)for(int y=0;y<2;y++)
     p.require(p.level.setBlock(origin.offset(x,y,z),Blocks.STONE.defaultBlockState(),flags),"depth STONE sidewall");
   }
   var dimension=new DimensionId(p.level.dimension().location().toString());var cells=new ArrayList<BlockPosition>();
   // Explicit native TEST fixture sources only, before owner/admission/START.
   for(int x=0;x<2;x++)for(int y=0;y<2;y++){
    var at=origin.offset(x,y,0);p.require(p.level.setBlock(at,Blocks.WATER.defaultBlockState(),flags),"real native source fixture");
    cells.add(new BlockPosition(dimension,at.getX(),at.getY(),at.getZ()));
   }
   p.cells=List.copyOf(cells);p.mask=ControlledWaterBinding.captureMask(p.level,p.cells,p.attached);
   p.footprint=FOOTPRINT.decode(p.mask.bytes());validate(false);
  }
  void validate(boolean controlled){
   var p=probe;long total=0;int bottom=p.cells.stream().mapToInt(BlockPosition::y).min().orElseThrow();
   for(var cell:p.cells){
    var state=p.level.getBlockState(pos(cell));int amount=controlled?DirectorControlledWater.volume(state):ControlledWaterBinding.nativeVolume(state);
    p.require(amount==8&&state.getFluidState().isSource(),"actual deep source amount/source mismatch");total+=amount;
    if(controlled){
     p.require(p.hash(cell).equals(p.owners.get(key(cell)).expectedPostHash()),"actual deep POST differs from WAL owner");
     if(cell.y()==bottom)p.require(state.getFluidState().getHeight(p.level,pos(cell))==1F,"bottom fluid must have true full height under upper fluid");
    }
   }
   p.require(total==32&&p.cells.size()==4&&p.cells.stream().mapToInt(BlockPosition::y).distinct().count()==2,"real two-layer 32-unit volume");
  }
  void guardRejections(){
   var p=probe;var bottom=pos(p.cells.getFirst());var binding=p.work.binding;var plan=p.work.plan;var blobs=p.work.captured.blobs();
   p.require(binding.revalidate(plan,blobs),"actual RESTORE PRE must initially validate");
   for(var guard:List.of(bottom.below(),bottom.above(2),bottom.west().above())){
    var before=p.level.getBlockState(guard);boolean rejected;
    try{
     p.level.setBlock(guard,before.isAir()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),
      Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
     rejected=!binding.revalidate(plan,blobs);
    }finally{
     p.level.setBlock(guard,before,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
    }
    p.require(rejected&&binding.revalidate(plan,blobs),"bottom/top/upper-side guard change must reject, exact restoration must revalidate");
   }
  }
  void poll(){
   var p=probe;p.require(p.server.isSameThread(),"native depth actor required");
   switch(phase){
    case 0->{
     if(!p.opening.isDone())return;p.require(!p.opening.isCompletedExceptionally(),"private deep J04 open failed");
     p.store=p.opening.getNow(null);p.require(!p.store.readOnlySafe(),"private deep J04 unavailable");p.read();phase=1;
    }
    case 1->{
     if(!p.readDone())return;p.require(p.records.isEmpty(),"depth fixture must never overwrite prior WAL");fixture();p.event=UUID.randomUUID();
     var rows=new ArrayList<DurableRecord>();
     for(var cell:p.cells){
      var owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),p.now()),key(cell),
       Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),
       p.hash(cell),new Epoch(0),Optional.empty());
      p.initial.put(key(cell),owner);p.owners.put(key(cell),owner);rows.add(new DurableOwnership(owner));
     }
     p.capture(ControlledWaterBinding.IMPORT);p.original=p.work.plan;p.originalBlobs=p.work.captured.blobs();p.append(rows,List.of());phase=2;
    }
    case 2->{if(!p.acked())return;validate(false);for(var cell:p.cells)p.publish(cell,p.owners.get(key(cell)));p.work.submit();phase=3;}
    case 3->{if(!p.work.poll())return;validate(true);p.read();phase=4;}
    case 4->{if(!p.readDone())return;p.commitImportMutations();phase=5;}
    case 5->{if(!p.acked())return;p.capture(ControlledWaterBinding.RESTORE);guardRejections();p.work.submit();phase=6;}
    case 6->{if(!p.work.poll())return;validate(false);for(var cell:p.cells)p.require(p.hash(cell).equals(p.original.preconditions().fingerprints().get(key(cell))),"exact original native source bytes required");p.read();phase=7;}
    case 7->{
     if(!p.readDone())return;
     p.require(p.records.stream().filter(DurablePrepare.class::isInstance).count()==2&&p.records.stream().filter(DurableResult.class::isInstance).count()==2,
      "exactly actual IMPORT/RESTORE PREPARE+RESULT required, no vertical pulse");
     p.require(p.mutations.size()==4&&p.mutations.values().stream().allMatch(m->m.state()==MutationState.ROLLED_BACK&&m.header().revision().value()==3&&m.durableSeq().value()>0),
      "four actual committed import mutations must be durably rolled back");
     var rows=new ArrayList<DurableRecord>();
     for(var cell:p.cells){var old=p.owners.get(key(cell));var h=old.header();
      var released=new OwnershipRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),h.lastSequence(),p.now()),key(cell),old.ownerTokens(),
       old.epoch(),OwnershipDisposition.RELEASED,old.creationMutationId(),p.hash(cell),old.playerOverrideEpoch(),old.exceptionContractId());
      p.owners.put(key(cell),released);rows.add(new DurableOwnership(released));
     }
     p.append(rows,List.of());phase=8;
    }
    case 8->{if(!p.acked())return;for(var cell:p.cells)p.publish(cell,p.owners.get(key(cell)));p.closing=p.store.closeAsync().toCompletableFuture();phase=9;}
    case 9->{
     if(!p.closing.isDone())return;p.require(!p.closing.isCompletedExceptionally(),"deep J04 close failed");
     System.out.println("DIRECTOR_NATIVE_FLOOD_DEPTH4_SOURCE32_REAL_IMPORT_RESTORE_GUARDS_PASS world="+p.world.value()+
      " event="+p.event+" layers=2 cells=4 units=32 vertical_pulse=false full_warn=false client=false human=false");
     p.done=true;done=true;phase=99;
    }
   }
  }
 }

}
