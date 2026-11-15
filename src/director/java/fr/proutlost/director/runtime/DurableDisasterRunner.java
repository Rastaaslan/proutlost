package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** same phase/ACK fences, plus a durable post-WARN proof update.
 * No method issues physical authority. The native bridge must derive receipts
 * from the live J06 transport, never from a client-supplied readable boolean. */
public final class DurableDisasterRunner {
 public enum Status { PENDING, COMMITTED, RECOVERY_REQUIRED }
 public record Transition(Status status,DisasterInstance state){}
 private record Delivery(CommitAck ack,Throwable failure){}
 private record Pending(DisasterInstance before,DisasterInstance after,DurableBatch batch,
   StoreSession session,Optional<Tick> warnEntry,boolean warningPublication,CompletableFuture<Delivery> delivery){}
 public static final FactType WARN_ENTRY=new FactType("proutlost_director:disaster_warn_entered");
 public static final FactType WARN_PROOF=new FactType("proutlost_director:disaster_warning_evidence");
 private static final Set<String> MARKERS=Set.of("NW","NE","SE","SW");
 private static final ModelCodec<PerceptionHeader> HEADER=new ModelCodec<>(PerceptionHeader.class);
 private static final ModelCodec<PerceptionTransform> TRANSFORM=new ModelCodec<>(PerceptionTransform.class);
 private final Thread actor=Thread.currentThread();
 private final DurableStore store;private final int capacity;
 private final java.util.function.Function<DisasterInstance,ScopeRef> revisionScope;
 private final Map<DisasterId,DisasterInstance> states=new HashMap<>();
 private final Map<DisasterId,Sequence> lastAcceptedSequences=new HashMap<>();
 private final Map<DisasterId,Tick> warnEnteredAt=new HashMap<>();
 private final Map<DisasterId,StoreSession> warningSessions=new HashMap<>();
 private final Map<DisasterId,Pending> pending=new HashMap<>();
 private final Set<DisasterId> uncertain=new HashSet<>();
 public DurableDisasterRunner(DurableStore store,int capacity,
   java.util.function.Function<DisasterInstance,ScopeRef> revisionScope){
  this.revisionScope=Objects.requireNonNull(revisionScope);this.store=Objects.requireNonNull(store);
  if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
 }
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
 private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
 /** Legacy restore is fail-closed for WARN: no entry tick is guessed from an updated proof header. */
 public void restore(DisasterInstance state){restoreState(state,Optional.empty());}
 /** Bootstrap supplies the latest WARN-entry tick from its verified chronological WAL scan. */
 public void restore(DisasterInstance state,Tick provenWarnEntry){
  require(provenWarnEntry.compareTo(state.header().updatedTick())<=0,"future WARN entry");
  restoreState(state,Optional.of(provenWarnEntry));
 }
 private void restoreState(DisasterInstance state,Optional<Tick> entry){
  actor();if(states.size()>=capacity||states.putIfAbsent(state.disasterId(),state)!=null)
   throw new IllegalArgumentException("disaster capacity/duplicate");
  lastAcceptedSequences.put(state.disasterId(),state.header().lastSequence());
  entry.ifPresent(t->warnEnteredAt.put(state.disasterId(),t));
 }
 public DisasterInstance view(DisasterId id){actor();return Objects.requireNonNull(states.get(id));}
 public Optional<Tick> warnEnteredAt(DisasterId id){actor();view(id);return Optional.ofNullable(warnEnteredAt.get(id));}
 private void writable(DisasterId id){
  if(pending.containsKey(id)||uncertain.contains(id))throw new IllegalStateException("phase pending or uncertain");
 }
 private DirectorFact fact(FactType type,DisasterInstance state,Map<String,JsonValue> payload){
  return new DirectorFact(new FactId(UUID.randomUUID()),type,
   List.of(new ScopeRef(ScopeKind.EVENT,state.eventInstanceId().value())),
   Optional.of(state.eventInstanceId()),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static String text(Map<String,JsonValue> values,String key){return ((JsonValue.Text)values.get(key)).value();}
 private static long counter(Map<String,JsonValue> values,String key){return Long.parseLong(text(values,key));}
 private static Map<String,JsonValue> object(JsonValue value){return ((JsonValue.ObjectValue)value).values();}
 private static DisasterInstance with(DisasterInstance before,DisasterStage stage,WarningEvidence evidence,
   Set<PlayerId> audience,Tick now){
  var h=before.header();return new DisasterInstance(new AggregateHeader(h.schemaVersion(),h.id(),
   h.revision().plus(1),h.lastSequence(),now),before.disasterId(),before.eventInstanceId(),
   before.recipeId(),stage,before.riskFactors(),before.riskScore(),before.envelope(),evidence,
   audience,before.evacuationRoutes(),before.propagationMaskRef(),before.mutationRefs(),
   before.stabilizationState(),before.durableConsequenceRefs());
 }
 public void propose(DisasterId id,TransactionId transaction,UnitValue threshold,
   DisasterRunner.Conditions conditions){
  actor();var before=view(id);writable(id);
  var session=Objects.requireNonNull(store.session(),"disaster store session");
  require(conditions.now().compareTo(before.header().updatedTick())>=0,"nonmonotonic tick");
  var stage=DisasterRunner.next(before.stage(),before.riskScore(),threshold,before.warningEvidence(),conditions);
  if(before.stage()==DisasterStage.WARN&&stage==DisasterStage.IMPACT){
   if(!session.equals(warningSessions.get(id)))throw new IllegalStateException("current-boot native warning proof missing");
   var entry=Objects.requireNonNull(warnEnteredAt.get(id),"proven WARN entry missing");
   if(!DisasterRunner.warningValidSince(before.warningEvidence(),conditions,entry))
    throw new IllegalStateException("WARN evidence predates durable WARN entry; block IMPACT");
  }
  require(stage!=before.stage(),"no phase transition");
  var after=with(before,stage,before.warningEvidence(),conditions.audience(),conditions.now());
  var entry=stage==DisasterStage.WARN?Optional.of(conditions.now()):Optional.ofNullable(warnEnteredAt.get(id));
  var facts=new ArrayList<DurableRecord>();
  if(stage==DisasterStage.WARN)facts.add(new DurableFact(fact(WARN_ENTRY,after,Map.of(
   "disaster_id",text(id.value()),"entered_tick",text(conditions.now().value()),
   "warn_revision",text(after.header().revision().value())))));
  queue(before,after,transaction,entry,facts,session,false);
 }
 /** Same-WARN revision: preserves its original entry tick and records the full proof inline.
  * receiptProof is canonical JSON generated by the trusted native bridge after checking
  * exact emitted headers with qualifiedRecipient + transport.received + live marker TTL.
  * Its header/marker/time bindings are checked again here. */
 public void publishWarning(DisasterId id,TransactionId transaction,WarningEvidence evidence,
   byte[] receiptProof,Tick now){
  actor();var before=view(id);writable(id);
  require(before.stage()==DisasterStage.WARN,"warning update outside WARN");
  var entry=Objects.requireNonNull(warnEnteredAt.get(id),"proven WARN entry missing");
  require(now.compareTo(before.header().updatedTick())>=0,"nonmonotonic warning tick");
  require(evidence.readable()&&evidence.persistentPerimeter()&&!evidence.audience().isEmpty(),"unreadable/incomplete perimeter");
  require(evidence.audience().containsAll(before.affectedAudience()),"warning dropped affected players");
  require(evidence.deployedTick().compareTo(entry)>0&&evidence.deployedTick().compareTo(now)<=0,"warning not deployed after WARN entry");
  require(evidence.leadTime().value()>0&&evidence.leadTime().compareTo(before.warningEvidence().leadTime())>=0,"warning lead reduced");
  require(evidence.audienceRevision().compareTo(before.warningEvidence().audienceRevision())>=0,"audience revision regressed");
  if(!evidence.audience().equals(before.warningEvidence().audience()))
   require(evidence.audienceRevision().compareTo(before.warningEvidence().audienceRevision())>0,"changed audience needs new revision");
  require(receiptProof!=null&&receiptProof.length>0&&receiptProof.length<=65536,"warning proof bound");
  require(BlobContent.hash(receiptProof).equals(evidence.proofHash()),"warning proof hash");
  require(Arrays.equals(CanonicalJsonV1.encode(CanonicalJsonV1.decode(receiptProof)),receiptProof),"noncanonical warning proof");
  var session=Objects.requireNonNull(store.session(),"warning store session");
  verifyReceiptProof(before,entry,evidence,receiptProof,session,now);
  var after=with(before,DisasterStage.WARN,evidence,evidence.audience(),now);
  var proof=new DurableFact(fact(WARN_PROOF,after,Map.of(
   "disaster_id",text(id.value()),"warn_entered_tick",text(entry.value()),
   "proof_base64",text(Base64.getEncoder().encodeToString(receiptProof)),
   "warning_evidence_base64",text(Base64.getEncoder().encodeToString(new ModelCodec<>(WarningEvidence.class).encode(evidence))))));
  queue(before,after,transaction,Optional.of(entry),List.of(proof),session,true);
 }
 private void verifyReceiptProof(DisasterInstance state,Tick entry,WarningEvidence evidence,
   byte[] bytes,StoreSession session,Tick now){
  var root=object(CanonicalJsonV1.decode(bytes));
  require(text(root,"schema").equals("1")&&text(root,"world").equals(session.worldId().value().toString())
   &&counter(root,"boot")==session.bootEpoch().value(),"warning proof world/boot");
  require(text(root,"disaster").equals(state.disasterId().value().toString())
   &&text(root,"event").equals(state.eventInstanceId().value().toString()),"warning proof lineage");
  require(counter(root,"warn_entered")==entry.value()&&counter(root,"deployed")==evidence.deployedTick().value()
   &&counter(root,"lead")==evidence.leadTime().value()&&counter(root,"audience_revision")==evidence.audienceRevision().value(),"warning proof timing");
  var envelopeHash=BlobContent.hash(new ModelCodec<>(Footprint.class).encode(state.envelope()));
  require(text(root,"envelope_hash").equals(envelopeHash.value()),"warning proof envelope");
  require(state.envelope().bounds().size()==1,"first LAB bridge needs one rectangular perimeter");
  var bounds=state.envelope().bounds().getFirst();
  var receipts=((JsonValue.Array)root.get("receipts")).values();
  require(!receipts.isEmpty()&&receipts.size()<=64,"warning receipts bound");
  var markers=new HashMap<PlayerId,Set<String>>();var effects=new HashMap<PlayerId,Set<EffectId>>();
  var connections=new HashMap<PlayerId,Epoch>();
  for(var encoded:receipts){
   var receipt=object(encoded);var player=new PlayerId(UUID.fromString(text(receipt,"player")));
   var marker=text(receipt,"marker");require(MARKERS.contains(marker),"unknown perimeter marker");
   require(((JsonValue.Bool)receipt.get("received")).value(),"renderer receipt missing");
   var header=HEADER.decode(Base64.getDecoder().decode(text(receipt,"header")));
   var transform=TRANSFORM.decode(Base64.getDecoder().decode(text(receipt,"transform")));
   require(header.worldId().equals(session.worldId())&&header.bootEpoch().equals(session.bootEpoch())
    &&header.dimension().equals(state.envelope().dimension())&&header.instanceId().equals(state.eventInstanceId()),"foreign warning header");
   var previous=connections.putIfAbsent(player,header.connectionEpoch());
   require(previous==null||previous.equals(header.connectionEpoch()),"mixed client sessions");
   long sent=counter(receipt,"sent_tick");require(sent>entry.value()&&sent<=evidence.deployedTick().value(),"stale warning send");
   require(Math.addExact(sent,header.remainingTtlTicks().value())>Math.addExact(now.value(),evidence.leadTime().value()),"perimeter expires before lead");
   boolean east=marker.equals("NE")||marker.equals("SE"),south=marker.equals("SW")||marker.equals("SE");
   double x=east?bounds.maxXExclusive()-.25:bounds.minX()+.25;
   double z=south?bounds.maxZExclusive()-.25:bounds.minZ()+.25;
   require(Math.abs(transform.x()-x)<1e-6&&Math.abs(transform.z()-z)<1e-6
    &&Math.abs(transform.y()-(bounds.minY()+.8))<1e-6,"perimeter marker outside its sealed corner");
   require(markers.computeIfAbsent(player,p->new HashSet<>()).add(marker),"duplicate marker");
   require(effects.computeIfAbsent(player,p->new HashSet<>()).add(header.effectId()),"shared marker effect ID");
  }
  require(markers.keySet().equals(evidence.audience()),"warning audience proof incomplete");
  for(var found:markers.values())require(found.equals(MARKERS),"perimeter receipt incomplete");
 }
 private void queue(DisasterInstance before,DisasterInstance after,TransactionId transaction,
   Optional<Tick> entry,List<DurableRecord> additional,StoreSession session,boolean warningPublication){
  var key=Objects.requireNonNull(revisionScope.apply(before));var rows=new ArrayList<DurableRecord>();
  rows.add(new DurableAggregate(new DisasterAggregate(after)));rows.addAll(additional);
  var batch=ResultTransaction.batch(transaction,session.storeGeneration(),
   Map.of(key,before.header().revision()),rows,List.of());
  var mailbox=new CompletableFuture<Delivery>();
  pending.put(before.disasterId(),new Pending(before,after,batch,session,entry,warningPublication,mailbox));
  try{store.append(batch).whenComplete((ack,error)->mailbox.complete(new Delivery(ack,error)));}
  catch(RuntimeException failure){mailbox.complete(new Delivery(null,failure));}
 }
 public Transition poll(DisasterId id){
  actor();if(uncertain.contains(id))return new Transition(Status.RECOVERY_REQUIRED,view(id));
  var p=pending.get(id);if(p==null)return new Transition(Status.COMMITTED,view(id));
  var d=p.delivery().getNow(null);if(d==null)return new Transition(Status.PENDING,p.before());
  pending.remove(id);var ack=d.ack();StoreSession currentSession;
  try{currentSession=Objects.requireNonNull(store.session(),"live J04 session");}
  catch(RuntimeException unavailable){uncertain.add(id);return new Transition(Status.RECOVERY_REQUIRED,p.before());}
  if(d.failure()!=null||ack==null||ack.ackStatus()!=AckStatus.DURABLE||!currentSession.equals(p.session())
   ||!ack.transactionId().equals(p.batch().transactionId())||!ack.transactionHash().equals(p.batch().transactionHash())
   ||!ack.storeGeneration().equals(p.session().storeGeneration())
   ||ack.durableSequence().compareTo(Objects.requireNonNull(lastAcceptedSequences.get(id),"disaster durable sequence"))<=0
   ||ack.permitAttempt().isPresent()||!ack.forcedBlobs().equals(DurableBlobReferences.collect(p.batch().records()))){
   uncertain.add(id);return new Transition(Status.RECOVERY_REQUIRED,p.before());
  }
  lastAcceptedSequences.put(id,ack.durableSequence());states.put(id,p.after());
  p.warnEntry().ifPresent(t->warnEnteredAt.put(id,t));
  if(p.warningPublication())warningSessions.put(id,p.session());
  else if(p.after().stage()==DisasterStage.WARN&&p.before().stage()!=DisasterStage.WARN)warningSessions.remove(id);
  return new Transition(Status.COMMITTED,p.after());
 }
 /** References only actual current same-event mutation rows. Immutable consequences are
  * re-included in this transaction, so a published FactId can never be orphaned.
  * The native bridge must construct them from the real physical RESULT/readback. */
 public void publishPhysicsReferences(DisasterId id,TransactionId transaction,
   Collection<MutationRecord> mutations,List<DirectorFact> consequences,String stabilization,Tick now){
  actor();var before=view(id);writable(id);
  require(now.compareTo(before.header().updatedTick())>=0&&!stabilization.isBlank(),"physics time/status");
  require(mutations.size()<=16&&consequences.size()<=32,"physics publication bound");
  var ids=new HashSet<MutationId>();var keys=new HashSet<AggregateKey>();
  for(var mutation:mutations){
   require(ids.add(mutation.mutationId())&&mutation.header().id().equals(mutation.mutationId().value())
    &&mutation.instanceId().equals(before.eventInstanceId())&&mutation.durableSeq().value()>0,"uncommitted/foreign mutation");
   keys.add(new AggregateKey(AggregateKind.MUTATION,mutation.mutationId().value()));
  }
  require(ids.containsAll(before.mutationRefs()),"physics dropped a mutation");
  var session=Objects.requireNonNull(store.session());
  if(!keys.isEmpty()){
   var query=new StoreQuery(session.worldId(),keys,new Sequence(0),Optional.empty(),keys.size());
   var read=store.view(query);ReadContracts.validate(read,query,session);
   require(read.status()==ReadStatus.AVAILABLE,"physics rows must be hydrated");
   var page=read.page().orElseThrow();require(!page.hasMore()&&page.aggregates().size()==keys.size(),"incomplete physics rows");
   var actual=new HashMap<MutationId,MutationRecord>();
   for(var restored:page.aggregates()){
    require(restored.state() instanceof MutationAggregate,"foreign physics aggregate");
    var value=((MutationAggregate)restored.state()).value();
    require(value.durableSeq().compareTo(restored.committedSequence())<=0,"future physical sequence");
    actual.put(value.mutationId(),value);
   }
   for(var mutation:mutations)require(mutation.equals(actual.get(mutation.mutationId())),"physics row changed");
  }
  var facts=new HashSet<>(before.durableConsequenceRefs());var additional=new ArrayList<DurableRecord>();
  for(var fact:consequences){
   require(fact.durable()&&fact.eventId().equals(Optional.of(before.eventInstanceId()))
     &&fact.evidenceStatus()==EvidenceStatus.CONFIRMED,"foreign/non-durable consequence");
   facts.add(fact.factId());additional.add(new DurableFact(fact));
  }
  var h=before.header();var after=new DisasterInstance(new AggregateHeader(h.schemaVersion(),h.id(),
   h.revision().plus(1),h.lastSequence(),now),before.disasterId(),before.eventInstanceId(),before.recipeId(),
   before.stage(),before.riskFactors(),before.riskScore(),before.envelope(),before.warningEvidence(),
   before.affectedAudience(),before.evacuationRoutes(),before.propagationMaskRef(),ids,stabilization,facts);
  queue(before,after,transaction,Optional.ofNullable(warnEnteredAt.get(id)),additional,session,false);
 }
 /** WATCH cannot reach STABILIZE through a fabricated safe context. After verified no-impact
  * ownership release, cancel its manual request explicitly. No physical authority is issued. */
 public void abortNoImpact(DisasterId id,TransactionId transaction,String reason,Tick now){
  actor();var before=view(id);writable(id);
  require(now.compareTo(before.header().updatedTick())>=0,"nonmonotonic abort tick");
  require(Set.of(DisasterStage.WATCH,DisasterStage.WARN).contains(before.stage())
    &&before.mutationRefs().isEmpty(),"abort after possible impact");
  var after=with(before,DisasterStage.ABORTED_SAFE,before.warningEvidence(),before.affectedAudience(),now);
  queue(before,after,transaction,Optional.ofNullable(warnEnteredAt.get(id)),List.of(new DurableFact(
   fact(new FactType("proutlost_director:disaster_cancelled_before_impact"),after,Map.of("reason",text(reason))))),
   Objects.requireNonNull(store.session()),false);
 }

 public void retire(DisasterId id){
  actor();var state=view(id);if(pending.containsKey(id)||uncertain.contains(id)
   ||!Set.of(DisasterStage.RESOLVED,DisasterStage.ABORTED_SAFE).contains(state.stage()))
   throw new IllegalStateException("disaster still active");
  states.remove(id);lastAcceptedSequences.remove(id);warnEnteredAt.remove(id);warningSessions.remove(id);
 }
}
