package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Optional producer-bound adapter. No Quest Core implementation, world ports or synchronous listeners.
 * Calls are serialized on an isolated executor through durable ACKs; inspect reads immutable projections.
 * Runtime owns admission/cleanup. CANCELLED removes the obligation, never retracts a real world fact.
 */
public final class DurableQuestAdapter implements QuestDirectorPort {
    private static final ResourceId PRODUCER = new ResourceId("proutlost_director:quest_adapter");
    private static final FactType BINDING = new FactType("proutlost_director:request_execution");
    private static final FactType ABORTED = new FactType("proutlost_director:catalogue_event_aborted");
    private static final FactType ENDED = new FactType("proutlost_director:request_execution_ended");
    private static final FactType CANCEL_RECEIPT = new FactType("proutlost_director:request_cancel_receipt");
    private record CancelReceipt(Fingerprint hash, Fingerprint policyHash, RequestAck ack) {}
    private Map<String,CancelReceipt> cancellations = new HashMap<>();
    private final DurableStore store;
    private final FactBusPort bus;
    private final ResourceId authorizedProducer;
    private final Executor executor;
    private final LongSupplier tick;
    private final int queueLimit;
    private final double maximumBias;
    private final ConsumerId inbox;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicReference<CompletableFuture<Void>> tail = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private volatile Map<RequestId, RequestView> requests = Map.of();
    // Access only on serialized executor.
    private Map<String, RequestId> keys = new HashMap<>();
    private Map<RequestId, Set<EventInstanceId>> executions = new HashMap<>();
    private volatile Map<RequestId, Set<EventInstanceId>> publishedExecutions = Map.of();
    private Map<RequestId,Set<EventInstanceId>> endedExecutions=new HashMap<>();
    private Map<MessageId, Fingerprint> received = new HashMap<>();
    private volatile boolean ready;

