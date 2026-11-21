package fr.proutlost.director.engine;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Durable request deferrals; no selected/started proposal can fulfill a requirement. */
public final class RequestScheduler {
    public record Service(List<Candidate> candidates, UnitValue credit, Optional<RequestId> request) {}
    public Service service(DecisionInput input, List<Candidate> candidates) {
        var ready = input.requests().stream().filter(v -> pending(v.state()) && candidates.stream().anyMatch(c -> matches(v.state(), c)))
            .sorted(Comparator.<RequestView>comparingInt(v -> v.state().schedulingPolicy().narrativePriority().rank()).reversed()
                .thenComparing(Comparator.comparingDouble((RequestView v) -> ageBoost(v.state(), input)).reversed())
                .thenComparing(v -> v.request().createdTick()).thenComparing(v -> v.state().requestId().value())).toList();
        var credit = new RequiredServiceCredit(input.config()).advance(input.state().world().engineControl().requiredServiceCredit(), !ready.isEmpty(), candidates.stream().anyMatch(Candidate::eligible));
        if (credit.lane() != RequiredServiceCredit.Lane.REQUIRE) return new Service(candidates, credit.credit(), Optional.empty());
        var request = ready.getFirst().state();
        return new Service(candidates.stream().filter(c -> matches(request, c)).toList(), credit.credit(), Optional.of(request.requestId()));
    }
    /** Attribute a genuinely selected eligible execution to at most one matching
     * pending REQUIRE, independently of which opportunity lane selected it.
     * This preserves service-credit accounting; only a confirmed bound fact fulfills it. */
    public Optional<RequestId> chooseMatchedForSelected(DecisionInput input, Candidate selected) {
        Objects.requireNonNull(input); Objects.requireNonNull(selected);
        return input.requests().stream().filter(v -> pending(v.state()) && matches(v.state(), selected))
            .sorted(Comparator.<RequestView>comparingInt(v -> v.state().schedulingPolicy().narrativePriority().rank()).reversed()
                .thenComparing(Comparator.comparingDouble((RequestView v) -> ageBoost(v.state(), input)).reversed())
                .thenComparing(v -> v.request().createdTick()).thenComparing(v -> v.state().requestId().value()))
            .map(v -> v.state().requestId()).findFirst();
    }
    /** Bounded monotone age tie-break after explicit narrative rank. A zero cap disables aging.
     * Saturating t/(t+1) uses persisted proven eligible ticks, never calendar request age. */
    public double ageBoost(DirectorRequestState state, DecisionInput input) {
        double ticks = state.eligibleWaitTicks().value();
        return new EngineParameters(input.config()).nonnegative("scheduler.require_age_boost_cap") * (ticks / (ticks + 1));
    }
    private static boolean pending(DirectorRequestState state) {
        return state.kind() == RequestKind.REQUIRE && (state.status() == RequestStatus.ACCEPTED || state.status() == RequestStatus.WAITING_CONTEXT || state.status() == RequestStatus.WAITING_SAFETY);
    }
    private static boolean matches(DirectorRequestState state, Candidate candidate) {
        if (!candidate.eligible() || !candidate.scopes().scopes().contains(state.scope()) || !(state.selector() instanceof AnyOfEvents events)
            || !events.eventIds().contains(candidate.definition().id())) return false;
        Set<PlayerId> players = new HashSet<>(); candidate.roles().roles().values().forEach(players::addAll);
        return players.containsAll(state.constraints().playerIds()) && state.constraints().regionIds().stream()
            .allMatch(r -> candidate.scopes().scopes().contains(new ScopeRef(ScopeKind.REGION, r.value())));
    }

