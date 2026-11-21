package fr.proutlost.director.runtime;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Actor-owned logical reservations, not world-write authority or durable admission.
 * The coordinator supplies validated priorities and live epochs, journals transitions,
 * and must mark an effect started before handing its first slice to WorldEffectPort.
 * Scope budget keys must include every applicable world/dimension/region/audience scope.
 */
public final class ResourceArbiter {
    public record Rank(boolean stabilization, boolean readyRequirement, boolean chainContinuation,
                       Priority contentPriority, long eligibleAge, double score, long seededTieBreak) {
        public Rank {
            Objects.requireNonNull(contentPriority);
            if (eligibleAge < 0 || !Double.isFinite(score) || score < 0) throw new IllegalArgumentException("rank");
        }
    }
    public record Candidate(ReservationId id, EventInstanceId instance, Rank rank, Tick expiry,
                            List<ResourceClaim> claims, Map<ScopeRef, BudgetVector> scopeCosts,List<PhysicalResourceClaim> physicalResources) {
        public Candidate(ReservationId id,EventInstanceId instance,Rank rank,Tick expiry,List<ResourceClaim> claims,Map<ScopeRef,BudgetVector> scopeCosts){this(id,instance,rank,expiry,claims,scopeCosts,List.of());}
        public Candidate {
            Objects.requireNonNull(id); Objects.requireNonNull(instance); Objects.requireNonNull(rank);
            Objects.requireNonNull(expiry); claims = List.copyOf(claims); scopeCosts = Map.copyOf(scopeCosts);physicalResources=List.copyOf(physicalResources);
            if(physicalResources.size()>256||physicalResources.stream().map(PhysicalResourceClaim::resource).distinct().count()!=physicalResources.size())throw new IllegalArgumentException("duplicate/bounded physical resources");
            if (claims.stream().map(ResourceClaim::resource).distinct().count() != claims.size())
                throw new IllegalArgumentException("duplicate resource claim");
        }
    }
    public record Decision(ReservationId candidate, Optional<ReservationToken> accepted,
                           Optional<ReasonCode> rejection, List<ReservationToken> displaced) {
        public Decision { displaced = List.copyOf(displaced); }
    }
    /** A registered mixer/merge must have separately tested algebra and contributor compensation.
     * This policy only admits sharing a claim; it cannot perform world effects.
     */
    public interface Compatibility {
        boolean share(ResourceClaim left, ResourceClaim right);
        boolean merge(ResourceId function, ResourceClaim left, ResourceClaim right);
    }
    private record Held(Candidate candidate, ReservationToken token, boolean started) {}
    private final Thread actor = Thread.currentThread();
    private final WorldId world;
    private final long ageCap;
    private final int windowLimit;
    private final Compatibility compatibility;
    private final Map<ReservationId, Held> held = new HashMap<>();
    private record Borrowed(ReservationToken token,ReservationToken parent){}
    private final Map<ReservationId,Borrowed> borrowed=new HashMap<>();
    private long generation;
    private Tick now = new Tick(0);

