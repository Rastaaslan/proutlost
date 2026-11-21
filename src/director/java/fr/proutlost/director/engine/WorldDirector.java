package fr.proutlost.director.engine;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DecisionPort;
import fr.proutlost.director.api.port.ProviderProvenance;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure decision calculation. Actor-captured planning snapshots and the original recorded AI
 * input are injected explicitly and must be retained alongside DecisionInput for replay.
 * A proposal grants no execution authority; live admission and durable permits remain J05/J04.
 */
public final class WorldDirector implements DecisionPort,ProviderProvenance {
    private final ProviderMode mode;
    private final Optional<CandidatePlanner> planner;
    private final Optional<AiInput> advisoryInput;
    public WorldDirector() { this(ProviderMode.PRODUCTION); }
    public WorldDirector(ProviderMode mode) { this.mode = Objects.requireNonNull(mode); this.planner = Optional.empty(); this.advisoryInput = Optional.empty(); }
    public WorldDirector(ProviderMode mode, CandidatePlanner planner) { this(mode, planner, Optional.empty()); }
    public WorldDirector(ProviderMode mode, CandidatePlanner planner, Optional<AiInput> advisoryInput) {
        this.mode = Objects.requireNonNull(mode); this.planner = Optional.of(Objects.requireNonNull(planner));
        this.advisoryInput = Objects.requireNonNull(advisoryInput);
    }

    @Override public Set<ProviderMode> supportedModes(){return Set.of(mode);}