    public Optional<AggregateChange> scheduled(DecisionInput input, Optional<RequestId> id) {
        return id.flatMap(request -> input.requests().stream().filter(v -> v.state().requestId().equals(request)).findFirst()).map(view -> {
            var before = view.state(); var header = before.header();
            var next = new DirectorRequestState(new AggregateHeader(header.schemaVersion(), header.id(), header.revision().plus(1), header.lastSequence(), input.state().world().simTick()),
                before.requestId(), before.producerId(), before.producerKey(), before.kind(), before.selector(), before.scope(), before.constraints(), before.fulfillment(), RequestStatus.SCHEDULED,
                before.eligibleWaitTicks(), before.expiresTick(), before.resultFactIds(), List.of(), before.schedulingPolicy());
            var aggregate = new RequestAggregate(next);
            return new AggregateChange(aggregate.key(), Optional.of(header.revision()), aggregate);
        });
    }
    public List<AggregateChange> defer(DecisionInput input, List<Candidate> candidates) {
        List<AggregateChange> changes = new ArrayList<>();
        for (var view : input.requests().stream().sorted(Comparator.comparing(r -> r.state().requestId().value().toString())).toList()) {
            var before = view.state();
            if (before.kind() != RequestKind.REQUIRE || !(before.status() == RequestStatus.ACCEPTED
                || before.status() == RequestStatus.WAITING_CONTEXT || before.status() == RequestStatus.WAITING_SAFETY)) continue;
            if (candidates.stream().anyMatch(c -> matches(before, c))) {
                long elapsed = input.state().world().simTick().value() - before.header().updatedTick().value();
                // Initial acceptance and a WAITING_* checkpoint do not attest prior readiness.
                // A gap beyond the freshness horizon also has no proven eligible interval.
                boolean previouslyReady = before.status() == RequestStatus.ACCEPTED && before.header().revision().value() > 1
                    && before.lastReasonCodes().isEmpty();
                long proven = previouslyReady && elapsed > 0 && elapsed <= new EngineParameters(input.config()).ticks("scheduler.snapshot_max_age_ticks") ? elapsed : 0;
                if (elapsed > 0 || before.status() != RequestStatus.ACCEPTED || before.header().revision().value() == 1) {
                    long waited = before.eligibleWaitTicks().value();
                    long nextWait = waited > Long.MAX_VALUE - proven ? Long.MAX_VALUE : waited + proven;
                    var header = before.header();
                    var next = new DirectorRequestState(new AggregateHeader(header.schemaVersion(), header.id(), header.revision().plus(1), header.lastSequence(), input.state().world().simTick()),
                        before.requestId(), before.producerId(), before.producerKey(), before.kind(), before.selector(), before.scope(), before.constraints(), before.fulfillment(), RequestStatus.ACCEPTED,
                        new DurationTicks(nextWait), before.expiresTick(), before.resultFactIds(), List.of(), before.schedulingPolicy());
                    var aggregate = new RequestAggregate(next);
                    changes.add(new AggregateChange(aggregate.key(), Optional.of(header.revision()), aggregate));
                }
                continue;
            }
            Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
            if (before.selector() instanceof AnyOfEvents selector) {
                for (var candidate : candidates) if (selector.eventIds().contains(candidate.definition().id())
                    && candidate.scopes().scopes().contains(before.scope())) reasons.addAll(candidate.reasons());
            } else reasons.add(ReasonCode.STALE_CONTEXT);
            if (reasons.isEmpty()) reasons.add(ReasonCode.NO_TARGET);
            RequestStatus status = reasons.contains(ReasonCode.STALE_CONTEXT) || reasons.contains(ReasonCode.NO_TARGET)
                ? RequestStatus.WAITING_CONTEXT : RequestStatus.WAITING_SAFETY;
            List<ReasonCode> ordered = List.copyOf(reasons);
            if (status == before.status() && ordered.equals(before.lastReasonCodes())
                && before.header().updatedTick().equals(input.state().world().simTick())) continue;
            var header = before.header();
            var next = new DirectorRequestState(new AggregateHeader(header.schemaVersion(), header.id(), header.revision().plus(1),
                header.lastSequence(), input.state().world().simTick()), before.requestId(), before.producerId(), before.producerKey(), before.kind(),
                before.selector(), before.scope(), before.constraints(), before.fulfillment(), status, before.eligibleWaitTicks(), before.expiresTick(),
                before.resultFactIds(), ordered, before.schedulingPolicy());
            var aggregate = new RequestAggregate(next);
            changes.add(new AggregateChange(aggregate.key(), Optional.of(header.revision()), aggregate));
        }
        return List.copyOf(changes);
    }
}
