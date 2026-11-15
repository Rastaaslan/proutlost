package fr.proutlost.director.engine;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Snapshot-only checks. Successful known checks still require role-bound planning/admission. */
public final class EligibilityEngine {
    private final ProviderMode mode;
    public EligibilityEngine(ProviderMode mode) { this.mode = Objects.requireNonNull(mode); }

    public ValidationResult validateKnown(CandidateGenerator.Generated candidate, DecisionInput input, double pacingFactor) {
        Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
        var definition = candidate.definition();
        var world = input.state().world();
        long now = world.simTick().value();
        var parameters = new EngineParameters(input.config());
        long maxAge = parameters.ticks("scheduler.snapshot_max_age_ticks");
        if (stale(input.context().capturedTick(), now, maxAge)) reasons.add(ReasonCode.STALE_CONTEXT);
        if (!input.context().scopes().worldId().equals(world.worldId()) || !input.memory().scopes().worldId().equals(world.worldId()))
            reasons.add(ReasonCode.STALE_CONTEXT);
        if (!world.catalogHash().equals(input.catalog().hash()) || !world.configHash().equals(input.config().provenanceHash()))
            reasons.add(ReasonCode.STALE_CONTEXT);
        if (world.runControl() != RunControl.RUNNING) reasons.add(ReasonCode.AUTH_DENIED);
        if (world.healthMode() == HealthMode.CRITICAL || world.healthMode() == HealthMode.READ_ONLY_SAFE
            || input.context().serverHealth() == HealthMode.CRITICAL || input.context().serverHealth() == HealthMode.READ_ONLY_SAFE)
            reasons.add(ReasonCode.WAL_UNAVAILABLE);
        if (!definition.enabled() || !definition.id().equals(candidate.reference().id())
            || !Objects.equals(input.catalog().events().get(definition.id()), definition)) reasons.add(ReasonCode.CATALOG_INVALID);
        if (mode == ProviderMode.PRODUCTION && (definition.calibration() != Calibration.APPROVED || input.config().calibration() != Calibration.APPROVED))
            reasons.add(ReasonCode.CATALOG_INVALID);
        if (!candidate.completeRoles()) reasons.add(ReasonCode.NO_TARGET);
        var players = ConditionEvaluator.players(candidate);
        if (!candidate.roles().roles().keySet().equals(definition.target().roles().keySet())) reasons.add(ReasonCode.NO_TARGET);
        for (var role : definition.target().roles().entrySet()) {
            var assigned = candidate.roles().roles().getOrDefault(role.getKey(), List.of());
            if (assigned.size() < role.getValue().min() || assigned.size() > role.getValue().max()
                || new HashSet<>(assigned).size() != assigned.size()) reasons.add(ReasonCode.NO_TARGET);
            for (var other : definition.target().roles().entrySet()) if (!role.getKey().equals(other.getKey())
                && !(role.getValue().overlap() && other.getValue().overlap())
                && !Collections.disjoint(assigned, candidate.roles().roles().getOrDefault(other.getKey(), List.of()))) reasons.add(ReasonCode.NO_TARGET);
        }
        if (definition.target().scope() == TargetScope.SOLO && players.size() > 1) reasons.add(ReasonCode.NO_TARGET);
        if (definition.target().scope() == TargetScope.GROUP) {
            var group = candidate.group().map(input.context().context().groups()::get);
            if (group.isEmpty() || !ConditionEvaluator.known(candidate, input, SensorKind.GROUP_MEMBERSHIP)) reasons.add(ReasonCode.STALE_CONTEXT);
            else {
                if (stale(group.orElseThrow().capturedTick(), now, maxAge)) reasons.add(ReasonCode.STALE_CONTEXT);
                if (!group.orElseThrow().members().containsAll(players)) reasons.add(ReasonCode.NO_TARGET);
                for (var id : players) {
                    var player = input.context().context().players().get(id);
                    if (player == null || !player.groupIds().contains(group.orElseThrow().groupId())) reasons.add(ReasonCode.NO_TARGET);
                }
            }
        }
        if (!ConditionEvaluator.known(candidate, input, SensorKind.PLAYER_ELIGIBILITY)) reasons.add(ReasonCode.STALE_CONTEXT);
        for (var scope : candidate.scopes().scopes()) {
            var observed = input.context().context().scopes().get(scope);
            if (observed == null || !Objects.equals(input.context().scopeRevisions().get(scope), observed.revision()))
                reasons.add(ReasonCode.STALE_CONTEXT);
        }
        for (var id : players) {
            var player = input.context().context().players().get(id);
            if (player == null || player.eligibility() != PlayerEligibility.ELIGIBLE) {
                reasons.add(ReasonCode.NO_TARGET); continue;
            }
            if (stale(player.capturedTick(), now, maxAge) || stale(player.position().tick(), now, maxAge)) reasons.add(ReasonCode.STALE_CONTEXT);
            var position = player.position().position();
            if (!input.context().loadedChunks().contains(new ChunkRef(position.dimension(), Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16))))
                reasons.add(ReasonCode.CHUNK_UNAVAILABLE);
            if (!player.clientCapabilities().connected() && definition.budget().clientUnits() > 0) reasons.add(ReasonCode.CAPABILITY_MISSING);
            var memories = input.memory().operationalPlayers().stream().filter(m -> m.playerId().equals(id)).toList();
            if (memories.size() != 1) reasons.add(ReasonCode.STALE_CONTEXT);
            else {
                var memory = memories.getFirst();
                if (memory.header().updatedTick().value() > now || memory.eventExposureRing().stream().anyMatch(e -> e.tick().value() > now)
                    || memory.roleExposureRing().stream().anyMatch(e -> e.tick().value() > now)) reasons.add(ReasonCode.STALE_CONTEXT);
                if (memory.recentDeathUntil().filter(t -> t.value() > now).isPresent()
                    || memory.eligibleOnlineTicks().value() < parameters.ticks("fairness.new_player_grace_ticks")) reasons.add(ReasonCode.SAFETY_COOLDOWN);
                if (!Objects.equals(input.memory().revisions().get(new ScopeRef(ScopeKind.PLAYER, id.value())), memory.header().revision()))
                    reasons.add(ReasonCode.STALE_CONTEXT);
            }
        }
        if (candidate.region().isPresent()) {
            var region = input.context().context().regions().get(candidate.region().orElseThrow());
            if (region == null || region.region().status() != RegionStatus.ACTIVE) reasons.add(ReasonCode.NO_TARGET);
            else {
                if (!region.tags().containsAll(definition.target().regionTags())) reasons.add(ReasonCode.NO_TARGET);
                for (var id : players) {
                    var player = input.context().context().players().get(id);
                    if (player == null || !player.position().position().dimension().equals(region.region().dimension())
                        || !region.presentPlayers().contains(id)) reasons.add(ReasonCode.NO_TARGET);
                }
            }
        } else if (!definition.target().regionTags().isEmpty()) reasons.add(ReasonCode.STALE_CONTEXT);
        Set<CapabilityId> required = new HashSet<>(definition.safety().requiredCapabilities());
        for (var step : definition.steps()) {
            var primitive = input.catalog().primitives().get(step.primitive());
            if (primitive == null || mode == ProviderMode.PRODUCTION && primitive.mode() == ProviderMode.TEST_ONLY) reasons.add(ReasonCode.CAPABILITY_MISSING);
            else {
                required.addAll(primitive.capabilities());
                // Each step must fit the declaration. Summing sequential steps would invent
                // simultaneous load; actual DAG overlap is resolved by the pure planner.
                if (!BudgetMath.fits(primitive.minimumCost(), definition.budget())) reasons.add(ReasonCode.CATALOG_INVALID);
            }
        }
        for (var id : required) {
            var capability = input.context().context().capabilities().get(id);
            if (capability == null || capability.status() != CapabilityStatus.AVAILABLE
                || mode == ProviderMode.PRODUCTION && capability.mode() != ProviderMode.PRODUCTION) reasons.add(ReasonCode.CAPABILITY_MISSING);
        }
        var budget = input.state().budgets();
        try {
            if (!BudgetMath.fits(BudgetMath.add(budget.reserved(), definition.budget()), budget.capacity())) reasons.add(ReasonCode.BUDGET_EXHAUSTED);
        } catch (ArithmeticException | IllegalArgumentException overflow) { reasons.add(ReasonCode.BUDGET_EXHAUSTED); }
        if (budget.reserved().globalIntensity() + definition.budget().globalIntensity() > parameters.nonnegative("budget.global_intensity") * pacingFactor)
            reasons.add(ReasonCode.BUDGET_EXHAUSTED);
        for (var scope : candidate.scopes().scopes()) if (scope.kind() == ScopeKind.REGION) {
            var reserved = budget.scopes().get(scope);
            if (reserved == null) reasons.add(ReasonCode.STALE_CONTEXT);
            else if (reserved.regionalIntensity() + definition.budget().regionalIntensity() > parameters.nonnegative("budget.region_intensity") * pacingFactor)
                reasons.add(ReasonCode.BUDGET_EXHAUSTED);
        }
        if (input.state().cooldowns().stream().anyMatch(c -> new CooldownPolicy().blocks(c, candidate, input)))
            reasons.add(ReasonCode.SAFETY_COOLDOWN);
        uniqueness(candidate,input,Optional.empty(),reasons);
        var condition = new ConditionEvaluator().evaluate(definition.eligibility(), candidate, input);
        if (condition != ConditionEvaluator.Truth.TRUE) reasons.add(condition == ConditionEvaluator.Truth.UNKNOWN ? ReasonCode.STALE_CONTEXT : ReasonCode.NO_TARGET);
        return new ValidationResult(reasons.isEmpty(), List.copyOf(reasons), new ReadSet(input.context().scopeRevisions(), Map.of(), Map.of()));
    }
    /** The physical incidental audience is subject to the same player gates as role targets. */
    public ValidationResult validateAudience(CandidateGenerator.Generated candidate, Set<PlayerId> audience, DecisionInput input) {
        return validateAudience(candidate,audience,input,Optional.empty());
    }
    /** Original later steps retain their own first-effect interval and unique use; other events remain fenced. */
    public ValidationResult validateAudience(CandidateGenerator.Generated candidate, Set<PlayerId> audience, DecisionInput input, EventInstanceId continuingEvent) {
        return validateAudience(candidate,audience,input,Optional.of(Objects.requireNonNull(continuingEvent)));
    }
    private ValidationResult validateAudience(CandidateGenerator.Generated candidate, Set<PlayerId> audience, DecisionInput input, Optional<EventInstanceId> continuingEvent) {
        Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
        Set<ScopeRef> scopes = new HashSet<>(candidate.scopes().scopes());
        long now = input.state().world().simTick().value();
        var parameters = new EngineParameters(input.config());
        long maxAge = parameters.ticks("scheduler.snapshot_max_age_ticks");
        for (var id : audience) {
            var scope = new ScopeRef(ScopeKind.PLAYER, id.value()); scopes.add(scope);
            var observed = input.context().context().scopes().get(scope);
            var player = input.context().context().players().get(id);
            if (observed == null || !observed.availableSensors().contains(SensorKind.PLAYER_ELIGIBILITY)
                || input.context().missingData().contains(SensorKind.PLAYER_ELIGIBILITY)
                || !Objects.equals(input.context().scopeRevisions().get(scope), observed.revision())) reasons.add(ReasonCode.STALE_CONTEXT);
            if (player == null || player.eligibility() != PlayerEligibility.ELIGIBLE) { reasons.add(ReasonCode.NO_TARGET); continue; }
            if (stale(player.capturedTick(), now, maxAge) || stale(player.position().tick(), now, maxAge)) reasons.add(ReasonCode.STALE_CONTEXT);
            var position = player.position().position();
            if (!input.context().loadedChunks().contains(new ChunkRef(position.dimension(), Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16)))) reasons.add(ReasonCode.CHUNK_UNAVAILABLE);
            if (!player.clientCapabilities().connected() && candidate.definition().budget().clientUnits() > 0) reasons.add(ReasonCode.CAPABILITY_MISSING);
            var memories = input.memory().operationalPlayers().stream().filter(m -> m.playerId().equals(id)).toList();
            if (memories.size() != 1) { reasons.add(ReasonCode.STALE_CONTEXT); continue; }
            var memory = memories.getFirst();
            if (memory.header().updatedTick().value() > now || !Objects.equals(input.memory().revisions().get(scope), memory.header().revision())
                || memory.eventExposureRing().stream().anyMatch(e -> e.tick().value() > now)
                || memory.roleExposureRing().stream().anyMatch(e -> e.tick().value() > now)) reasons.add(ReasonCode.STALE_CONTEXT);
            if (memory.eligibleOnlineTicks().value() < parameters.ticks("fairness.new_player_grace_ticks")
                || memory.recentDeathUntil().filter(t -> t.value() > now).isPresent()) reasons.add(ReasonCode.SAFETY_COOLDOWN);
        }
        var affected = new CandidateGenerator.Generated(candidate.id(), candidate.definition(), candidate.reference(), candidate.region(), candidate.group(),
            candidate.roles(), new ScopeSet(candidate.scopes().worldId(), scopes), candidate.completeRoles());
        for (var cooldown : input.state().cooldowns()) if (!ownEffectInterval(cooldown,affected,continuingEvent)
            && new CooldownPolicy().blocks(cooldown, affected, input)) reasons.add(ReasonCode.SAFETY_COOLDOWN);
        // Queued, already-audited plans must recheck a use consumed by another
        // event while they waited. This is read-only; PREPARE still owns reserve.
        uniqueness(affected,input,continuingEvent,reasons);
        return new ValidationResult(reasons.isEmpty(), List.copyOf(reasons), new ReadSet(input.context().scopeRevisions(), Map.of(), Map.of()));
    }
    private static void uniqueness(CandidateGenerator.Generated candidate,DecisionInput input,
            Optional<EventInstanceId> continuingEvent,Set<ReasonCode> reasons) {
        var definition=candidate.definition();Set<UUID> uniquenessScopes=new HashSet<>();
        switch(definition.uniqueness().scope()) {
            case REPEATABLE -> { }
            case ONCE_PER_WORLD -> uniquenessScopes.add(input.state().world().worldId().value());
            case ONCE_PER_PLAYER -> ConditionEvaluator.players(candidate).forEach(p->uniquenessScopes.add(p.value()));
            case ONCE_PER_REGION -> {
                if(candidate.region().isEmpty())reasons.add(ReasonCode.STALE_CONTEXT);
                else collectLineage(candidate.region().orElseThrow(),input,uniquenessScopes,reasons);
            }
        }
        for(var use:input.state().uniqueness())if(use.definitionId().equals(definition.id())
            &&use.status()!=UniqueStatus.RELEASED_BEFORE_EFFECT
            &&continuingEvent.filter(use.instanceId()::equals).isEmpty()
            &&(uniquenessScopes.contains(use.canonicalScopeId())||!Collections.disjoint(uniquenessScopes,use.originScopeIds())))
            reasons.add(ReasonCode.UNIQUE_CONSUMED);
    }
    private static boolean ownEffectInterval(CooldownRecord cooldown,CandidateGenerator.Generated candidate,Optional<EventInstanceId> continuingEvent) {
        return continuingEvent.filter(cooldown.sourceInstanceId()::equals).isPresent()
            && cooldown.safetyClass()==Risk.SAFE
            && cooldown.reason().equals("ACTUAL_SELECTED_EFFECT") && cooldown.releaseCondition().isEmpty()
            && cooldown.binding().filter(b->b.selector().equals(new EventCooldownSelector(candidate.definition().id()))
                && b.releaseMode()==CooldownReleaseMode.AFTER_TICK && b.releasePredicate().isEmpty()
                && b.affectedScopes().scopes().size()==1
                && b.affectedScopes().scopes().stream().allMatch(scope->
                    Set.of(ScopeKind.WORLD,ScopeKind.REGION,ScopeKind.PLAYER).contains(scope.kind())
                    && cooldown.kind().equals("EVENT_"+scope.kind().name()))).isPresent();
    }
    private static boolean stale(Tick tick, long now, long maximumAge) {
        return tick.value() > now || now - tick.value() > maximumAge;
    }
    private static void collectLineage(RegionId start, DecisionInput input, Set<UUID> ids, Set<ReasonCode> reasons) {
        var pending = new ArrayDeque<RegionId>(); pending.add(start);
        while (!pending.isEmpty()) {
            var id = pending.removeFirst();
            if (!ids.add(id.value())) continue;
            var record = input.memory().regionRecords().get(id);
            if (record == null) { reasons.add(ReasonCode.STALE_CONTEXT); continue; }
            pending.addAll(record.parentIds());
            record.aliases().forEach(alias -> ids.add(alias.value()));
        }
    }
}