    public record Evaluation(DecisionProposal proposal, Map<CandidateId, DeferredPlanning> planningDeferrals,
            Map<CandidateId,CandidateGenerator.Generated> generatedCandidates) {
        public Evaluation { planningDeferrals = Map.copyOf(planningDeferrals);generatedCandidates=Map.copyOf(generatedCandidates); }
        public Evaluation(DecisionProposal proposal,Map<CandidateId,DeferredPlanning> deferrals){this(proposal,deferrals,Map.of());}
    }
    @Override public DecisionProposal evaluate(DecisionInput input) { return evaluateDetailed(input).proposal(); }
    /** Retains exact API sampling DTOs until the shared DecisionProposal transport is extended. */
    public Evaluation evaluateDetailed(DecisionInput input) {
        return evaluateNormalized(AdminTensionOverride.normalize(input));
    }
    private Evaluation evaluateNormalized(DecisionInput input) {
        Objects.requireNonNull(input);
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("decision CPU work cancelled");
        var world = input.state().world();
        var random = new DecisionRandom(input.seed());
        Fingerprint inputHash = RecordedAdvice.hash(new ModelCodec<>(DecisionInput.class).encode(new DecisionInput(input.context(), input.memory(), input.catalog(), input.config(), input.requests(), input.chains(), input.seed(), input.state(), Optional.empty())));
        DecisionId id = new DecisionId(UUID.nameUUIDFromBytes(("engine-g0-v1/" + inputHash.value()).getBytes(StandardCharsets.UTF_8)));
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("decision CPU hash cancelled");
        var pacing = new PacingController(input.config());
        var signals = signals(input);
        // Administrative pause blocks gameplay; it is not a measured HEALTH episode.
        // Keep the original pacing/tension/control (including an uninitialized revision0).
        // simTick and the NO_PLAN audit still advance; RUNNING retains every normal guard.
        var measuredPacing = world.runControl() == RunControl.PAUSED
            ? new PacingController.Result(world.pacingState(), world.tension(), world.engineControl(),
                PacingController.Cause.NONE, new EngineParameters(input.config()).unit(
                    "pacing." + world.pacingState().name().toLowerCase(Locale.ROOT) + "_intensity_factor"))
            : world.engineControl().revision().value() == 0
                ? pacing.initialize(world.pacingState(), world.tension(), world.engineControl(), world.simTick(), signals, random)
                : pacing.advance(world.pacingState(), world.tension(), world.engineControl(), world.simTick(), signals, random);
        var nextPacing=AdminTensionOverride.active(input).map(tension->new PacingController.Result(measuredPacing.pacing(),
                tension,measuredPacing.control(),measuredPacing.cause(),measuredPacing.intensityFactor())).orElse(measuredPacing);
        var window = new CandidateGenerator().generate(input, random);
        List<Candidate> candidates = new ArrayList<>();
        Map<CandidateId, ExecutionPlan> plans = new HashMap<>();
        Map<CandidateId, FeatureExtractor.Features> extracted = new HashMap<>();
        List<ValidationResult> validations = new ArrayList<>();
        List<SystemError> errors = new ArrayList<>();
        Map<CandidateId, DeferredPlanning> deferrals = new HashMap<>();
        long missingFeatures = 0, microDeferred = 0, planningDeferred = 0;
        for (var generated : window.candidates()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("decision candidate work cancelled");
            var known = new EligibilityEngine(mode).validateKnown(generated, input, nextPacing.intensityFactor());
            var features = new FeatureExtractor().extract(generated, input);
            extracted.put(generated.id(), features);
            missingFeatures += features.missing().size();
            var score = new ScoreEngine(input.config()).score(generated.definition().selection().baseWeight(), features.values(), features.repetitionPenalty(), features.habitationPenalty(), 0, humourMultiplier(generated.definition(), nextPacing.tension(), input.config()));
            Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
            reasons.addAll(known.reasons());
            if (score.score() < generated.definition().selection().scoreFloor()) reasons.add(ReasonCode.NO_TARGET);
            if (generated.definition().families().contains(Family.SAFE_MICRO_EVENT)) {
                if (!microDue(generated, input)) reasons.add(ReasonCode.RATE_LIMITED);
            }
            Optional<ExecutionPlan> plan = Optional.empty();
            if (reasons.isEmpty() && planner.isPresent()) {
                var planned = planner.orElseThrow().plan(generated, input);
                plan = planned.plan(); reasons.addAll(planned.reasons());
                if (!planned.observations().isEmpty()) deferrals.put(generated.id(), new DeferredPlanning(planned.observations(), planned.reasons()));
            }
            if (plan.isEmpty()) {
                if (reasons.isEmpty()) reasons.add(ReasonCode.CAPABILITY_MISSING);
                planningDeferred++;
            }
            plan.ifPresent(value -> plans.put(generated.id(), value));
            var checked = new ValidationResult(reasons.isEmpty(), List.copyOf(reasons), plan.map(ExecutionPlan::readSet).orElse(known.checkedVersions()));
            if (!checked.valid() && generated.definition().families().contains(Family.SAFE_MICRO_EVENT)) microDeferred++;
            validations.add(checked);
            List<ScoreContribution> contributions = new ArrayList<>(score.contributions());
            features.missing().stream().sorted().forEach(feature -> contributions.add(new ScoreContribution("unknown:" + feature.name(),
                new EngineParameters(input.config()).unit("score.neutral_feature"), 0, 0)));

            candidates.add(new Candidate(generated.id(), generated.reference(), checked.valid(), checked.reasons(), contributions, score.score(), generated.roles(), generated.scopes()));
            Set<ScopeRef> retryScopes = new HashSet<>(generated.scopes().scopes());
            var targeted = deferrals.get(generated.id());
            if (targeted != null) targeted.observations().forEach(request -> retryScopes.addAll(request.scopes().scopes()));
            for (var reason : checked.reasons()) errors.add(new SystemError(reason, Severity.WARNING, true,
                Optional.of(new RetryCondition(reason, Optional.empty(), retryScopes)),
                retryScopes, new CorrelationId(id.value()), SafeState.DEFERRED));
        }
        Optional<AiProposalResult> advisor = input.advisorySnapshot().map(recorded -> validateAdvice(input, id, candidates, recorded));
        Map<CandidateId, Double> adjustments = new HashMap<>();
        advisor.flatMap(AiProposalResult::accepted).ifPresent(a -> a.proposal().adjustments().forEach(delta -> adjustments.put(delta.candidateId(), delta.deltaLogWeight())));
        List<Candidate> adjusted = new ArrayList<>();
        for (var candidate : candidates) {
            double delta = adjustments.getOrDefault(candidate.id(), 0.0);
            if (delta == 0) { adjusted.add(candidate); continue; }
            var features = extracted.get(candidate.id());
            var score = new ScoreEngine(input.config()).score(input.catalog().events().get(candidate.definition().id()).selection().baseWeight(), features.values(), features.repetitionPenalty(), features.habitationPenalty(), delta, humourMultiplier(input.catalog().events().get(candidate.definition().id()), nextPacing.tension(), input.config()));
            adjusted.add(new Candidate(candidate.id(), candidate.definition(), candidate.eligible(), candidate.reasons(), score.contributions(), score.score(), candidate.roles(), candidate.scopes()));
        }
        candidates.clear(); candidates.addAll(adjusted);
        boolean regularAvailable = candidates.stream().anyMatch(c -> c.eligible() && !input.catalog().events().get(c.definition().id()).families().contains(Family.SAFE_MICRO_EVENT));
        List<Candidate> selectable = candidates.stream().filter(c -> !regularAvailable || !input.catalog().events().get(c.definition().id()).families().contains(Family.SAFE_MICRO_EVENT)).toList();
        var service = new RequestScheduler().service(input, selectable);
        var selected = new WeightedSelector(input.config()).select(service.candidates(), random);
        var audit = new AuditDecision(id, input.context(), input.config().revision(), input.config().provenanceHash(),
            new Revision(input.catalog().generation().value()), input.catalog().hash(), candidates, selected.pool(), input.seed(), random.draws(), advisor,
            selected.chosen(), selected.chosen().map(plans::get).map(ExecutionPlan::roles).orElse(new RoleAssignments(Map.of())), input.context().scopes(), validations, List.of(), List.of(), Optional.empty(), List.of(),
            Map.of(), List.of(), List.of(), List.of(), Map.of("candidate_partitions", window.partitions(), "rotation_start", window.start(),
            "partitions_examined", (long) window.examined(), "missing_features", missingFeatures, "MIN_PRESENCE_DEFERRED", microDeferred,
            "role_bound_planner_deferred", planningDeferred), errors);
        var header = world.header();
        var nextWorld = new DirectorWorldState(new AggregateHeader(header.schemaVersion(), header.id(), header.revision().plus(1), header.lastSequence(), world.simTick()),
            world.worldId(), world.storeGeneration(), world.simTick(), world.runControl(), world.healthMode(), nextPacing.pacing(), nextPacing.tension(),
            world.intensityProfileId(), world.catalogHash(), world.configHash(), world.bootEpoch(), world.lastCheckpointSeq(), new EngineControlState(1, nextPacing.control().revision(), nextPacing.control().pacingEnteredTick(), nextPacing.control().pacingDeadlineTick(),
                nextPacing.control().tensionEma(), nextPacing.control().tensionLastUpdateTick(), nextPacing.control().belowThresholdSince(), service.credit()));
        var aggregate = new WorldAggregate(nextWorld);
        List<AggregateChange> changes = new ArrayList<>();
        changes.add(new AggregateChange(aggregate.key(), Optional.of(header.revision()), aggregate));
        changes.addAll(new RequestScheduler().defer(input, candidates));
        if (selected.chosen().isPresent()) {
            var chosen = candidates.stream().filter(c -> c.id().equals(selected.chosen().orElseThrow())).findFirst().orElseThrow();
            var requests = new RequestScheduler();
            requests.scheduled(input, requests.chooseMatchedForSelected(input, chosen))
                .ifPresent(change -> { changes.removeIf(old -> old.key().equals(change.key())); changes.add(change); });
        }
        var generatedCandidates=new HashMap<CandidateId,CandidateGenerator.Generated>();
        window.candidates().forEach(candidate->generatedCandidates.put(candidate.id(),candidate));
        return new Evaluation(new DecisionProposal(id, candidates, selected.chosen().map(plans::get), audit, changes), deferrals,generatedCandidates);
    }

