package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import java.util.concurrent.*;

/** One durable chain projection; no physical claims are retained between steps.
 * Eligibility scores must come from evaluation of the pinned transition conditions on a fresh
 * snapshot. A branch is persisted before returning its next event for ordinary admission.
 */
public final class ChainRunner {
    public record Advance(ChainInstance state, Optional<DefinitionId> nextEvent, ResourceId transition, long seed) {}
    private record Delivery(CommitAck ack,Throwable failure) {}
    private final Thread actor=Thread.currentThread();
    private final DurableStore store; private final ChainDefinition definition; private final ScopeRef scope;
    private ChainInstance current; private Advance pending; private DurableBatch batch; private StoreSession session;
    private CompletableFuture<Delivery> delivery;
    /** Positive producer provenance is required; legacy or TEST_ONLY evidence
     * is never upgraded merely because its fact type exists in a new catalogue. */
    public static boolean productionSource(DirectorFact fact) {
        return fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED
            &&new JsonValue.Text(ProviderMode.PRODUCTION.name()).equals(fact.payload().get("provider_mode"))
            &&!fact.factType().equals(AdminContracts.RECORDED_DECISION_INPUT_FACT)
            &&!fact.factType().value().startsWith("proutlost_director:lab_")
            &&!new JsonValue.Bool(true).equals(fact.payload().get("manual_lab"))
            &&!new JsonValue.Bool(true).equals(fact.payload().get("laboratory_fixture"));
    }
    /** Unpublished revision zero is only an in-memory proposal. The first real
     * source fact and selected branch create revision one in a single J04 batch. */
    public static ChainRunner initial(DurableStore store,ChainDefinition definition,ChainId id,
                                      ScopeRef scope,Set<RegionId> lineage,Tick now) {
        if(scope.kind()==ScopeKind.REGION&&!lineage.contains(new RegionId(scope.id())))
            throw new IllegalArgumentException("initial chain lineage");
        var reference=new DefinitionRef(definition.id(),definition.contentRevision(),
            BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(definition)));
        var state=new ChainInstance(new AggregateHeader(1,id.value(),new Revision(0),new Sequence(0),now),
            id,reference,definition.mode(),ChainState.WAITING,Set.of(definition.entryState()),Set.of(),
            lineage,List.of(),List.of(),Optional.empty(),new UnitValue(1),now);
        return new ChainRunner(store,definition,state,scope);
    }
    public ChainRunner(DurableStore store, ChainDefinition definition, ChainInstance restored, ScopeRef scope) {
        this.store=Objects.requireNonNull(store);this.definition=Objects.requireNonNull(definition);current=Objects.requireNonNull(restored);
        this.scope=Objects.requireNonNull(scope);
        if(definition.planningHorizon()<1 || !definition.id().equals(restored.recipe().id())
                || !definition.contentRevision().equals(restored.recipe().revision())
                || !BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(definition)).equals(restored.recipe().hash())
                || scope.kind()!=definition.scope() || restored.plannedHorizon().size()>definition.planningHorizon())
            throw new IllegalArgumentException("pinned chain contract");
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public Optional<Advance> propose(DirectorFact fact,Map<ResourceId,Double> eligibleScores,long seed,Tick tick,TransactionId transaction) {
        return propose(fact,eligibleScores,seed,tick,transaction,next->List.of());
    }
    /** Consequences are computed on the selected immutable next state BEFORE any pending state or append.
     * The caller owns the regional-memory lane; returned records share the branch fsync/ACK. */
    public Optional<Advance> propose(DirectorFact fact,Map<ResourceId,Double> eligibleScores,long seed,Tick tick,
            TransactionId transaction,java.util.function.Function<ChainInstance,List<DurableRecord>> consequences) {
        actor();if(pending!=null)throw new IllegalStateException("branch awaiting durability");
        Objects.requireNonNull(consequences);
        if(tick.compareTo(current.nextEvaluationTick())<0 || current.relevance().value()<definition.relevanceMinimum().value()
                || !fact.durable() || fact.evidenceStatus()!=EvidenceStatus.CONFIRMED || current.consumedFactIds().contains(fact.factId())
                || !fact.subjectRefs().contains(scope) || current.currentNodes().stream().allMatch(definition.terminalStates()::contains))return Optional.empty();
        var candidates=definition.transitions().stream().filter(t->current.currentNodes().contains(t.from())
                && t.onFact().equals(fact.factType()) && eligibleScores.containsKey(t.id()))
                .sorted(Comparator.comparing(t->t.id().value())).toList();
        if(candidates.isEmpty())return Optional.empty();
        double total=0;
        for(var c:candidates){double score=eligibleScores.get(c.id());if(!Double.isFinite(score)||score<=0)throw new IllegalArgumentException("transition score");total+=score;}
        if(!Double.isFinite(total))throw new IllegalArgumentException("transition weight overflow");
        double draw=new SplittableRandom(seed).nextDouble(total);ChainTransition selected=candidates.getLast();
        for(var c:candidates){draw-=eligibleScores.get(c.id());if(draw<0){selected=c;break;}}
        if(!definition.states().contains(selected.to()) || definition.terminalStates().contains(selected.to()) && selected.eventId().isPresent())
            throw new IllegalArgumentException("invalid transition target");
        var consumed=new HashSet<>(current.consumedFactIds());consumed.add(fact.factId());
        var history=new ArrayList<>(current.branchHistory());history.add(new BranchHistory(selected.id(),fact.factId(),tick));
        var header=new AggregateHeader(1,current.header().id(),current.header().revision().plus(1),current.header().lastSequence(),tick);
        ChainState state=definition.terminalStates().contains(selected.to())
                ? selected.settleStateTag().isPresent()?ChainState.SETTLED_AS_WORLD_STATE:ChainState.COMPLETED
                : selected.eventId().isPresent()?ChainState.ACTIVE:ChainState.WAITING;
        var next=new ChainInstance(header,current.chainId(),current.recipe(),current.mode(),state,Set.of(selected.to()),
                consumed,current.lineageRefs(),selected.eventId().isPresent()?List.of(selected.id()):List.of(),history,
                selected.settleStateTag().map(t->new ResourceId(t.value())),current.relevance(),tick.plus(definition.minimumStepGapTicks().value()));
        var records=new ArrayList<DurableRecord>();records.add(new DurableAggregate(new ChainAggregate(next)));
        records.addAll(List.copyOf(consequences.apply(next)));
        pending=new Advance(next,selected.eventId(),selected.id(),seed);session=store.session();
        // CHAIN has its own AggregateKey revision fence in J04. Its revision is
        // not the revision of the real REGION scope; never compare those domains.
        batch=ResultTransaction.batch(transaction,session.storeGeneration(),Map.of(),
                records,List.of());
        delivery=new CompletableFuture<>();
        try{store.append(batch).whenComplete((ack,error)->delivery.complete(new Delivery(ack,error)));}
        catch(RuntimeException error){delivery.complete(new Delivery(null,error));}
        // Proposal is inspectable but nextEvent is not executable until pollCommitted returns it.
        return Optional.of(new Advance(current,Optional.empty(),selected.id(),seed));
    }
    public Optional<Advance> pollCommitted() {
        actor();if(pending==null)return Optional.empty();
        Delivery d=delivery.getNow(null);if(d==null)return Optional.empty();
        if(d.failure()!=null || d.ack()==null || d.ack().ackStatus()!=AckStatus.DURABLE
                || !session.equals(store.session()) || !d.ack().transactionId().equals(batch.transactionId())
                || !d.ack().transactionHash().equals(batch.transactionHash()) || !d.ack().storeGeneration().equals(session.storeGeneration())
                || d.ack().durableSequence().value()==0 || d.ack().permitAttempt().isPresent()
                || !d.ack().forcedBlobs().equals(DurableBlobReferences.collect(batch.records())))throw new IllegalStateException("branch requires durable reconciliation");
        Advance result=pending;current=result.state();pending=null;batch=null;delivery=null;return Optional.of(result);
    }
    public ChainInstance snapshot(){actor();return current;}
}
