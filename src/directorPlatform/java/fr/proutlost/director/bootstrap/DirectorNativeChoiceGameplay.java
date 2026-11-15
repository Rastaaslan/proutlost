package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;

/** Explicit manual primitive LAB. It does not claim the packaged GROUP/clearing event ran. */
public final class DirectorNativeChoiceGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final FactType OFFERED=new FactType("proutlost_director:lab_choice_offered");
 private static final FactType CLOSED=new FactType("proutlost_director:lab_choice_closed");
 private static final int TTL=600;
 private static final int PENDING_INPUT_TICKS=180; // Original offer TTL is never extended.
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,4096,100,4096,100);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final LongSupplier floor;private final LongConsumer generation;
 private DirectorNativeAnchorOwner owner;private DirectorNativeTraceGameplay mutations;
 private final Map<OperationToken,PrepareRecord> unmatchedOffers=new LinkedHashMap<>();
 private final Set<UUID> openEvents=new LinkedHashSet<>(),closedEvents=new HashSet<>();
 private final Map<UUID,DirectorFact> selected=new HashMap<>();
 private boolean scanned,failed,closed;
 private DurableBatch recoveryBatch;private CompletableFuture<CommitAck> recoveryAck;private UUID recoveringEvent;
 private Flight flight;
 private record PendingInput(PlayerId player,PerceptionHeader header,ResourceId action,DirectorFact decision,
   StoreSession session,AnchorRecord anchor,long deadline){}
 private static final class Flight {
  UUID id;AnchorRecord anchor;SharedChoiceOfferBinding binding;PreparedEffect prepared;
  SelectedEventStart selected;SharedChoice args=SharedChoiceOfferBinding.LAB_ARGS;int ttl=TTL;AdmissionService admission;DecisionId admissionDecision;
  EventRunner runner;ResourceArbiter arbiter;ReservationToken token;LiveValidationSnapshot live;
  StoreSession session;DurableBatch prepareBatch,resultBatch,closeBatch;
  CompletableFuture<CommitAck> prepareAck,resultAck,closeAck;
  long expires,receiptDeadline;String phase="OFFERING";DirectorFact decision;
  DirectorNativeTraceGameplay.TraceStatus mutation;PendingInput pending;
  EventRunner.Stage stage=EventRunner.Stage.PREPARING;DecisionInput applyingInput;StoreSession applyingSession;
  String contextWait="",contextFailure="";boolean stopRequested;
 }
 public DirectorNativeChoiceGameplay(MinecraftServer server,WorldId world,JournalStore store,LongSupplier floor,LongConsumer generation){
  this.content=null;
  this.server=server;this.world=world;this.store=store;this.floor=floor;this.generation=generation;actor();
 }
 public DirectorNativeChoiceGameplay(MinecraftServer server,WorldId world,JournalStore store,LongSupplier floor,LongConsumer generation,DirectorRuntimeContent content){
  this.content=Objects.requireNonNull(content);
  this.server=server;this.world=world;this.store=store;this.floor=floor;this.generation=generation;actor();
 }
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("choice LAB actor");}
 private Tick clock(){return new Tick(DirectorStandaloneMod.directorLifecycle().runtimeClock(server));}
 public void bind(DirectorNativeAnchorOwner value,DirectorNativeTraceGameplay lane){
  actor();if(owner!=null&&(owner!=value||mutations!=lane))throw new IllegalStateException("choice owner/lane changed");
  owner=value;mutations=lane;
 }
 public void resetRecoveryScan(){
  actor();if(flight!=null)throw new IllegalStateException("choice active during recovery scan");
  unmatchedOffers.clear();openEvents.clear();closedEvents.clear();selected.clear();scanned=false;
  recoveryBatch=null;recoveryAck=null;recoveringEvent=null;
 }
 public void acceptRecovery(DurableRecord record){
  actor();if(scanned)throw new IllegalStateException("choice recovery already sealed");
  if(record instanceof DurablePrepare row&&row.value().durableBlobs().preparedPlan().codecId().equals(SharedChoiceOfferBinding.PLAN_CODEC))
   unmatchedOffers.put(row.value().operationToken(),row.value());
  if(record instanceof DurableResult row)unmatchedOffers.remove(row.value().operationToken());
  if(record instanceof DurableFact row&&row.value().eventId().isPresent()){
   var fact=row.value();var event=fact.eventId().orElseThrow().value();
   if(fact.factType().equals(OFFERED))openEvents.add(event);
   else if(fact.factType().equals(CLOSED))closedEvents.add(event);
   else if(fact.factType().equals(SharedChoiceResolutionBinding.SELECTED)){
    var old=selected.putIfAbsent(event,fact);if(old!=null&&!old.equals(fact))throw new IllegalStateException("conflicting durable choice");
   }
  }
 }
 public void finishRecoveryScan(){actor();openEvents.removeAll(closedEvents);scanned=true;}
 public boolean recovering(){actor();return !scanned||recoveryAck!=null||!unmatchedOffers.isEmpty()||!openEvents.isEmpty();}
 private DirectorFact fact(FactType type,UUID event,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
   Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 private DurableBatch appendBatch(List<DurableRecord> rows){
  return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
 }
 private boolean durable(DurableBatch batch,CompletableFuture<CommitAck> future){
  if(future==null||!future.isDone())return false;
  if(future.isCompletedExceptionally())throw new IllegalStateException("choice WAL completion failed");
  var ack=future.getNow(null);
  if(ack==null||ack.ackStatus()!=AckStatus.DURABLE||!ack.transactionId().equals(batch.transactionId())||
     !ack.transactionHash().equals(batch.transactionHash())||!ack.storeGeneration().equals(store.session().storeGeneration()))
   throw new IllegalStateException("choice exact durable ACK unavailable");
  return true;
 }
 public boolean selectedAvailable(){
  actor();return !failed&&!recovering()&&owner!=null&&mutations!=null&&!store.readOnlySafe()&&flight==null&&mutations.sharedChoiceReady();
 }
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 private SharedChoiceOfferBinding selectedBinding(AnchorRecord anchor,List<PlayerId> audience,int ttl,SharedChoice args){
  var p=anchor.position();
  return new SharedChoiceOfferBinding(server,owner,anchor,audience,new PerceptionTransform(p.x()+.5,p.y()+.1,p.z()+.5,0,0,2),
   ttl,this::clock,args,new TagId("proutlost:clearing_choice"),false);
 }
 public DirectorNativeCatalogueExecution.Preview preview(fr.proutlost.director.api.model.Candidate candidate,DecisionInput input,EventInstanceId event){
  actor();if(!selectedAvailable())throw new IllegalStateException("shared choice lane unavailable");
  var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(definition.steps().size()!=1||!(definition.steps().getFirst().args() instanceof SharedChoice args)||
     !args.anchorTag().equals(new TagId("proutlost:clearing")))throw new IllegalArgumentException("original GROUP clearing choice required");
  var participants=NativeCataloguePlanning.players(candidate);
  if(participants.size()<2||participants.size()>6)throw new IllegalArgumentException("actual GROUP witnesses 2..6 required");
  var anchor=NativeCataloguePlanning.anchor(candidate,input,args.anchorTag());
  var ttl=(int)Math.min(TTL,definition.timing().maxDurationTicks().value());
  var binding=selectedBinding(anchor,participants.stream().sorted().toList(),ttl,args);
  var intent=NativeCataloguePlanning.intent(candidate,definition,definition.steps().getFirst(),event,NativeCataloguePlanning.point(anchor.position()),input);
  return NativeCataloguePlanning.captured(candidate,input,event,List.of(binding.capture(intent,NativeCataloguePlanning.raw(input))),participants);
 }
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();if(!selectedAvailable())throw new IllegalStateException("shared choice lane unavailable");
  var current=selected.refresh();var definition=selected.definition();
  if(!definition.id().equals(new DefinitionId("proutlost_director:candidate/shared_choice"))||definition.steps().size()!=1||
     !(definition.steps().getFirst().args() instanceof SharedChoice args)||selected.participants().size()<2||selected.participants().size()>6)
   throw new IllegalArgumentException("original shared_choice GROUP composition required");
  var intent=selected.plan().effects().getFirst();
  var anchors=current.context().context().anchors().values().stream().filter(a->a.active()&&a.tags().contains(args.anchorTag())&&
   NativeCataloguePlanning.point(a.position()).equals(intent.expectedFootprint())).toList();
  if(anchors.size()!=1)throw new IllegalStateException("selected real clearing anchor changed");
  Flight f=new Flight();f.id=selected.eventId().value();f.anchor=anchors.getFirst();f.selected=selected;f.args=args;f.session=store.session();
  f.ttl=(int)Math.min(TTL,definition.timing().maxDurationTicks().value());
  f.binding=selectedBinding(f.anchor,selected.participants().stream().sorted().toList(),f.ttl,args);
  prepareOffer(f,current);flight=f;
  return "Catalogue shared_choice prepared: "+f.id+"; "+selected.participants().size()+" real group witnesses.";
 }
 public String begin(ServerPlayer player){requireLaboratory();
  actor();if(!player.isAlive()||!player.hasPermissions(4))return "Joueur OP4 vivant requis.";
  if(failed||recovering()||owner==null||mutations==null||store.readOnlySafe())return "Choix en attente de recuperation J04/J01.";
  if(flight!=null)return "Un choix LAB est deja actif: "+flight.id;
  if(!DirectorStandaloneMod.directorLifecycle().runtimeAnchorWritesQuiescent(server))return "Ecritures d'ancres encore actives.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return "Vise un vrai feu de camp observe a six blocs.";
  var pos=hit.getBlockPos();var point=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),pos.getX(),pos.getY(),pos.getZ());
  var anchor=owner.at(point).filter(AnchorRecord::active).orElse(null);
  if(anchor==null)return "Aucune ancre de camp observee.";
  if(anchor.tags().contains(SharedChoiceOfferBinding.RESOLVED))return "COMPLETE_FROM_STATE: ce camp possede deja un choix LAB durable.";
  var spec=DirectorGameplayAssets.specs().get(SharedChoiceOfferBinding.SYMBOL);
  if(spec==null)return "Symbole LAB absent du manifeste.";
  var audience=server.getPlayerList().getPlayers().stream().filter(p->p.isAlive()&&p.hasPermissions(4)&&
   p.serverLevel()==player.serverLevel()&&p.distanceToSqr(anchor.position().x()+.5,anchor.position().y(),anchor.position().z()+.5)<=64)
   .map(p->new PlayerId(p.getUUID())).filter(p->DirectorNativeNetworkBootstrap.qualifiedRecipient(server,p,PerceptionKind.SYMBOL,
    SharedChoiceOfferBinding.SYMBOL,spec.hash()).isPresent()).sorted().limit(6).toList();
  if(!audience.contains(new PlayerId(player.getUUID())))return "Ce vrai client n'a pas negocie le symbole.";
  Flight f=new Flight();f.id=UUID.randomUUID();f.anchor=anchor;f.session=store.session();
  f.binding=new SharedChoiceOfferBinding(server,owner,anchor,audience,new PerceptionTransform(point.x()+.5,point.y()+.1,point.z()+.5,
   net.minecraft.util.Mth.wrapDegrees(180-player.getYRot()),0,2),TTL,this::clock);
  prepareOffer(f);flight=f;
  return "Choix de primitive LAB prepare: "+f.id+"; "+audience.size()+" participant(s) reels. Le catalogue GROUP/clearing reste non qualifie.";
 }
 private void prepareOffer(Flight f){prepareOffer(f,null);}
 private void prepareOffer(Flight f,DecisionInput current){
  var p=f.anchor.position();var footprint=new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of());
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());
  var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),clock(),Map.of(),context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  var intent=f.selected==null?new EffectIntent(new OperationToken(UUID.randomUUID()),SharedChoiceOfferBinding.ID,1,f.args,footprint,new EventInstanceId(f.id)):
   f.selected.plan().effects().getFirst();
  var capture=f.binding.capture(intent,f.selected==null?new PlanningSnapshot(sample,new OwnershipView(Map.of()),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of())):NativeCataloguePlanning.raw(current));
  f.prepared=capture.prepared().orElseThrow();
  f.arbiter=new ResourceArbiter(world,new Epoch(Math.max(floor.getAsLong(),store.session().bootEpoch().value())),1000,4,
   new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var key=TraceBinding.key(f.anchor);
  var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),intent.instanceId(),
   new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),clock().plus(1200),f.prepared.claims(),Map.of(scope,BudgetMath.zero()));
  if(f.selected==null)f.token=f.arbiter.arbitrate(List.of(candidate),clock(),Map.of(key,new Epoch(f.anchor.header().revision().value())),Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
  else{
   f.applyingInput=Objects.requireNonNull(current);f.applyingSession=f.session;
   try{
    var held=SelectedEventAdmission.reserve(f.selected,f.prepared,f.arbiter,this::clock,new DurationTicks(1200),CAP,()->selectedLive(f)&&f.binding.available());
    f.admission=held.service();f.admissionDecision=held.decision();f.token=held.token();
   }finally{f.applyingInput=null;f.applyingSession=null;}
  }
  generation.accept(f.token.generation().value());
  f.live=new LiveValidationSnapshot(f.prepared.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,clock(),f.token.generation());
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),footprint,f.prepared.preconditions(),List.of(intent),f.prepared.budgetEstimate());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),f.token.generation(),f.prepared.preconditions(),
   f.token.expiryTick(),slice,f.token,capture.validation(),f.selected==null?List.of():f.selected.reservation(f.token,clock()),Set.of(),List.of(),f.prepared.durableBlobs());
  f.prepareBatch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),
   capture.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  var tracked=tracking(f);var authority=new CommitPermit.Authority(tracked,4);
  var effects=new ServerEffectPort(authority,i->{throw new IllegalStateException("captured");},s->f.live,
   Map.of(SharedChoiceOfferBinding.ID,selectedGuard(f)),f.binding.capabilities(),4,s->new StabilizationLiveSnapshot(store.session(),f.live,new OwnershipView(Map.of())),m->{},(m,r)->{});
  effects.hydrate(f.prepared,capture.blobs());f.runner=new EventRunner(authority,tracked,content==null?effects:content.attach(f.prepared,capture.blobs(),effects,f.selected!=null),f.arbiter,4);
  f.runner.submit(f.prepareBatch,Map.of(),f.live,result->{
   var payload=new HashMap<String,JsonValue>();
   payload.put("anchor",new JsonValue.Text(f.anchor.anchorId().value().toString()));payload.put("manual",new JsonValue.Bool(f.selected==null));
   payload.put("audience",new JsonValue.Array(f.binding.players().stream().sorted().map(id->(JsonValue)new JsonValue.Text(id.value().toString())).toList()));
   if(f.selected!=null){
    payload.put("selected_plan",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(ExecutionPlan.class).encode(f.selected.plan()))));
    payload.put("selected_decision",new JsonValue.Text(f.selected.proposal().proposalId().value().toString()));
   }
   var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),intent.operationToken(),result,Set.of(),List.of(),List.of())));
   rows.add(new DurableFact(fact(OFFERED,f.id,payload)));var blobs=new ArrayList<BlobContent>();
   if(f.selected!=null){var ledger=f.selected.effectContribution(prepare,result,clock());rows.addAll(ledger.records());blobs.addAll(ledger.blobs());}
   f.resultBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
   return f.resultBatch;
  });
 }
 private boolean selectedLive(Flight f){
  if(f.selected==null)return true;
  // Pinned only for this actor's admission or current runner.poll call.
  return f.applyingInput!=null&&f.applyingSession!=null&&f.applyingSession.equals(store.session())
   &&f.applyingInput.context().scopeRevisions().equals(f.prepared.preconditions().revisions());
 }
 private ServerEffectPort.Binding selectedGuard(Flight f){
  var binding=f.binding;
  return new ServerEffectPort.Binding(){
   public ProviderMode mode(){return binding.mode();}
   public Set<PrimitiveId> supportedPrimitives(){return binding.supportedPrimitives();}
   public Set<CapabilityId> capabilities(){return binding.capabilities();}
   public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){return binding.capture(intent,context);}
   public boolean revalidate(PreparedEffect prepared,BlobBundle blobs){return selectedLive(f)&&binding.revalidate(prepared,blobs);}
   public SliceResult apply(PreparedEffect prepared,BlobBundle blobs){
    if(!selectedLive(f))throw new IllegalStateException("selected choice context revoked");
    return binding.apply(prepared,blobs);
   }
   public ReconcileResult reconcile(RecoveryProbe probe){return binding.reconcile(probe);}
  };
 }
 private DurableStore tracking(Flight f){
  return new DurableStore(){
   public CompletionStage<CommitAck> append(DurableBatch batch){
    var result=store.append(batch);if(batch.transactionId().equals(f.prepareBatch.transactionId()))f.prepareAck=result.toCompletableFuture();
    else if(f.resultBatch!=null&&batch.transactionId().equals(f.resultBatch.transactionId()))f.resultAck=result.toCompletableFuture();return result;
   }
   public StoreReadResult view(StoreQuery q){return store.view(q);}public CompletionStage<StoreReadResult> read(StoreQuery q){return store.read(q);}
   public StoreSession session(){return store.session();}public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest q){return store.readBlobs(q);}
  };
 }
 private String sameDecision(DirectorFact fact,PlayerId actor,ResourceId action,String outcome){
  return fact.payload().get("actor").equals(new JsonValue.Text(actor.value().toString()))&&
   fact.payload().get("action").equals(new JsonValue.Text(action.value()))?outcome+" "+action.value():"Choix deja verrouille par une autre decision.";
 }
 /** The frozen C2S DTO joins the same explicit input path as the existing clickable command. */
 public String selectPacket(ServerPlayer player,AnchorInteraction interaction){
  actor();var f=flight;var id=new PlayerId(player.getUUID());
  if(f==null||!f.id.equals(interaction.header().instanceId().value())||!f.anchor.anchorId().equals(interaction.anchorId())
      ||f.binding.header(id).filter(interaction.header()::equals).isEmpty())return "Interaction refusée : offre ou perception actuelle absente.";
  return select(player,f.id,interaction.actionId());
 }
 public String select(ServerPlayer player,UUID event,ResourceId action){
  actor();if(!player.isAlive())return "Joueur vivant requis.";
  var id=new PlayerId(player.getUUID());var f=flight;var old=selected.get(event);
  if(old!=null)return sameDecision(old,id,action,"RESOLVED");
  if(f!=null&&f.stopRequested)return "Offre en cours de nettoyage administratif.";
  if(f==null||!f.id.equals(event))return "Offre absente, fermee ou appartenant a une ancienne session.";
  if(f.selected==null&&!player.hasPermissions(4))return "Le choix LAB manuel requiert OP4.";
  if(f.selected!=null&&!f.selected.participants().contains(id))return "Le joueur n'appartient pas aux roles selectionnes.";
  if(!f.args.choiceIds().contains(action))return "Action inconnue.";
  if(f.decision!=null)return sameDecision(f.decision,id,action,"PENDING");
  if(f.pending!=null)return sameDecision(f.pending.decision(),id,action,"PENDING_EXPLICIT_INPUT");
  if(!f.phase.equals("OPEN")||clock().value()>=f.expires)return "Le choix n'est pas ouvert.";

  var header=f.binding.header(id).orElse(null);if(header==null)return "Le joueur n'appartient pas aux temoins reels captures.";
  boolean[] accepted={false};
  var interaction=new AnchorInteraction(header,f.anchor.anchorId(),action);
  boolean authorized=DirectorNativeNetworkBootstrap.acceptGameplayChoice(server,id,interaction,
   value->flight==f&&f.phase.equals("OPEN")&&f.pending==null&&f.binding.available()&&f.binding.livePlayer(id)&&
    value.anchorId().equals(f.anchor.anchorId())&&f.args.choiceIds().contains(value.actionId()),
   value->{
    var decision=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(("lab-choice-v1/"+event).getBytes(StandardCharsets.UTF_8))),
     SharedChoiceResolutionBinding.SELECTED,f.selected==null?List.of(new ScopeRef(ScopeKind.WORLD,world.value())):f.selected.plan().scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),Optional.of(new EventInstanceId(event)),
     EvidenceStatus.CONFIRMED,Map.of("actor",new JsonValue.Text(id.value().toString()),"anchor",new JsonValue.Text(f.anchor.anchorId().value().toString()),
      "action",new JsonValue.Text(action.value()),"received_header",CanonicalJsonV1.decode(new ModelCodec<>(PerceptionHeader.class).encode(header))),
     true,Optional.empty());
    f.pending=new PendingInput(id,header,action,decision,store.session(),f.anchor,
      Math.min(f.expires,Math.addExact(clock().value(),PENDING_INPUT_TICKS)));
    f.phase="WAITING_MUTATION_LANE";accepted[0]=true;
    System.out.println("DIRECTOR_LAB_CHOICE_EXPLICIT_INPUT_QUEUED event="+f.id+" actor="+id.value()+" action="+action.value()+" deadline="+f.pending.deadline());
   });
  return !authorized?"Interaction refusee: preuve J06/session/portee indisponible.":accepted[0]?
   "Choix explicite memorise jusqu'a 9 secondes, dans la duree de l'offre ; attente des ecritures d'ancres.":
   "Interaction non retenue ; aucun choix consomme.";
 }
 private boolean pendingLive(Flight f,PendingInput p){
  return flight==f&&f.phase.equals("WAITING_MUTATION_LANE")&&f.pending==p&&p.session().equals(store.session())
   &&f.session.equals(p.session())&&clock().value()<p.deadline()&&clock().value()<f.expires
   &&f.anchor.equals(p.anchor())&&owner.current(p.anchor().anchorId()).filter(p.anchor()::equals).isPresent()
   &&f.args.choiceIds().contains(p.action())&&p.header().instanceId().value().equals(f.id)
   &&f.binding.header(p.player()).filter(p.header()::equals).isPresent()&&f.binding.available()&&f.binding.livePlayer(p.player())
   &&DirectorNativeNetworkBootstrap.warningReceived(server,p.player(),p.header());
 }
 private void pollPending(Flight f){
  var p=Objects.requireNonNull(f.pending,"explicit pending choice missing");
  if(!pendingLive(f,p)){
   var player=server.getPlayerList().getPlayer(p.player().value());
   if(player!=null)player.sendSystemMessage(Component.literal("[Director LAB] Choix non execute : attente expiree ou contexte/session modifie. Camp conserve."));
   System.out.println("DIRECTOR_LAB_CHOICE_PENDING_CANCELLED event="+f.id+" mutation_started=false");
   closeOffer(f,"PENDING_EXPIRED_OR_CONTEXT_CHANGED_KEEP_UNCHANGED");return;
  }
  // This getter reads the SAME ready() barrier used by resolveSharedChoice; it relaxes nothing.
  if(!mutations.sharedChoiceReady()||f.selected!=null&&!f.selected.forwardContextAvailable())return;
  var interaction=new AnchorInteraction(p.header(),p.anchor().anchorId(),p.action());
  boolean authorized=DirectorNativeNetworkBootstrap.acceptGameplayChoice(server,p.player(),interaction,
   value->pendingLive(f,p)&&value.equals(interaction)&&mutations.sharedChoiceReady(),
   value->{
    var begun=f.selected==null?mutations.resolveSharedChoice(f.id,p.anchor().anchorId(),p.player(),p.header(),p.decision(),outcome->f.mutation=outcome):
     mutations.resolveSharedChoiceSelected(f.selected,p.anchor().anchorId(),p.player(),p.header(),p.decision(),
      ((DurablePrepare)f.prepareBatch.records().getFirst()).value(),outcome->f.mutation=outcome);
    if(begun!=null){
     f.decision=p.decision();f.pending=null;f.phase="RESOLVING";
     var player=server.getPlayerList().getPlayer(p.player().value());
     if(player!=null)player.sendSystemMessage(Component.literal("[Director LAB] Choix transmis : RESULT durable en attente."));
     System.out.println("DIRECTOR_LAB_CHOICE_PENDING_STARTED event="+f.id+" action="+p.action().value());
    }
   });
  // A transient inbound/rate refusal is not an accepted decision. Keep only this exact explicit
  // input until its original deadline, then revalidate all authority again; never invent a vote.
  if(!authorized)return;
 }
 private static Component choicePrompt(UUID event,boolean catalogue){
  var route=(catalogue?"/proutlost choice select ":"/proutlost director lab choice select ")+event+" ";
  return Component.literal("[Director LAB] Choix du camp : ")
   .append(Component.literal("[Écouter]").withStyle(style->style
    .withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
     net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,route+"listen"))))
   .append(Component.literal("  "))
   .append(Component.literal("[Laisser une trace]").withStyle(style->style
    .withColor(net.minecraft.ChatFormatting.GREEN).withUnderlined(true)
    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
     net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,route+"leave_trace"))));
 }
 private boolean closeUnsentOffer(Flight f){
  if(!f.binding.definitivelyNeverSent()||f.resultBatch!=null||!durable(f.prepareBatch,f.prepareAck))return false;
  if(!f.anchor.equals(owner.current(f.anchor.anchorId()).orElse(null)))return false;
  var prepare=((DurablePrepare)f.prepareBatch.records().getFirst()).value();
  var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
  var rows=new ArrayList<DurableRecord>();var blobs=new ArrayList<BlobContent>();
  rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),safe,Set.of(),List.of(),List.of())));
  rows.add(new DurableFact(fact(CLOSED,f.id,Map.of("reason",new JsonValue.Text("SESSION_RENEWAL_BEFORE_SEND_NO_REPLAY")))));
  if(f.selected!=null){
   rows.addAll(f.selected.abortRecords("CHOICE_OFFER_ABORTED_SAFE",Map.of("no_physical_effect",new JsonValue.Bool(true),
    "perception_exposure",new JsonValue.Bool(false),"anchor_unchanged",new JsonValue.Bool(true),"source",new JsonValue.Text("LIVE_BINDING_NO_SEND_ATTEMPT"))));
   var ledger=f.selected.abortContribution(prepare,safe,clock());rows.addAll(ledger.records());blobs.addAll(ledger.blobs());
  }
  f.closeBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
  f.closeAck=store.append(f.closeBatch).toCompletableFuture();f.phase="CLOSING";return true;
 }
 private void closeOffer(Flight f,String reason){
  f.pending=null;f.binding.stop();var rows=new ArrayList<DurableRecord>();
  var closedFact=fact(CLOSED,f.id,Map.of("reason",new JsonValue.Text(reason)));
  rows.add(new DurableFact(closedFact));var closeBlobs=new ArrayList<BlobContent>();
  // A selected resolution's original completion/outbox is already atomic with the anchor RESULT.
  if(f.selected!=null&&!reason.equals("PLAYER_SELECTED")){
   if(f.mutation!=null&&!f.mutation.outcome().equals("ABORTED_SAFE"))
    throw new IllegalStateException("choice anchor outcome uncertain; no confirmed abort");
   var current=owner.current(f.anchor.anchorId()).orElseThrow();
   var absence=f.selected.unchangedOutcomeContribution(((DurablePrepare)f.prepareBatch.records().getFirst()).value(),closedFact,f.anchor,current,clock());
   rows.addAll(absence.records());closeBlobs.addAll(absence.blobs());
   rows.addAll(f.selected.abortRecords(reason,Map.of("no_physical_effect",new JsonValue.Bool(true),
    "perception_exposure",new JsonValue.Bool(true),"anchor_unchanged",new JsonValue.Bool(f.anchor.equals(current)))));
  }
  f.closeBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,closeBlobs);
  f.closeAck=store.append(f.closeBatch).toCompletableFuture();f.phase="CLOSING";
 }
 private void recover(){
  if(recoveryAck!=null){
   if(durable(recoveryBatch,recoveryAck)){
    unmatchedOffers.values().removeIf(p->p.reservation().instanceId().value().equals(recoveringEvent));
    openEvents.remove(recoveringEvent);closedEvents.add(recoveringEvent);recoveryAck=null;recoveryBatch=null;
   }
   return;
  }
  if(unmatchedOffers.isEmpty()&&openEvents.isEmpty())return;
  recoveringEvent=!unmatchedOffers.isEmpty()?unmatchedOffers.values().iterator().next().reservation().instanceId().value():openEvents.iterator().next();
  var records=new ArrayList<DurableRecord>();
  for(var prepare:unmatchedOffers.values())if(prepare.reservation().instanceId().value().equals(recoveringEvent))
   records.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),
    new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
  records.add(new DurableFact(fact(CLOSED,recoveringEvent,Map.of("reason",new JsonValue.Text("RESTART_CLEANUP_NO_REPLAY")))));
  recoveryBatch=appendBatch(records);recoveryAck=store.append(recoveryBatch).toCompletableFuture();
 }
 public boolean controlRecoveryReady(){actor();return !failed&&scanned&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stopRequested=true;f.pending=null;if(f.phase.equals("RESOLVING"))mutations.requestStop(Optional.of(new EventInstanceId(f.id)));
  return Optional.of(()->{actor();return closedEvents.contains(event)?AdminStatus.SUCCEEDED:failed?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public void poll(){
  actor();
  if(failed){
   // Drain only a positively unexposed offer on this same live actor. The
   // binding sets sentAt before its first network call and never clears it.
   // A failed lane stays failed; this never polls authority or sends a prompt.
   var f=flight;
   if(f==null||!scanned||store.readOnlySafe()||!f.session.equals(store.session()))return;
   try{
    if(f.phase.equals("OFFERING")&&f.resultBatch==null){
     boolean headersEmpty=true;
     for(var player:f.binding.players())if(f.binding.header(player).isPresent())headersEmpty=false;
     boolean neverSent=false;
     try{f.binding.sentAt();}
     catch(NullPointerException absent){neverSent="prompt not sent".equals(absent.getMessage());}
     if(!neverSent||!headersEmpty||!durable(f.prepareBatch,f.prepareAck))return;
     if(!f.anchor.equals(owner.current(f.anchor.anchorId()).orElse(null)))return;
     var prepare=((DurablePrepare)f.prepareBatch.records().getFirst()).value();
     if(!prepare.transactionId().equals(f.prepareBatch.transactionId())||
        !prepare.operationToken().equals(f.prepared.intent().operationToken())||
        !prepare.reservation().instanceId().value().equals(f.id))return;
     var actual=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
     var rows=new ArrayList<DurableRecord>();
     rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of())));
     rows.add(new DurableFact(fact(CLOSED,f.id,Map.of("reason",new JsonValue.Text("OFFER_ABORTED_BEFORE_SEND")))));
     var blobs=new ArrayList<BlobContent>();
     if(f.selected!=null){
      rows.addAll(f.selected.abortRecords("CHOICE_OFFER_ABORTED_SAFE",Map.of(
       "no_physical_effect",new JsonValue.Bool(true),"perception_exposure",new JsonValue.Bool(false),
       "anchor_unchanged",new JsonValue.Bool(true),"source",new JsonValue.Text("LIVE_BINDING_SENT_AT_NULL"))));
      var ledger=f.selected.abortContribution(prepare,actual,clock());
      rows.addAll(ledger.records());blobs.addAll(ledger.blobs());
     }
     f.closeBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
     f.closeAck=store.append(f.closeBatch).toCompletableFuture();f.phase="FAILED_OFFER_ABORT_PENDING";
     System.out.println("DIRECTOR_LAB_CHOICE_NO_EXPOSURE_ABORT_SUBMITTED event="+f.id+" source=LIVE_BINDING_SENT_AT_NULL");
    }else if(f.phase.equals("FAILED_OFFER_ABORT_PENDING")&&durable(f.closeBatch,f.closeAck)){
     f.arbiter.release(f.token);closedEvents.add(f.id);flight=null;
     System.out.println("DIRECTOR_LAB_CHOICE_NO_EXPOSURE_ABORT_DURABLE event="+f.id+" lane_still_failed=true");
    }
   }catch(RuntimeException blocked){
    System.out.println("DIRECTOR_LAB_CHOICE_NO_EXPOSURE_ABORT_BLOCKED "+blocked);
   }
   return;
  }
  if(!scanned)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("choice WAL unavailable");
   if(recovering()){recover();return;}
   var f=flight;if(f==null)return;if(!f.session.equals(store.session()))throw new IllegalStateException("choice session revoked");
    if(f.stopRequested&&Set.of("RECEIPT","OPEN","WAITING_MUTATION_LANE").contains(f.phase)){closeOffer(f,"ADMIN_STOP_KEEP_UNCHANGED");return;}
   switch(f.phase){
    case "OFFERING"->{
     try{
      if(f.selected!=null&&f.stage==EventRunner.Stage.PREPARING&&clock().compareTo(f.token.expiryTick())<0&&f.binding.available()
          &&f.binding.players().stream().allMatch(p->DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,p,1))){
       var session=store.session();
       try{
        f.applyingInput=f.selected.refresh();f.applyingSession=session;f.contextWait="";f.contextFailure="";
       }catch(SelectedEventStart.ContextUnavailable unavailable){
        var detail=unavailable.toString();
        if(!detail.equals(f.contextWait)){f.contextWait=detail;System.out.println("DIRECTOR_LAB_CHOICE_OFFER_WAITING_FRESH_CONTEXT event="+f.id+" detail="+detail);}
        return; // No authority poll or permit consumption while the original token remains live.
       }catch(RuntimeException changed){
        // Keep genuine selected changes on the existing false-guard recovery path.
        f.contextFailure=changed.toString();
       }
       if(!session.equals(store.session()))throw new IllegalStateException("choice session revoked during offer context capture");
      }
      // Expiry rejects normally; RESULT_PENDING drains its exact ACK without another context read.
      f.live=new LiveValidationSnapshot(f.live.readSet(),f.live.loadedChunks(),f.live.health(),clock(),f.token.generation());
      var progress=f.runner.poll(f.prepareBatch.transactionId(),f.live);f.stage=progress.stage();
      if(progress.stage()==EventRunner.Stage.COMMITTED){
       if(!durable(f.resultBatch,f.resultAck))throw new IllegalStateException("choice offer exact RESULT missing");
       if(f.stopRequested){closeOffer(f,"ADMIN_STOP_KEEP_UNCHANGED");return;}
       f.expires=f.binding.sentAt().value()+f.ttl;f.receiptDeadline=clock().value()+100;f.phase="RECEIPT";
      }else if(progress.stage()==EventRunner.Stage.RECOVERY_REQUIRED&&f.binding.definitivelyNeverSent()){
       if(!closeUnsentOffer(f))throw new IllegalStateException("unsent choice exact durable reconciliation unavailable");
      }else if(progress.stage()==EventRunner.Stage.REJECTED||progress.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
       throw new IllegalStateException("choice offer requires recovery stage="+progress.stage()+" failure="+progress.failure()
        +(f.contextFailure.isEmpty()?"":" selected_context="+f.contextFailure));
     }finally{f.applyingInput=null;f.applyingSession=null;}
    }
    case "RECEIPT"->{
     if(clock().value()>=f.expires)closeOffer(f,"KEEP_UNCHANGED");
     else if(f.binding.allReceived()&&f.binding.available()){
      f.phase="OPEN";
      for(var id:f.binding.players()){
       var p=server.getPlayerList().getPlayer(id.value());
       if(p!=null)p.sendSystemMessage(choicePrompt(f.id,f.selected!=null));
      }
     }else if(!f.binding.available()||clock().value()>=f.receiptDeadline)closeOffer(f,"RENDERER_UNAVAILABLE_KEEP_UNCHANGED");
    }
    case "OPEN"->{if(clock().value()>=f.expires||!f.binding.available())closeOffer(f,"KEEP_UNCHANGED");}
    case "WAITING_MUTATION_LANE"->pollPending(f);
    case "RESOLVING"->{
     var status=f.mutation;
     if(status!=null&&status.eventId().equals(f.id)){
      if(status.outcome().equals("COMMITTED")){
       selected.put(f.id,f.decision);closeOffer(f,"PLAYER_SELECTED");
      }else if(status.outcome().equals("ABORTED_SAFE"))closeOffer(f,"CONTEXT_CHANGED_KEEP_UNCHANGED");
      else if(status.phase().equals("FAILED_SAFE"))throw new IllegalStateException("choice mutation recovery required");
     }
    }
    case "CLOSING"->{if(durable(f.closeBatch,f.closeAck)){
     f.arbiter.release(f.token);closedEvents.add(f.id);flight=null;
     System.out.println("DIRECTOR_LAB_CHOICE_CLEANUP_DURABLE event="+f.id+" selected="+(f.decision!=null&&selected.containsKey(f.id)));
    }}
    default->throw new IllegalStateException("choice phase");
   }
  }catch(SelectedEventStart.ContextUnavailable unavailable){
   if(flight==null||!Set.of("OFFERING","WAITING_MUTATION_LANE").contains(flight.phase))throw unavailable;
   // The explicit input and its original deadline remain unchanged; no mutation was started.
  }catch(RuntimeException failure){failed=true;if(flight!=null)try{flight.binding.stop();}catch(RuntimeException ignored){}
   System.out.println("DIRECTOR_LAB_CHOICE_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 public String status(){actor();return failed?"CHOICE_RECOVERY_REQUIRED":recovering()?"CHOICE_RECOVERING":
  flight==null?"CHOICE_IDLE committed="+selected.size():"CHOICE_"+flight.phase+" "+flight.id;}
 public void close(){actor();if(flight!=null)flight.binding.stop();closed=true;}
}