    private static double humourMultiplier(EventDefinition definition, GlobalTension tension, ConfigView config) {
        return definition.families().contains(Family.HUMOUR) && tension.ordinal() >= GlobalTension.TENSE.ordinal()
            ? new EngineParameters(config).unit("pacing.humour_tense_multiplier") : 1;
    }

    private AiProposalResult validateAdvice(DecisionInput input, DecisionId id, List<Candidate> candidates, AiProposalResult recorded) {
        var invalid = new AiProposalResult(AiStatus.FALLBACK, Optional.empty(), Optional.of(ReasonCode.AI_INVALID));
        if (recorded.status() == AiStatus.FALLBACK) return recorded;
        if (advisoryInput.isEmpty()) return invalid;
        var original = advisoryInput.orElseThrow();
        if (!original.decisionId().equals(id) || !original.catalogHash().equals(input.catalog().hash())
            || !original.contextHash().equals(RecordedAdvice.hash(new ModelCodec<>(SnapshotBatch.class).encode(input.context())))) return invalid;
        Map<CandidateId, Candidate> eligible = new HashMap<>();
        candidates.stream().filter(Candidate::eligible).forEach(c -> eligible.put(c.id(), c));
        for (var candidate : original.candidates()) {
            var actual = eligible.get(candidate.candidateId());
            if (actual == null || !actual.definition().id().equals(candidate.eventDefinitionId())
                || Double.compare(actual.score(), candidate.deterministicScore()) != 0) return invalid;
        }
        if (recorded.accepted().orElseThrow().proposal().adjustments().stream().anyMatch(a -> Math.abs(a.deltaLogWeight()) > new EngineParameters(input.config()).nonnegative("ai.max_abs_log_delta"))) return invalid;
        return RecordedAdvice.validate(original, recorded, eligible.keySet());
    }

