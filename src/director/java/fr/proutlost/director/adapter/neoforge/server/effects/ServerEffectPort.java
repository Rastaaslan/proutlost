package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.WorldEffectPort;
import fr.proutlost.director.api.port.ProviderProvenance;
import java.util.*;
import java.util.function.*;

/** Actor-only dispatch boundary. Production bindings and qualification are separate checks.
 * No binding can be reached through apply until the exact durable slice permit is consumed.
 */
public final class ServerEffectPort implements InstalledEffectPort,ProviderProvenance {
    public interface Binding {
        ProviderMode mode();
        /** Exact primitive IDs this physical implementation accepts; never inferred from capabilities. */
        Set<PrimitiveId> supportedPrimitives();
        Set<CapabilityId> capabilities();
        EffectPreparation capture(EffectIntent intent,PlanningSnapshot context);
        boolean revalidate(PreparedEffect plan,BlobBundle blobs);
        SliceResult apply(PreparedEffect plan,BlobBundle blobs);
        ReconcileResult reconcile(RecoveryProbe probe);
    }
    private record Captured(PreparedEffect plan,BlobBundle blobs,Binding binding) {}
    private final Thread actor=Thread.currentThread();
    private final CommitPermit.Authority authority;
    private final Function<EffectIntent,PlanningSnapshot> planning;
    private final Function<PreparedSlice,LiveValidationSnapshot> live;
    private final Function<PreparedSlice,StabilizationLiveSnapshot> stopLive;
    private final Consumer<StabilizationMarker> localMarker;
    private final BiConsumer<StabilizationMarker,SliceResult> localOutcome;
    private final Map<PrimitiveId,Binding> bindings;
    private final Set<CapabilityId> qualified;
    private final int capacity;
    private final Map<OperationToken,Captured> prepared=new HashMap<>();
    private final Map<OperationToken,PreparedSlice> cleanupSlices=new HashMap<>();
    public ServerEffectPort(CommitPermit.Authority authority,Function<EffectIntent,PlanningSnapshot> planning,
                            Function<PreparedSlice,LiveValidationSnapshot> live,Map<PrimitiveId,Binding> bindings,
                            Set<CapabilityId> qualified,int capacity,
                            Function<PreparedSlice,StabilizationLiveSnapshot> stopLive,
                            Consumer<StabilizationMarker> localMarker,
                            BiConsumer<StabilizationMarker,SliceResult> localOutcome) {
        this.authority=Objects.requireNonNull(authority);this.planning=Objects.requireNonNull(planning);this.live=Objects.requireNonNull(live);
        this.stopLive=Objects.requireNonNull(stopLive);this.localMarker=Objects.requireNonNull(localMarker);this.localOutcome=Objects.requireNonNull(localOutcome);
        this.bindings=Map.copyOf(bindings);this.qualified=Set.copyOf(qualified);if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;
        if(bindings.values().stream().anyMatch(b->b.mode()!=ProviderMode.PRODUCTION))throw new IllegalArgumentException("TEST_ONLY gameplay binding");
        // Binding a capability-compatible implementation under a foreign primitive
        // ID would let an approved permit invoke an unrelated physical mutation.
        // A native adapter must claim its exact J02 identity, not only its caps.
        for(var entry:this.bindings.entrySet()) {
            if(!Objects.requireNonNull(entry.getValue().supportedPrimitives(),"physical primitive identity")
                    .contains(entry.getKey()))
                throw new IllegalArgumentException("PHYSICAL_PRIMITIVE_BINDING_MISMATCH: "+entry.getKey().value());
        }
    }
    @Override public Set<ProviderMode> supportedModes(){
        return Set.of(authority.supportedModes().contains(ProviderMode.PRODUCTION)
            ?ProviderMode.PRODUCTION:ProviderMode.TEST_ONLY);
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    /** Safe, immutable J05 inventory. Never expose the live Binding objects:
     * their apply() method is a physical mutation entry point without a permit.
     */
    public record BindingView(ProviderMode mode, Set<PrimitiveId> primitives,
                              Set<CapabilityId> capabilities, boolean qualified) {
        public BindingView {
            Objects.requireNonNull(mode);
            primitives=Set.copyOf(primitives);
            capabilities=Set.copyOf(capabilities);
        }
    }
    public Map<PrimitiveId, BindingView> installedBindingDescriptors() {
        actor();
        Map<PrimitiveId, BindingView> views=new HashMap<>();
        bindings.forEach((id,b)->views.put(id,new BindingView(b.mode(),
            b.supportedPrimitives(),b.capabilities(),qualified.containsAll(b.capabilities()))));
        return Map.copyOf(views);
    }
    private Binding binding(PrimitiveId id){Binding b=bindings.get(id);if(b==null || !qualified.containsAll(b.capabilities()))throw new IllegalArgumentException("CAPABILITY_MISSING: "+id.value());return b;}
    public EffectPreparation prepare(EffectIntent intent) {
        actor();Binding b=binding(intent.primitive());
        if(prepared.containsKey(intent.operationToken()) || prepared.size()>=capacity)throw new IllegalStateException("prepared operation capacity/duplicate");
        EffectPreparation capture=b.capture(intent,planning.apply(intent));
        if(!capture.intent().equals(intent))throw new IllegalArgumentException("capture identity");
        if(capture.validation().valid())hydrate(capture.prepared().orElseThrow(),capture.blobs());
        return capture;
    }
    /** Bytes are fetched asynchronously by the coordinator; hydration itself is immutable data. */
    public void hydrate(PreparedEffect plan,BlobBundle blobs) {
        actor();Binding b=binding(plan.intent().primitive());
        // A durable PREPARE must not silently change its required capability
        // set when rebound to a live physical implementation.
        if(!plan.capabilities().equals(b.capabilities()))
            throw new IllegalArgumentException("prepared physical capabilities differ from binding");
        Set<BlobRef> refs=CommitPermit.Authority.references(plan.durableBlobs());
        // PREPARE seals the exact immutable references. Accepting extra bytes
        // would let a physical binding consume unjournaled data after fsync.
        // BlobBundle already enforces unique refs and verifies each digest.
        Set<BlobRef> hydratedRefs=blobs.contents().stream()
            .map(BlobContent::reference).collect(java.util.stream.Collectors.toSet());
        if(!hydratedRefs.equals(refs))
            throw new IllegalArgumentException("hydration differs from durable PREPARE");
        if(!prepared.containsKey(plan.intent().operationToken()) && prepared.size()>=capacity)throw new IllegalStateException("prepared capacity");
        Captured old=prepared.get(plan.intent().operationToken());
        if(old!=null && (!old.plan().equals(plan)||!old.blobs().equals(blobs)))throw new IllegalArgumentException("operation substitution");
        prepared.put(plan.intent().operationToken(),new Captured(plan,blobs,b));
    }
    private List<Captured> validate(PreparedSlice slice) {
        actor();if(slice.operations().isEmpty())throw new IllegalArgumentException("empty physical slice");
        List<Captured> captures=new ArrayList<>();Set<OperationToken> tokens=new HashSet<>();
        Set<ResourceKey> postKeys=new HashSet<>();BudgetVector total=BudgetMath.zero();
        for(var op:slice.operations()) {
            Captured c=prepared.get(op.operationToken());
            if(c==null)throw new IllegalArgumentException("operation not hydrated");
            if(!tokens.add(op.operationToken()) || !c.plan().intent().equals(op)
                    || c.binding().mode()!=ProviderMode.PRODUCTION
                    || !c.plan().capabilities().equals(c.binding().capabilities())
                    || !qualified.containsAll(c.plan().capabilities())
                    || !slice.footprint().equals(op.expectedFootprint()) || !c.plan().preconditions().equals(slice.preconditions())
                    || !c.binding().revalidate(c.plan(),c.blobs()))throw new IllegalArgumentException("stale physical plan");
            // All preconditions are checked BEFORE any physical effect. Two
            // operations claiming the same post-state would silently execute
            // under a stale read/CAS without an ordered intermediate PREPARE.
            if(!Collections.disjoint(postKeys,c.plan().expectedPost().keySet()))
                throw new IllegalArgumentException("overlapping physical post-state");
            postKeys.addAll(c.plan().expectedPost().keySet());
            total=BudgetMath.add(total,c.plan().budgetEstimate());captures.add(c);
        }
        if(!BudgetMath.fits(total,slice.maximumWork()))throw new IllegalArgumentException("slice work budget");
        return List.copyOf(captures);
    }
    public SliceResult apply(PreparedSlice slice,CommitPermit permit) {
        var captures=validate(slice);
        permit.consume(authority,slice,live.apply(slice));
        return execute(captures);
    }
    /** Stop is independent of writer availability. The supplied snapshot is cross-checked with
     * a fresh actor capture; callbacks cannot manufacture world access or substitute ownership.
     * Marker and outcome sinks must be world-local actor writes, never durable-writer IO.
     */
    public SliceResult stabilize(PreparedSlice slice,CommitPermit.StabilizationPermit permit,StabilizationLiveSnapshot supplied) {
        var captures=validate(slice);
        var fresh=Objects.requireNonNull(stopLive.apply(slice));
        if(!fresh.equals(supplied))throw new IllegalArgumentException("stale stop snapshot");
        var marker=permit.consume(authority,slice,fresh);
        localMarker.accept(marker); // If this fails the authority remains spent: reconcile, never retry.
        var result=execute(captures);
        localOutcome.accept(marker,result);
        return result;
    }
    private SliceResult execute(List<Captured> captures) {
        Set<OperationToken> applied=new HashSet<>();Map<ResourceKey,Fingerprint> hashes=new HashMap<>();Set<ResourceKey> released=new HashSet<>();
        Set<FactId> factIds=new HashSet<>();
        List<FactEvidence> evidence=new ArrayList<>();Set<BlockPosition> conflicts=new HashSet<>();long remaining=0;Outcome outcome=Outcome.SUCCEEDED;
        for(var c:captures) {
            SliceResult result=c.binding().apply(c.plan(),c.blobs());
            // A physical effect may already have occurred. Reject an unbound result
            // rather than publish fabricated foreign operation/resource evidence.
            // The durable coordinator must reconcile an ambiguous physical outcome.
            if(!Set.of(c.plan().intent().operationToken()).containsAll(result.appliedOperationIds())
                    ||result.outcome()==Outcome.SUCCEEDED && (!result.appliedOperationIds().contains(c.plan().intent().operationToken())
                        ||result.remainingWork()!=0 || !result.conflictCells().isEmpty())
                    ||!c.plan().expectedPost().keySet().containsAll(result.observedHashes().keySet())
                    // A physical SUCCESS cannot omit or forge the durable post-state. The
                    // stop path has a world-local outcome sink and bypasses EventRunner.
                    ||result.outcome()==Outcome.SUCCEEDED
                        && !result.observedHashes().entrySet().containsAll(c.plan().expectedPost().entrySet())
                    ||!c.plan().intent().expectedFootprint().cells().containsAll(result.conflictCells())
                    ||!c.plan().preconditions().epochs().keySet().containsAll(result.releasedClaims()))
                throw new IllegalStateException("out-of-slice physical outcome; durable reconciliation required");
            // A confirmed J05 fact may only cite physical observations that
            // this exact operation actually returned. Also prohibit duplicate
            // fact IDs across a multi-operation slice, before J04 result ACK.
            for(FactEvidence fact:result.factEvidence()) {
                if(!factIds.add(fact.factId()) ||
                        fact.status()==EvidenceStatus.CONFIRMED &&
                        // A confirmed *physical* fact must have an actual observation.
                        // An empty map would satisfy the all-matching test vacuously
                        // and launder a fact without native post-state evidence.
                        (fact.observations().isEmpty() ||
                        fact.observations().entrySet().stream().anyMatch(entry ->
                            !entry.getValue().equals(result.observedHashes().get(entry.getKey())))))
                    throw new IllegalStateException("unbound physical fact evidence; durable reconciliation required");
            }
            applied.addAll(result.appliedOperationIds());hashes.putAll(result.observedHashes());released.addAll(result.releasedClaims());evidence.addAll(result.factEvidence());conflicts.addAll(result.conflictCells());
            remaining=Math.addExact(remaining,result.remainingWork());
            if(result.outcome()!=Outcome.SUCCEEDED){outcome=Outcome.PARTIAL;break;}
        }
        return new SliceResult(outcome,applied,hashes,remaining,released,conflicts,evidence);
    }
    /** Normal cleanup is another prepared inverse mutation with its own durable permit.
     * Writer-lost stabilization uses the separately forced stop authority.
     */
    public void bindCleanup(CleanupIntent intent,PreparedSlice inverse) {
        actor();if(!intent.operationToken().equals(inverse.operationToken()) || !intent.footprint().equals(inverse.footprint())
                || inverse.operations().stream().anyMatch(o->!o.instanceId().equals(intent.instanceId())))throw new IllegalArgumentException("cleanup binding");
        if(cleanupSlices.size()>=capacity || cleanupSlices.putIfAbsent(intent.operationToken(),inverse)!=null)throw new IllegalStateException("cleanup capacity/duplicate");
    }
    public CleanupResult cleanup(CleanupIntent intent,CommitPermit permit) {
        actor();PreparedSlice slice=Objects.requireNonNull(cleanupSlices.get(intent.operationToken()),"cleanup inverse not prepared");
        if(!slice.footprint().equals(intent.footprint()))throw new IllegalArgumentException("cleanup footprint");
        SliceResult r=apply(slice,permit);
        // A partial/ambiguous cleanup retains its registered inverse for WAL
        // reconciliation. Never silently treat a partially applied inverse as done.
        if(r.outcome()==Outcome.SUCCEEDED&&r.remainingWork()==0&&r.conflictCells().isEmpty())
            cleanupSlices.remove(intent.operationToken());
        return new CleanupResult(r.outcome(),r.appliedOperationIds(),r.observedHashes(),r.remainingWork(),r.releasedClaims(),r.conflictCells(),r.factEvidence());
    }
    public ReconcileResult reconcile(RecoveryProbe probe){actor();return binding(probe.durableIntent().intent().primitive()).reconcile(probe);}
    public void releaseHydrated(OperationToken operation){actor();prepared.remove(operation);}
}
