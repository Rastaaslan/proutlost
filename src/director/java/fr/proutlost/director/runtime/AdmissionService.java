package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Common queued AdmissionPort for automatic, REQUIRE and admin requests.
 * resolveWindow is called once after collection, not after each arrival. Acceptance is a logical
 * reservation only; EventRunner still requires durable PREPARE and live permit revalidation.
 */
public final class AdmissionService implements AdmissionPort,ProviderProvenance {
    public record Assessment(ValidationResult validation,List<ResourceClaim> expandedClaims,
                             ResourceArbiter.Rank rank,Map<ScopeRef,BudgetVector> scopeCosts) {
        public Assessment {Objects.requireNonNull(validation);expandedClaims=List.copyOf(expandedClaims);Objects.requireNonNull(rank);scopeCosts=Map.copyOf(scopeCosts);}
    }
    /** Mandatory live validator: evaluates safety, uniqueness, cooldowns, audiences, read sets,
     * expanded primitive footprints and phase costs. It must not perform any world write.
     */
    public interface HardValidator {
        ProviderMode mode();
        Assessment assess(AdmissionRequest request,Tick now);
    }
    private record Pending(AdmissionRequest request,ReservationId id) {}
    private final Thread actor=Thread.currentThread();private final CatalogPort catalog;private final HardValidator validator;
    private final ResourceArbiter arbiter;private final Supplier<Tick> clock;private final DurationTicks lease;private final ProviderMode mode;private final int capacity;
    private final Map<DecisionId,Pending> requests=new LinkedHashMap<>();private final Map<DecisionId,AdmissionResult> decisions=new HashMap<>();
    public AdmissionService(CatalogPort catalog,HardValidator validator,ResourceArbiter arbiter,Supplier<Tick> clock,
                             DurationTicks lease,int capacity,ProviderMode mode){
        this.catalog=Objects.requireNonNull(catalog);this.validator=Objects.requireNonNull(validator);this.arbiter=Objects.requireNonNull(arbiter);this.clock=Objects.requireNonNull(clock);this.lease=Objects.requireNonNull(lease);this.mode=Objects.requireNonNull(mode);
        if(lease.value()<1||capacity<1)throw new IllegalArgumentException("admission bounds");this.capacity=capacity;
        if(mode==ProviderMode.PRODUCTION&&validator.mode()!=ProviderMode.PRODUCTION)throw new IllegalArgumentException("TEST_ONLY admission validator");
    }
    @Override public Set<ProviderMode> supportedModes(){return Set.of(mode);}
    public ProviderMode mode(){return mode;}
    public List<ReservationToken> snapshotReservations(){actor();return List.copyOf(arbiter.snapshot());}
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    private static AdmissionResult reject(ReasonCode reason){return new AdmissionResult(AdmissionStatus.REJECTED,Optional.empty(),List.of(reason),Optional.empty());}
    private static AdmissionResult defer(ReasonCode reason,Tick tick){return new AdmissionResult(AdmissionStatus.DEFERRED,Optional.empty(),List.of(reason),Optional.of(new RetryCondition(reason,Optional.of(tick),Set.of())));}
    private Optional<ReasonCode> contract(AdmissionRequest request){
        var plan=request.plan();var snapshot=catalog.current();var definition=snapshot.events().get(plan.definition().id());
        if(definition==null||!definition.enabled()||!definition.contentRevision().equals(plan.definition().revision())
                || !BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)).equals(plan.definition().hash())
                || mode==ProviderMode.PRODUCTION&&definition.calibration()!=Calibration.APPROVED)return Optional.of(ReasonCode.CATALOG_INVALID);
        if(!request.expectedRevisions().equals(plan.readSet())||!request.targetBindings().equals(plan.roles())||plan.effects().isEmpty())return Optional.of(ReasonCode.STALE_CONTEXT);
        EventInstanceId instance=plan.effects().getFirst().instanceId();Set<OperationToken> ops=new HashSet<>();
        for(var effect:plan.effects()){
            var primitive=snapshot.primitives().get(effect.primitive());
            if(primitive==null||primitive.version()!=effect.version()||mode==ProviderMode.PRODUCTION&&primitive.mode()!=ProviderMode.PRODUCTION)return Optional.of(ReasonCode.CAPABILITY_MISSING);
            if(!effect.instanceId().equals(instance)||!ops.add(effect.operationToken()))return Optional.of(ReasonCode.CATALOG_INVALID);
        }
        return Optional.empty();
    }
    public AdmissionResult admit(AdmissionRequest request){
        actor();Tick now;
        // A missing or broken time source is never permission to retain a
        // previously cached acceptance or to create a new reservation.
        try {now=Objects.requireNonNull(clock.get(),"admission clock unavailable");}
        catch(RuntimeException failedClock){return reject(ReasonCode.INTERNAL_FAULT);}
        Pending old=requests.get(request.proposalId());
        if(old!=null){
            if(!old.request().equals(request))return reject(ReasonCode.IDEMPOTENCY_CONFLICT);
            AdmissionResult ready=decisions.get(request.proposalId());
            if(ready!=null&&ready.token().isPresent()) {
                if(!arbiter.valid(ready.token().get(),now))return reject(ReasonCode.STALE_CONTEXT);
                // Idempotence is NOT immunity to a later catalogue reload or
                // live-safety change. Retain the original reservation for
                // explicit lifecycle release, but never reissue its token after
                // an invalid production dependency or unavailable provider.
                try {
                    var invalid=contract(request);
                    if(invalid.isPresent())return reject(invalid.get());
                    Assessment current=Objects.requireNonNull(validator.assess(request,now),"live assessment absent");
                    if(!current.validation().valid())
                        return new AdmissionResult(AdmissionStatus.REJECTED,Optional.empty(),
                            current.validation().reasons(),Optional.empty());
                    if(!current.validation().checkedVersions().equals(request.expectedRevisions()))
                        return reject(ReasonCode.STALE_CONTEXT);
                }catch(RuntimeException changedDependency){
                    return reject(ReasonCode.INTERNAL_FAULT);
                }
            }
            return ready!=null?ready:defer(ReasonCode.CLAIM_CONFLICT,now.plus(1));
        }
        // Catalog and live-provider failures are denials, never permission to
        // skip validation or an uncaught exception on the server actor.
        // No reservation is installed until every check has completed.
        try {
            var invalid=contract(request);if(invalid.isPresent())return reject(invalid.get());
            if(requests.size()>=capacity)return defer(ReasonCode.BUDGET_EXHAUSTED,now.plus(1));
            Assessment assessment=Objects.requireNonNull(validator.assess(request,now),"live assessment absent");
            if(!assessment.validation().valid())return new AdmissionResult(AdmissionStatus.REJECTED,Optional.empty(),assessment.validation().reasons(),Optional.empty());
            if(!assessment.validation().checkedVersions().equals(request.expectedRevisions()))return reject(ReasonCode.STALE_CONTEXT);
            var id=new ReservationId(UUID.nameUUIDFromBytes(("director-reservation-v1\0"+request.plan().scopes().worldId().value()+"\0"+request.proposalId().value()).getBytes(StandardCharsets.UTF_8)));
            requests.put(request.proposalId(),new Pending(request,id));return defer(ReasonCode.CLAIM_CONFLICT,now.plus(1));
        } catch(RuntimeException invalidCatalogOrProvider) {
            return reject(ReasonCode.INTERNAL_FAULT);
        }
    }
    /** A stored ACCEPTED decision may retain its claim for crash recovery,
     * but an old token must not reappear in a later decision-window response
     * after a catalogue reload, a live-safety failure or a clock outage.
     * Revalidation is a read: the original reservation stays lifecycle-owned.
     */
    private Map<DecisionId,AdmissionResult> visibleDecisions() {
        Map<DecisionId,AdmissionResult> visible=new HashMap<>(decisions);
        for(var entry:requests.entrySet()) {
            var result=decisions.get(entry.getKey());
            if(result!=null && result.token().isPresent())
                visible.put(entry.getKey(),admit(entry.getValue().request()));
        }
        return Map.copyOf(visible);
    }
    private Map<DecisionId,AdmissionResult> unavailableClockDecisions() {
        Map<DecisionId,AdmissionResult> visible=new HashMap<>(decisions);
        for(var entry:requests.entrySet()) {
            var result=decisions.get(entry.getKey());
            if(result!=null && result.token().isPresent())
                visible.put(entry.getKey(),reject(ReasonCode.INTERNAL_FAULT));
        }
        return Map.copyOf(visible);
    }
    public Map<DecisionId,AdmissionResult> resolveWindow(Map<ResourceKey,Epoch> epochs,Map<ScopeRef,BudgetVector> capacities){
        actor();Tick now;
        try {now=Objects.requireNonNull(clock.get(),"admission clock unavailable");}
        catch(RuntimeException failedClock) {
            // Keep existing terminal results and accepted claims for recovery,
            // but never fabricate a new logical acceptance when time is lost.
            for(var id:requests.keySet())decisions.putIfAbsent(id,reject(ReasonCode.INTERNAL_FAULT));
            return unavailableClockDecisions();
        }
        List<ResourceArbiter.Candidate> ready=new ArrayList<>();Map<ReservationId,DecisionId> ids=new HashMap<>();
        for(var e:requests.entrySet()) {
            if(decisions.containsKey(e.getKey()))continue;
            try {
                var request=e.getValue().request();var invalid=contract(request);
                if(invalid.isPresent()){decisions.put(e.getKey(),reject(invalid.get()));continue;}
                Assessment a=Objects.requireNonNull(validator.assess(request,now),"live assessment absent");
                if(!a.validation().valid()){decisions.put(e.getKey(),new AdmissionResult(AdmissionStatus.REJECTED,Optional.empty(),a.validation().reasons(),Optional.empty()));continue;}
                if(!a.validation().checkedVersions().equals(request.expectedRevisions())){decisions.put(e.getKey(),reject(ReasonCode.STALE_CONTEXT));continue;}
                // New content, including DANGER, cannot claim ongoing-stabilization urgency.
                var rank=new ResourceArbiter.Rank(false,a.rank().readyRequirement(),a.rank().chainContinuation(),a.rank().contentPriority(),a.rank().eligibleAge(),a.rank().score(),a.rank().seededTieBreak());
                ready.add(new ResourceArbiter.Candidate(e.getValue().id(),request.plan().effects().getFirst().instanceId(),rank,now.plus(lease.value()),a.expandedClaims(),a.scopeCosts()));ids.put(e.getValue().id(),e.getKey());
            } catch(RuntimeException invalidCatalogOrProvider) {
                // Reject only this decision; an unrelated healthy admission
                // can still be resolved on the same actor-owned window.
                decisions.put(e.getKey(),reject(ReasonCode.INTERNAL_FAULT));
            }
        }
        try {
            for(var d:arbiter.arbitrate(ready,now,epochs,capacities)) {
                decisions.put(ids.get(d.candidate()),d.accepted().isPresent()?new AdmissionResult(AdmissionStatus.ACCEPTED,d.accepted(),List.of(),Optional.empty())
                        :defer(d.rejection().orElseThrow(),now.plus(1)));
                for(var displaced:d.displaced())requests.forEach((id,p)->{if(p.id().equals(displaced.id()))decisions.put(id,reject(ReasonCode.STALE_CONTEXT));});
            }
        } catch(RuntimeException failedArbitration) {
            // An invalid bound, generation overflow or broken compatibility
            // must not escape onto the Minecraft server actor. In an ambiguous
            // partial arbitration, keep any held claims for reconciliation:
            // never release them or hand a token to J05 without an exact result.
            for(var candidate:ready)decisions.put(ids.get(candidate.id()),reject(ReasonCode.INTERNAL_FAULT));
        }
        return visibleDecisions();
    }
    /** Retry a deferred window only after its caller has refreshed all required evidence. */
    public void retire(DecisionId id){actor();AdmissionResult result=decisions.get(id);if(result!=null&&result.token().isPresent())throw new IllegalStateException("accepted reservation requires lifecycle release");requests.remove(id);decisions.remove(id);}
    public void released(DecisionId id,ReservationToken token){actor();var d=decisions.get(id);if(d==null||!d.token().equals(Optional.of(token))||arbiter.snapshot().contains(token))throw new IllegalStateException("reservation not released/transferred");requests.remove(id);decisions.remove(id);}
}
