package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Provider/J04 regression only: real blocks, real permits and fsync; no J03 eligibility or J06 claim.
 * Reopens the SAME private WAL with the Minecraft level still loaded; this is not cold NBT proof. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class NativeGrowthMutationJournalGameTests {
 private NativeGrowthMutationJournalGameTests(){}
 private static final Set<Probe> ACTIVE=Collections.newSetFromMap(new IdentityHashMap<>());
 private static final PrimitiveId GROWTH=new PrimitiveId("proutlost:camp_growth");
 private static final ResourceId RECIPE=new ResourceId("proutlost_director:test_growth_mutation");
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 public static boolean active(MinecraftServer server){return ACTIVE.stream().anyMatch(p->p.level.getServer()==server);}

 /** Observe the actual batches/ACKs submitted by EventRunner; all IO delegates to JournalStore. */
 private static final class TrackedStore implements DurableStore {
  final JournalStore actual;
  DurableBatch resultBatch;
  CompletableFuture<CommitAck> resultAck;
  TrackedStore(JournalStore actual){this.actual=actual;}
  public CompletionStage<CommitAck> append(DurableBatch batch){
   var future=actual.append(batch).toCompletableFuture();
   if(batch.records().stream().anyMatch(r->r instanceof DurableResult)){
    check(resultBatch==null,"second RESULT on one execution");
    resultBatch=batch;resultAck=future;
   }
   return future;
  }
  public StoreReadResult view(StoreQuery q){return actual.view(q);}
  public CompletionStage<StoreReadResult> read(StoreQuery q){return actual.read(q);}
  public StoreSession session(){return actual.session();}
  public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest q){return actual.readBlobs(q);}
 }
 private static final class Execution {
  TrackedStore tracked;
  PreparedEffect plan;
  PrepareRecord prepare;
  ResourceArbiter arbiter;
  ReservationToken token;
  EventRunner runner;
  LiveValidationSnapshot live;
  MutationRecord row;
  List<DurableRecord> rows;
  boolean inverse;
  OwnershipRecord before;
  DurableBatch batch;
 }
 private static final class Probe {
  final ServerLevel level;
  final boolean coordinatorRecovery;
  final UUID correlationOnlyNotObservedPlayer=UUID.randomUUID();
  final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
  DirectorNativeGameplay recovery;
  final BlockPos block;
  final BlockState original,desired;
  final WorldId world;
  final BlockPosition position;
  final ResourceKey key;
  final Footprint footprint;
  final OwnershipRecord admitted;
  final EventInstanceId event=new EventInstanceId(UUID.randomUUID());
  final AtomicReference<OwnershipView> ledger=new AtomicReference<>(new OwnershipView(Map.of()));
  final Path path;
  CompletableFuture<JournalStore> opening;
  JournalStore store;
  Execution forward,inverse,current;
  MutationRecord committed,pending;
  DurableBatch metadata;
  CompletableFuture<CommitAck> ack;
  CompletableFuture<Void> closing;
  StoreQuery query;
  StoreSession readSession;
  CompletableFuture<StoreReadResult> reading;
  Sequence forwardSequence,inverseSequence;
  OwnershipRecord released;
  RuntimeException failure;
  int phase;
  boolean done;
  Probe(GameTestHelper helper){this(helper,false);}
  Probe(GameTestHelper helper,boolean coordinatorRecovery){
   this.coordinatorRecovery=coordinatorRecovery;level=helper.getLevel();check(level.getServer().isSameThread(),"native actor");
   block=helper.absolutePos(coordinatorRecovery?new BlockPos(0,1,0):new BlockPos(1,2,1));
   if(coordinatorRecovery){
    var observedState=level.getBlockState(block);var observedOwner=attached.read(level,block);
    check(observedOwner.isEmpty()&&(observedState.is(Blocks.AIR)||observedState.equals(Blocks.STONE.defaultBlockState())),
     "unowned AIR/default STONE recovery fixture required: position="+block+" observed_block="+observedState+" observed_owner="+observedOwner);
    // Only the template interior is used; encasing barriers and owned cells are never overwritten.
    if(observedState.is(Blocks.AIR))level.setBlock(block,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
    check(level.getBlockState(block).equals(Blocks.STONE.defaultBlockState()),
     "native STONE fixture setup failed: position="+block+" observed_block="+level.getBlockState(block)+" observed_owner="+attached.read(level,block));
   }
   original=level.getBlockState(block);
   check(!original.hasBlockEntity()&&original.getFluidState().isEmpty()
    &&!(original.getBlock() instanceof FallingBlock)&&!(original.getBlock() instanceof BaseFireBlock)
    &&original.equals(original.getBlock().defaultBlockState()),"safe default fixture state required");
   desired=original.is(Blocks.MOSS_BLOCK)?Blocks.STONE.defaultBlockState():Blocks.MOSS_BLOCK.defaultBlockState();
   var dim=new DimensionId(level.dimension().location().toString());
   position=new BlockPosition(dim,block.getX(),block.getY(),block.getZ());
   key=new ResourceKey(OwnershipDomain.BLOCK,dim,block.getX()+","+block.getY()+","+block.getZ());
   footprint=new Footprint(dim,Set.of(new ChunkRef(dim,block.getX()>>4,block.getZ()>>4)),Set.of(position),List.of());
   check(level.getChunkSource().getChunkNow(block.getX()>>4,block.getZ()>>4)!=null,"fixture chunk not loaded");
   if(coordinatorRecovery)check(attached.read(level,block).isEmpty(),"recovery fixture already has native ownership");
   world=level.getServer().overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   admitted=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),
    key,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
    new MutationId(UUID.randomUUID()),hash(),new Epoch(0),Optional.empty());
   path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("director-native-growth-mutation-test-only").resolve(UUID.randomUUID().toString());
   opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();ACTIVE.add(this);
  }
  Tick now(){return new Tick(level.getServer().overworld().getGameTime());}
  Fingerprint hash(){return BlockTransformBinding.stateBlob(level.getBlockState(block)).reference().hash();}
  OwnershipRecord owner(){return Objects.requireNonNull(ledger.get().records().get(key));}
  PlanningSnapshot snapshot(){
   var o=owner();
   var cell=new TerrainCellSnapshot(position,hash(),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch());
   var terrain=new TerrainSnapshot(footprint,Map.of(position,cell),List.of(),Map.of(),new Revision(1));
   var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
   var snap=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   return new PlanningSnapshot(snap,ledger.get(),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
  }
  ResourceId blockId(BlockState state){return new ResourceId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());}
  Execution execute(boolean reverse){
   var e=new Execution();e.inverse=reverse;e.before=owner();e.tracked=new TrackedStore(store);
   var binding=new BlockTransformBinding(d->level,ledger::get,(p,next)->{
    check(p.equals(position),"foreign physical cell");ledger.set(new OwnershipView(Map.of(key,next)));
    if(coordinatorRecovery)attached.write(level,block,next);
   },this::now,Map.of(RECIPE,new BlockTransformBinding.Recipe(
     Map.of(blockId(reverse?desired:original),blockId(reverse?original:desired)),true)),1);
   var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),GROWTH,1,
    new CampGrowth(new TagId("proutlost_director:test_fixture"),RECIPE,1),footprint,event);
   var captured=binding.capture(intent,snapshot());
   check(captured.validation().valid()&&captured.prepared().isPresent(),"actual block capture unavailable");
   e.plan=captured.prepared().orElseThrow();
   long floor=Math.max(store.session().bootEpoch().value(),forward==null?0:forward.token.generation().value());
   e.arbiter=new ResourceArbiter(world,new Epoch(floor),1000,4,new ResourceArbiter.Compatibility(){
    public boolean share(ResourceClaim a,ResourceClaim b){return false;}
    public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
   });
   var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();
   e.plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
   var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),event,
    new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),now().plus(1200),
    e.plan.claims(),Map.of(scope,e.plan.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),footprint,
    e.plan.preconditions(),List.of(intent),e.plan.budgetEstimate());
   e.prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),e.token.generation(),
    e.plan.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),List.of(),Set.of(),List.of(),e.plan.durableBlobs());
   e.live=new LiveValidationSnapshot(e.plan.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
   var authority=new CommitPermit.Authority(e.tracked,4);
   var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("already captured");},s->e.live,
    Map.of(GROWTH,binding),binding.capabilities(),4,
    s->new StabilizationLiveSnapshot(store.session(),e.live,ledger.get()),m->{},(m,r)->{});
   port.hydrate(e.plan,captured.blobs());e.runner=new EventRunner(authority,e.tracked,port,e.arbiter,4);
   e.batch=new DurableBatch(e.prepare.transactionId(),store.session().storeGeneration(),e.prepare.readSet().revisions(),
    List.of(new DurablePrepare(e.prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(e.prepare));
   if(coordinatorRecovery&&!reverse){
    // Schema-compatible recovery fixture. This UUID is correlation data, never a player observation/authorization.
    var payload=Map.<String,JsonValue>of(
     "plan",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(PreparedEffect.class).encode(e.plan))),
     "owner",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(OwnershipRecord.class).encode(admitted))),
     "original",new JsonValue.Text(blockId(original).value()),
     "player",new JsonValue.Text(correlationOnlyNotObservedPlayer.toString()),
     "test_only_correlation",new JsonValue.Text("NOT_OBSERVED_PLAYER_NOT_J06_EVIDENCE"));
    var dossier=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:lab_growth_dossier"),
     List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(event),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
    append(List.of(new DurableFact(dossier)));
   }else submit(e);
   return e;
  }
  void submit(Execution e){
   e.runner.submit(e.batch,e.plan.expectedPost(),e.live,actual->{
    e.rows=DirectorNativeGameplay.blockResultRecords(e.plan,e.prepare,e.before,owner(),
     e.inverse?Optional.of(committed):Optional.empty(),actual,e.inverse,now());
    check(e.rows.size()==3&&e.rows.stream().filter(r->r instanceof DurableResult).count()==1
     &&e.rows.stream().filter(r->r instanceof DurableOwnership).count()==1
     &&e.rows.stream().filter(r->r instanceof DurableMutation).count()==1,"RESULT must contain the exact three joined rows");
    e.row=((DurableMutation)e.rows.stream().filter(r->r instanceof DurableMutation).findFirst().orElseThrow()).value();
    var result=((DurableResult)e.rows.stream().filter(r->r instanceof DurableResult).findFirst().orElseThrow()).value();
    check(result.result().equals(actual)&&result.mutations().equals(Set.of(admitted.creationMutationId())),"real RESULT lost mutation join");
    return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),e.rows,List.of());
   });
  }
  boolean applied(Execution e){
   e.live=new LiveValidationSnapshot(e.live.readSet(),footprint.loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
   var receipt=e.runner.poll(e.prepare.transactionId(),e.live);
   check(receipt.stage()!=EventRunner.Stage.REJECTED&&receipt.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"real EventRunner failed: "+receipt);
   if(receipt.stage()!=EventRunner.Stage.COMMITTED)return false;
   check(receipt.result().orElseThrow().outcome()==Outcome.SUCCEEDED,"physical effect did not succeed");
   check(level.getBlockState(block).equals(e.inverse?original:desired),"actual native block not exact");
   check(e.tracked.resultBatch.records().equals(e.rows),"Mutation/Ownership not in actual RESULT transaction");
   ack(e.tracked.resultBatch,e.tracked.resultAck);
   return true;
  }
  void point(){
   readSession=store.session();
   query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.MUTATION,admitted.creationMutationId().value()),
    new AggregateKey(AggregateKind.OWNERSHIP,admitted.header().id())),new Sequence(0),Optional.empty(),2);
   reading=store.read(query).toCompletableFuture();
  }
  StoreReadResult read(){
   var value=reading.join();ReadContracts.validate(value,query,readSession);
   check(readSession.equals(store.session())&&value.status()==ReadStatus.AVAILABLE&&!value.page().orElseThrow().hasMore(),"exact point read unavailable");
   return value;
  }
  RestoredAggregate mutation(StoreReadResult value){
   var rows=value.page().orElseThrow().aggregates();
   check(rows.size()==2,"mutation/ownership point join absent");
   var mutation=rows.stream().filter(r->r.state() instanceof MutationAggregate).findFirst().orElseThrow();
   var owned=(OwnershipAggregate)rows.stream().filter(r->r.state() instanceof OwnershipAggregate).findFirst().orElseThrow().state();
   check(owned.value().equals(owner())&&owned.value().creationMutationId().equals(admitted.creationMutationId()),"durable ownership join mismatch");
   return mutation;
  }
  void append(List<DurableRecord> rows){
   metadata=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
   ack=store.append(metadata).toCompletableFuture();
  }
  void checkPhysicalRow(Execution e,RestoredAggregate restored){
   check(restored.state() instanceof MutationAggregate,"wrong point type");
   var row=((MutationAggregate)restored.state()).value();
   check(row.equals(e.row)&&row.mutationId().equals(admitted.creationMutationId())&&row.durableSeq().value()==0,"RESULT mutation not exact/seq0");
   check(row.state()==(e.inverse?MutationState.ROLLED_BACK:MutationState.VERIFIED)
    &&row.header().revision().value()==(e.inverse?3:1),"physical mutation state/revision");
   check(restored.committedSequence().equals(e.tracked.resultAck.join().durableSequence())
    &&restored.transactionHash().equals(e.tracked.resultBatch.transactionHash()),"point sequence/hash differs from actual RESULT ACK");
   if(e.inverse){
    check(row.sliceId().equals(committed.sliceId())&&row.preStateRef().equals(committed.preStateRef())
     &&row.postStateRef().equals(committed.postStateRef()),"inverse replaced original mutation provenance");
    check(row.reconciliationEvidence().size()==1&&row.reconciliationEvidence().getFirst().observedHash().equals(Optional.of(hash())),
     "inverse evidence is not observed original state");
   }
   pending=DirectorNativeGameplay.committedMutation(row,restored.committedSequence(),now());
   check(pending.header().revision().value()==row.header().revision().value()+1
    &&pending.header().lastSequence().equals(restored.committedSequence())&&pending.durableSeq().equals(restored.committedSequence())
    &&pending.state()==(e.inverse?MutationState.ROLLED_BACK:MutationState.COMMITTED),"point finalization is not actual positive sequence");
  }
  void checkFinalRow(MutationRecord expected){
   var restored=mutation(read());check(((MutationAggregate)restored.state()).value().equals(expected),"metadata mutation row differs after ACK/reopen");
   check(expected.durableSeq().value()>0&&expected.durableSeq().equals(current.inverse?inverseSequence:forwardSequence),"stable physical commit sequence lost");
  }
  void partialContract(){
   // Data-only negative path. This fabricated PARTIAL is never submitted or counted as physical gameplay.
   var partial=new SliceResult(Outcome.PARTIAL,Set.of(),Map.of(),1,Set.of(),Set.of(position),List.of());
   var rows=DirectorNativeGameplay.blockResultRecords(forward.plan,forward.prepare,forward.before,forward.before,Optional.empty(),partial,false,now());
   check(rows.size()==1&&rows.stream().noneMatch(r->r instanceof DurableMutation||r instanceof DurableOwnership),"unchanged PARTIAL fabricated mutation/duplicate ownership");
   var result=((DurableResult)rows.stream().filter(r->r instanceof DurableResult).findFirst().orElseThrow()).value();
   check(result.result().equals(partial)&&result.result().outcome()==Outcome.PARTIAL&&result.mutations().isEmpty(),"PARTIAL promoted to SUCCESS/join");
  }
  void poll(){
   check(level.getServer().isSameThread(),"native actor");
   switch(phase){
    case 0->{if(!opening.isDone())return;store=opening.join();append(List.of(new DurableOwnership(admitted)));phase=1;}
    case 1->{
     if(!ack.isDone())return;ack(metadata,ack);ledger.set(new OwnershipView(Map.of(key,admitted)));
     if(coordinatorRecovery)attached.write(level,block,admitted);
     current=forward=execute(false);phase=coordinatorRecovery?101:2;
    }
    case 101->{if(!ack.isDone())return;ack(metadata,ack);submit(current);phase=2;}
    case 2->{if(!applied(current))return;partialContract();point();phase=3;}
    case 3->{if(!reading.isDone())return;var restored=mutation(read());checkPhysicalRow(current,restored);forwardSequence=restored.committedSequence();append(List.of(new DurableMutation(pending)));phase=4;}
    case 4->{if(!ack.isDone())return;ack(metadata,ack);committed=pending;point();phase=5;}
    case 5->{if(!reading.isDone())return;checkFinalRow(committed);closing=store.closeAsync().toCompletableFuture();phase=6;}
    case 6->{if(!closing.isDone())return;closing.join();opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=7;}
    case 7->{if(!opening.isDone())return;store=opening.join();point();phase=8;}
    case 8->{if(!reading.isDone())return;checkFinalRow(committed);check(level.getBlockState(block).equals(desired),"forward physical POST lost");current=inverse=execute(true);phase=9;}
    case 9->{if(!applied(current))return;point();phase=10;}
    case 10->{
     if(!reading.isDone())return;var restored=mutation(read());checkPhysicalRow(current,restored);inverseSequence=restored.committedSequence();
     if(coordinatorRecovery){
      check(current.row.state()==MutationState.ROLLED_BACK&&current.row.durableSeq().value()==0,"interruption must precede inverse metadata/DONE");
      check(attached.read(level,block).orElseThrow().equals(owner()),"saved local inverse POST owner differs");
      closing=store.closeAsync().toCompletableFuture();phase=30;
     }else{append(List.of(new DurableMutation(pending)));phase=11;}
    }
    case 11->{
     if(!ack.isDone())return;ack(metadata,ack);committed=pending;
     var o=owner();var h=o.header();
     released=new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),
      key,o.ownerTokens(),o.epoch(),OwnershipDisposition.RELEASED,o.creationMutationId(),o.expectedPostHash(),o.playerOverrideEpoch(),o.exceptionContractId());
     append(List.of(new DurableOwnership(released)));phase=12;
    }
    case 12->{
     if(!ack.isDone())return;ack(metadata,ack);ledger.set(new OwnershipView(Map.of(key,released)));
     check(forward.arbiter.release(forward.token)&&inverse.arbiter.release(inverse.token),"claims not released after durable cleanup");
     point();phase=13;
    }
    case 13->{
     if(!reading.isDone())return;checkFinalRow(committed);
     readSession=store.session();query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),64);
     reading=store.read(query).toCompletableFuture();phase=14;
    }
    case 14->{
     if(!reading.isDone())return;var rows=read().page().orElseThrow().records();
     check(rows.stream().filter(r->r instanceof DurablePrepare).count()==2
      &&rows.stream().filter(r->r instanceof DurableResult).count()==2
      &&rows.stream().filter(r->r instanceof DurableMutation).count()==4,"actual two physical transactions/four mutation revisions missing");
     check(rows.stream().filter(r->r instanceof DurableMutation).allMatch(r->((DurableMutation)r).value().mutationId().equals(admitted.creationMutationId())),"creation MutationId changed");
     closing=store.closeAsync().toCompletableFuture();phase=15;
    }
    case 15->{if(!closing.isDone())return;closing.join();opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=16;}
    case 16->{if(!opening.isDone())return;store=opening.join();point();phase=17;}
    case 17->{
     if(!reading.isDone())return;checkFinalRow(committed);
     check(owner().disposition()==OwnershipDisposition.RELEASED&&level.getBlockState(block).equals(original),"exact cleanup/owner lost after second WAL reopen");
     closing=store.closeAsync().toCompletableFuture();phase=18;
    }
    case 18->{
     if(!closing.isDone())return;closing.join();ACTIVE.remove(this);done=true;phase=19;
     System.out.println("DIRECTOR_NATIVE_GROWTH_STABLE_MUTATION_RESULT_POINTSEQ_INVERSE_REOPEN_PASS mutation="
      +admitted.creationMutationId().value()+" forwardSequence="+forwardSequence.value()+" inverseSequence="+inverseSequence.value()+" wal="+path);
    }
    case 30->{if(!closing.isDone())return;closing.join();opening=JournalStore.open(path,world,OPTIONS).toCompletableFuture();phase=31;}
    case 31->{if(!opening.isDone())return;store=opening.join();point();phase=32;}
    case 32->{
     if(!reading.isDone())return;checkPhysicalRow(current,mutation(read()));
     check(level.getBlockState(block).equals(original)&&attached.read(level,block).orElseThrow().equals(owner()),"native inverse POST changed before coordinator recovery");
     recovery=new DirectorNativeGameplay(level.getServer(),world,store);phase=33;
    }
    case 33->{
     recovery.poll();var status=recovery.status();check(!status.startsWith("RECOVERY_REQUIRED"),"real coordinator recovery failed: "+status);
     if(!status.startsWith("IDLE pending=0"))return;
     ledger.set(new OwnershipView(Map.of(key,attached.read(level,block).orElseThrow())));point();phase=34;
    }
    case 34->{
     if(!reading.isDone())return;var restored=mutation(read());var row=((MutationAggregate)restored.state()).value();
     check(row.mutationId().equals(admitted.creationMutationId())&&row.state()==MutationState.ROLLED_BACK
      &&row.header().revision().value()==4&&row.durableSeq().equals(inverseSequence)&&row.header().lastSequence().equals(inverseSequence),
      "real coordinator did not finalize exact inverse physical commit");
     check(row.sliceId().equals(committed.sliceId())&&row.preStateRef().equals(committed.preStateRef())
      &&row.postStateRef().equals(committed.postStateRef())&&row.reconciliationEvidence().equals(current.row.reconciliationEvidence()),
      "recovery replaced original mutation/evidence");
     check(owner().disposition()==OwnershipDisposition.RELEASED&&level.getBlockState(block).equals(original),"recovery did not release exact original block");
     readSession=store.session();query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),64);
     reading=store.read(query).toCompletableFuture();phase=35;
    }
    case 35->{
     if(!reading.isDone())return;var rows=read().page().orElseThrow().records();
     check(rows.stream().filter(r->r instanceof DurablePrepare).count()==2&&rows.stream().filter(r->r instanceof DurableResult).count()==2,
      "coordinator recovery replayed a physical PREPARE/RESULT");
     check(rows.stream().filter(r->r instanceof DurableMutation).count()==4
      &&rows.stream().filter(r->r instanceof DurableFact f&&f.value().factType().equals(new FactType("proutlost_director:lab_growth_done"))
       &&f.value().eventId().equals(Optional.of(event))).count()==1,"recovery metadata/DONE missing or duplicated");
     check(forward.arbiter.release(forward.token)&&inverse.arbiter.release(inverse.token),"recovery claims not released");
     recovery.close();recovery=null;closing=store.closeAsync().toCompletableFuture();phase=18;
     System.out.println("DIRECTOR_NATIVE_GROWTH_COORDINATOR_RECOVERY_RAW_INVERSE_NO_REPLAY_PASS mutation="+admitted.creationMutationId().value());
    }
    default->{}
   }
  }
  void close(){ACTIVE.remove(this);if(recovery!=null){recovery.close();recovery=null;}if(store!=null)store.closeAsync();}
 }
 private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
 private static CommitAck ack(DurableBatch batch,CompletableFuture<CommitAck> pending){
  var result=pending.join();
  check(result.ackStatus()==AckStatus.DURABLE&&result.transactionId().equals(batch.transactionId())
   &&result.transactionHash().equals(batch.transactionHash())&&result.storeGeneration().equals(batch.expectedStoreGeneration())
   &&result.durableSequence().value()>0&&result.permitAttempt().equals(batch.permitAttempt())
   &&result.forcedBlobs().equals(DurableBlobReferences.collect(batch.records())),"actual transaction ACK mismatch");
  return result;
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void stableGrowthMutationJoinsRealResultAndInverse(GameTestHelper helper){
  var probe=new Probe(helper);
  await(helper,probe);
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void inverseResultRecoveredByActualCoordinatorWithoutReplay(GameTestHelper helper){
  var probe=new Probe(helper,true);
  await(helper,probe);
 }
 private static void await(GameTestHelper helper,Probe probe){
  helper.succeedWhen(()->{
   if(probe.failure!=null){helper.assertTrue(false,probe.failure.toString());return;}
   try{probe.poll();}
   catch(RuntimeException failure){
    probe.failure=failure;failure.printStackTrace();
    try{probe.close();}catch(RuntimeException close){failure.addSuppressed(close);close.printStackTrace();}
    helper.assertTrue(false,failure.toString());return;
   }
   helper.assertTrue(probe.done,"growth MutationRecord physical/WAL proof pending");
  });
 }
}