    /** generationFloor is restored from the durable reservation projection at bootstrap. */
    public ResourceArbiter(WorldId world, Epoch generationFloor, long ageCap, int windowLimit,
                           Compatibility compatibility) {
        this.world = Objects.requireNonNull(world); generation = generationFloor.value();
        if (ageCap < 0 || windowLimit < 1) throw new IllegalArgumentException("bounds");
        this.ageCap = ageCap; this.windowLimit = windowLimit;
        this.compatibility = Objects.requireNonNull(compatibility);
    }
    private void actor() {
        if (Thread.currentThread() != actor) throw new IllegalStateException("server actor required");
    }
    private void advance(Tick tick) {
        actor(); if (tick.compareTo(now) < 0) throw new IllegalArgumentException("clock regression"); now = tick;
    }
    private int compare(Candidate a, Candidate b) {
        Rank x = a.rank(), y = b.rank();
        int c = Boolean.compare(y.stabilization(), x.stabilization());
        if (c == 0) c = Boolean.compare(y.readyRequirement(), x.readyRequirement());
        if (c == 0) c = Boolean.compare(y.chainContinuation(), x.chainContinuation());
        if (c == 0) c = y.contentPriority().compareTo(x.contentPriority());
        if (c == 0) c = Long.compare(Math.min(y.eligibleAge(), ageCap), Math.min(x.eligibleAge(), ageCap));
        if (c == 0) c = Double.compare(y.score(), x.score());
        if (c == 0) c = Long.compareUnsigned(x.seededTieBreak(), y.seededTieBreak());
        if (c == 0) c = a.id().value().compareTo(b.id().value());
        return c;
    }
    private boolean compatible(ResourceClaim a, ResourceClaim b) {
        if (!a.expectedEpoch().equals(b.expectedEpoch())) return false;
        if (a.policy() == ClaimPolicy.SHARE && b.policy() == ClaimPolicy.SHARE)
            return compatibility.share(a, b);
        if (a.policy() == ClaimPolicy.MERGE && b.policy() == ClaimPolicy.MERGE
                && a.mergeFunction().equals(b.mergeFunction()))
            return compatibility.merge(a.mergeFunction().orElseThrow(), a, b);
        return false;
    }
    /** Resolve a complete ready window. Caller must not submit its members one by one. */
    public List<Decision> arbitrate(List<Candidate> window, Tick tick, Map<ResourceKey, Epoch> liveEpochs,
                                    Map<ScopeRef, BudgetVector> capacities) {
        advance(tick);
        if (window.size() > windowLimit || window.stream().map(Candidate::id).distinct().count() != window.size())
            throw new IllegalArgumentException("window bound or duplicate candidate");
        // Guard the ENTIRE window before any claim is published. Otherwise a
        // late generation overflow could leave an unacknowledged partial grant.
        if (generation > Long.MAX_VALUE - window.size())
            throw new ArithmeticException("reservation generation exhausted for window");
        List<Candidate> ordered = new ArrayList<>(window); ordered.sort(this::compare);
        // The whole window is a logical transaction: a later compatibility,
        // budget, or generation failure must not publish partial reservations
        // or silently remove preempted ownership claims.
        Map<ReservationId, Held> trial = new HashMap<>(held);
        long stagedGeneration = generation;
        List<Decision> decisions = new ArrayList<>();
        for (Candidate candidate : ordered) {
            Set<ReservationId> victims = new HashSet<>();
            ReasonCode failure = null;
            if (trial.containsKey(candidate.id())) failure = ReasonCode.IDEMPOTENCY_CONFLICT;
            else if (candidate.expiry().compareTo(now) <= 0) failure = ReasonCode.STALE_CONTEXT;
            for (ResourceClaim claim : candidate.claims()) {
                if (failure != null) break;
                if (!claim.expectedEpoch().equals(liveEpochs.get(claim.resource()))) {
                    failure = ReasonCode.STALE_CONTEXT; break;
                }
                for (Held existing : trial.values()) {
                    for (ResourceClaim old : existing.token().claims()) {
                        if (!old.resource().equals(claim.resource()) || compatible(claim, old)) continue;
                        if (claim.policy() == ClaimPolicy.PREEMPT_BEFORE_EFFECT && !existing.started()
                                && compare(candidate, existing.candidate()) < 0) victims.add(existing.token().id());
                        else failure = ReasonCode.CLAIM_CONFLICT;
                    }
                }
            }
            if(failure==null)for(var claim:candidate.physicalResources()){
                long used=claim.units();
                for(var existing:trial.values())for(var old:existing.candidate().physicalResources()){
                    if(victims.contains(existing.token().id())||!old.resource().equals(claim.resource()))continue;
                    if(old.capacity()!=claim.capacity()||old.mode()==PhysicalResourceClaim.Mode.EXCLUSIVE||claim.mode()==PhysicalResourceClaim.Mode.EXCLUSIVE){failure=ReasonCode.CLAIM_CONFLICT;break;}
                    used=Math.addExact(used,old.units());
                }
                if(used>claim.capacity())failure=ReasonCode.CLAIM_CONFLICT;
                if(failure!=null)break;
            }
            if (failure == null && !fits(candidate, victims, capacities, trial)) failure = ReasonCode.BUDGET_EXHAUSTED;
            if (failure != null) {
                decisions.add(new Decision(candidate.id(), Optional.empty(), Optional.of(failure), List.of()));
                continue;
            }
            // Construct everything before publishing, including overflow-sensitive generation.
            long next = Math.incrementExact(stagedGeneration);
            ReservationToken token = new ReservationToken(candidate.id(), world, candidate.instance(),
                    new Epoch(next), candidate.expiry(), candidate.claims());
            List<ReservationToken> displaced = victims.stream().map(id -> trial.get(id).token())
                    .sorted(Comparator.comparing(t -> t.id().value())).toList();
            victims.forEach(trial::remove);
            trial.put(token.id(), new Held(candidate, token, false)); stagedGeneration = next;
            decisions.add(new Decision(candidate.id(), Optional.of(token), Optional.empty(), displaced));
        }
        // Only publish the complete window. A thrown exception leaves the
        // existing owners and reservation generation exactly as they were.
        held.clear();
        held.putAll(trial);
        generation = stagedGeneration;
        return List.copyOf(decisions);
    }
    private boolean fits(Candidate candidate, Set<ReservationId> victims, Map<ScopeRef, BudgetVector> capacities,
                         Map<ReservationId, Held> trial) {
        Map<ScopeRef, BudgetVector> costs = new HashMap<>();
        try {
            BudgetVector minimum = BudgetMath.zero();
            for (ResourceClaim claim : candidate.claims()) minimum = BudgetMath.add(minimum, claim.cost());
            BudgetVector worldCost = candidate.scopeCosts().get(new ScopeRef(ScopeKind.WORLD, world.value()));
            if (worldCost == null || !BudgetMath.fits(minimum, worldCost)) return false;
            for (Held h : trial.values()) if (!victims.contains(h.token().id()))
                h.candidate().scopeCosts().forEach((s, b) -> costs.merge(s, b, BudgetMath::add));
            candidate.scopeCosts().forEach((s, b) -> costs.merge(s, b, BudgetMath::add));
        } catch (ArithmeticException | IllegalArgumentException overflow) { return false; }
        return costs.entrySet().stream().allMatch(e -> capacities.containsKey(e.getKey())
                && BudgetMath.fits(e.getValue(), capacities.get(e.getKey())));
    }
    public boolean valid(ReservationToken token, Tick tick) {
        advance(tick); Held h = held.get(token.id());
        if(h!=null&&h.token().equals(token))return tick.compareTo(token.expiryTick())<0;
        var child=borrowed.get(token.id());if(child==null||!child.token().equals(token))return false;
        var parent=held.get(child.parent().id());return parent!=null&&parent.token().equals(child.parent())&&tick.compareTo(token.expiryTick())<0;
    }
    public void markStarted(ReservationToken token, Tick tick) {
        if (!valid(token, tick)) throw new IllegalArgumentException("stale reservation");
        var child=borrowed.get(token.id());var parent=child==null?token:child.parent();
        Held h = held.get(parent.id()); held.put(parent.id(), new Held(h.candidate(), parent, true));
    }
    /** Expired started effects keep claims until journaled cleanup or durable transfer.
     * Returned tokens are maintenance work, not permission to execute cleanup.
     */
    public List<ReservationToken> expired(Tick tick) {
        advance(tick);
        return held.values().stream().map(Held::token).filter(t -> t.expiryTick().compareTo(tick) <= 0)
                .sorted(Comparator.comparing(t -> t.id().value())).toList();
    }
    /** Called after the coordinator proves no effect, completed cleanup or durable transfer. */
    public boolean release(ReservationToken token) {
        actor();var child=borrowed.get(token.id());
        if(child!=null){if(!child.token().equals(token))return false;borrowed.remove(token.id());return true;}
        Held h = held.get(token.id());
        if (h == null || !h.token().equals(token)||hasBorrowed(token)) return false;
        held.remove(token.id()); return true;
    }
    /** Rebuild only a logical quarantine from an actual J04-restored site pin.
     * Empty physical claims and absolute expiry0 make this unusable by J05.
     * Its existing counted resources remain held until durable cleanup proof. */
    public ReservationToken retainPhysicalResources(EventInstanceId event,List<PhysicalResourceClaim> resources){
        actor();if(resources.isEmpty()||held.size()>=windowLimit*2)throw new IllegalArgumentException("bounded retained resource pin required");
        var id=new ReservationId(UUID.randomUUID());long next=Math.incrementExact(generation);
        var candidate=new Candidate(id,event,new Rank(false,false,false,Priority.MICRO,0,0,0),new Tick(0),List.of(),Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),BudgetMath.zero()),resources);
        var token=new ReservationToken(id,world,event,new Epoch(next),new Tick(0),List.of());held.put(id,new Held(candidate,token,true));generation=next;return token;
    }
    /** Borrow an exact subset of an existing global reservation. It charges no
     * second budget and acquires no additional resource. A child cannot outlive,
     * renew, release or widen its parent. The caller still needs an actual J04
     * PREPARE and ordinary J05 one-shot permit for every physical tranche. */
    public ReservationToken borrow(ReservationToken parent,List<ResourceClaim> claims,Tick tick,DurationTicks duration){
        if(!valid(parent,tick)||!held.containsKey(parent.id())||duration.value()<1||claims.isEmpty()
            ||!parent.claims().containsAll(claims)||claims.stream().map(ResourceClaim::resource).distinct().count()!=claims.size()
            ||hasBorrowed(parent)||borrowed.size()>=windowLimit)throw new IllegalArgumentException("exact held global claim subset required");
        long expiry=Math.min(parent.expiryTick().value(),Math.addExact(tick.value(),duration.value()));
        if(expiry<=tick.value())throw new IllegalArgumentException("expired borrowed reservation");
        long next=Math.incrementExact(generation);var token=new ReservationToken(new ReservationId(UUID.randomUUID()),world,parent.instanceId(),new Epoch(next),new Tick(expiry),claims);
        borrowed.put(token.id(),new Borrowed(token,parent));generation=next;return token;
    }
    public boolean hasBorrowed(ReservationToken parent){actor();return borrowed.values().stream().anyMatch(b->b.parent().equals(parent));}
    /** Renew only an actually held logical lease. This is not a J05 permit. */
    public ReservationToken renew(ReservationToken token,Tick tick,DurationTicks duration){
        advance(tick);if(hasBorrowed(token))throw new IllegalStateException("in-flight sealed tranche retains parent generation");Held before=held.get(token.id());
        if(before==null||!before.token().equals(token)||tick.compareTo(token.expiryTick())>=0||duration.value()<1)
            throw new IllegalArgumentException("logical lease not held/current");
        long next=Math.incrementExact(generation);var old=before.candidate();
        var updated=new Candidate(old.id(),old.instance(),old.rank(),tick.plus(duration.value()),old.claims(),old.scopeCosts(),old.physicalResources());
        var renewed=new ReservationToken(token.id(),world,token.instanceId(),new Epoch(next),updated.expiry(),token.claims());
        held.put(token.id(),new Held(updated,renewed,before.started()));generation=next;return renewed;
    }
    /** Same retained claims after expiry, for owner-proven stabilization only.
     * This never approves a forward effect and still grants no J05 authority. */
    public ReservationToken renewRetainedForCleanup(ReservationToken token,Tick tick,DurationTicks duration){
        advance(tick);var before=held.get(token.id());
        if(before==null||!before.token().equals(token)||!before.started()||hasBorrowed(token)||duration.value()<1)
            throw new IllegalArgumentException("retained started reservation required");
        long next=Math.incrementExact(generation);var old=before.candidate();
        var updated=new Candidate(old.id(),old.instance(),old.rank(),tick.plus(duration.value()),old.claims(),old.scopeCosts(),old.physicalResources());
        var renewed=new ReservationToken(token.id(),world,token.instanceId(),new Epoch(next),updated.expiry(),token.claims());
        held.put(token.id(),new Held(updated,renewed,true));generation=next;return renewed;
    }
    public void restoreGenerationFloor(Epoch floor){actor();if(!held.isEmpty()&&floor.value()>generation)throw new IllegalStateException("generation floor after admission");generation=Math.max(generation,floor.value());}
    public List<ReservationToken> snapshot() {
        actor(); return held.values().stream().map(Held::token)
                .sorted(Comparator.comparing(t -> t.id().value())).toList();
    }
}
