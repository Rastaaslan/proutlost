package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.NativeWriterLossProbe;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;

/** Native source-set fixture mechanics; no client/WARN/full-catastrophe claim. */
public final class NativeWriterLossCases {
    private NativeWriterLossCases() {}
    public static final BudgetVector CAP =
            new BudgetVector(100,100,100,100,100,2048,4194304,100,1048576,4096);
    private static final Map<MinecraftServer,Object> ACTIVE = new IdentityHashMap<>();

    /** Shared actor gate: weather and terrain fixtures must never overlap. */
    public static synchronized boolean tryAcquire(MinecraftServer server, Object owner) {
        if (!server.isSameThread()) throw new IllegalStateException("server actor required");
        Object existing = ACTIVE.get(server);
        if (existing != null) return existing == owner;
        ACTIVE.put(server, Objects.requireNonNull(owner)); return true;
    }
    public static synchronized void release(MinecraftServer server, Object owner) {
        if (!server.isSameThread() || ACTIVE.get(server) != owner)
            throw new IllegalStateException("native fixture owner mismatch");
        ACTIVE.remove(server);
    }
    /** Include this in the native-only StartupClock pacing predicate. */
    public static synchronized boolean active(MinecraftServer server) {
        return ACTIVE.containsKey(server);
    }

    public static final class Execution {
        private final MinecraftServer server;
        private final NativeWriterLossProbe store;
        private final EffectPreparation captured;
        private final PreparedEffect plan;
        private final PrepareRecord prepare;
        private final ResourceArbiter arbiter;
        private final ReservationToken reservation;
        private final EventRunner runner;
        private final Supplier<Tick> clock;
        private final Function<SliceResult,List<DurableRecord>> resultRows;
        private final Optional<NativeWriterLossProbe.FailurePoint> failPoint;
        private LiveValidationSnapshot live;
        private EventRunner.View view;
        private SliceResult actual;
        private boolean submitted, released;

        public Execution(MinecraftServer server, WorldId world, NativeWriterLossProbe store,
                ServerEffectPort.Binding binding, EffectPreparation captured,
                Supplier<OwnershipView> owners, Supplier<Tick> clock,
                Function<SliceResult,List<DurableRecord>> resultRows,
                Optional<NativeWriterLossProbe.FailurePoint> failPoint) {
            this(server,world,store,binding,captured,owners,clock,resultRows,failPoint,Set.of());
        }
        /** Explicit effects must match the actual provider, including a durable damage allowance. */
        public Execution(MinecraftServer server, WorldId world, NativeWriterLossProbe store,
                ServerEffectPort.Binding binding, EffectPreparation captured,
                Supplier<OwnershipView> owners, Supplier<Tick> clock,
                Function<SliceResult,List<DurableRecord>> resultRows,
                Optional<NativeWriterLossProbe.FailurePoint> failPoint,
                Set<NoninvertibleEffect> noninvertibleEffects) {
            this.server=Objects.requireNonNull(server); this.store=Objects.requireNonNull(store);
            this.captured=Objects.requireNonNull(captured); this.clock=Objects.requireNonNull(clock);
            this.resultRows=Objects.requireNonNull(resultRows);
            this.failPoint=Objects.requireNonNull(failPoint); actor();
            plan=captured.prepared().orElseThrow();
            var intent=plan.intent(); var epochs=new HashMap<ResourceKey,Epoch>();
            plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
            arbiter=new ResourceArbiter(world,new Epoch(Math.max(store.session().bootEpoch().value(),1)),
                    1000,4,new ResourceArbiter.Compatibility() {
                public boolean share(ResourceClaim a,ResourceClaim b){return false;}
                public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}
            });
            var scope=new ScopeRef(ScopeKind.WORLD,world.value());
            var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),
                    intent.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),
                    clock.get().plus(4000),plan.claims(),Map.of(scope,plan.budgetEstimate()));
            reservation=arbiter.arbitrate(List.of(candidate),clock.get(),epochs,Map.of(scope,CAP))
                    .getFirst().accepted().orElseThrow();
            var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),
                    intent.expectedFootprint(),plan.preconditions(),List.of(intent),plan.budgetEstimate());
            prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),
                    reservation.generation(),plan.preconditions(),reservation.expiryTick(),slice,reservation,
                    captured.validation(),List.of(),noninvertibleEffects,List.of(),plan.durableBlobs());
            live=new LiveValidationSnapshot(plan.preconditions(),intent.expectedFootprint().loadedChunks(),
                    HealthMode.NORMAL,clock.get(),reservation.generation());
            var authority=new CommitPermit.Authority(store,4);
            var port=new ServerEffectPort(authority,x->{throw new IllegalStateException("already captured");},
                    x->live,Map.of(intent.primitive(),binding),binding.capabilities(),4,
                    x->new StabilizationLiveSnapshot(store.session(),live,owners.get()),m->{},(m,r)->{});
            port.hydrate(plan,captured.blobs());
            runner=new EventRunner(authority,store,port,arbiter,4);
        }
        private void actor() {
            if (!server.isSameThread()) throw new IllegalStateException("server actor required");
        }
        public PrepareRecord prepare(){return prepare;}
        public PreparedEffect plan(){return plan;}
        public Optional<SliceResult> actual(){actor();return Optional.ofNullable(actual);}

        public void submit() {
            actor(); if(submitted)throw new IllegalStateException("fixture execution already submitted");
            submitted=true;
            var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),
                    prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),
                    captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
            runner.submit(batch,plan.expectedPost(),live,result->{
                actual=result;
                var rows=new ArrayList<DurableRecord>();
                rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),
                        prepare.operationToken(),result,Set.of(),List.of(),List.of())));
                var extra=List.copyOf(resultRows.apply(result));
                if(extra.stream().anyMatch(r->r instanceof DurableResult || r instanceof DurablePrepare))
                    throw new IllegalArgumentException("resultRows must not add PREPARE/RESULT");
                rows.addAll(extra);
                var resultBatch=ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),rows,List.of());
                return failPoint.isPresent()
                        ? store.armResult(resultBatch,failPoint.orElseThrow()) : resultBatch;
            });
        }
        public EventRunner.View poll() {
            actor(); if(!submitted)throw new IllegalStateException("submit first");
            live=new LiveValidationSnapshot(live.readSet(),live.loadedChunks(),live.health(),
                    clock.get(),reservation.generation());
            return view=runner.poll(prepare.transactionId(),live);
        }
        public void releaseCommitted() {
            actor();
            if(view==null || view.stage()!=EventRunner.Stage.COMMITTED || released)
                throw new IllegalStateException("release only once after actual durable result");
            if(!arbiter.release(reservation))throw new IllegalStateException("reservation release failed");
            released=true;
        }
    }
}