    /** Exact computational replay from the captured input, including its recorded advisory. */
    public DecisionProposal replay(DecisionInput recordedInput, AuditDecision expectedAudit) {
        var replayed = evaluate(recordedInput);
        byte[] actual = new ModelCodec<>(AuditDecision.class).encode(replayed.audit());
        byte[] expected = new ModelCodec<>(AuditDecision.class).encode(expectedAudit);
        if (!Arrays.equals(actual, expected)) throw new IllegalArgumentException("Decision replay mismatch");
        return replayed;
    }
    private static boolean microDue(CandidateGenerator.Generated candidate, DecisionInput input) {
        var parameters = new EngineParameters(input.config());
        long now = input.state().world().simTick().value();
        var players = ConditionEvaluator.players(candidate);
        if (players.isEmpty()) return false;
        for (var player : players) {
            var memory = input.memory().operationalPlayers().stream().filter(m -> m.playerId().equals(player)).findFirst();
            if (memory.isEmpty() || memory.orElseThrow().eligibleOnlineTicks().value() < parameters.ticks("pacing.micro_presence_ticks")) return false;
            for (var exposure : memory.orElseThrow().eventExposureRing()) {
                long gap = exposure.family() == Family.SAFE_MICRO_EVENT ? parameters.ticks("pacing.micro_safety_gap_ticks") : parameters.ticks("pacing.micro_presence_ticks");
                if (exposure.tick().value() > now || now - exposure.tick().value() < gap) return false;
            }
        }
        return true;
    }
    private static PacingController.Signals signals(DecisionInput input) {
        var world = input.state().world();
        double capacity = input.state().budgets().capacity().globalIntensity();
        Optional<UnitValue> intensity = capacity > 0 ? Optional.of(new UnitValue(Math.clamp(input.state().budgets().reserved().globalIntensity() / capacity, 0, 1))) : Optional.empty();
        Optional<UnitValue> risk = input.context().missingData().contains(SensorKind.RISK) ? Optional.empty()
            : input.context().context().regions().values().stream().flatMap(r -> r.risks().values().stream()).max(Comparator.comparingDouble(UnitValue::value));
        boolean death = input.memory().operationalPlayers().stream().anyMatch(m -> m.recentDeathUntil().filter(t -> t.compareTo(world.simTick()) > 0).isPresent());
        HealthMode health = world.healthMode().ordinal() >= input.context().serverHealth().ordinal() ? world.healthMode() : input.context().serverHealth();
        // Recent significant-fact normalization and narrative bias require the affinity projection.
        return new PacingController.Signals(intensity, risk, EvidenceSignals.component(input, MemoryComponent.NARRATIVE_FACT, Optional.empty()), 0,
            EvidenceSignals.component(input, MemoryComponent.ACTIVITY, Optional.empty()).orElse(new UnitValue(new EngineParameters(input.config()).unit("score.neutral_feature"))), death, health,
            input.state().budgets().reserved().globalIntensity() < capacity);
    }
}
