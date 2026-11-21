package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import java.util.concurrent.*;

/** Read-only native J07 WAL joins. These are pure contract tests, not GameTests. */
public final class AdminActiveWalProjectionTests {
    private static int assertions;
    private static UUID id(long n) { return new UUID(0,n); }
    private static final WorldId WORLD = new WorldId(id(1));
    private static final EventInstanceId EVENT = new EventInstanceId(id(2));
    private static final MutationId M1 = new MutationId(id(3));
    private static final MutationId M2 = new MutationId(id(4));
    private static final Sequence CUT = new Sequence(10);
    private static final BlobContent BLOB = BlobContent.capture(
        new ResourceId("proutlost_director:wal_join_test"),1,new byte[]{1,2,3});
    private static final EventFilter EVENT_FILTER = new EventFilter(Optional.empty(),Set.of());
    private static void check(boolean condition,String why) {
        if (!condition) throw new AssertionError(why);
        assertions++;
    }
    private static AggregateHeader header(UUID identity) {
        return new AggregateHeader(1,identity,new Revision(1),new Sequence(2),new Tick(2));
    }
    private static EventInstance event(Set<MutationId> mutations,
            Set<ReservationId> reservations, Set<EffectId> effects) {
        return new EventInstance(header(EVENT.value()),EVENT,
            new DefinitionRef(new DefinitionId("proutlost:test"),new Revision(1),
                BLOB.reference().hash()),new Epoch(1),EventState.RUNNING,"phase",
            new RoleAssignments(Map.of()),Set.of(),BLOB.reference(),Map.of(),
            reservations,effects,mutations,Optional.empty(),Set.of(),
            Optional.of(new Tick(1)),new DurationTicks(1),new DurationTicks(100),
            RecoveryMode.RECOVER,Optional.empty());
    }
    private static MutationRecord mutation(MutationId identity,EventInstanceId owner) {
        return mutation(identity,owner,header(identity.value()),new Sequence(2));
    }
    private static MutationRecord mutation(MutationId identity,EventInstanceId owner,
            AggregateHeader header,Sequence durable) {
        return new MutationRecord(header,identity,owner,
            new SliceId(id(5)),MutationState.VERIFIED,
            new PrimitiveId("proutlost:trace"),1,
            new Footprint(new DimensionId("minecraft:overworld"),Set.of(),Set.of(),List.of()),
            BLOB.reference(),BLOB.reference(),new OwnerToken(id(6)),new Epoch(1),
            new ReadSet(Map.of(),Map.of(),Map.of()),Set.of(),List.of(),
            Optional.empty(),durable);
    }
    private static final class FakeStore implements DurableStore {
        final StoreSession session = new StoreSession(WORLD,new Epoch(1),
            new Epoch(1),new SessionId(id(8)));
        final Map<MutationId,MutationRecord> records = new HashMap<>();
        Sequence responseCut=CUT;
        boolean dropOne,duplicate,offWorld,failRead,rebindAfterRead,readCompleted;
        StoreSession replySession;
        StoreQuery lastQuery;
        int reads;
        FakeStore() {records.put(M1,mutation(M1,EVENT)); records.put(M2,mutation(M2,EVENT));}
        @Override public StoreSession session() {
            if(rebindAfterRead && readCompleted)return new StoreSession(WORLD,new Epoch(1),new Epoch(2),new SessionId(id(98)));
            return offWorld?new StoreSession(new WorldId(id(101)),new Epoch(1),
                new Epoch(1),new SessionId(id(8))):session;
        }
        @Override public StoreReadResult view(StoreQuery query) {
            reads++;lastQuery=query;
            if(failRead) throw new IllegalStateException("simulated J04 fault");
            var restored=new ArrayList<RestoredAggregate>();
            for(var key:query.aggregateKeys()) {
                for(var record:records.values()) {
                    if(key.kind()==AggregateKind.MUTATION &&
                            key.id().equals(record.mutationId().value())) {
                        if(!dropOne || !record.mutationId().equals(M2)) restored.add(
                            new RestoredAggregate(new MutationAggregate(record),
                                new Sequence(2),BLOB.reference().hash()));
                    }
                }
            }
            if(duplicate && !restored.isEmpty())restored.add(restored.getFirst());
            var stamp=new ReadStamp(replySession==null?session():replySession,responseCut,ReadContracts.queryHash(query));
            var result=new StoreReadResult(ReadStatus.AVAILABLE,
                Optional.of(new StoreView(List.of(),restored,responseCut,
                    false,Optional.empty())),Optional.of(stamp),List.of());
            readCompleted=true;return result;
        }
        @Override public CompletionStage<CommitAck> append(DurableBatch ignored) {
            throw new UnsupportedOperationException("read-only test");
        }
        @Override public CompletionStage<StoreReadResult> read(StoreQuery ignored) {
            throw new UnsupportedOperationException("read-only test");
        }
        @Override public CompletionStage<BlobReadResult> readBlobs(BlobReadRequest ignored) {
            throw new UnsupportedOperationException("read-only test");
        }
    }
    private static Optional<AdminRow> query(FakeStore store,EventInstance event) {
        return AdminActiveWalProjection.row(store,new EventAggregate(event),
            EVENT_FILTER,WORLD,CUT);
    }
    private static EventInstance withHeader(EventInstance previous,AggregateHeader h) {
        return new EventInstance(h,previous.instanceId(),previous.definition(),
            previous.catalogGeneration(),previous.state(),previous.phase(),
            previous.roleAssignments(),previous.regionIds(),previous.contextRef(),
            previous.stepStates(),previous.reservationRefs(),previous.effectRefs(),
            previous.mutationRefs(),previous.chainId(),previous.requirementIds(),
            previous.startedTick(),previous.elapsedActiveTicks(),
            previous.maxDurationTicks(),previous.recoveryPolicy(),previous.outcome());
    }
    private static ChainInstance withHeader(ChainInstance previous,AggregateHeader h) {
        return new ChainInstance(h,previous.chainId(),previous.recipe(),
            previous.mode(),previous.state(),previous.currentNodes(),
            previous.consumedFactIds(),previous.lineageRefs(),
            previous.plannedHorizon(),previous.branchHistory(),
            previous.regionalStateRef(),previous.relevance(),
            previous.nextEvaluationTick());
    }
    private static DisasterInstance disaster(AggregateHeader h,Set<MutationId> refs) {
        var disaster=new DisasterId(id(77));
        return new DisasterInstance(h,disaster,EVENT,new DefinitionId("proutlost:test_disaster"),
            DisasterStage.WARN,Map.of(),new UnitValue(.4),
            new Footprint(new DimensionId("minecraft:overworld"),Set.of(),Set.of(),List.of()),
            new WarningEvidence(Set.of(),new Tick(1),new DurationTicks(20),BLOB.reference().hash(),new Epoch(1),false,false),
            Set.of(),List.of(),BLOB.reference(),refs,"warning_pending",Set.of(new FactId(id(78))));
    }
    private static Optional<AdminRow> disasterRow(FakeStore store,DisasterInstance value,EventFilter filter) {
        return AdminActiveWalProjection.row(store,new DisasterAggregate(value),filter,WORLD,CUT);
    }
    private static void disasters() {
        var actual=disaster(header(id(77)),Set.of(M1,M2));
        var store=new FakeStore();var found=disasterRow(store,actual,EVENT_FILTER).orElseThrow();
        check(found.scope().equals(new ScopeRef(ScopeKind.EVENT,EVENT.value()))
            && found.data() instanceof DisasterAdminData data && data.disaster().equals(actual),
            "ACTIVE preserves the real DisasterInstance and real event scope, never a fake EventAggregate");
        check(store.reads==1 && store.lastQuery.aggregateKeys().equals(Set.of(
            new AggregateKey(AggregateKind.MUTATION,M1.value()),new AggregateKey(AggregateKind.MUTATION,M2.value())))
            && store.lastQuery.limit()==2 && store.lastQuery.pageCursor().isEmpty(),
            "disaster resolves exactly its actual bounded mutation keys at the caller cut");
        check(disasterRow(new FakeStore(),actual,new EventFilter(Optional.of(EVENT),Set.of())).isPresent(),
            "disaster matches its linked EventInstanceId");
        var wrongId=new FakeStore();
        check(disasterRow(wrongId,actual,new EventFilter(Optional.of(new EventInstanceId(actual.disasterId().value())),Set.of())).isEmpty()
            && wrongId.reads==0,"DisasterId is never mistaken for EventInstanceId");
        for(var state:EventState.values()){
            var filtered=new FakeStore();
            check(disasterRow(filtered,actual,new EventFilter(Optional.empty(),Set.of(state))).isEmpty()
                && filtered.reads==0,"EventState filter excludes disaster without converting its stage: "+state);
        }
        var missing=new FakeStore();missing.dropOne=true;
        check(disasterRow(missing,actual,EVENT_FILTER).isEmpty(),"missing referenced disaster mutation withholds row");
        var foreign=new FakeStore();foreign.records.put(M2,mutation(M2,new EventInstanceId(id(90))));
        check(disasterRow(foreign,actual,EVENT_FILTER).isEmpty(),"disaster mutation must belong to its linked event");
        var mixedCut=new FakeStore();mixedCut.responseCut=new Sequence(11);
        check(disasterRow(mixedCut,actual,EVENT_FILTER).isEmpty(),"disaster and mutation page must use the same WAL cut");
        var forgedHeader=new FakeStore();forgedHeader.records.put(M2,mutation(M2,EVENT,header(id(91)),new Sequence(2)));
        check(disasterRow(forgedHeader,actual,EVENT_FILTER).isEmpty(),"mutation key cannot certify a foreign embedded header");
        var futureMutation=new FakeStore();futureMutation.records.put(M2,mutation(M2,EVENT,header(M2.value()),new Sequence(11)));
        check(disasterRow(futureMutation,actual,EVENT_FILTER).isEmpty(),"future mutation durable sequence withheld");
        var futureDisaster=new FakeStore();
        check(disasterRow(futureDisaster,disaster(new AggregateHeader(1,id(77),new Revision(1),new Sequence(11),new Tick(2)),Set.of(M1)),EVENT_FILTER).isEmpty()
            && futureDisaster.reads==0,"future disaster header sequence withheld before join");
        check(disasterRow(new FakeStore(),disaster(header(id(92)),Set.of()),EVENT_FILTER).isEmpty(),
            "actual DisasterId/header identity must agree");
        for(var stamp:List.of(new StoreSession(WORLD,new Epoch(2),new Epoch(1),new SessionId(id(8))),
                new StoreSession(WORLD,new Epoch(1),new Epoch(2),new SessionId(id(8))),
                new StoreSession(WORLD,new Epoch(1),new Epoch(1),new SessionId(id(93))))){
            var rebound=new FakeStore();rebound.replySession=stamp;
            check(disasterRow(rebound,actual,EVENT_FILTER).isEmpty(),"foreign boot/generation/session mutation stamp rejected");
        }
        var changed=new FakeStore();changed.rebindAfterRead=true;
        check(disasterRow(changed,actual,EVENT_FILTER).isEmpty(),"session change while resolving disaster mutations withholds row");
        var noRefs=new FakeStore();
        check(disasterRow(noRefs,disaster(header(id(77)),Set.of()),EVENT_FILTER).isPresent()&&noRefs.reads==0,
            "disaster without mutation refs requires no manufactured join");
        var many=new HashSet<MutationId>();for(int n=100;n<117;n++)many.add(new MutationId(id(n)));
        var bounded=new FakeStore();check(disasterRow(bounded,disaster(header(id(77)),many),EVENT_FILTER).isEmpty()&&bounded.reads==0,
            "disaster mutation bound16 is retained");
        var lines=fr.proutlost.director.admin.client.AdminDetails.lines(found,20);
        check(lines.getFirst().equals("CATASTROPHE / phase durable : WARN")
            && lines.contains("Catastrophe : "+actual.disasterId().value())
            && lines.contains("Evenement lie : "+EVENT.value()),"F8 text exposes distinct recorded disaster/event identities and stage");
        check(lines.stream().anyMatch(l->l.startsWith("WARN WAL :")&&l.contains("lisible=false"))
            && lines.stream().anyMatch(l->l.contains("faits non joints")),
            "F8 warning flags remain labelled WAL data and consequence refs remain unresolved");
        check(fr.proutlost.director.admin.client.AdminDetails.lines(found,2).size()==2,
            "disaster text still obeys its bounded detail limit");
    }
    public static void main(String[] args) {
        var store=new FakeStore();
        var actual=event(Set.of(M1,M2),Set.of(),Set.of());
        var resolved=query(store,actual);
        check(resolved.isPresent(),"complete same-cut mutation join");
        var data=(EventAdminData)resolved.orElseThrow().data();
        check(data.mutations().size()==2 && data.mutations().stream()
            .map(MutationRecord::mutationId).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(M1,M2)),"each required mutation is present");
        check(data.claims().isEmpty() && data.facts().isEmpty(),
            "empty unsupported collections only when unreferenced");
        check(store.reads==1,"one exact bounded J04 page");
        var empty=new FakeStore();
        var noRefs=query(empty,event(Set.of(),Set.of(),Set.of()));
        check(noRefs.isPresent() && ((EventAdminData)noRefs.orElseThrow().data())
            .mutations().isEmpty() && empty.reads==0,
            "no read or manufactured mutation for event without references");
        var partial=new FakeStore();partial.dropOne=true;
        check(query(partial,actual).isEmpty(),"missing mutation withheld");
        var duplicate=new FakeStore();duplicate.duplicate=true;
        check(query(duplicate,actual).isEmpty(),"duplicate aggregate withheld");
        var foreign=new FakeStore();foreign.records.put(M2,
            mutation(M2,new EventInstanceId(id(333))));
        check(query(foreign,actual).isEmpty(),"foreign event mutation withheld");
        var stale=new FakeStore();stale.responseCut=new Sequence(11);
        check(query(stale,actual).isEmpty(),"different J04 cut withheld");
        var invalid=new FakeStore();invalid.failRead=true;
        check(query(invalid,actual).isEmpty(),"J04 unavailable fails closed");
        var wrongWorld=new FakeStore();wrongWorld.offWorld=true;
        check(query(wrongWorld,actual).isEmpty(),"foreign world session denied");
        var forgedEvent=new FakeStore();
        check(query(forgedEvent,withHeader(actual,header(id(900)))).isEmpty()
            && forgedEvent.reads==0,"foreign event header identity withheld");
        var futureEvent=new FakeStore();
        check(query(futureEvent,withHeader(actual,new AggregateHeader(1,EVENT.value(),
            new Revision(1),new Sequence(11),new Tick(2)))).isEmpty()
            && futureEvent.reads==0,"future event header WAL cut withheld");
        var unboundClaims=new FakeStore();
        check(query(unboundClaims,event(Set.of(M1),Set.of(new ReservationId(id(22))),
            Set.of())).isEmpty() && unboundClaims.reads==0,
            "no partial success with unresolved reservation");
        var unboundEffects=new FakeStore();
        check(query(unboundEffects,event(Set.of(M1),Set.of(),
            Set.of(new EffectId(id(23))))).isEmpty() && unboundEffects.reads==0,
            "no partial success with unresolved effects");
        var oversized=new FakeStore();
        Set<MutationId> many=new HashSet<>();
        for(long n=100;n<118;n++)many.add(new MutationId(id(n)));
        check(query(oversized,event(many,Set.of(),Set.of())).isEmpty()
            && oversized.reads==0,"unbounded joins refused before read");
        var filterStore=new FakeStore();
        check(AdminActiveWalProjection.row(filterStore,new EventAggregate(actual),
            new EventFilter(Optional.of(new EventInstanceId(id(999))),Set.of()),
            WORLD,CUT).isEmpty() && filterStore.reads==0,"ID filter before reads");
        var chainId=new ChainId(id(30));
        var chain=new ChainInstance(header(chainId.value()),chainId,
            new DefinitionRef(new DefinitionId("proutlost:test_chain"),
                new Revision(1),BLOB.reference().hash()),ChainMode.DYNAMIC,
            ChainState.ACTIVE,Set.of("active"),Set.of(),Set.of(),List.of(),List.of(),
            Optional.empty(),new UnitValue(1),new Tick(2));
        check(AdminActiveWalProjection.row(new FakeStore(),
            new ChainAggregate(chain),new ChainFilter(Optional.of(chainId)),
            WORLD,CUT).isPresent(),"chain without consumed facts remains visible");
        check(AdminActiveWalProjection.row(new FakeStore(),
            new ChainAggregate(withHeader(chain,header(id(901)))),
            new ChainFilter(Optional.empty()),WORLD,CUT).isEmpty(),
            "foreign chain header identity withheld");
        check(AdminActiveWalProjection.row(new FakeStore(),
            new ChainAggregate(withHeader(chain,new AggregateHeader(1,chainId.value(),
                new Revision(1),new Sequence(11),new Tick(2)))),
            new ChainFilter(Optional.empty()),WORLD,CUT).isEmpty(),
            "future chain header WAL cut withheld");
        var withFact=new ChainInstance(chain.header(),chain.chainId(),
            chain.recipe(),chain.mode(),chain.state(),chain.currentNodes(),
            Set.of(new FactId(id(55))),chain.lineageRefs(),chain.plannedHorizon(),
            chain.branchHistory(),chain.regionalStateRef(),chain.relevance(),
            chain.nextEvaluationTick());
        check(AdminActiveWalProjection.row(new FakeStore(),new ChainAggregate(withFact),
            new ChainFilter(Optional.empty()),WORLD,CUT).isEmpty(),
            "chain with unjoined facts withheld");
        disasters();
        System.out.println("DIRECTOR_ADMIN_ACTIVE_WAL_JOIN_PASS assertions="+assertions);
    }
}