    public DurableQuestAdapter(DurableStore store, FactBusPort bus, ResourceId authorizedProducer,
            Executor executor, LongSupplier tick, int queueLimit, double maximumBias) {
        this.store=Objects.requireNonNull(store); this.bus=Objects.requireNonNull(bus);
        this.authorizedProducer=Objects.requireNonNull(authorizedProducer); this.executor=Objects.requireNonNull(executor);
        this.tick=Objects.requireNonNull(tick);
        if(queueLimit<1 || !Double.isFinite(maximumBias) || maximumBias<0) throw new IllegalArgumentException("quest policy");
        this.queueLimit=queueLimit; this.maximumBias=maximumBias;
        inbox=new ConsumerId(UUID.nameUUIDFromBytes((store.session().worldId()+":"+authorizedProducer.value()).getBytes(StandardCharsets.UTF_8)));
    }
    /** Restore before exposing the port. Store.view is an immutable memory projection, not filesystem IO. */
    public CompletionStage<Void> restore() { return restoreUsing(store::view); }
    /** Native bridge only: IO waits execute on this adapter\u0027s isolated executor, never Minecraft. */
    public CompletionStage<Void> restoreHydrated() {
        return restoreUsing(query->store.read(query).toCompletableFuture().join());
    }
    private CompletionStage<Void> restoreUsing(Function<StoreQuery,StoreReadResult> reader) {
        return serial(() -> {
            var restoreSession=store.session();
            // Fence admissions before reading any page. Build privately so a failed page leaves
            // the last complete projection and all its indexes intact, but not writable.
            ready=false;
            Map<RequestId,RequestView> restored=new HashMap<>();
            List<DirectorRequestState> scheduledStates=new ArrayList<>();
            // Keep every encountered revision until replay is complete: a delayed
            // conflicting OLD revision must not evade a latest-only comparison.
            Map<RequestId,Map<Revision,RequestView>> restoredRevisions=new HashMap<>();
            Map<String,RequestId> restoredKeys=new HashMap<>();
            Map<String,CancelReceipt> restoredCancellations=new HashMap<>();
            List<DirectorFact> restoredCancellationFacts=new ArrayList<>();
            Map<RequestId,Set<EventInstanceId>> restoredExecutions=new HashMap<>();
            Map<RequestId,Set<EventInstanceId>> restoredEnded=new HashMap<>();
            Map<FactId,DirectorFact> abortSources=new HashMap<>();List<DirectorFact> endingReceipts=new ArrayList<>();
            // Bindings may precede their request records on paginated WAL reads.
            // Verify them against the COMPLETE restored request projection before publishing.
            List<DirectorFact> restoredBindings=new ArrayList<>();
            Map<MessageId,Fingerprint> restoredReceived=new HashMap<>();
            Optional<String> cursor=Optional.empty(); Set<String> cursors=new HashSet<>();
            do {
                var query=new StoreQuery(restoreSession.worldId(),Set.of(),new Sequence(0),cursor,1024);
                var result=reader.apply(query);
                ReadContracts.validate(result,query,restoreSession);
                if(result.status()!=ReadStatus.AVAILABLE)
                    throw new IllegalStateException("Quest restore requires hydrated read: "+result.status());
                var page=result.page().orElseThrow();
                for(var record:page.records()) {
                    if(record instanceof DurableRequest r && r.request().request().producerId().equals(authorizedProducer)) {
                        var v=r.request(); var prior=restored.get(v.state().requestId());
                        var seenRevision=restoredRevisions.computeIfAbsent(v.state().requestId(), k -> new HashMap<>())
                                .putIfAbsent(v.revision(),v);
                        Checks.require(seenRevision==null || seenRevision.equals(v),
                                "conflicting request state for same WAL revision");
                        if(prior!=null) {
                            Checks.require(prior.request().equals(v.request()) && prior.state().schedulingPolicy().equals(v.state().schedulingPolicy()),"durable request identity/policy changed");
                        }
                        if(prior==null || v.revision().compareTo(prior.revision())>0) restored.put(v.state().requestId(),v);
                    }
                    if(record instanceof DurableAggregate aggregate && aggregate.state() instanceof RequestAggregate request
                            && request.value().producerId().equals(authorizedProducer))scheduledStates.add(request.value());
                    if(record instanceof DurableInbox i && i.value().consumerId().equals(inbox)) {
                        var seen=restoredReceived.putIfAbsent(i.value().messageId(),i.value().payloadHash());
                        Checks.require(seen==null || seen.equals(i.value().payloadHash()),"conflicting durable inbox payload for one message");
                    }
                    if(record instanceof DurableFact f && f.value().factType().equals(BINDING)) restoredBindings.add(f.value());
                    if(record instanceof DurableFact f && f.value().factType().equals(ENDED))endingReceipts.add(f.value());
                    var sources=new ArrayList<DirectorFact>();
                    if(record instanceof DurableFact f)sources.add(f.value());
                    if(record instanceof DurableOutbox out&&out.value().payload() instanceof FactPayload payload)sources.add(payload.value());
                    if(record instanceof DurableResult durableResult){sources.addAll(durableResult.value().facts());for(var message:durableResult.value().outbox())if(message.payload() instanceof FactPayload payload)sources.add(payload.value());}
                    for(var source:sources)if(source.factType().equals(ABORTED)){
                        var previous=abortSources.putIfAbsent(source.factId(),source);
                        Checks.require(previous==null||previous.equals(source),"conflicting actual abort source");
                    }
                    if(record instanceof DurableFact f && f.value().factType().equals(CANCEL_RECEIPT)) restoredCancellationFacts.add(f.value());
                }
                cursor=page.nextCursor();
                if(!page.hasMore()) break;
                Checks.require(cursor.isPresent() && cursors.add(cursor.orElseThrow()),"store pagination");
            } while(true);
            Checks.require(restoreSession.equals(store.session()),"Quest restore session changed");
            for(var state:scheduledStates){
                var prior=restored.get(state.requestId());
                Checks.require(prior!=null,"scheduler state without original durable request");
                var view=foldScheduled(prior,state);
                var seen=restoredRevisions.computeIfAbsent(state.requestId(),k->new HashMap<>()).putIfAbsent(view.revision(),view);
                Checks.require(seen==null||seen.equals(view),"conflicting request aggregate for same WAL revision");
                if(view.revision().compareTo(prior.revision())>0)restored.put(state.requestId(),view);
            }
            restored.values().forEach(v -> {
                Checks.require(restoredKeys.putIfAbsent(v.request().producerKey(),v.state().requestId())==null,"duplicate durable request key");
            });
            for(var receipt:restoredCancellationFacts)restoreCancellation(receipt,restoredCancellations,restored);
            Checks.require(Collections.disjoint(restoredKeys.keySet(),restoredCancellations.keySet()),"durable producer key collision");
            for(var binding:restoredBindings)restoreBinding(binding,restoredExecutions,restored);
            for(var receipt:endingReceipts)restoreEnding(receipt,restoredEnded,restoredExecutions,restored,abortSources);
            var restoredRequests=Map.copyOf(restored);
            // Indexes are executor-confined; requests is the single volatile reader publication.
            keys=restoredKeys; cancellations=restoredCancellations; executions=restoredExecutions; endedExecutions=restoredEnded; received=restoredReceived;
            requests=restoredRequests; publishExecutions(); ready=true;
            return CompletableFuture.completedFuture(null);
        });
    }
    public boolean isReady(){return ready;}
    public CompletionStage<Void> drain(){return serial(()->CompletableFuture.completedFuture(null));}
    public List<RequestView> snapshot() { return requests.values().stream().sorted(Comparator.comparing(v->v.state().requestId().value())).toList(); }
    public boolean boundExecution(RequestId request,EventInstanceId event) {return publishedExecutions.getOrDefault(request,Set.of()).contains(event);}
    private void publishExecutions(){
        Map<RequestId,Set<EventInstanceId>> copy=new HashMap<>();executions.forEach((id,events)->{
            var active=new HashSet<>(events);active.removeAll(endedExecutions.getOrDefault(id,Set.of()));copy.put(id,Set.copyOf(active));
        });
        publishedExecutions=Map.copyOf(copy);
    }
    /** Fold a real scheduler Aggregate without persisting or advancing the request revision again. */
    private static RequestView foldScheduled(RequestView original,DirectorRequestState state){
        var prior=original.state();
        Checks.require(prior.header().id().equals(state.header().id())&&prior.requestId().equals(state.requestId())
          &&prior.producerId().equals(state.producerId())&&prior.producerKey().equals(state.producerKey())
          &&prior.kind()==state.kind()&&prior.selector().equals(state.selector())&&prior.scope().equals(state.scope())
          &&prior.constraints().equals(state.constraints())&&prior.fulfillment()==state.fulfillment()
          &&prior.expiresTick().equals(state.expiresTick())&&prior.schedulingPolicy().equals(state.schedulingPolicy()),
          "durable scheduled request identity/policy changed");
        return new RequestView(original.request(),state,state.header().revision());
    }
    /** Typed positive durable point reads only; original request envelopes/policies remain authoritative. */
    public CompletionStage<Void> refreshDurable(Set<RequestId> ids){
        var requested=Set.copyOf(ids);if(requested.size()>1024)return CompletableFuture.failedFuture(new IllegalArgumentException("request refresh bound"));
        return serial(()->{
            requireReady();if(requested.isEmpty())return CompletableFuture.completedFuture(null);
            var session=store.session();var keys=new HashSet<AggregateKey>();
            for(var id:requested){inspect(id);keys.add(new AggregateKey(AggregateKind.REQUEST,id.value()));}
            var query=new StoreQuery(session.worldId(),keys,new Sequence(0),Optional.empty(),keys.size());
            return store.read(query).thenApplyAsync(read->{
                ReadContracts.validate(read,query,session);
                Checks.require(session.equals(store.session())&&read.status()==ReadStatus.AVAILABLE,"request refresh unavailable");
                var page=read.page().orElseThrow();Checks.require(!page.hasMore()&&page.aggregates().size()==requested.size(),"request refresh incomplete");
                var next=new HashMap<>(requests);var seen=new HashSet<RequestId>();
                for(var row:page.aggregates()){
                    Checks.require(row.state() instanceof RequestAggregate&&row.committedSequence().value()>0,"request refresh must be durable RequestAggregate");
                    var state=((RequestAggregate)row.state()).value();Checks.require(requested.contains(state.requestId())&&seen.add(state.requestId()),"request refresh keys");
                    var old=inspect(state.requestId());var view=foldScheduled(old,state);int comparison=view.revision().compareTo(old.revision());
                    Checks.require(comparison>=0&&(comparison!=0||view.equals(old)),"request refresh regressed/conflicting revision");
                    next.put(state.requestId(),view);
                }
                requests=Map.copyOf(next);return (Void)null;
            },executor);
        });
    }
    public RequestView inspect(RequestId id) {
        var value=requests.get(id); if(value==null) throw new NoSuchElementException("unknown request"); return value;
    }
    public CompletionStage<RequestAck> submit(DirectorRequest request) {
        return submit(request,RequestSchedulingPolicy.legacyWire());
    }
    /** Trusted producer-bound Java path. Policy never comes from the closed wire payload. */
    public CompletionStage<RequestAck> submit(DirectorRequest request, RequestSchedulingPolicy policy) {
        Objects.requireNonNull(request); Objects.requireNonNull(policy);
        return serial(() -> submitSerial(request,policy));
    }
    private CompletionStage<RequestAck> submitSerial(DirectorRequest request, RequestSchedulingPolicy policy) {
        requireReady(); RequestId id=RequestContracts.id(request.payload());
        if(!request.worldId().equals(store.session().worldId()) || !request.producerId().equals(authorizedProducer)) return immediate(id,ReasonCode.AUTH_DENIED);
        var oldId=keys.get(request.producerKey());
        if(oldId!=null) {
            var old=requests.get(oldId);
            return CompletableFuture.completedFuture(RequestContracts.idempotenceHash(old.request()).equals(RequestContracts.idempotenceHash(request)) && DurableRequest.policyHash(old.state().schedulingPolicy()).equals(DurableRequest.policyHash(policy)) ? ack(old) : rejection(id,ReasonCode.IDEMPOTENCY_CONFLICT));
        }
        if(request.payload() instanceof CancelPayload c) return cancelSerial(new CancelRequest(c.requestId(),c.expectedRevision(),request.producerKey()),request,policy);
        if(cancellations.containsKey(request.producerKey()) || received.containsKey(cancelMessage(request.producerKey()))) return immediate(id,ReasonCode.IDEMPOTENCY_CONFLICT);
        if(requests.containsKey(id) || received.containsKey(request.messageId())) return immediate(id,ReasonCode.IDEMPOTENCY_CONFLICT);
        Selector selector; TargetConstraints targets; FulfillmentMode mode; RequestKind kind; Optional<Tick> expires;
        if(request.payload() instanceof DirectorPreference p) {
            if(p.boundedWeightBias()>maximumBias || p.expiresTick().value()<=tick.getAsLong() || p.expiresTick().value()<=request.createdTick().value()) return immediate(id,ReasonCode.STALE_CONTEXT);
            selector=p.selector(); targets=new TargetConstraints(Set.of(),Set.of()); mode=FulfillmentMode.NEW_EXECUTION; kind=RequestKind.PREFER; expires=Optional.of(p.expiresTick());
        } else {
            var r=(DirectorRequirement)request.payload(); selector=r.selector(); targets=r.targetConstraints(); mode=r.fulfillmentMode(); kind=RequestKind.REQUIRE; expires=Optional.empty();
        }
        var state=new DirectorRequestState(new AggregateHeader(1,id.value(),new Revision(1),new Sequence(0),new Tick(tick.getAsLong())),id,
                request.producerId(),request.producerKey(),kind,selector,request.scope(),targets,mode,RequestStatus.ACCEPTED,new DurationTicks(0),expires,Set.of(),List.of(),policy);
        var view=new RequestView(request,state,state.header().revision());
        return persist(view,List.of(new DurableInbox(new InboxRecord(inbox,request.messageId(),RequestContracts.idempotenceHash(request),new Sequence(0),new Revision(1)))))
                .thenApply(ok -> { if(!ok)return rejection(id,ReasonCode.WAL_UNAVAILABLE); keys.put(request.producerKey(),id); received.put(request.messageId(),RequestContracts.idempotenceHash(request)); return ack(view); });
    }
    public CompletionStage<RequestAck> cancel(CancelRequest request) { return serial(() -> cancelSerial(request,null,RequestSchedulingPolicy.legacyWire())); }
    private MessageId cancelMessage(String key) {
        return new MessageId(UUID.nameUUIDFromBytes((authorizedProducer.value()+":cancel:"+key).getBytes(StandardCharsets.UTF_8)));
    }
    private CompletionStage<RequestAck> cancelSerial(CancelRequest request,DirectorRequest delivery,RequestSchedulingPolicy policy) {
        requireReady();
        // One producer-key domain for PREFER, REQUIRE and CANCEL, including the direct Java path.
        if(keys.containsKey(request.producerKey()))return immediate(request.requestId(),ReasonCode.IDEMPOTENCY_CONFLICT);
        var old=requests.get(request.requestId());
        if(old==null)return immediate(request.requestId(),cancellations.containsKey(request.producerKey())?ReasonCode.IDEMPOTENCY_CONFLICT:ReasonCode.NO_TARGET);
        var semantic=delivery==null?new DirectorRequest(new MessageId(UUID.randomUUID()),store.session().worldId(),authorizedProducer,request.producerKey(),old.request().scope(),old.request().correlationId(),Optional.empty(),new Sequence(0),new Tick(tick.getAsLong()),new CancelPayload(request.requestId(),request.expectedRevision())):delivery;
        var hash=RequestContracts.idempotenceHash(semantic);
        var receipt=cancellations.get(request.producerKey());
        if(receipt!=null)return CompletableFuture.completedFuture(receipt.hash().equals(hash) && receipt.policyHash().equals(DurableRequest.policyHash(policy))?receipt.ack():rejection(request.requestId(),ReasonCode.IDEMPOTENCY_CONFLICT));
        var message=cancelMessage(request.producerKey());
        var legacyHash=BlobContent.hash(new ModelCodec<>(CancelRequest.class).encode(request));
        // Preserve replay of previously written cancellation inbox records as well.
        if(received.containsKey(message))return CompletableFuture.completedFuture(received.get(message).equals(legacyHash) && policy.equals(RequestSchedulingPolicy.legacyWire())?ack(old):rejection(request.requestId(),ReasonCode.IDEMPOTENCY_CONFLICT));
        if(delivery!=null && received.containsKey(delivery.messageId()))return immediate(request.requestId(),ReasonCode.IDEMPOTENCY_CONFLICT);
        if(!old.revision().equals(request.expectedRevision()))return immediate(request.requestId(),ReasonCode.STALE_CONTEXT);
        boolean alreadyTerminal=terminal(old.state().status());
        var next=alreadyTerminal?old:change(old,RequestStatus.CANCELLED,List.of(),old.state().resultFactIds());
        var result=ack(next);
        var fact=new DirectorFact(new FactId(UUID.randomUUID()),CANCEL_RECEIPT,List.of(old.request().scope()),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("producer_id",new JsonValue.Text(authorizedProducer.value()),"producer_key",new JsonValue.Text(request.producerKey()),
                        "request_hash",new JsonValue.Text(hash.value()),"policy_hash",new JsonValue.Text(DurableRequest.policyHash(policy).value()),"ack",CanonicalJsonV1.decode(new ModelCodec<>(RequestAck.class).encode(result))),true,Optional.empty());
        List<DurableRecord> records=new ArrayList<>();
        records.add(new DurableFact(fact));
        records.add(new DurableInbox(new InboxRecord(inbox,message,legacyHash,new Sequence(0),next.revision())));
        if(delivery!=null)records.add(new DurableInbox(new InboxRecord(inbox,delivery.messageId(),hash,new Sequence(0),next.revision())));
        // Even a no-transition cancellation consumes its producer key durably before ACK.
        // Receipt-only batches must not duplicate the request aggregate or transition outbox.
        var committed=alreadyTerminal?append(records,Optional.empty()):persist(next,records);
        return committed.thenApply(ok -> {
            if(ok){received.put(message,legacyHash);if(delivery!=null)received.put(delivery.messageId(),hash);cancellations.put(request.producerKey(),new CancelReceipt(hash,DurableRequest.policyHash(policy),result));}
            return ok?result:rejection(request.requestId(),ReasonCode.WAL_UNAVAILABLE);
        });
    }
    /** A cancellation receipt is a replay credential, not an arbitrary WAL fact.
     * Validate against the FULL request projection, including receipts paginated
     * before their target. An invalid receipt fences all admissions on restore.
     */
    private void restoreCancellation(DirectorFact fact,Map<String,CancelReceipt> target,
            Map<RequestId,RequestView> knownRequests) {
        if(!new JsonValue.Text(authorizedProducer.value()).equals(fact.payload().get("producer_id")))return;
        var fields=fact.payload().keySet();
        Checks.require(fact.durable() && fact.evidenceStatus()==EvidenceStatus.CONFIRMED &&
                fact.eventId().isEmpty() && fact.supersedesFactId().isEmpty() &&
                (fields.equals(Set.of("producer_id","producer_key","request_hash","policy_hash","ack")) ||
                 fields.equals(Set.of("producer_id","producer_key","request_hash","ack"))),
                "invalid cancellation receipt evidence/shape");
        Checks.require(fact.payload().get("producer_key") instanceof JsonValue.Text &&
                fact.payload().get("request_hash") instanceof JsonValue.Text &&
                fact.payload().get("ack") instanceof JsonValue.ObjectValue &&
                (!fields.contains("policy_hash") || fact.payload().get("policy_hash") instanceof JsonValue.Text),
                "invalid cancellation receipt payload");
        var key=((JsonValue.Text)fact.payload().get("producer_key")).value();
        var hash=new Fingerprint(((JsonValue.Text)fact.payload().get("request_hash")).value());
        var ack=new ModelCodec<>(RequestAck.class).decode(CanonicalJsonV1.encode(fact.payload().get("ack")));
        var original=knownRequests.get(ack.requestId());
        Checks.require(original!=null && terminal(original.state().status()) &&
                ack.equals(ack(original)) &&
                fact.subjectRefs().equals(List.of(original.request().scope())),
                "cancellation receipt target/state/scope mismatch");
        // Direct and envelope cancellation share the same semantic hash.
        // For an active cancellation the durable ACK revision advanced once;
        // a receipt for an already-terminal request did not advance it.
        boolean authentic=hash.equals(cancellationHash(key,original,original.revision()));
        if(original.state().status()==RequestStatus.CANCELLED && original.revision().value()>1)
            authentic |= hash.equals(cancellationHash(key,original,new Revision(original.revision().value()-1)));
        Checks.require(authentic,"cancellation receipt semantic hash mismatch");
        var policyHash=fields.contains("policy_hash")
                ?new Fingerprint(((JsonValue.Text)fact.payload().get("policy_hash")).value())
                :DurableRequest.policyHash(RequestSchedulingPolicy.legacyWire());
        var receipt=new CancelReceipt(hash,policyHash,ack);var prior=target.putIfAbsent(key,receipt);
        Checks.require(prior==null || prior.equals(receipt),"conflicting cancellation receipts");
    }
    private Fingerprint cancellationHash(String key,RequestView original,Revision expected) {
        // Only producer/key/scope/payload contribute to idempotenceHash. Reuse
        // inert delivery metadata: no message is sent and no WAL record written.
        var semantic=new DirectorRequest(original.request().messageId(),store.session().worldId(),
                authorizedProducer,key,original.request().scope(),original.request().correlationId(),
                Optional.empty(),new Sequence(0),original.request().createdTick(),
                new CancelPayload(original.state().requestId(),expected));
        return RequestContracts.idempotenceHash(semantic);
    }
    /** Runtime reports a safety/admission state; fulfillment is intentionally excluded. */
    public CompletionStage<RequestAck> update(RequestId id, Revision expected, RequestStatus status, List<ReasonCode> reasons) {
        if(!Set.of(RequestStatus.WAITING_CONTEXT,RequestStatus.WAITING_SAFETY,RequestStatus.SCHEDULED,RequestStatus.EXECUTING,RequestStatus.CONFLICT).contains(status))
            return CompletableFuture.failedFuture(new IllegalArgumentException("runtime state"));
        return serial(() -> {
            requireReady(); var old=inspect(id);
            if(!old.revision().equals(expected) || !StateTransitions.allows(old.state().status(),status,old.state().kind(),false)) return immediate(id,ReasonCode.STALE_CONTEXT);
            var next=change(old,status,reasons,old.state().resultFactIds());
            return persist(next,List.of()).thenApply(ok -> ok?ack(next):rejection(id,ReasonCode.WAL_UNAVAILABLE));
        });
    }
    /** Bind an admitted new execution durably before consuming its fulfillment facts. No admission is performed here. */
    public CompletionStage<RequestAck> bindExecution(RequestId id, Revision expected, EventInstanceId event) {
        return serial(() -> {
            requireReady(); var old=inspect(id);
            if(!old.revision().equals(expected) || terminal(old.state().status()))return immediate(id,ReasonCode.STALE_CONTEXT);
            if(endedExecutions.getOrDefault(id,Set.of()).contains(event))return immediate(id,ReasonCode.STALE_CONTEXT);
            if(executions.getOrDefault(id,Set.of()).contains(event))return CompletableFuture.completedFuture(ack(old));
            boolean scheduled=old.state().status()==RequestStatus.SCHEDULED;
            if(!scheduled && !StateTransitions.allows(old.state().status(),RequestStatus.SCHEDULED,old.state().kind(),false))
                return immediate(id,ReasonCode.STALE_CONTEXT);
            var fact=new DirectorFact(new FactId(UUID.randomUUID()),BINDING,List.of(old.request().scope()),Optional.of(event),EvidenceStatus.CONFIRMED,
                    Map.of("request_id",new JsonValue.Text(id.value().toString()),"producer_id",new JsonValue.Text(authorizedProducer.value())),true,Optional.empty());
            // An additional binding is not a state transition and must not advance the request revision.
            var next=scheduled?old:change(old,RequestStatus.SCHEDULED,List.of(),old.state().resultFactIds());
            var records=List.<DurableRecord>of(new DurableFact(fact));
            var committed=scheduled?append(records,Optional.empty()):persist(next,records);
            return committed.thenApply(ok -> {if(ok){restoreBinding(fact);publishExecutions();}return ok?ack(next):rejection(id,ReasonCode.WAL_UNAVAILABLE);});
        });
    }
    private void restoreBinding(DirectorFact fact) { restoreBinding(fact,executions,requests); }
    private void restoreBinding(DirectorFact fact,Map<RequestId,Set<EventInstanceId>> target,
            Map<RequestId,RequestView> knownRequests) {
        if(!new JsonValue.Text(authorizedProducer.value()).equals(fact.payload().get("producer_id")))return;
        Checks.require(fact.durable() && fact.evidenceStatus()==EvidenceStatus.CONFIRMED &&
                fact.eventId().isPresent() && fact.supersedesFactId().isEmpty() &&
                fact.payload().keySet().equals(Set.of("producer_id","request_id")),"invalid durable execution binding");
        var rawId=fact.payload().get("request_id");
        Checks.require(rawId instanceof JsonValue.Text,"invalid execution request id");
        RequestId id=RequestId.parse(((JsonValue.Text)rawId).value());
        RequestView request=knownRequests.get(id);
        Checks.require(request!=null && request.request().producerId().equals(authorizedProducer) &&
                fact.subjectRefs().equals(List.of(request.request().scope())),"execution binding request/scope mismatch");
        target.computeIfAbsent(id,k -> new HashSet<>()).add(fact.eventId().orElseThrow());
    }
    /** Consume only trusted durable outbox envelopes. ACK the bus after this stage succeeds.
     * NEW_EXECUTION must match a durably bound execution, not merely a newer timestamp.
     */
    public CompletionStage<RequestAck> acceptFact(RequestId id, DirectorEnvelope envelope) {
        return serial(() -> {
            requireReady(); var old=inspect(id);
            if(!envelope.worldId().equals(store.session().worldId()) || envelope.worldSequence().isEmpty() || !(envelope.payload() instanceof FactPayload)) return immediate(id,ReasonCode.AUTH_DENIED);
            if(!(old.request().payload() instanceof DirectorRequirement requirement) || terminal(old.state().status()))return CompletableFuture.completedFuture(ack(old));
            var factHash=BlobContent.hash(DirectorMessageCodec.encode(envelope));
            var priorHash=received.get(envelope.messageId());
            if(priorHash!=null && !priorHash.equals(factHash))return immediate(id,ReasonCode.IDEMPOTENCY_CONFLICT);
            var fact=((FactPayload)envelope.payload()).value();
            if(fact.evidenceStatus()!=EvidenceStatus.CONFIRMED || !fact.durable() ||
                    (requirement.fulfillmentMode()==FulfillmentMode.NEW_EXECUTION && (fact.eventId().isEmpty() || (!executions.getOrDefault(id,Set.of()).contains(fact.eventId().orElseThrow())||endedExecutions.getOrDefault(id,Set.of()).contains(fact.eventId().orElseThrow())))) ||
                    !requirement.targetConstraints().playerIds().stream().allMatch(p -> fact.subjectRefs().contains(new ScopeRef(ScopeKind.PLAYER,p.value()))) ||
                    !requirement.targetConstraints().regionIds().stream().allMatch(r -> fact.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,r.value()))) ||
                    !matches(requirement.requiredFactPredicate(),fact)) return CompletableFuture.completedFuture(ack(old));
            var facts=new HashSet<>(old.state().resultFactIds());facts.add(fact.factId());
            var next=change(old,RequestStatus.FULFILLED,List.of(),facts);
            return persist(next,List.of(new DurableInbox(new InboxRecord(inbox,envelope.messageId(),factHash,envelope.worldSequence().orElseThrow(),next.revision()))))
                    .thenApply(ok -> {if(ok)received.put(envelope.messageId(),factHash);return ok?ack(next):rejection(id,ReasonCode.WAL_UNAVAILABLE);});
        });
    }
    private static boolean abortProven(DirectorFact fact){
        return fact.factType().equals(ABORTED)&&fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isPresent()
            &&(new JsonValue.Bool(true).equals(fact.payload().get("no_physical_effect"))||new JsonValue.Bool(true).equals(fact.payload().get("recovery_complete")));
    }
    private void restoreEnding(DirectorFact receipt,Map<RequestId,Set<EventInstanceId>> ended,
            Map<RequestId,Set<EventInstanceId>> bound,Map<RequestId,RequestView> known,Map<FactId,DirectorFact> sources){
        if(!new JsonValue.Text(authorizedProducer.value()).equals(receipt.payload().get("producer_id")))return;
        Checks.require(receipt.durable()&&receipt.evidenceStatus()==EvidenceStatus.CONFIRMED&&receipt.eventId().isPresent()
            &&receipt.supersedesFactId().isEmpty()&&receipt.payload().keySet().equals(Set.of("producer_id","request_id","source_fact")),"invalid execution ending receipt");
        var id=RequestId.parse(((JsonValue.Text)receipt.payload().get("request_id")).value());
        var source=sources.get(new FactId(UUID.fromString(((JsonValue.Text)receipt.payload().get("source_fact")).value())));
        var request=known.get(id);var event=receipt.eventId().orElseThrow();
        Checks.require(request!=null&&bound.getOrDefault(id,Set.of()).contains(event)&&receipt.subjectRefs().equals(List.of(request.request().scope()))
            &&source!=null&&abortProven(source)&&source.eventId().equals(Optional.of(event))
            &&source.subjectRefs().contains(request.request().scope())&&request.request().payload() instanceof DirectorRequirement requirement
            &&requirement.targetConstraints().playerIds().stream().allMatch(p->source.subjectRefs().contains(new ScopeRef(ScopeKind.PLAYER,p.value())))
            &&requirement.targetConstraints().regionIds().stream().allMatch(r->source.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,r.value()))),
            "execution ending has no actual bound proven abort source");
        ended.computeIfAbsent(id,k->new HashSet<>()).add(event);
    }
    /** A real completed/no-write abort removes only this execution, never fulfills the obligation.
     * Consume only trusted actual durable outbox data. Uncertain delivery has no reset authority.
     */
    public CompletionStage<RequestAck> acceptExecutionAbort(RequestId id,DirectorEnvelope envelope){
        return serial(()->{
            requireReady();var old=inspect(id);
            if(!envelope.worldId().equals(store.session().worldId())||envelope.worldSequence().isEmpty()
                ||!(envelope.payload() instanceof FactPayload payload))return immediate(id,ReasonCode.AUTH_DENIED);
            var fact=payload.value();
            if(!(old.request().payload() instanceof DirectorRequirement requirement)||terminal(old.state().status())||!abortProven(fact))
                return CompletableFuture.completedFuture(ack(old));
            var event=fact.eventId().orElseThrow();
            if(!executions.getOrDefault(id,Set.of()).contains(event)||endedExecutions.getOrDefault(id,Set.of()).contains(event)
                ||!fact.subjectRefs().contains(old.request().scope())
                ||!requirement.targetConstraints().playerIds().stream().allMatch(p->fact.subjectRefs().contains(new ScopeRef(ScopeKind.PLAYER,p.value())))
                ||!requirement.targetConstraints().regionIds().stream().allMatch(r->fact.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,r.value()))))
                return CompletableFuture.completedFuture(ack(old));
            var hash=BlobContent.hash(DirectorMessageCodec.encode(envelope));var receivedHash=received.get(envelope.messageId());
            if(receivedHash!=null)return CompletableFuture.completedFuture(receivedHash.equals(hash)?ack(old):rejection(id,ReasonCode.IDEMPOTENCY_CONFLICT));
            var receipt=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(("request-execution-ended-v1/"+authorizedProducer.value()+"/"+id.value()+"/"+event.value()+"/"+fact.factId().value()).getBytes(StandardCharsets.UTF_8))),
                ENDED,List.of(old.request().scope()),Optional.of(event),EvidenceStatus.CONFIRMED,
                Map.of("producer_id",new JsonValue.Text(authorizedProducer.value()),"request_id",new JsonValue.Text(id.value().toString()),
                    "source_fact",new JsonValue.Text(fact.factId().value().toString())),true,Optional.empty());
            boolean anotherActive=executions.getOrDefault(id,Set.of()).stream().anyMatch(bound->!bound.equals(event)&&!endedExecutions.getOrDefault(id,Set.of()).contains(bound));
            var next=anotherActive||old.state().status()==RequestStatus.WAITING_CONTEXT?old:change(old,RequestStatus.WAITING_CONTEXT,List.of(ReasonCode.STALE_CONTEXT),old.state().resultFactIds());
            var rows=List.<DurableRecord>of(new DurableFact(receipt),new DurableInbox(new InboxRecord(inbox,envelope.messageId(),hash,envelope.worldSequence().orElseThrow(),next.revision())));
            var committed=next==old?append(rows,Optional.empty()):persist(next,rows);
            return committed.thenApply(ok->{if(ok){received.put(envelope.messageId(),hash);endedExecutions.computeIfAbsent(id,k->new HashSet<>()).add(event);publishExecutions();}
                return ok?ack(next):rejection(id,ReasonCode.WAL_UNAVAILABLE);});
        });
    }
    public CompletionStage<RequestAck> expirePreference(RequestId id) {
        return serial(() -> {
            requireReady(); var old=inspect(id);
            if(old.state().kind()!=RequestKind.PREFER || terminal(old.state().status()) || old.state().expiresTick().orElseThrow().value()>tick.getAsLong())return CompletableFuture.completedFuture(ack(old));
            var next=change(old,RequestStatus.EXPIRED,List.of(),old.state().resultFactIds());
            return persist(next,List.of()).thenApply(ok -> ok?ack(next):rejection(id,ReasonCode.WAL_UNAVAILABLE));
        });
    }
    /** Pull, don't call quest listeners synchronously. Slow consumers retain their cursor and outbox. */
    public Subscription poll(ConsumerSpec spec) {
        if(spec.batchLimit()<1 || spec.batchLimit()>queueLimit)throw new IllegalArgumentException("consumer batch bound");
        var result=bus.subscribe(spec);
        ReadContracts.validate(result,spec,store.session());
        if(result.status()!=ReadStatus.AVAILABLE)
            throw new IllegalStateException("Quest subscription requires hydrated page: "+result.status());
        return result.page().orElseThrow();
    }
    public CompletionStage<Subscription> pollAsync(ConsumerSpec spec) {
        if(spec.batchLimit()<1 || spec.batchLimit()>queueLimit)
            return CompletableFuture.failedFuture(new IllegalArgumentException("consumer batch bound"));
        var boundSession=store.session();
        return bus.readSubscription(spec).thenApply(result -> {
            if(!boundSession.equals(store.session())) throw new IllegalStateException("store session changed");
            ReadContracts.validate(result,spec,boundSession);
            if(result.status()!=ReadStatus.AVAILABLE)
                throw new IllegalStateException("Quest subscription not available: "+result.status());
            return result.page().orElseThrow();
        });
    }
    public CompletionStage<ConsumeAck> acknowledge(ConsumerAck ack) { return bus.acknowledge(ack); }
    private CompletionStage<Boolean> persist(RequestView view,List<DurableRecord> additional) {
        var original=envelope(view.request());
        List<DurableRecord> records=new ArrayList<>();records.add(new DurableRequest(original,view,RequestContracts.idempotenceHash(view.request())));
        records.addAll(additional);
        var fact=new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:request_state"),List.of(view.request().scope()),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("request_id",new JsonValue.Text(view.state().requestId().value().toString()),"state",new JsonValue.Text(view.state().status().name()),"revision",new JsonValue.Text(Long.toString(view.revision().value()))),true,Optional.empty());
        records.add(new DurableFact(fact));
        records.add(new DurableOutbox(new DirectorEnvelope(1,new MessageId(UUID.randomUUID()),store.session().worldId(),PRODUCER,
                view.state().requestId().value()+":"+view.revision().value(),view.request().scope(),view.request().correlationId(),Optional.of(view.request().messageId()),new Sequence(view.revision().value()),Optional.empty(),new Tick(tick.getAsLong()),PayloadType.DirectorFact,1,new FactPayload(fact))));
        return append(records,Optional.of(view));
    }
    private CompletionStage<Boolean> append(List<DurableRecord> records,Optional<RequestView> publication) {
        List<JsonValue> encoded=records.stream().map(r -> CanonicalJsonV1.decode(new ModelCodec<>(DurableRecord.class).encode(r))).toList();
        var hash=BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.Array(encoded)));
        var batch=new DurableBatch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,List.of(),Optional.empty(),hash);
        var session=store.session();
        return store.append(batch).handleAsync((ack,error) -> {
            if(error!=null || ack==null || ack.ackStatus()!=AckStatus.DURABLE || !ack.transactionId().equals(batch.transactionId()) ||
                    !ack.transactionHash().equals(hash) || !ack.storeGeneration().equals(batch.expectedStoreGeneration()) || ack.durableSequence().value()==0 || !session.equals(store.session())) { ready=false; return false; }
            publication.ifPresent(view -> {
                Map<RequestId,RequestView> next=new HashMap<>(requests);next.put(view.state().requestId(),view);requests=Map.copyOf(next);
            });
            return true;
        },executor);
    }
    private RequestView change(RequestView old,RequestStatus status,List<ReasonCode> reasons,Set<FactId> facts) {
        var s=old.state();var h=s.header();var rev=h.revision().plus(1);
        var state=new DirectorRequestState(new AggregateHeader(1,h.id(),rev,h.lastSequence(),new Tick(tick.getAsLong())),s.requestId(),s.producerId(),s.producerKey(),s.kind(),s.selector(),s.scope(),s.constraints(),s.fulfillment(),status,s.eligibleWaitTicks(),s.expiresTick(),facts,reasons,s.schedulingPolicy());
        return new RequestView(old.request(),state,rev);
    }
    private static boolean matches(FactPredicate predicate,DirectorFact fact) {
        return switch(predicate) {
            case FactMatch m -> m.factType().equals(fact.factType()) && fact.subjectRefs().contains(m.scopeMatch()) && m.payloadEquals().entrySet().stream().allMatch(e -> Objects.equals(fact.payload().get(e.getKey()),e.getValue()));
            case AllFacts a -> a.predicates().stream().allMatch(p -> matches(p,fact));
            case AnyFacts a -> a.predicates().stream().anyMatch(p -> matches(p,fact));
            case NotFact n -> !matches(n.predicate(),fact);
        };
    }
    private static DirectorEnvelope envelope(DirectorRequest r) {return new DirectorEnvelope(1,r.messageId(),r.worldId(),r.producerId(),r.producerKey(),r.scope(),r.correlationId(),r.causationId(),r.producerSequence(),Optional.empty(),r.createdTick(),PayloadType.DirectorRequest,1,new RequestMessage(r.payload()));}
    private static boolean terminal(RequestStatus s) {return Set.of(RequestStatus.CANCELLED,RequestStatus.FULFILLED,RequestStatus.CONFLICT,RequestStatus.EXPIRED).contains(s);}
    private static RequestAck ack(RequestView v) {return new RequestAck(v.state().requestId(),v.revision(),v.state().status(),v.state().lastReasonCodes(),v.state().resultFactIds());}
    private RequestAck rejection(RequestId id,ReasonCode reason) {var v=requests.get(id);return new RequestAck(id,v==null?new Revision(0):v.revision(),v==null?RequestStatus.CONFLICT:v.state().status(),List.of(reason),v==null?Set.of():v.state().resultFactIds());}
    private CompletionStage<RequestAck> immediate(RequestId id,ReasonCode reason) {return CompletableFuture.completedFuture(rejection(id,reason));}
    private void requireReady() {if(!ready)throw new IllegalStateException("restore required");}
    private <T> CompletionStage<T> serial(Supplier<CompletionStage<T>> action) {
        if(queued.incrementAndGet()>queueLimit){queued.decrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("quest backpressure"));}
        var barrier=new CompletableFuture<Void>();var previous=tail.getAndSet(barrier);var result=new CompletableFuture<T>();
        previous.whenComplete((v,e) -> {
            try {executor.execute(() -> {
                try {action.get().whenComplete((value,error) -> {if(error!=null)result.completeExceptionally(error);else result.complete(value);queued.decrementAndGet();barrier.complete(null);});}
                catch(RuntimeException failure){result.completeExceptionally(failure);queued.decrementAndGet();barrier.complete(null);}
            });}catch(RejectedExecutionException failure){result.completeExceptionally(failure);queued.decrementAndGet();barrier.complete(null);}
        });
        return result.minimalCompletionStage();
    }
}
