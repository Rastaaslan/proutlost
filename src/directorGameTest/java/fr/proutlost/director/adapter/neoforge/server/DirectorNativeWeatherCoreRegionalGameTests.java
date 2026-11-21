package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.effects.weathercore.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import io.github.steamautomata.weathercore.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Real pinned Core provider + J04 + exact permit. No client/production-coordinator claim. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWeatherCoreRegionalGameTests {
 private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 private static final FactType CHECKPOINT=new FactType("proutlost_director:qa_regional_cont_checkpoint");
 private enum Scenario { INVERSE, EXPIRY, COLD_SAVE, COLD_REARM, RUNTIME_SAVE, RUNTIME_COLD, RUNTIME_REVOKE, RUNTIME_RECOVER, RUNTIME_LOSS_SAVE, RUNTIME_LOSS_COLD }
 private static final class Probe {
  final GameTestHelper h;final ServerLevel level;final Scenario scenario;final DimensionId dim;
  final Path root;final WorldId world;final CompletableFuture<JournalStore> opening;
  final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>();final Set<String> globalsBefore;
  final Map<ResourceKey,Revision> revisions=new HashMap<>();
  final ModelCodec<PreparedEffect> codec=new ModelCodec<>(PreparedEffect.class);
  JournalStore store;CompletableFuture<CommitAck> writing;CompletableFuture<Void> closing;
  CompletableFuture<BlobReadResult> bytes;
  WeatherCoreRegionalBinding binding;EventInstanceId event;ResourceKey key;OwnershipRecord owner;
  Footprint footprint;EffectPreparation forward,current;PreparedEffect original;
  EventRunner runner;CommitPermit.Authority authority;ServerEffectPort port;ResourceArbiter arbiter;
  PrepareRecord prepare;LiveValidationSnapshot live;ReservationToken token;
  int stage;long deadline;UUID weather;Weather originalObject;long previousBoot;boolean done;
  Probe(GameTestHelper helper,Scenario scenario){
   this.h=helper;this.scenario=scenario;level=helper.getLevel();dim=new DimensionId(level.dimension().location().toString());
   if(!Boolean.getBoolean("proutlost.director.nativeLab")||!Boolean.getBoolean("proutlost.director.nativeGameTests")
      ||!Boolean.getBoolean("proutlost.quest.neutralLab")||!level.getServer().getPlayerList().getPlayers().isEmpty())
     throw new IllegalStateException("explicit zero-client neutral native QA required");
   try{
    Path w=level.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
    if(!w.equals(Path.of(System.getProperty("proutlost.quest.neutralLabRoot","")).toRealPath())
       ||Files.isSymbolicLink(w.resolve("TEST_ONLY"))
       ||!Files.readString(w.resolve("TEST_ONLY")).trim().equals("PROUTLOST_NEUTRAL_QA_V1"))throw new IllegalStateException("owned TEST_ONLY world");
    root=w.resolve(scenario.name().startsWith("RUNTIME_")?(scenario.name().startsWith("RUNTIME_LOSS_")?"director-regional-runtime-loss-a04":scenario==Scenario.RUNTIME_RECOVER?"director-regional-runtime-cold-a01":scenario==Scenario.RUNTIME_REVOKE?"director-regional-runtime-revoke-a02":"director-regional-runtime-cold-a02"):scenario==Scenario.COLD_SAVE||scenario==Scenario.COLD_REARM?"director-regional-cont-cold-a01":"director-regional-cont-"+scenario.name().toLowerCase()+"-"+UUID.randomUUID());
    Path identity=root.resolve("QA_WORLD_ID");
    if(scenario==Scenario.COLD_REARM||(scenario==Scenario.RUNTIME_COLD||scenario==Scenario.RUNTIME_RECOVER||scenario==Scenario.RUNTIME_LOSS_COLD)){world=new WorldId(UUID.fromString(Files.readString(identity).trim()));}
    else{Files.createDirectory(root);world=new WorldId(UUID.randomUUID());Files.writeString(identity,world.value().toString(),StandardOpenOption.CREATE_NEW);}
   }catch(java.io.IOException e){throw new IllegalStateException(e);}
   globalsBefore=globals();
   opening=(scenario==Scenario.RUNTIME_LOSS_SAVE?fr.proutlost.director.persistence.NativeRegionalWriterLossFixture.open(root.resolve("j04"),world,OPTIONS):JournalStore.open(root.resolve("j04"),world,OPTIONS)).toCompletableFuture();
  }
  Set<String> globals(){return WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global)
      .map(w->w.uuid()+"/"+w.type().toString()).collect(java.util.stream.Collectors.toSet());}
  void check(boolean v,String why){h.assertTrue(v,why);}
  void reject(Runnable r,String why){boolean refused=false;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){refused=true;}check(refused,why);}
  OwnershipView ownership(){return new OwnershipView(owners);}
  Tick now(){return new Tick(level.getGameTime());}
  SnapshotBatch snapshot(){
   var scope=new ScopeRef(ScopeKind.WORLD,world.value());
   var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
   var read=store.view(q);ReadContracts.validate(read,q,store.session());check(read.status()==ReadStatus.AVAILABLE,"actual WORLD read unavailable");
   var w=((WorldAggregate)read.page().orElseThrow().aggregates().getFirst().state()).value();
   return new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of(scope)),now(),Map.of(scope,w.header().revision()),
      new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  }
  PlanningSnapshot planning(){return new PlanningSnapshot(snapshot(),ownership(),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));}
  CompletableFuture<CommitAck> append(List<DurableRecord> rows){return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of())).toCompletableFuture();}
  boolean ack(){if(!writing.isDone())return false;check(writing.getNow(null).ackStatus()==AckStatus.DURABLE,"metadata ACK");writing=null;return true;}
  void setupBinding(){binding=WeatherCoreIntegration.createRegionalBinding(d->level,this::ownership,k->revisions.getOrDefault(k,new Revision(1)),4);}
  BlobContent stop(){return forward.blobs().contents().stream().filter(b->b.reference().equals(original.durableBlobs().stopPlan())).findFirst().orElseThrow();}
  Weather object(){var s=binding.observedState(dim,event).state();return WeatherManager.get(level).weathersAt(new Vec3(s.region().centerX(),0,s.region().centerZ())).filter(w->w.uuid().equals(s.weather())).findFirst().orElseThrow();}
  void execute(EffectPreparation captured){
   current=captured;var p=captured.prepared().orElseThrow();var i=p.intent();var scope=new ScopeRef(ScopeKind.WORLD,world.value());
   arbiter=new ResourceArbiter(world,new Epoch(store.session().bootEpoch().value()*10+stage),1000,4,new ResourceArbiter.Compatibility(){
    public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   var c=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),event,new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),now().plus(1000),p.claims(),Map.of(scope,p.budgetEstimate()));
   token=arbiter.arbitrate(List.of(c),now(),Map.of(key,owner.epoch()),Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),p.preconditions(),List.of(i),p.budgetEstimate());
   prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),token.generation(),p.preconditions(),now().plus(900),slice,token,captured.validation(),List.of(),Set.of(),List.of(),p.durableBlobs());
   live=new LiveValidationSnapshot(p.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),token.generation());
   authority=new CommitPermit.Authority(store,4);
   port=new ServerEffectPort(authority,x->{throw new IllegalStateException("captured");},x->live,Map.of(i.primitive(),binding),binding.capabilities(),4,x->new StabilizationLiveSnapshot(store.session(),live,ownership()),x->{},(x,y)->{});
   var extra=new ArrayList<>(captured.blobs().contents());extra.add(BlobContent.capture(new ResourceId("proutlost_director:qa_unsealed"),1,new byte[]{77}));
   reject(()->port.hydrate(p,new BlobBundle(extra)),"unjournaled blob accepted");
   port.hydrate(p,captured.blobs());runner=new EventRunner(authority,store,port,arbiter,4);
   runner.submit(new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),p.preconditions().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare)),p.expectedPost(),live,
      actual->ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableResult(new ResultRecord(prepare.transactionId(),i.operationToken(),actual,Set.of(),List.of(),List.of()))),List.of()));
  }
  boolean committed(){
   live=new LiveValidationSnapshot(live.readSet(),live.loadedChunks(),live.health(),now(),token.generation());
   var v=runner.poll(prepare.transactionId(),live);check(v.stage()!=EventRunner.Stage.REJECTED&&v.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native effect pending/failed "+v.failure());
   if(v.stage()!=EventRunner.Stage.COMMITTED)return false;
   check(v.result().orElseThrow().outcome()==Outcome.SUCCEEDED,"actual regional result");check(arbiter.release(token),"exact reservation release");return true;
  }
  RecoveryEvidence proof(PreparedEffect p){
   var actual=binding.observedFingerprint(dim,event);
   return new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(actual),Optional.of(owner.ownerTokens().iterator().next()),owner.epoch(),owner.playerOverrideEpoch(),false);
  }
  void cleanup(){
   var stopFootprint=new Footprint(dim,Set.of(),Set.of(),footprint.bounds());
   var i=WeatherCoreRegionalBinding.cleanupRequest(new OperationToken(UUID.randomUUID()),event,stopFootprint,revisions.get(key));
   binding.registerCleanup(i,stop());execute(binding.capture(i,planning()));stage=5;
  }
  void step(){
   switch(stage){
    case 0->{
     if(!opening.isDone())return;store=opening.getNow(null);check(!store.readOnlySafe(),"actual private J04");
     if(scenario==Scenario.COLD_REARM){
      var q=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);var read=store.view(q);ReadContracts.validate(read,q,store.session());
      check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"complete cold private J04 read");
      var rows=read.page().orElseThrow().records();
      var f=rows.stream().filter(r->r instanceof DurableFact d&&d.value().factType().equals(CHECKPOINT)).map(r->((DurableFact)r).value()).findFirst().orElseThrow();
      original=codec.decode(Base64.getDecoder().decode(((JsonValue.Text)f.payload().get("plan")).value()));
      previousBoot=Long.parseLong(((JsonValue.Text)f.payload().get("boot")).value());
      check(store.session().bootEpoch().value()>previousBoot,"new cold J04 session");
      event=original.intent().instanceId();footprint=original.intent().expectedFootprint();key=WeatherCoreRegionalBinding.resource(dim,event);
      owner=rows.stream().filter(r->r instanceof DurableOwnership d&&d.value().resourceKey().equals(key)).map(r->((DurableOwnership)r).value()).findFirst().orElseThrow();
      owners.put(key,owner);revisions.put(key,new Revision(1));setupBinding();
      var observed=binding.observedState(dim,event);check(observed.state().kind()==WeatherCoreRegionalState.Kind.ACTIVE&&!observed.renderArmed(),"cold native region must exist disarmed");
      deadline=observed.state().deadline();weather=observed.state().weather();originalObject=object();
      check(WeatherCoreRegionalWitness.read(checkpointWitness(f)).state().weather().equals(weather),"cold native identity differs from checkpoint");
      bytes=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),CommitPermit.Authority.references(original.durableBlobs()))).toCompletableFuture();stage=8;return;
     }
     event=new EventInstanceId(UUID.randomUUID());key=WeatherCoreRegionalBinding.resource(dim,event);revisions.put(key,new Revision(1));
     var at=h.absolutePos(BlockPos.ZERO);int cx=(at.getX()>>4)*16+8,cz=(at.getZ()>>4)*16+8;
     footprint=new Footprint(dim,Set.of(new ChunkRef(dim,cx>>4,cz>>4)),Set.of(),List.of(new Aabb(dim,cx-4,level.getMinBuildHeight(),cz-4,cx+4,level.getMaxBuildHeight(),cz+4)));
     owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),key,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),BlobContent.hash(new byte[]{1}),new Epoch(0),Optional.empty());owners.put(key,owner);setupBinding();
     var w=new DirectorWorldState(new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),now()),world,store.session().storeGeneration(),now(),RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,PacingState.REST,GlobalTension.CALM,new ResourceId("proutlost:qa_regional"),BlobContent.hash(new byte[]{1}),BlobContent.hash(new byte[]{2}),store.session().bootEpoch(),new Sequence(0),new EngineControlState(1,new Revision(0),now(),now(),new UnitValue(0),now(),Optional.empty(),new UnitValue(.5)));
     writing=append(List.of(new DurableAggregate(new WorldAggregate(w)),new DurableOwnership(owner)));stage=1;
    }
    case 1->{
     if(!ack())return;
     var intent=WeatherCoreRegionalBinding.request(new OperationToken(UUID.randomUUID()),event,footprint,new DurationTicks(scenario==Scenario.EXPIRY?160:scenario==Scenario.COLD_SAVE?70000:3000),new Revision(1));
     forward=binding.capture(intent,planning());original=forward.prepared().orElseThrow();
     owners.put(key,new OwnershipRecord(owner.header(),key,Set.of(new OwnerToken(UUID.randomUUID())),owner.epoch(),owner.disposition(),owner.creationMutationId(),owner.expectedPostHash(),owner.playerOverrideEpoch(),owner.exceptionContractId()));
     check(!binding.revalidate(original,forward.blobs()),"same-epoch rotated owner not refused");owners.put(key,owner);
     revisions.put(key,new Revision(2));check(!binding.revalidate(original,forward.blobs()),"regional revision drift not refused");revisions.put(key,new Revision(1));
     execute(forward);check(binding.observedState(dim,event).state().kind()==WeatherCoreRegionalState.Kind.ABSENT,"region preceded forced PREPARE");stage=2;
    }
    case 2->{
     if(!committed())return;var observed=binding.observedState(dim,event);check(observed.renderArmed(),"fresh region not armed");
     deadline=observed.state().deadline();weather=observed.state().weather();originalObject=object();
     check(binding.reconcile(new RecoveryProbe(original,List.of(proof(original)),forward.blobs())).status()==ReconcileStatus.SAME_POST,"actual current-owner regional post not reconciled");
     check(globalsBefore.equals(globals()),"regional creation changed global UUID/type");
     if(scenario==Scenario.COLD_SAVE){
      var f=new DirectorFact(new FactId(UUID.randomUUID()),CHECKPOINT,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(event),EvidenceStatus.CONFIRMED,
       Map.of("plan",new JsonValue.Text(Base64.getEncoder().encodeToString(codec.encode(original))),"witness",new JsonValue.Text(Base64.getEncoder().encodeToString(observed.bytes())),"boot",new JsonValue.Text(""+store.session().bootEpoch().value())),true,Optional.empty());
      writing=append(List.of(new DurableFact(f)));stage=3;
     }else if(scenario==Scenario.EXPIRY)stage=4;else cleanup();
    }
    case 3->{if(!ack())return;closing=store.closeAsync().toCompletableFuture();stage=7;}
    case 4->{
     if(level.getGameTime()<deadline+2)return;
     check(binding.observedState(dim,event).state().kind()==WeatherCoreRegionalState.Kind.ABSENT,"actual native expiry absent");
     check(binding.reconcile(new RecoveryProbe(original,List.of(proof(original)),forward.blobs())).status()!=ReconcileStatus.SAME_POST,"absence fabricated original impact");
     check(globalsBefore.equals(globals()),"regional expiry changed global");
     closing=store.closeAsync().toCompletableFuture();stage=7;
    }
    case 5->{if(!committed())return;stage=6;}
    case 6->{
     check(binding.observedState(dim,event).state().kind()==WeatherCoreRegionalState.Kind.ABSENT,"only owned region failed to retire");
     check(globalsBefore.equals(globals()),"regional cleanup changed global UUID/type");
     closing=store.closeAsync().toCompletableFuture();stage=7;
    }
    case 7->{
     if(!closing.isDone())return;check(!closing.isCompletedExceptionally(),"private J04 close failed");done=true;
     System.out.println("DIRECTOR_NATIVE_REGIONAL_"+scenario+"_J04_CORE_PASS world="+world.value()+" event="+event.value()+" uuid="+weather+" deadline="+deadline+" cold_minecraft="+(scenario==Scenario.COLD_REARM)+" clients=false production_coordinator=false");
    }
    case 8->{
     if(!bytes.isDone())return;var loaded=bytes.getNow(null);check(loaded.status()==AckStatus.DURABLE,"actual old J04 blobs unavailable");
     forward=new EffectPreparation(original.intent(),new ValidationResult(true,List.of(),original.preconditions()),Optional.of(original),new BlobBundle(loaded.contents()));
     check(binding.reconcile(new RecoveryProbe(original,List.of(proof(original)),forward.blobs())).status()==ReconcileStatus.AMBIGUOUS,"cold metadata-only equality inferred armed post");
     var i=WeatherCoreRegionalBinding.resumeRequest(new OperationToken(UUID.randomUUID()),event,footprint,new Revision(1));
     execute(binding.capture(i,planning()));check(!binding.observedState(dim,event).renderArmed(),"REARM preceded fresh durable PREPARE");stage=9;
    }
    case 9->{
     if(!committed())return;var observed=binding.observedState(dim,event);
     check(observed.renderArmed()&&observed.state().weather().equals(weather)&&observed.state().deadline()==deadline&&object()==originalObject,"REARM replaced native instance/UUID or prolonged expiry");
     check(binding.reconcile(new RecoveryProbe(original,List.of(proof(original)),forward.blobs())).status()==ReconcileStatus.SAME_POST,"real REARM failed exact original post");
     cleanup();
    }
    default->throw new IllegalStateException("regional stage");
   }
  }
  byte[] checkpointWitness(DirectorFact fact){return Base64.getDecoder().decode(((JsonValue.Text)fact.payload().get("witness")).value());}
 }
 private static final class RuntimeProbe {
  final Probe p;final Scenario scenario;boolean done,approved=true,recoveryOnlyVerified;int stage;Object lane;UUID oldUuid;long oldDeadline;
  CompletableFuture<CommitAck> initial;CompletableFuture<Void> closing;ResourceArbiter sharedArbiter;
  fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime runtime;RegionalWeatherJournal.Request request;
  RuntimeProbe(GameTestHelper h,Scenario scenario){this.scenario=scenario;p=new Probe(h,scenario);}
  void check(boolean v,String why){p.check(v,why);}
  RegionalWeatherJournal.State checkpoint(){
   var q=new StoreQuery(p.world,Set.of(),new Sequence(0),Optional.empty(),128);var read=p.store.view(q);ReadContracts.validate(read,q,p.store.session());
   check(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"complete actual runtime checkpoint");
   return read.page().orElseThrow().records().stream().filter(r->r instanceof DurableFact d&&d.value().factType().equals(RegionalWeatherJournal.TYPE))
    .map(r->RegionalWeatherJournal.state(((DurableFact)r).value())).max(Comparator.comparingLong(RegionalWeatherJournal.State::ordinal)).orElseThrow();
  }
  WeatherCoreRegionalWitness nativeState(){return WeatherCoreIntegration.createRegionalBinding(d->p.level,()->new OwnershipView(Map.of()),k->new Revision(1),4).observedState(p.dim,request.event());}
  void createRuntime(){
   sharedArbiter=new ResourceArbiter(p.world,new Epoch(p.store.session().bootEpoch().value()*100),1000,4,new ResourceArbiter.Compatibility(){
    public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   var guard=new fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.AdmissionGuard(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public Set<CapabilityId> qualifiedCapabilities(){return approved?Set.of(WeatherCoreRegionalBinding.CAPABILITY):Set.of();}
    public Set<CapabilityId> cleanupCapabilities(){return Set.of(WeatherCoreRegionalBinding.CAPABILITY);}
    public boolean mayStart(RegionalWeatherJournal.Request r){return approved;}
    public boolean mayApply(RegionalWeatherJournal.Request r){if(scenario==Scenario.RUNTIME_LOSS_SAVE)fr.proutlost.director.persistence.NativeRegionalWriterLossFixture.armAfterDurablePrepare(p.store,r.operation());return approved;}
    public boolean mayResume(RegionalWeatherJournal.Request r){return approved;}
    public boolean available(RegionalWeatherJournal.Request r){return r.footprint().loadedChunks().stream().allMatch(c->p.level.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
    public Revision regionalRevision(RegionalWeatherJournal.Request r){return new Revision(1);}
    public PlanningSnapshot capture(RegionalWeatherJournal.Request r,OwnershipView owned){if(scenario==Scenario.RUNTIME_LOSS_COLD&&!recoveryOnlyVerified)throw new fr.proutlost.director.bootstrap.SelectedEventStart.ContextUnavailable("BOOTSTRAP_HAS_NO_APPLY_CONTEXT");return new PlanningSnapshot(p.snapshot(),owned,new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));}
    public Optional<fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Reservation> reservation(RegionalWeatherJournal.Request r,PreparedEffect plan,boolean cleanup){
     if(!cleanup&&!approved)return Optional.empty();var scope=new ScopeRef(ScopeKind.WORLD,p.world.value());var now=clock();
     var c=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),r.event(),new ResourceArbiter.Rank(cleanup,false,false,Priority.AMBIENT,0,1,42),now.plus(900),plan.claims(),Map.of(scope,plan.budgetEstimate()));
     var epochs=new HashMap<ResourceKey,Epoch>();plan.claims().forEach(claim->epochs.put(claim.resource(),claim.expectedEpoch()));
     return sharedArbiter.arbitrate(List.of(c),now,epochs,Map.of(scope,CAP)).getFirst().accepted().map(t->new fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Reservation(sharedArbiter,t));
    }
    public Tick clock(){return p.now();}
    public boolean acquireMetadataLane(Object owner){if(lane==null||lane==owner){lane=owner;return true;}return false;}
    public void releaseMetadataLane(Object owner){check(lane==owner,"exact runtime lane owner");lane=null;}
   };
   runtime=new fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime(p.level.getServer(),p.world,p.store,guard,4);
  }
  void step(){
   switch(stage){
    case 0->{
     if(!p.opening.isDone())return;p.store=p.opening.getNow(null);check(!p.store.readOnlySafe(),"runtime actual J04");
     if((scenario==Scenario.RUNTIME_COLD||scenario==Scenario.RUNTIME_RECOVER||scenario==Scenario.RUNTIME_LOSS_COLD)){
      request=checkpoint().request();p.footprint=request.footprint();var actual=nativeState();
      check(!actual.renderArmed()&&actual.state().kind()==WeatherCoreRegionalState.Kind.ACTIVE,"runtime cold native lease must start disarmed");
      oldUuid=actual.state().weather();oldDeadline=actual.state().deadline();if(scenario==Scenario.RUNTIME_LOSS_COLD)approved=false;createRuntime();stage=2;return;
     }
     var at=p.h.absolutePos(BlockPos.ZERO);int x=(at.getX()>>4)*16+8,z=(at.getZ()>>4)*16+8;
     p.footprint=new Footprint(p.dim,Set.of(new ChunkRef(p.dim,x>>4,z>>4)),Set.of(),List.of(new Aabb(p.dim,x-4,p.level.getMinBuildHeight(),z-4,x+4,p.level.getMaxBuildHeight(),z+4)));
     request=new RegionalWeatherJournal.Request(new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),p.footprint,new DurationTicks(70000),BlobContent.hash(new byte[]{0}));
     var w=new DirectorWorldState(new AggregateHeader(1,p.world.value(),new Revision(1),new Sequence(0),p.now()),p.world,p.store.session().storeGeneration(),p.now(),RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,PacingState.REST,GlobalTension.CALM,new ResourceId("proutlost:qa_regional_runtime"),BlobContent.hash(new byte[]{1}),BlobContent.hash(new byte[]{2}),p.store.session().bootEpoch(),new Sequence(0),new EngineControlState(1,new Revision(0),p.now(),p.now(),new UnitValue(0),p.now(),Optional.empty(),new UnitValue(.5)));
     initial=p.append(List.of(new DurableAggregate(new WorldAggregate(w))));stage=1;
    }
    case 1->{if(!initial.isDone())return;check(initial.getNow(null).ackStatus()==AckStatus.DURABLE,"runtime WORLD seed ACK");createRuntime();stage=2;}
    case 2->{if(scenario==Scenario.RUNTIME_LOSS_COLD)runtime.pollRecoveryOnly();else runtime.poll();if(runtime.recovering())return;check(!runtime.diagnostic().contains("Exception"),"runtime recovery failure");
     if((scenario!=Scenario.RUNTIME_COLD&&scenario!=Scenario.RUNTIME_RECOVER&&scenario!=Scenario.RUNTIME_LOSS_COLD))runtime.submit(request);stage=3;}
    case 3->{
     if(scenario==Scenario.RUNTIME_LOSS_COLD&&!recoveryOnlyVerified){
      runtime.pollRecoveryOnly();
      var read=p.store.view(new StoreQuery(p.world,Set.of(),new Sequence(0),Optional.empty(),128));
      if(read.status()!=ReadStatus.AVAILABLE||read.page().orElseThrow().records().stream().noneMatch(row->row instanceof DurableResult result&&result.value().operationToken().equals(request.operation())&&result.value().result().outcome()==Outcome.DELIVERY_UNCERTAIN)||runtime.metadataBusy())return;
      var r=runtime.receipt(request.event()).orElseThrow();var actual=nativeState();
      check(r.forwardInvocationsThisBoot()==0&&r.rearmInvocationsThisBoot()==0&&!actual.renderArmed()&&actual.state().weather().equals(oldUuid)&&actual.state().deadline()==oldDeadline&&!checkpoint().completed()&&checkpoint().uncertain(),"metadata-only recovery applied/rearmed/renewed or falsely released uncertain native ownership");
      recoveryOnlyVerified=true;return;
     }
     runtime.poll();var receipt=runtime.receipt(request.event());if(receipt.isEmpty())return;var r=receipt.orElseThrow();
     if(scenario==Scenario.RUNTIME_LOSS_SAVE&&r.stage()==fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.RECOVERY_REQUIRED){
      check(fr.proutlost.director.persistence.NativeRegionalWriterLossFixture.injected()&&r.forwardInvocationsThisBoot()==1&&r.uncertainHistoricalOutcome()&&nativeState().renderArmed(),"actual post-effect WAL loss did not preserve uncertainty/owned native state");
      runtime.close();closing=p.store.closeAsync().toCompletableFuture();stage=5;return;
     }
     if(scenario==Scenario.RUNTIME_LOSS_COLD){
      check(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.RECOVERY_REQUIRED,"cold writer-loss safe cleanup failed "+r.diagnostic());
      if(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.COMPLETE||runtime.metadataBusy())return;
      check(r.forwardInvocationsThisBoot()==0&&r.rearmInvocationsThisBoot()==0&&r.uncertainHistoricalOutcome()&&nativeState().state().kind()==WeatherCoreRegionalState.Kind.ABSENT&&checkpoint().completed()&&checkpoint().uncertain(),"cold unknown outcome was replayed, forgotten, or not safely closed");
      var read=p.store.view(new StoreQuery(p.world,Set.of(),new Sequence(0),Optional.empty(),128));
      check(read.status()==ReadStatus.AVAILABLE&&read.page().orElseThrow().records().stream().anyMatch(row->row instanceof DurableResult result&&result.value().operationToken().equals(request.operation())&&result.value().result().outcome()==Outcome.DELIVERY_UNCERTAIN),"missing historical RESULT must remain DELIVERY_UNCERTAIN");
      runtime.close();closing=p.store.closeAsync().toCompletableFuture();stage=5;return;
     }
     check(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.RECOVERY_REQUIRED,"runtime failed safe "+r.diagnostic());
     if(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.ACTIVE||runtime.metadataBusy())return;
     var actual=nativeState();check(actual.renderArmed(),"coordinator actual armed state");
     if((scenario==Scenario.RUNTIME_COLD||scenario==Scenario.RUNTIME_RECOVER||scenario==Scenario.RUNTIME_LOSS_COLD)){
      check(r.forwardInvocationsThisBoot()==0&&r.rearmInvocationsThisBoot()==1&&actual.state().weather().equals(oldUuid)&&actual.state().deadline()==oldDeadline,"coordinator cold replay or renewed native deadline");
      runtime.stop(request.event());stage=4;
     }else{
      check(r.forwardInvocationsThisBoot()==1&&r.rearmInvocationsThisBoot()==0,"fresh coordinator invocation counts");
      if(scenario==Scenario.RUNTIME_REVOKE){approved=false;stage=4;}else{runtime.close();closing=p.store.closeAsync().toCompletableFuture();stage=5;}
     }
    }
    case 4->{
     runtime.poll();var r=runtime.receipt(request.event()).orElseThrow();
     check(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.RECOVERY_REQUIRED,"coordinator cleanup failed "+r.diagnostic());
     if(r.stage()!=fr.proutlost.director.bootstrap.DirectorRegionalWeatherRuntime.Stage.COMPLETE||runtime.metadataBusy())return;
     check(nativeState().state().kind()==WeatherCoreRegionalState.Kind.ABSENT&&checkpoint().completed(),"native cleanup and actual J04 completion");
     if(scenario==Scenario.RUNTIME_REVOKE)check(r.rearmInvocationsThisBoot()==0,"revoked approval rearmed renderer");
     runtime.close();closing=p.store.closeAsync().toCompletableFuture();stage=5;
    }
    case 5->{if(!closing.isDone())return;check(!closing.isCompletedExceptionally()&&p.globalsBefore.equals(p.globals()),"runtime close/global preservation");
     done=true;System.out.println("DIRECTOR_NATIVE_REGIONAL_"+scenario+"_J04_CORE_PASS world="+p.world.value()+" event="+request.event().value()+" production=false clients=false");}
    default->throw new IllegalStateException("runtime QA stage");
   }
  }
 }

 private static void run(GameTestHelper h,Scenario s){
  if(s.name().startsWith("RUNTIME_")){var p=new RuntimeProbe(h,s);h.succeedWhen(()->{if(!p.done)p.step();h.assertTrue(p.done,"regional coordinator real J04 pending");});return;}
  var p=new Probe(h,s);h.succeedWhen(()->{if(!p.done)p.step();h.assertTrue(p.done,"regional real J04/native operation pending");});
 }
 @GameTestGenerator public static Collection<TestFunction> regionalQualification(){
  if(!Boolean.getBoolean("proutlost.director.nativeRegionalQualification"))return List.of();
  return Arrays.stream(Scenario.values()).map(s->new TestFunction("p002_regional","proutlost_director.p002_regional_"+s.name().toLowerCase()+(s.name().startsWith("RUNTIME_LOSS_")?"_a04":s.name().startsWith("RUNTIME_")?"_a02":"_a01"),"proutlost_director:smoke_empty",net.minecraft.world.level.block.Rotation.NONE,2400,0,true,h->run(h,s))).toList();
 }
}
