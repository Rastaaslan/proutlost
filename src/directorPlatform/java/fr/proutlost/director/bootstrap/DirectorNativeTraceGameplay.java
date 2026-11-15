package fr.proutlost.director.bootstrap;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;

/** Explicit primitive LAB lane, not the unchanged required_signal catalogue event. */
public final class DirectorNativeTraceGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 public record TraceStatus(UUID eventId,String phase,String outcome,Optional<AnchorRecord> before,
   Optional<AnchorRecord> after,long resultSequence){}
 private static final TagId CAMP=new TagId("proutlost:camp"),LAB=new TagId("proutlost_director:lab_traced");
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,4096,100,4096,100);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final DirectorNativeAnchorOwner owner;private final LongSupplier floor;private final LongConsumer generation;
 private final ArrayDeque<PrepareRecord> recovery=new ArrayDeque<>();
 private CompletableFuture<CommitAck> recoveryAck;private Flight flight;private boolean closed,failed;
 private TraceStatus last=new TraceStatus(null,"IDLE","NONE",Optional.empty(),Optional.empty(),0);
 private static final class Flight {
  UUID id;DirectorNativeAnchorOwner.Lease lease;AnchorMutationBinding binding;PreparedEffect prepared;
  SelectedEventStart selected;boolean completeSelected,stopRequested;AdmissionService admission;DecisionId admissionDecision;PrepareRecord completionPrepare;
  EventRunner runner;ResourceArbiter arbiter;ReservationToken token;LiveValidationSnapshot live;
  DurableBatch prepareBatch,resultBatch;CompletableFuture<CommitAck> prepareAck,resultAck,abortAck;
  StoreSession session;String phase="RUNNING";long sequence;Runnable afterApply;Consumer<TraceStatus> onComplete=s->{};
  EventRunner.Stage stage=EventRunner.Stage.PREPARING;DecisionInput applyingInput;StoreSession applyingSession;
  String contextWait="",contextFailure="",abortFailure="";
 }
 public DirectorNativeTraceGameplay(MinecraftServer server,WorldId world,JournalStore store,
   DirectorNativeAnchorOwner owner,List<PrepareRecord> unresolved,LongSupplier floor,LongConsumer generation){
  this.content=null;
  this.server=server;this.world=world;this.store=store;this.owner=owner;this.floor=floor;this.generation=generation;
  recovery.addAll(unresolved);actor();
 }
 public DirectorNativeTraceGameplay(MinecraftServer server,WorldId world,JournalStore store,
   DirectorNativeAnchorOwner owner,List<PrepareRecord> unresolved,LongSupplier floor,LongConsumer generation,DirectorRuntimeContent content){
  this.content=Objects.requireNonNull(content);
  this.server=server;this.world=world;this.store=store;this.owner=owner;this.floor=floor;this.generation=generation;
  recovery.addAll(unresolved);actor();
 }
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("Trace LAB actor");}
 private Tick clock(){
  // The current recovered observation clock is sampled independently of snapshot cadence.
  return new Tick(DirectorStandaloneMod.directorLifecycle().runtimeClock(server));
 }
 public boolean sharedChoiceReady(){actor();return ready();}
 public boolean selectedAvailable(){actor();return ready();}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 public DirectorNativeCatalogueExecution.Preview preview(fr.proutlost.director.api.model.Candidate candidate,DecisionInput input,EventInstanceId event){
  actor();if(!ready())throw new IllegalStateException("trace mutation lane busy");
  var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(definition.steps().size()!=1||!(definition.steps().getFirst().args() instanceof Trace args))
   throw new IllegalArgumentException("one actual Trace catalogue step required");
  var anchor=NativeCataloguePlanning.anchor(candidate,input,args.anchorTag());
  var intent=NativeCataloguePlanning.intent(candidate,definition,definition.steps().getFirst(),event,NativeCataloguePlanning.point(anchor.position()),input);
  var binding=new TraceBinding(server,owner,new DirectorNativeAnchorOwner.Lease(anchor,intent.operationToken()),this::clock);
  return NativeCataloguePlanning.captured(candidate,input,event,List.of(binding.preview(intent,NativeCataloguePlanning.raw(input))),Set.of());
 }
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();if(!ready())throw new IllegalStateException("trace mutation lane busy");
  var current=selected.refresh();var definition=selected.definition();
  if(!definition.id().equals(new DefinitionId("proutlost_director:candidate/required_signal"))||definition.steps().size()!=1||
     !(definition.steps().getFirst().args() instanceof Trace args))throw new IllegalArgumentException("original required_signal required");
  var intent=selected.plan().effects().getFirst();
  var anchors=current.context().context().anchors().values().stream().filter(a->a.active()&&a.tags().contains(args.anchorTag())&&
   NativeCataloguePlanning.point(a.position()).equals(intent.expectedFootprint())).toList();
  if(anchors.size()!=1)throw new IllegalStateException("selected actual signal anchor changed");
  startMutation(anchors.getFirst().anchorId(),intent.primitive(),intent.typedArgs(),selected.eventId().value(),
   lease->new TraceBinding(server,owner,lease,this::clock),()->{},()->{},selected,intent.operationToken(),args.anchorTag(),true);
  return "Catalogue required_signal prepare: "+selected.eventId().value();
 }
 public UUID resolveSharedChoiceSelected(SelectedEventStart selected,AnchorId id,PlayerId player,PerceptionHeader receipt,
   DirectorFact decision,PrepareRecord originalOfferPrepare,Consumer<TraceStatus> completion){requireSelected(selected);
  actor();if(!ready())return null;
  var original=selected.plan().effects().getFirst();var args=(SharedChoice)original.typedArgs();
  var operation=DirectorNativeCatalogueExecution.operation(selected.eventId(),"choice-resolution");
  var started=startMutation(id,original.primitive(),args,selected.eventId().value(),
   lease->new SharedChoiceResolutionBinding(server,owner,lease,this::clock,player,receipt,decision,args,new TagId("proutlost:clearing_choice"),false),
   ()->{},()->{},selected,operation,args.anchorTag(),false);
  flight.onComplete=Objects.requireNonNull(completion);flight.completionPrepare=Objects.requireNonNull(originalOfferPrepare);return started;
 }
 private boolean ready(){return !failed&&flight==null&&recovery.isEmpty()&&recoveryAck==null&&
  DirectorStandaloneMod.directorLifecycle().runtimeAnchorWritesQuiescent(server)&&!store.readOnlySafe();}
 public String begin(ServerPlayer player){requireLaboratory();
  actor();if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)
   return "Vise un feu de camp observe a moins de six blocs.";
  var p=hit.getBlockPos();var point=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),p.getX(),p.getY(),p.getZ());
  var anchor=owner.at(point).filter(AnchorRecord::active).orElse(null);
  if(anchor==null)return "Aucune ancre de camp observee a cet emplacement.";
  if(!ready())return "Trace attend la fin des ecritures d'ancres.";
  if(anchor.tags().contains(LAB))return "COMPLETE_FROM_STATE : trace deja durable sur "+anchor.anchorId().value();
  var id=start(anchor.anchorId(),LAB,()->{},()->{});
  return "Trace LAB preparee : "+id+" ; aucune requete required_signal creee.";
 }
 public UUID labBegin(AnchorId id,Runnable afterCapture,Runnable afterApply){requireLaboratory();
  actor();if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))throw new IllegalStateException("native Trace fixture disabled");
  if(!ready())return null;
  return start(id,new TagId("proutlost_director:test_trace_"+UUID.randomUUID()),afterCapture,afterApply);
 }
 /** Same durable actor lane; only a genuinely accepted command supplies decision/receipt. */
 public UUID resolveSharedChoice(UUID event,AnchorId id,PlayerId player,PerceptionHeader receipt,DirectorFact decision,Consumer<TraceStatus> completion){requireLaboratory();
  actor();if(!ready())return null;
  var started=startMutation(id,SharedChoiceOfferBinding.ID,SharedChoiceOfferBinding.LAB_ARGS,event,
   lease->new SharedChoiceResolutionBinding(server,owner,lease,this::clock,player,receipt,decision),()->{},()->{});
  flight.onComplete=Objects.requireNonNull(completion);return started;
 }
 /** Explicit native fixture lane: it never creates an ACK, authenticated command or human vote. */
 public UUID labBeginAnchorMutation(AnchorId id,PrimitiveId primitive,PrimitiveArgs args,UUID event,
   Function<DirectorNativeAnchorOwner.Lease,AnchorMutationBinding> factory,Runnable afterCapture,Runnable afterApply){requireLaboratory();
  actor();if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))throw new IllegalStateException("native anchor mutation fixture disabled");
  if(!ready())return null;
  return startMutation(id,primitive,args,event,factory,afterCapture,afterApply);
 }
 private UUID start(AnchorId id,TagId tag,Runnable afterCapture,Runnable afterApply){
  return startMutation(id,TraceBinding.ID,new Trace(CAMP,tag,true),UUID.randomUUID(),
   lease->new TraceBinding(server,owner,lease,this::clock),afterCapture,afterApply);
 }
 private UUID startMutation(AnchorId id,PrimitiveId primitive,PrimitiveArgs args,UUID event,
   Function<DirectorNativeAnchorOwner.Lease,AnchorMutationBinding> factory,Runnable afterCapture,Runnable afterApply){
  return startMutation(id,primitive,args,event,factory,afterCapture,afterApply,null,new OperationToken(UUID.randomUUID()),CAMP,false);
 }
 private UUID startMutation(AnchorId id,PrimitiveId primitive,PrimitiveArgs args,UUID event,
   Function<DirectorNativeAnchorOwner.Lease,AnchorMutationBinding> factory,Runnable afterCapture,Runnable afterApply,
   SelectedEventStart selected,OperationToken operation,TagId requiredTag,boolean completeSelected){
  Flight f=new Flight();f.id=event;f.session=store.session();f.afterApply=afterApply;f.selected=selected;f.completeSelected=completeSelected;
  var current=selected==null?null:selected.refresh();f.lease=owner.acquireTrace(id,operation,requiredTag);
  f.applyingInput=current;f.applyingSession=f.session;
  try{
   var anchor=f.lease.before();var p=anchor.position();
   var footprint=new Footprint(anchor.dimension(),Set.of(new ChunkRef(anchor.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of());
   var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
   var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),clock(),Map.of(),
    context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   var intent=new EffectIntent(operation,primitive,1,args,footprint,new EventInstanceId(f.id));
   f.binding=factory.apply(f.lease);
   if(selected!=null){
    sample=current.context();
    if(completeSelected&&!intent.equals(selected.plan().effects().getFirst()))throw new IllegalArgumentException("selected trace identity changed");
   }
   var planning=selected==null?new PlanningSnapshot(sample,new OwnershipView(Map.of()),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of())):NativeCataloguePlanning.raw(current);
   var capture=f.binding.capture(intent,planning);
   f.prepared=capture.prepared().orElseThrow();
   f.arbiter=new ResourceArbiter(world,new Epoch(Math.max(floor.getAsLong(),store.session().bootEpoch().value())),1000,4,
    new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}
     public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   var scope=new ScopeRef(ScopeKind.WORLD,world.value());var key=TraceBinding.key(anchor);
   var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),intent.instanceId(),
    new ResourceArbiter.Rank(false,false,false,args instanceof Trace?Priority.REQUIRE:Priority.AMBIENT,0,1,42),clock().plus(1200),f.prepared.claims(),Map.of(scope,BudgetMath.zero()));
   if(selected==null)f.token=f.arbiter.arbitrate(List.of(candidate),clock(),Map.of(key,new Epoch(anchor.header().revision().value())),Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
   else{
    var held=SelectedEventAdmission.reserve(selected,f.prepared,f.arbiter,this::clock,new DurationTicks(1200),CAP,
     ()->selectedLive(f)&&owner.unchanged(f.lease));
    f.admission=held.service();f.admissionDecision=held.decision();f.token=held.token();
   }
   generation.accept(f.token.generation().value());
   f.live=new LiveValidationSnapshot(f.prepared.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,clock(),f.token.generation());
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),operation,footprint,f.prepared.preconditions(),List.of(intent),f.prepared.budgetEstimate());
   var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),operation,f.token.generation(),f.prepared.preconditions(),
    f.token.expiryTick(),slice,f.token,capture.validation(),selected!=null&&completeSelected?selected.reservation(f.token,clock()):List.of(),Set.of(),List.of(),f.prepared.durableBlobs());
   f.prepareBatch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),
    List.of(new DurablePrepare(prepare)),capture.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
   DurableStore tracked=tracking(f);
   var authority=new CommitPermit.Authority(tracked,4);
   ServerEffectPort.Binding binding=wrapped(f.binding,f.afterApply,()->selectedLive(f));
   var effects=new ServerEffectPort(authority,i->{throw new IllegalStateException("captured");},s->f.live,
    Map.of(primitive,binding),binding.capabilities(),4,
    s->new StabilizationLiveSnapshot(store.session(),f.live,new OwnershipView(Map.of())),m->{},(m,r)->{});
   effects.hydrate(f.prepared,capture.blobs());
   f.runner=new EventRunner(authority,tracked,content==null?effects:content.attach(f.prepared,capture.blobs(),effects,f.selected!=null),f.arbiter,4);
   afterCapture.run();
   f.runner.submit(f.prepareBatch,f.prepared.expectedPost(),f.live,result->{
    var records=new ArrayList<DurableRecord>();
    records.add(new DurableAggregate(new AnchorAggregate(f.binding.after())));
    records.add(new DurableResult(new ResultRecord(prepare.transactionId(),operation,result,Set.of(),List.of(),List.of())));
    if(args instanceof Trace trace){
     records.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),
      new FactType("proutlost_director:lab_trace_applied"),List.of(scope),Optional.of(intent.instanceId()),
      EvidenceStatus.CONFIRMED,Map.of("anchor",new JsonValue.Text(anchor.anchorId().value().toString()),
       "tag",new JsonValue.Text(trace.stateTag().value())),true,Optional.empty())));
    }
    for(var fact:f.binding.resultFacts())records.add(new DurableFact(fact));
    var blobs=new ArrayList<BlobContent>();
    if(selected!=null&&completeSelected){
     var effect=selected.effectContribution(prepare,result,clock());records.addAll(effect.records());blobs.addAll(effect.blobs());
     var evidence=Map.<String,JsonValue>of("anchor",new JsonValue.Text(anchor.anchorId().value().toString()),
      "state_tag",new JsonValue.Text(((Trace)args).stateTag().value()),"actual_anchor_post",new JsonValue.Text(TraceBinding.state(f.binding.after()).reference().hash().value()));
     var completion=selected.completionContribution(prepare,evidence,clock());records.addAll(completion.records());blobs.addAll(completion.blobs());
    }
    if(selected!=null&&!completeSelected&&args instanceof SharedChoice&&f.completionPrepare!=null){
     var evidence=new HashMap<String,JsonValue>();
     evidence.put("anchor",new JsonValue.Text(anchor.anchorId().value().toString()));
     evidence.put("actual_anchor_post",new JsonValue.Text(TraceBinding.state(f.binding.after()).reference().hash().value()));
     f.binding.resultFacts().stream().filter(fact->fact.factType().equals(SharedChoiceResolutionBinding.SELECTED)).findFirst()
      .ifPresent(fact->evidence.putAll(fact.payload()));
     var completion=selected.completionContribution(f.completionPrepare,evidence,clock());
     records.addAll(completion.records());blobs.addAll(completion.blobs());
    }
    f.resultBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,blobs);
    return f.resultBatch;
   });
   flight=f;last=status(f,"RUNNING","PENDING");
   System.out.println("DIRECTOR_LAB_TRACE_PREPARE_SUBMITTED event="+f.id+" anchor="+id.value());
   return f.id;
  }catch(RuntimeException rejected){
   if(f.binding==null||!f.binding.wasApplied())owner.releaseNotApplied(f.lease);
   if(f.arbiter!=null&&f.token!=null)f.arbiter.release(f.token);
   throw rejected;
  }finally{f.applyingInput=null;f.applyingSession=null;}
 }
 private DurableStore tracking(Flight f){
  return new DurableStore(){
   public CompletionStage<CommitAck> append(DurableBatch batch){
    var ack=store.append(batch);
    if(batch.transactionId().equals(f.prepareBatch.transactionId()))f.prepareAck=ack.toCompletableFuture();
    else if(f.resultBatch!=null&&batch.transactionId().equals(f.resultBatch.transactionId()))f.resultAck=ack.toCompletableFuture();
    return ack;
   }
   public StoreReadResult view(StoreQuery q){return store.view(q);}
   public CompletionStage<StoreReadResult> read(StoreQuery q){return store.read(q);}
   public StoreSession session(){return store.session();}
   public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest q){return store.readBlobs(q);}
  };
 }
 private boolean selectedLive(Flight f){
  if(f.stopRequested)return false;
  if(f.selected==null)return true;
  // This frame is pinned only during this actor's admission or runner.poll call.
  return f.applyingInput!=null&&f.applyingSession!=null&&f.applyingSession.equals(store.session())
   &&f.applyingInput.context().scopeRevisions().equals(f.prepared.preconditions().revisions());
 }
 private ServerEffectPort.Binding wrapped(AnchorMutationBinding delegate,Runnable hook,BooleanSupplier live){
  return new ServerEffectPort.Binding(){
   public ProviderMode mode(){return delegate.mode();}
   public Set<PrimitiveId> supportedPrimitives(){return delegate.supportedPrimitives();}
   public Set<CapabilityId> capabilities(){return delegate.capabilities();}
   public EffectPreparation capture(EffectIntent i,PlanningSnapshot p){return delegate.capture(i,p);}
   public boolean revalidate(PreparedEffect p,BlobBundle b){return live.getAsBoolean()&&delegate.revalidate(p,b);}
   public SliceResult apply(PreparedEffect p,BlobBundle b){if(!live.getAsBoolean())throw new IllegalStateException("selected context revoked before anchor apply");var result=delegate.apply(p,b);hook.run();return result;}
   public ReconcileResult reconcile(RecoveryProbe p){return delegate.reconcile(p);}
  };
 }
 private boolean durable(CompletableFuture<CommitAck> future){
  if(future==null||!future.isDone())return false;
  if(future.isCompletedExceptionally())return false;
  var ack=future.getNow(null);
  return ack!=null&&ack.ackStatus()==AckStatus.DURABLE&&ack.storeGeneration().equals(store.session().storeGeneration());
 }
 private TraceStatus status(Flight f,String phase,String outcome){
  return new TraceStatus(f.id,phase,outcome,Optional.of(f.lease.before()),Optional.ofNullable(f.binding==null?null:f.binding.after()),f.sequence);
 }
 private void complete(Flight f,String outcome){
  f.arbiter.release(f.token);last=status(f,"IDLE",outcome);f.phase="TERMINAL";flight=null;f.onComplete.accept(last);
  System.out.println("DIRECTOR_LAB_TRACE_"+outcome+" event="+f.id+" anchor="+f.lease.before().anchorId().value()
   +(outcome.equals("ABORTED_SAFE")?" stage="+f.stage+" failure="+f.abortFailure:""));
 }
 public boolean controlRecoveryReady(){actor();return !failed&&recovery.isEmpty()&&recoveryAck==null&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stopRequested=true;
  return Optional.of(()->{actor();return f.phase.equals("TERMINAL")&&f.sequence>0?AdminStatus.SUCCEEDED:failed||(f.phase.equals("TERMINAL")&&f.sequence==0)?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("Trace WAL unavailable");
   if(!recovery.isEmpty()){
    if(recoveryAck==null){
     var p=recovery.getFirst();var uncertain=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
     recoveryAck=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
      List.of(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),uncertain,Set.of(),List.of(),List.of()))),List.of())).toCompletableFuture();
    }else if(durable(recoveryAck)){recovery.removeFirst();recoveryAck=null;System.out.println("DIRECTOR_LAB_TRACE_RECOVERY_NO_REPLAY");}
    else if(recoveryAck.isDone())throw new IllegalStateException("Trace recovery ACK failed");
    return;
   }
   var f=flight;if(f==null)return;
   if(!f.session.equals(store.session()))throw new IllegalStateException("Trace session revoked");
   if(f.phase.equals("RUNNING")){
    try{
     if(!f.stopRequested&&f.selected!=null&&f.stage==EventRunner.Stage.PREPARING&&!f.binding.wasApplied()&&clock().compareTo(f.token.expiryTick())<0){
      var session=store.session();
      try{
       f.applyingInput=f.selected.refresh();f.applyingSession=session;f.contextWait="";f.contextFailure="";
      }catch(SelectedEventStart.ContextUnavailable unavailable){
       var detail=unavailable.toString();
       if(!detail.equals(f.contextWait)){f.contextWait=detail;System.out.println("DIRECTOR_LAB_TRACE_WAITING_FRESH_CONTEXT event="+f.id+" detail="+detail);}
       last=status(f,"WAITING_FRESH_CONTEXT","NO_APPLY");return; // No authority poll or permit consumption.
      }catch(RuntimeException changed){
       // A genuinely changed selected context still takes the existing false-guard ABORTED_SAFE path.
       f.contextFailure=changed.toString();
      }
      if(!session.equals(store.session()))throw new IllegalStateException("Trace session revoked during context capture");
     }
     // Expired authority is rejected normally; RESULT_PENDING drains without another context read.
     f.live=new LiveValidationSnapshot(f.live.readSet(),f.live.loadedChunks(),f.live.health(),clock(),f.token.generation());
     var progress=f.runner.poll(f.prepareBatch.transactionId(),f.live);f.stage=progress.stage();
     if(progress.stage()==EventRunner.Stage.COMMITTED){
      if(!durable(f.resultAck))throw new IllegalStateException("Trace exact RESULT ACK absent");
      f.sequence=f.resultAck.getNow(null).durableSequence().value();f.phase="PROJECTING";
     }else if(progress.stage()==EventRunner.Stage.REJECTED||progress.stage()==EventRunner.Stage.RECOVERY_REQUIRED){
      f.abortFailure=progress.failure().orElse("NONE")+(f.contextFailure.isEmpty()?"":" selected_context="+f.contextFailure);
      if(f.binding.wasApplied())throw new IllegalStateException("Trace applied result requires recovery: "+progress.failure());
      if(durable(f.prepareAck)){
       var result=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
       var p=((DurablePrepare)f.prepareBatch.records().getFirst()).value();
       var aborted=new ArrayList<DurableRecord>();aborted.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),result,Set.of(),List.of(),List.of())));
       var abortBlobs=new ArrayList<BlobContent>();
       if(f.selected!=null&&f.completeSelected){
        aborted.addAll(f.selected.abortRecords("TRACE_ABORTED_SAFE",
         Map.of("no_physical_effect",new JsonValue.Bool(true),"anchor",new JsonValue.Text(f.lease.before().anchorId().value().toString()))));
        var ledger=f.selected.abortContribution(p,result,clock());aborted.addAll(ledger.records());abortBlobs.addAll(ledger.blobs());
       }
       f.abortAck=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),aborted,abortBlobs)).toCompletableFuture();
       f.phase="ABORT_PENDING";
      }else{owner.releaseNotApplied(f.lease);complete(f,"ABORTED_SAFE");return;}
     }
    }finally{f.applyingInput=null;f.applyingSession=null;}
   }
   if(f.phase.equals("PROJECTING")){
    var life=DirectorStandaloneMod.directorLifecycle();var view=life.memoryProjectionView(server);
    if(view.isPresent()&&view.get().complete()&&!view.get().failClosed()&&view.get().lastDurableSequence()>=f.sequence){
     var projected=life.runtimeMemoryView(server).map(m->m.anchors().get(f.lease.before().anchorId())).orElse(null);
     if(!f.binding.after().equals(projected))throw new IllegalStateException("Trace durable anchor readback mismatch");
     owner.releaseDurable(f.lease,projected);complete(f,"COMMITTED");return;
    }
   }
   if(f.phase.equals("ABORT_PENDING")){
    if(durable(f.abortAck)){f.sequence=f.abortAck.getNow(null).durableSequence().value();owner.releaseNotApplied(f.lease);complete(f,"ABORTED_SAFE");return;}
    if(f.abortAck.isDone())throw new IllegalStateException("Trace abort ACK failed");
   }
   last=status(f,f.phase,"PENDING");
  }catch(RuntimeException failure){failed=true;last=flight==null?last:status(flight,"FAILED_SAFE","RECOVERY_REQUIRED");
   if(flight!=null)flight.onComplete.accept(last);
   System.out.println("DIRECTOR_LAB_TRACE_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 public TraceStatus status(){actor();return last;}
 public DirectorNativeAnchorOwner owner(){actor();return owner;}
 public void close(){actor();closed=true;}
}
