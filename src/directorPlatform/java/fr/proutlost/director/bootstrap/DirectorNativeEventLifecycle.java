package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/** Actor-owned EVENT metadata for actual audited catalogue instances. This does
 * not admit or apply a world effect, fabricate a SliceResult, claim a client
 * receipt, infer an outcome from chat, or replay any native action after reboot.
 * Root feeds ONLY validated J04 pages through accept(); no bare record injection.
 * Context and every EVENT revision are appended by the same existing JournalStore.
 * PROPOSED means audit durable; driver begin waits for its exact real J04 ACK.
 * Progress refers to observed durable frames, not queued work or wall-clock guesses. */
public final class DirectorNativeEventLifecycle {
 public static final ResourceId CONTEXT=new ResourceId("proutlost_director:catalogue_event_context_v1");
 private static final ModelCodec<EventDefinition> DEF=new ModelCodec<>(EventDefinition.class);
 private static final ModelCodec<ExecutionPlan> PLAN=new ModelCodec<>(ExecutionPlan.class);
 private static final ModelCodec<SnapshotBatch> SNAPSHOT=new ModelCodec<>(SnapshotBatch.class);
 private static final FactType ABORT=new FactType("proutlost_director:catalogue_event_aborted");
 private static final FactType PERCEPTION_END=new FactType("proutlost_director:lab_perception_end");
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final CatalogSnapshot catalog;private final Supplier<Tick> clock;private final ProviderMode mode;
 private final boolean publicGroupQualification;
 private final boolean realGroupClientsQualification;
 private final DirectorProductionRuntimeQa.Inputs declaredQa;
 private final Map<EventInstanceId,Entry> entries=new LinkedHashMap<>();
 private final Map<TransactionId,Entry> preparedByTransaction=new HashMap<>();
 private final Map<TransactionId,ResultRecord> pendingResults=new LinkedHashMap<>();
 private final Map<FactId,DirectorFact> seenFacts=new HashMap<>();
 private final Set<EventInstanceId> cold=new HashSet<>();
 private final Set<FactId> forwardedCompletionFacts=new HashSet<>();
 private StoreQuery scanQuery;private CompletableFuture<StoreReadResult> scanRead;
 private Sequence coveredThrough=new Sequence(0);private boolean recovering=true;
 private final StoreSession readerSession;private final Map<FactId,Sequence> factCuts=new HashMap<>();
 private Pending writing;private Entry hydrating;private boolean failed;private String failure="";
 private static final class Entry {
  EventInstance committed,initial;EventDefinition definition;ExecutionPlan plan;SnapshotBatch snapshot;
  BlobContent context;CompletableFuture<BlobReadResult> hydration;boolean initialAck;
  Sequence sourceCut=new Sequence(0);boolean publishable;
  final Map<TransactionId,PrepareRecord> prepares=new LinkedHashMap<>();
  final Map<OperationToken,ResultRecord> results=new HashMap<>();
  final Set<MutationId> mutations=new HashSet<>();final Set<RequestId> requirements=new HashSet<>();
  DirectorFact completion,abort,perceptionEnd;Sequence abortCut,perceptionEndCut;EventInstanceId id;DisasterInstance disaster;
  final Set<ReservationId> uniqueReservations=new HashSet<>();UniqueStatus uniqueStatus;
   final TerminalEventProgressMemo terminalProgressMemo=new TerminalEventProgressMemo();
 }
 private record Pending(Entry entry,DurableBatch batch,CompletableFuture<CommitAck> ack,EventInstance last,boolean initial){}
 public DirectorNativeEventLifecycle(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock){
  this(server,world,store,catalog,clock,ProviderMode.TEST_ONLY);
 }
 public DirectorNativeEventLifecycle(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock,ProviderMode mode){
  this(server,world,store,catalog,clock,mode,false);
 }
 /** One closed non-narrative fixture on a distinct marked QA world. Existing
  * constructors retain their nine-event/two-chain and PRODUCTION constraints. */
 public static DirectorNativeEventLifecycle publicGroupQualification(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock){
  DirectorPublicGroupQaFence.require(server);
  if(!catalog.equals(DirectorPublicGroupQaCatalog.snapshot()))throw new IllegalArgumentException("exact closed public GROUP fixture/hash required");
  var definition=catalog.events().get(new DefinitionId("proutlost_director:qa_public_group/observed"));
  if(catalog.events().size()!=1||!catalog.chains().isEmpty()||definition==null||definition.category()!=Category.HYBRID
   ||definition.target().scope()!=TargetScope.REGION||definition.target().roles().size()!=1
   ||!definition.target().roles().getOrDefault("observer",new Role(1,1,false)).equals(new Role(0,0,false))
   ||definition.steps().size()!=1||!(definition.steps().getFirst().args() instanceof CampGrowth growth)||growth.maxBlocks()!=1
   ||!definition.completion().factType().equals(new FactType("proutlost_director:qa_public_group_observed")))
   throw new IllegalArgumentException("closed public GROUP native fixture required");
  return new DirectorNativeEventLifecycle(server,world,store,catalog,clock,ProviderMode.TEST_ONLY,true);
 }
 private DirectorNativeEventLifecycle(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock,ProviderMode mode,boolean publicGroupQualification){
  this(server,world,store,catalog,clock,mode,publicGroupQualification,false);
 }
 /** Distinct, exactly closed real-client QA fixture. The zero-client factory
  * continues to require its original flag/marker/empty PlayerList unchanged. */
 public static DirectorNativeEventLifecycle realGroupClientsQualification(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock){
  DirectorRealGroupClientsQaFence.require(server);
  if(!catalog.equals(DirectorRealGroupClientsQaCatalog.snapshot()))throw new IllegalArgumentException("exact real-client GROUP fixture/hash required");
  return new DirectorNativeEventLifecycle(server,world,store,catalog,clock,ProviderMode.TEST_ONLY,false,true);
 }
 private DirectorNativeEventLifecycle(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock,ProviderMode mode,boolean publicGroupQualification,boolean realGroupClientsQualification){
  this(server,world,store,catalog,clock,mode,publicGroupQualification,realGroupClientsQualification,null);
 }
 static DirectorNativeEventLifecycle declaredSynthetic(MinecraftServer server,WorldId world,JournalStore store,DirectorProductionRuntimeQa.Inputs inputs,Supplier<Tick> clock){
  Objects.requireNonNull(inputs).require(server);
  return new DirectorNativeEventLifecycle(server,world,store,inputs.catalog(),clock,ProviderMode.TEST_ONLY,false,false,inputs);
 }
 private DirectorNativeEventLifecycle(MinecraftServer server,WorldId world,JournalStore store,CatalogSnapshot catalog,Supplier<Tick> clock,ProviderMode mode,boolean publicGroupQualification,boolean realGroupClientsQualification,DirectorProductionRuntimeQa.Inputs declaredQa){
  this.declaredQa=declaredQa;
  this.publicGroupQualification=publicGroupQualification;
  this.realGroupClientsQualification=realGroupClientsQualification;
  this.mode=Objects.requireNonNull(mode);this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);
  this.catalog=Objects.requireNonNull(catalog);this.clock=Objects.requireNonNull(clock);actor();readerSession=store.session();
  if(!publicGroupQualification&&!realGroupClientsQualification&&((mode==ProviderMode.TEST_ONLY?catalog.events().size()!=9:catalog.events().size()<9||catalog.events().size()>128)||catalog.chains().size()!=2))
   throw new IllegalArgumentException("runtime catalogue exceeds installed composition bounds");
  if(mode==ProviderMode.TEST_ONLY&&((!Boolean.getBoolean("proutlost.director.nativeLab")&&declaredQa==null)||
    catalog.events().values().stream().anyMatch(d->d.calibration()!=Calibration.DEFAULT_FOR_TESTING)))
   throw new IllegalArgumentException("actual TEST_ONLY nine-event/two-chain LAB catalogue required");
  if(mode==ProviderMode.PRODUCTION&&(catalog.events().values().stream().anyMatch(d->d.calibration()!=Calibration.APPROVED)||
    catalog.chains().values().stream().anyMatch(d->d.calibration()!=Calibration.APPROVED)||
    catalog.primitives().values().stream().anyMatch(d->d.mode()!=ProviderMode.PRODUCTION)||
    catalog.events().values().stream().flatMap(d->d.steps().stream()).anyMatch(step->!catalog.primitives().containsKey(step.primitive()))))
   throw new IllegalArgumentException("approved production catalogue and providers required");
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("EVENT metadata server actor required");}
 private Tick now(){return Objects.requireNonNull(clock.get());}
 private String phase(String suffix){return declaredQa!=null?"DECLARED_SYNTHETIC_QA_"+suffix:(mode==ProviderMode.PRODUCTION?"RUNTIME_PRODUCTION_":"LAB_TEST_ONLY_")+suffix;}
 private static boolean terminal(EventState s){return Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(s);}
 /** Every mutable per-entry evidence change is followed by touch, even an older
  * repeated cut or a late completion/abort/perception fact with unchanged maps.
  * finishWrite may replace the immutable EVENT head; its identity also misses. */
 private static void touch(Entry e,Sequence cut){e.terminalProgressMemo.invalidate();if(cut.compareTo(e.sourceCut)>0)e.sourceCut=cut;}
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static String value(Map<String,JsonValue> object,String key){return ((JsonValue.Text)object.get(key)).value();}
 private static JsonValue encode(byte[] bytes){return NativeCatalogueColdCompletion.encodedBytes(bytes);}
 private static byte[] decode(Map<String,JsonValue> object,String key){return NativeCatalogueColdCompletion.decodedBytes(object.get(key));}
 private static UUID named(String prefix,UUID event){return UUID.nameUUIDFromBytes((prefix+"\0"+event).getBytes(StandardCharsets.UTF_8));}
 private BlobContent context(SelectedEventStart selected){return BlobContent.capture(CONTEXT,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
  "source",text("ACTUAL_AUDITED_CATALOGUE_SELECTION"),"world",text(world.value()),"audit_decision",text(selected.proposal().proposalId().value()),
  "catalog_generation",text(selected.captured().catalog().generation().value()),
  "definition",encode(DEF.encode(selected.definition())),"plan",encode(PLAN.encode(selected.plan())),
  "snapshot",encode(SNAPSHOT.encode(selected.captured().context()))))));}
 private DirectorNativeCatalogueExecution.GateResult gate(DirectorNativeCatalogueExecution.GateStatus status,String detail){return new DirectorNativeCatalogueExecution.GateResult(status,detail);}
 public DirectorNativeCatalogueExecution.GateResult beginAudited(SelectedEventStart selected,DirectorDecisionJournal journal){
  return beginAudited(selected,journal,Set.of());
 }
 /** requirement IDs must come from the caller's actual ACKed J08 binding, never
  * from a merely pending REQUIRE selector. No assigned-event field is invented. */
 public DirectorNativeCatalogueExecution.GateResult beginAudited(SelectedEventStart selected,DirectorDecisionJournal journal,Set<RequestId> actualBoundRequirements){
  actor();Objects.requireNonNull(selected);Objects.requireNonNull(journal);actualBoundRequirements=Set.copyOf(actualBoundRequirements);
  if(failed||store.readOnlySafe())return gate(DirectorNativeCatalogueExecution.GateStatus.REJECTED,"EVENT metadata J04 unavailable: "+failure);
  if(recovering())return gate(DirectorNativeCatalogueExecution.GateStatus.WAITING,"actual EVENT metadata J04 recovery/context join pending");
  if(!journal.poll(selected.proposal().proposalId()).mayProceedToAdmission())return gate(DirectorNativeCatalogueExecution.GateStatus.WAITING,"actual J03 audit ACK pending");
  if(selected.mode()!=mode||!selected.plan().scopes().worldId().equals(world)||!selected.captured().catalog().equals(catalog))
   throw new IllegalArgumentException("actual selected world/catalogue changed");
  var id=selected.eventId();var e=entries.get(id);
  if(e==null){
   retireTerminalEntries();if(entries.size()>=64)return gate(DirectorNativeCatalogueExecution.GateStatus.WAITING,"EVENT metadata active bound");
   e=new Entry();e.id=id;e.definition=selected.definition();e.plan=selected.plan();e.snapshot=selected.captured().context();e.context=context(selected);e.requirements.addAll(actualBoundRequirements);
   var regions=new HashSet<RegionId>();for(var scope:selected.plan().scopes().scopes())if(scope.kind()==ScopeKind.REGION)regions.add(new RegionId(scope.id()));
   var steps=new TreeMap<String,StepState>();selected.definition().steps().forEach(s->steps.put(s.id(),StepState.WAITING));
   e.initial=new EventInstance(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),now()),id,selected.plan().definition(),catalog.generation(),
    EventState.PROPOSED,phase("AUDIT_DURABLE_NATIVE_NOT_STARTED"),selected.plan().roles(),regions,e.context.reference(),steps,Set.of(),Set.of(),Set.of(),
    selected.parentChain(),actualBoundRequirements,Optional.empty(),new DurationTicks(0),selected.definition().timing().maxDurationTicks(),selected.definition().recovery().mode(),Optional.empty());
   entries.put(id,e);
  }else if(e.plan!=null&&(!e.plan.equals(selected.plan())||!e.definition.equals(selected.definition())||!e.initial.chainId().equals(selected.parentChain())))
   throw new IllegalArgumentException("EVENT instance identity collision");
  if(e.initialAck)return gate(DirectorNativeCatalogueExecution.GateStatus.READY,"actual EVENT PROPOSED J04 ACK durable; native driver may attempt admission");
  if(writing==null&&e.committed==null)append(e,List.of(e.initial),List.of(e.context),true);
  return gate(DirectorNativeCatalogueExecution.GateStatus.WAITING,"EVENT PROPOSED real J04 ACK pending");
 }
 /** The actual query/hash/session/cut must accompany rows. A caller's boolean
  * or callback text is not evidence. Invalid/stale pages produce no progress. */
 public boolean accept(StoreQuery query,StoreReadResult result){
  actor();if(result.status()!=ReadStatus.AVAILABLE)return false;
  var session=store.session();ReadContracts.validate(result,query,session);
  if(!query.worldId().equals(world))throw new IllegalArgumentException("EVENT observation world");
  var page=result.page().orElseThrow();var cut=result.stamp().orElseThrow().snapshotSequence();
  for(var restored:page.aggregates()){
   if(restored.committedSequence().value()<=0||restored.committedSequence().compareTo(cut)>0)throw new IllegalArgumentException("future/uncommitted EVENT evidence");
   aggregate(restored.state(),cut);
  }
  for(var row:page.records())record(row,cut);
  if(!page.hasMore())for(var e:entries.values())if(e.sourceCut.compareTo(cut)<=0)e.publishable=true;
  return true;
 }
 /** F9 contains actual envelopes/facts only. Message cursor is NOT a WAL cut. */
 public boolean accept(ConsumerSpec consumer,SubscriptionReadResult result){
  actor();if(result.status()!=ReadStatus.AVAILABLE)return false;ReadContracts.validate(result,consumer,store.session());
  var cut=result.stamp().orElseThrow().store().snapshotSequence();
  for(var envelope:result.page().orElseThrow().batch())if(envelope.payload() instanceof FactPayload f)fact(f.value(),cut);
  if(!result.page().orElseThrow().hasMore())for(var e:entries.values())if(e.sourceCut.compareTo(cut)<=0)e.publishable=true;
  return true;
 }
 private void aggregate(AggregateState state,Sequence cut){
  if(state instanceof EventAggregate event){
   var value=event.value();if(!value.contextRef().codecId().equals(CONTEXT)||value.contextRef().codecVersion()!=1)return;
   var e=entries.get(value.instanceId());
   if(e==null){
    retireTerminalEntries();if(entries.size()>=64&&terminal(value.state()))return;
    if(entries.size()>=64)throw new IllegalStateException("actual active EVENT metadata bound exceeded");
    e=new Entry();e.id=value.instanceId();entries.put(e.id,e);cold.add(e.id);
   }
   if(e.committed==null||value.header().revision().compareTo(e.committed.header().revision())>0)e.committed=value;
   if(e.initial==null)e.initial=value;touch(e,cut);
  }
  if(state instanceof MutationAggregate mutation){var e=entries.get(mutation.value().instanceId());if(e!=null){e.mutations.add(mutation.value().mutationId());touch(e,cut);}}
  if(state instanceof UniqueAggregate unique){var e=entries.get(unique.value().instanceId());if(e!=null){
   unique.value().reservationToken().ifPresent(token->e.uniqueReservations.add(token.id()));e.uniqueStatus=unique.value().status();touch(e,cut);
  }}
  if(state instanceof DisasterAggregate disaster){var e=entries.get(disaster.value().eventInstanceId());if(e!=null){
   var value=disaster.value();if(e.disaster==null||value.header().revision().compareTo(e.disaster.header().revision())>0)e.disaster=value;
   e.mutations.addAll(value.mutationRefs());touch(e,cut);
  }}
 }
 private void record(DurableRecord row,Sequence cut){
  if(row instanceof DurableAggregate a)aggregate(a.state(),cut);
  else if(row instanceof DurableMutation m)aggregate(new MutationAggregate(m.value()),cut);
  else if(row instanceof DurableUnique u)aggregate(new UniqueAggregate(u.value()),cut);
  else if(row instanceof DurablePrepare p){
   var e=entries.get(p.value().reservation().instanceId());if(e==null)return;
   if(p.value().slice().operations().stream().anyMatch(i->!i.instanceId().equals(e.id)))throw new IllegalArgumentException("foreign operation inside actual PREPARE");
   var old=e.prepares.putIfAbsent(p.value().transactionId(),p.value());if(old!=null&&!old.equals(p.value()))throw new IllegalArgumentException("conflicting actual PREPARE");
   p.value().uniqueness().forEach(u->aggregate(new UniqueAggregate(u),cut));
   preparedByTransaction.put(p.value().transactionId(),e);var deferred=pendingResults.remove(p.value().transactionId());if(deferred!=null)result(e,deferred,cut);touch(e,cut);
  }else if(row instanceof DurableResult r){
   r.value().facts().forEach(f->fact(f,cut));
   var e=preparedByTransaction.get(r.value().prepareTransactionId());
   if(e==null){if(pendingResults.size()<128)pendingResults.putIfAbsent(r.value().prepareTransactionId(),r.value());return;}
   result(e,r.value(),cut);
  }else if(row instanceof DurableFact f)fact(f.value(),cut);
  else if(row instanceof DurableRequest r){
   for(var e:entries.values())if(e.completion!=null&&r.request().state().resultFactIds().contains(e.completion.factId())){
    e.requirements.add(r.request().state().requestId());touch(e,cut);
   }
  }
 }
 private void result(Entry e,ResultRecord result,Sequence cut){
  var p=e.prepares.get(result.prepareTransactionId());if(p==null||!p.operationToken().equals(result.operationToken()))throw new IllegalArgumentException("RESULT without exact original PREPARE");
  var old=e.results.putIfAbsent(result.operationToken(),result);if(old!=null&&!old.equals(result))throw new IllegalArgumentException("conflicting actual RESULT");
  e.mutations.addAll(result.mutations());touch(e,cut);
 }
 private void fact(DirectorFact fact,Sequence cut){
  if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||fact.eventId().isEmpty())return;
  boolean technicalEnd=fact.factType().equals(PERCEPTION_END)&&
   text("RESTART_CLEANUP_NO_REPLAY").equals(fact.payload().get("reason"))&&
   fact.subjectRefs().size()==1&&fact.subjectRefs().getFirst().equals(new ScopeRef(ScopeKind.WORLD,world.value()));
  boolean original=Objects.equals(fact.payload().get("source"),text("CATALOGUE_SELECTED"))||fact.factType().equals(ABORT);
  if(!original&&!technicalEnd)return;
  factCuts.merge(fact.factId(),cut,(a,b)->a.compareTo(b)<=0?a:b);
  var old=seenFacts.putIfAbsent(fact.factId(),fact);if(old!=null&&!old.equals(fact))throw new IllegalArgumentException("conflicting actual fact");
  if(seenFacts.size()>256)seenFacts.entrySet().removeIf(row->row.getValue().eventId().filter(entries::containsKey).isEmpty());
  var e=entries.get(fact.eventId().orElseThrow());if(e==null||e.definition==null)return;
  if(technicalEnd){
   if(e.plan.effects().stream().allMatch(i->i.typedArgs() instanceof TargetSound||i.typedArgs() instanceof Apparition||i.typedArgs() instanceof LocalWeather)){
    if(e.perceptionEnd!=null&&!e.perceptionEnd.equals(fact))throw new IllegalArgumentException("conflicting actual perception recovery END");
    e.perceptionEnd=fact;e.perceptionEndCut=factCuts.get(fact.factId());touch(e,cut);
   }
   return;
  }
  boolean identity=Objects.equals(fact.payload().get("definition"),text(e.definition.id().value()))&&
   new HashSet<>(fact.subjectRefs()).equals(e.plan.scopes().scopes());
  if(identity&&fact.factType().equals(e.definition.completion().factType())&&fact.factId().value().equals(named("catalogue-completion-v1",e.id.value()))&&
    Objects.equals(fact.payload().get("source"),text("CATALOGUE_SELECTED")))e.completion=fact;
  if(identity&&fact.factType().equals(ABORT)&&fact.factId().value().equals(named("catalogue-aborted-v1",e.id.value()))){
   e.abort=fact;e.abortCut=factCuts.get(fact.factId());
  }
  touch(e,cut);
 }
 private void append(Entry e,List<EventInstance> revisions,List<BlobContent> blobs,boolean initial){
  if(writing!=null)throw new IllegalStateException("one EVENT metadata writer");
  var rows=revisions.stream().map(v->(DurableRecord)new DurableAggregate(new EventAggregate(v))).toList();
  var batch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,blobs);
  writing=new Pending(e,batch,store.append(batch).toCompletableFuture(),revisions.getLast(),initial);
 }
 private boolean finishWrite(){
  if(writing==null||!writing.ack().isDone())return false;var p=writing;
  if(p.ack().isCompletedExceptionally())throw new IllegalStateException("EVENT metadata append failed");
  var ack=p.ack().getNow(null);
  var ackState=MetadataAppendAcknowledgement.classify(p.batch(),ack,readerSession,store.session(),store.readOnlySafe());
  if(ackState==MetadataAppendAcknowledgement.State.RETRY_SAME_BATCH){
   writing=new Pending(p.entry(),p.batch(),store.append(p.batch()).toCompletableFuture(),p.last(),p.initial());return false;
  }
  if(ackState!=MetadataAppendAcknowledgement.State.DURABLE)
   throw new IllegalStateException("EVENT metadata exact J04 ACK unavailable status="+(ack==null?"null":ack.ackStatus())+
    " transaction="+p.batch().transactionId()+" event="+p.entry().id+" target="+p.last().state()+" revision="+p.last().header().revision());
  var e=p.entry();if(e.committed==null||p.last().header().revision().compareTo(e.committed.header().revision())>=0)e.committed=p.last();
  if(p.initial())e.initialAck=true;writing=null;return true;
 }
 private void hydrate(Entry e){
  // The shared writer has bounded admission. Fan-out across retained EVENTs must
  // not turn healthy queue pressure into missing/corrupt-context certification.
  if(hydrating!=null&&hydrating!=e)return;
  if(store.readOnlySafe()||!readerSession.equals(store.session()))
   throw hydrationFailure(e,"writer/session unavailable");
  if(e.hydration==null){
   hydrating=e;
   e.hydration=store.readBlobs(new BlobReadRequest(world,readerSession.storeGeneration(),Set.of(e.committed.contextRef()))).toCompletableFuture();
   return;
  }
  if(!e.hydration.isDone())return;
  if(e.hydration.isCompletedExceptionally())throw hydrationFailure(e,"read failed");
  var loaded=e.hydration.getNow(null);
  if(store.readOnlySafe()||!readerSession.equals(store.session()))
   throw hydrationFailure(e,"writer/session lost during read");
  if(loaded!=null&&loaded.status()==AckStatus.UNAVAILABLE&&loaded.errors().isEmpty()&&loaded.contents().isEmpty()){
   // A missing/corrupt blob latches read-only and revokes the session in J04.
   // Retry only healthy bounded-admission pressure, one request on a later poll.
   e.hydration=null;return;
  }
  if(loaded==null||loaded.status()!=AckStatus.DURABLE||!loaded.errors().isEmpty()||
    loaded.contents().size()!=1||!loaded.contents().getFirst().reference().equals(e.committed.contextRef()))
   throw hydrationFailure(e,"bytes unavailable/status="+(loaded==null?"null":loaded.status()));
  e.context=loaded.contents().getFirst();var data=((JsonValue.ObjectValue)CanonicalJsonV1.decode(e.context.bytes())).values();
  if(!value(data,"source").equals("ACTUAL_AUDITED_CATALOGUE_SELECTION")||!value(data,"world").equals(world.value().toString()))throw new IllegalArgumentException("foreign EVENT context");
  e.definition=DEF.decode(decode(data,"definition"));e.plan=PLAN.decode(decode(data,"plan"));e.snapshot=SNAPSHOT.decode(decode(data,"snapshot"));
  var ref=new DefinitionRef(e.definition.id(),e.definition.contentRevision(),BlobContent.hash(DEF.encode(e.definition)));
  if(!ref.equals(e.committed.definition())||!e.plan.definition().equals(ref)||!e.plan.roles().equals(e.committed.roleAssignments())||
    !e.plan.scopes().worldId().equals(world)||e.plan.effects().stream().anyMatch(i->!i.instanceId().equals(e.id))||
    Long.parseLong(value(data,"catalog_generation"))!=e.committed.catalogGeneration().value())throw new IllegalArgumentException("actual EVENT context identity drift");
  new CatalogueEventLedger.Selection(e.definition,e.plan); // Exact unchanged original step/operation binding.
  if(!e.snapshot.scopes().worldId().equals(world))throw new IllegalArgumentException("foreign actual EVENT snapshot");
  e.requirements.addAll(e.committed.requirementIds());e.hydration=null;hydrating=null;
  // A validated original fact may have arrived while hydration was pending.
  for(var fact:List.copyOf(seenFacts.values()))if(fact.eventId().equals(Optional.of(e.id)))fact(fact,factCuts.getOrDefault(fact.factId(),e.sourceCut));
 }
 private IllegalStateException hydrationFailure(Entry e,String detail){
  return new IllegalStateException("actual EVENT context "+detail+" event="+e.id.value()+" hash="+e.committed.contextRef().hash().value());
 }
 private boolean contextsPending(){
  return entries.values().stream().anyMatch(e->e.committed!=null&&e.definition==null);
 }
 private String originalStep(Entry e,OperationToken op){
  for(int i=0;i<e.plan.effects().size();i++)if(e.plan.effects().get(i).operationToken().equals(op))return e.definition.steps().get(i).id();return null;
 }
 /** Actual old perception cleanup closes only as PARTIAL, retaining successful-send
  * evidence or uncertainty. No original completion, absence, receipt or replay is inferred. */
 private boolean perceptionRecoveryClosed(Entry e){
  if(recovering||e.perceptionEnd==null||e.perceptionEndCut==null||coveredThrough.compareTo(e.perceptionEndCut)<0||
    e.prepares.isEmpty()||!e.mutations.isEmpty()||!e.committed.mutationRefs().isEmpty()||e.disaster!=null||
    e.plan.effects().stream().anyMatch(i->!(i.typedArgs() instanceof TargetSound||i.typedArgs() instanceof Apparition||i.typedArgs() instanceof LocalWeather)))
   return false;
  for(var prepare:e.prepares.values()){
   var intent=e.plan.effects().stream().filter(i->i.operationToken().equals(prepare.operationToken())).findFirst().orElse(null);
   var result=e.results.get(prepare.operationToken());
   if(intent==null||!prepare.reservation().instanceId().equals(e.id)||!prepare.slice().operations().equals(List.of(intent))||
     result==null||!result.prepareTransactionId().equals(prepare.transactionId())||!result.operationToken().equals(prepare.operationToken())||
     !result.mutations().isEmpty()||!result.result().observedHashes().isEmpty())return false;
   var actual=result.result();
   boolean uncertain=actual.outcome()==Outcome.DELIVERY_UNCERTAIN&&actual.appliedOperationIds().isEmpty();
   boolean sent=actual.outcome()==Outcome.SUCCEEDED&&actual.appliedOperationIds().equals(Set.of(prepare.operationToken()))
      &&actual.remainingWork()==0&&actual.conflictCells().isEmpty();
   if(!uncertain&&!sent)return false;
  }
  return true;
 }
 private List<EventInstance> progress(Entry e){
  var base=e.committed;
   // Journal tail/revocation and completion observer delivery still run first.
   // Owner touches invalidate even late facts that do not change any map size.
   // An equal but distinct immutable head/definition/plan/disaster recomputes.
   if(terminal(base.state())&&e.terminalProgressMemo.unchanged(base,e.definition,e.plan,e.disaster))return List.of();
   var steps=new TreeMap<>(base.stepStates());var reservations=new HashSet<>(base.reservationRefs());
  reservations.addAll(e.uniqueReservations);
  var effects=new HashSet<>(base.effectRefs());var mutations=new HashSet<>(base.mutationRefs());mutations.addAll(e.mutations);
  boolean applied=false,uncertain=false,unmatched=false,executed=false;
  for(var p:e.prepares.values()){
   reservations.add(p.reservation().id());String step=originalStep(e,p.operationToken());if(step!=null&&steps.get(step)==StepState.WAITING)steps.put(step,StepState.PREPARED);
   var r=e.results.get(p.operationToken());if(r==null){unmatched=true;continue;}
   boolean wrote=!r.result().appliedOperationIds().isEmpty()||!r.result().observedHashes().isEmpty();applied|=wrote;
   uncertain|=r.result().outcome()==Outcome.DELIVERY_UNCERTAIN;executed|=r.result().outcome()!=Outcome.ABORTED_SAFE||wrote;
   if(step==null)continue;
   var args=p.slice().operations().getFirst().typedArgs();boolean perception=args instanceof TargetSound||args instanceof Apparition||args instanceof LocalWeather||args instanceof Warning||args instanceof SharedChoice;
   if(perception&&r.result().appliedOperationIds().contains(p.operationToken()))effects.add(new EffectId(p.operationToken().value()));
   if(wrote){
    var expected=new HashMap<ResourceKey,Fingerprint>();p.durableBlobs().expectedPostStates().forEach((k,v)->expected.put(k,v.hash()));
    boolean exact=(r.result().outcome()==Outcome.SUCCEEDED||r.result().outcome()==Outcome.RECONCILED)&&!perception&&r.result().observedHashes().equals(expected);
    if(steps.get(step)!=StepState.CONFIRMED)steps.put(step,exact?StepState.CONFIRMED:StepState.APPLIED);
   }else if(r.result().outcome()==Outcome.ABORTED_SAFE&&steps.get(step)!=StepState.CONFIRMED)steps.put(step,StepState.SKIPPED_SAFE);
   else if(!r.result().conflictCells().isEmpty()&&steps.get(step)!=StepState.CONFIRMED)steps.put(step,StepState.CONFLICT);
  }
  if(e.disaster!=null&&e.disaster.recipeId().equals(e.definition.id())){
   var warning=e.disaster.warningEvidence();var audience=new HashSet<PlayerId>();e.plan.roles().roles().values().forEach(audience::addAll);
   for(var step:e.definition.steps())if(step.args() instanceof Warning args&&warning.readable()&&warning.persistentPerimeter()&&
     warning.audience().containsAll(audience)&&warning.leadTime().compareTo(args.minimumLeadTicks())>=0&&
     !warning.proofHash().equals(BlobContent.hash(new byte[0])))steps.put(step.id(),StepState.CONFIRMED);
  }
  var path=new ArrayList<EventState>();var state=base.state();Optional<Outcome> outcome=base.outcome();String phase=base.phase();
  if(!terminal(state)){
   if(state==EventState.PROPOSED&&!e.uniqueReservations.isEmpty()){
    path.add(EventState.RESERVED);state=EventState.RESERVED;phase=phase("ACTUAL_UNIQUE_RESERVATION_DURABLE");
   }
   if(!e.prepares.isEmpty()){
    if(state==EventState.PROPOSED){path.add(EventState.RESERVED);state=EventState.RESERVED;}
    if(state==EventState.RESERVED){path.add(EventState.PREPARING);state=EventState.PREPARING;}
    if(state==EventState.PREPARING){path.add(EventState.READY);state=EventState.READY;}
    phase=phase("ACTUAL_NATIVE_PREPARE_DURABLE");
   }
   if(executed&&state==EventState.RECOVERING){path.add(EventState.READY);state=EventState.READY;}
   if(executed&&state==EventState.READY){
    path.add(EventState.RUNNING);state=EventState.RUNNING;phase=phase("ACTUAL_NATIVE_RESULT_DURABLE");
   }
   if(uncertain)phase=phase("ACTUAL_DELIVERY_UNCERTAIN_RECOVERY_PENDING");
   if(e.completion!=null){
    // The original trusted provider completion is positive causal proof of the
    // completed lifecycle. Catch up VALID state arcs without inventing IDs or
    // PREPARE/RESULT records when F9 reached us before the chronological reader.
    if(state==EventState.PROPOSED){path.add(EventState.RESERVED);state=EventState.RESERVED;}
    if(state==EventState.RESERVED){path.add(EventState.PREPARING);state=EventState.PREPARING;}
    if(state==EventState.PREPARING||state==EventState.RECOVERING){path.add(EventState.READY);state=EventState.READY;}
    if(state==EventState.READY){path.add(EventState.RUNNING);state=EventState.RUNNING;}
    if(state==EventState.RUNNING){path.add(EventState.RESOLVING);state=EventState.RESOLVING;}
    path.add(EventState.SUCCEEDED);state=EventState.SUCCEEDED;outcome=Optional.of(Outcome.SUCCEEDED);
    steps.replaceAll((id,value)->StepState.CONFIRMED);phase=phase("ORIGINAL_CONFIRMED_COMPLETION_DURABLE");
   }else if(e.abort!=null){
    boolean historicalPossible=!base.effectRefs().isEmpty()||base.stepStates().values().stream().anyMatch(v->v==StepState.APPLIED||v==StepState.CONFIRMED);
    applied|=historicalPossible;
    boolean historyCovered=!recovering&&e.abortCut!=null&&coveredThrough.compareTo(e.abortCut)>=0;
    if(unmatched){phase=phase("ORIGINAL_ABORT_NATIVE_PREPARE_UNRESOLVED");}
    else if(!historyCovered&&!applied&&!uncertain){phase=phase("ABORT_AWAITS_ACTUAL_CHRONOLOGY_ABSENCE_PROOF");}
    else{
     if(state==EventState.RUNNING){path.add(EventState.RESOLVING);state=EventState.RESOLVING;}
     var terminal=applied||uncertain?EventState.PARTIAL:EventState.ABORTED_SAFE;path.add(terminal);state=terminal;
     outcome=Optional.of(uncertain&&!applied?Outcome.DELIVERY_UNCERTAIN:applied?Outcome.PARTIAL:Outcome.ABORTED_SAFE);
     phase=applied||uncertain?phase("ACTUAL_ABORT_AFTER_POSSIBLE_OR_PARTIAL_EFFECT"):phase("ACTUAL_ABORT_NO_APPLIED_EFFECT");
    }
   }else if(perceptionRecoveryClosed(e)){
    if(state==EventState.RUNNING){path.add(EventState.RESOLVING);state=EventState.RESOLVING;}
    if(state==EventState.RESOLVING||state==EventState.CLEANING){
     path.add(EventState.PARTIAL);state=EventState.PARTIAL;outcome=Optional.of(applied?Outcome.PARTIAL:Outcome.DELIVERY_UNCERTAIN);
     phase=applied?phase("PERCEPTION_RESTART_CLEANUP_PARTIAL_EFFECT"):phase("PERCEPTION_RESTART_CLEANUP_DELIVERY_UNCERTAIN");
    }
   }
  }
  var refsChanged=!steps.equals(base.stepStates())||!reservations.equals(base.reservationRefs())||!effects.equals(base.effectRefs())||
   !mutations.equals(base.mutationRefs())||!e.requirements.equals(base.requirementIds())||!phase.equals(base.phase())||!outcome.equals(base.outcome());
   if(path.isEmpty()&&!refsChanged){
    if(terminal(base.state()))e.terminalProgressMemo.reconciled(base,e.definition,e.plan,e.disaster);
    return List.of();
   }
   if(path.isEmpty())path.add(state);
  var result=new ArrayList<EventInstance>();var header=base.header();var at=now();
  if(at.compareTo(header.updatedTick())<0)throw new IllegalStateException("EVENT metadata actor clock regressed");
  for(int i=0;i<path.size();i++){
   header=new AggregateHeader(1,header.id(),header.revision().plus(1),header.lastSequence(),at);
   var last=i==path.size()-1;result.add(new EventInstance(header,base.instanceId(),base.definition(),base.catalogGeneration(),path.get(i),
    last?phase:e.completion!=null?phase("ACTUAL_ORIGINAL_COMPLETION_CAUSAL_CATCHUP"):phase("DURABLE_PREPARE_LIFECYCLE_CATCHUP"),base.roleAssignments(),base.regionIds(),base.contextRef(),steps,reservations,effects,mutations,
    base.chainId(),e.requirements,base.startedTick(),base.elapsedActiveTicks(),base.maxDurationTicks(),base.recoveryPolicy(),last?outcome:Optional.empty()));
  }
  return List.copyOf(result);
 }
 /** Own bounded async recovery/tail. An EMPTY point read or F9 batch is NEVER
  * an absence proof. Only completing every page from sequence zero, then every
  * contiguous tail interval, advances coveredThrough for abort classification. */
 private void readActualJournal(){
  if(!store.session().equals(readerSession))throw new IllegalStateException("EVENT metadata reader session revoked");
  if(scanRead==null){
   if(scanQuery==null)scanQuery=new StoreQuery(world,Set.of(),new Sequence(coveredThrough.value()==0?0:Math.addExact(coveredThrough.value(),1)),Optional.empty(),128);
   scanRead=store.read(scanQuery).toCompletableFuture();return;
  }
  if(!scanRead.isDone())return;if(scanRead.isCompletedExceptionally())throw new IllegalStateException("EVENT metadata actual chronology read failed");
  var result=scanRead.getNow(null);ReadContracts.validate(result,scanQuery,readerSession);
  if(result.status()==ReadStatus.STALE){scanRead=null;scanQuery=null;return;}
  // A tail at coveredThrough+1 can precede the next real COMMIT.
  // UNKNOWN/future interval is WAIT, never an empty proven page or new coverage.
  if(result.status()==ReadStatus.NOT_RESIDENT){scanRead=null;return;}
  if(result.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("EVENT metadata chronology unavailable "+result.status());
  accept(scanQuery,result);var page=result.page().orElseThrow();
  if(page.hasMore())scanQuery=new StoreQuery(world,Set.of(),scanQuery.sequence(),page.nextCursor(),128);
  else{
   var cut=result.stamp().orElseThrow().snapshotSequence();if(cut.compareTo(coveredThrough)>0)coveredThrough=cut;
   recovering=false;scanQuery=null;
  }
  scanRead=null;
 }
 /** Poll before new native admissions. Reads actual J04 and publishes metadata;
  * never calls any world/effect operation, driver, or native replay. */
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("EVENT metadata J04 writer lost");
   readActualJournal();
   if(writing!=null){finishWrite();if(writing!=null)return;}
   if(recovering)return;
   for(var e:List.copyOf(entries.values())){
    if(e.committed==null)continue;
    if(e.definition==null){hydrate(e);continue;}
    if(!e.publishable)continue;
    // Notify observers ONLY after an actual confirmed durable J04 fact passed
    // the chronology validation. Director remains autonomous if a consumer fails.
    if(e.completion!=null && !forwardedCompletionFacts.contains(e.completion.factId())){
     try{
      net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
       new fr.proutlost.director.integration.quest.DirectorCommittedFactEvent(server,e.completion));
      forwardedCompletionFacts.add(e.completion.factId());
     }catch(RuntimeException rejected){
      System.err.println("DIRECTOR_QUEST_FACT_OBSERVER_FAILED_SAFE "+rejected.getClass().getSimpleName());
     }
    }
    var revisions=progress(e);if(revisions.isEmpty())continue;
    append(e,List.of(revisions.getFirst()),List.of(),false);return;
   }
  }catch(RuntimeException stopped){failed=true;failure=stopped.toString();System.out.println((mode==ProviderMode.PRODUCTION?"DIRECTOR_RUNTIME_EVENT_METADATA_FAILED_SAFE reason=":"DIRECTOR_LAB_EVENT_METADATA_FAILED_SAFE reason=")+failure+" physical_replay=false");}
 }
 private void retireTerminalEntries(){
  if(entries.size()<64)return;
  entries.entrySet().removeIf(row->row.getValue().committed!=null&&terminal(row.getValue().committed.state())&&
   (writing==null||writing.entry()!=row.getValue())&&hydrating!=row.getValue()&&store.physicalEventReadback(row.getKey(),4096).filter(PhysicalEventReadback::released).isPresent());
  preparedByTransaction.entrySet().removeIf(row->!entries.containsKey(row.getValue().id));
 }
 /** Complete native EVENT data at one verified journal cut. This does not
  * enable the TEST_ONLY unjoined-ID projection or infer claims from a DTO. */
 public Optional<AdminRow> inspect(EventInstance head,Sequence cut,List<ResourceClaim> liveClaims){
  actor();if((mode!=ProviderMode.PRODUCTION&&declaredQa==null)||failed||recovering()||!coveredThrough.equals(cut))return Optional.empty();
  var entry=entries.get(head.instanceId());
  if(entry==null||entry.committed==null||!entry.committed.equals(head)||!entry.publishable||entry.definition==null
   ||entry.definition.calibration()!=(declaredQa==null?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING)||!entry.definition.id().equals(head.definition().id())
   ||declaredQa!=null&&!entry.definition.equals(declaredQa.catalog().events().get(entry.definition.id())))return Optional.empty();
  var mutations=fr.proutlost.director.admin.server.AdminActiveWalProjection.mutationsForEvent(store,head.instanceId(),head.mutationRefs(),world,cut);
  if(mutations.isEmpty())return Optional.empty();
  var facts=seenFacts.values().stream().filter(f->f.eventId().filter(head.instanceId()::equals).isPresent())
   .sorted(Comparator.comparing(f->f.factId().value())).toList();
  return Optional.of(new AdminRow(new ScopeRef(ScopeKind.EVENT,head.instanceId().value()),head.header().revision(),head.header().updatedTick(),
   new EventAdminData(head,mutations.get(),List.copyOf(liveClaims),facts)));
 }
 public boolean recovering(){actor();return recovering||contextsPending();}
 /** Qualification closes its private WAL only after the actual terminal EVENT
  * head and all queued metadata writes are settled. Never an apply authority. */
 public boolean publicGroupQualificationSettled(EventInstanceId id){
  actor();if(!publicGroupQualification)throw new IllegalStateException("public GROUP QA lifecycle required");
  DirectorPublicGroupQaFence.require(server);return qualificationSettled(id);
 }
 public boolean realGroupClientsQualificationSettled(EventInstanceId id){
  actor();if(!realGroupClientsQualification)throw new IllegalStateException("real-client GROUP QA lifecycle required");
  DirectorRealGroupClientsQaFence.require(server);return qualificationSettled(id);
 }
 private boolean qualificationSettled(EventInstanceId id){
  var e=entries.get(id);
  return !failed&&!recovering()&&writing==null&&hydrating==null&&e!=null&&e.committed!=null
   &&e.committed.state()==EventState.SUCCEEDED&&e.completion!=null&&e.publishable;
 }
 public boolean failed(){actor();return failed;}
 public String status(){actor();return (mode==ProviderMode.PRODUCTION?"EVENT_METADATA_PRODUCTION recovering=":"EVENT_METADATA_TEST_ONLY recovering=")+recovering()+" covered_cut="+coveredThrough.value()+" entries="+entries.size()+" pending="+(writing!=null)+" hydrating="+(hydrating!=null)+" failed="+failed+" production_approved="+(mode==ProviderMode.PRODUCTION)+" "+failure;}
}
