package fr.proutlost.director.engine;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Standalone JDK 21 tests against the frozen contracts JAR; no gameplay test providers. */
public final class EngineAlgorithmsTest {
    private static int assertions;
    private static ConfigView config;
    private static final Fingerprint HASH = new Fingerprint("0".repeat(64));
    private static final DefinitionId DEF = new DefinitionId("proutlost:test");
    private static final RoleAssignments ROLES = new RoleAssignments(Map.of());
    private static final ScopeSet SCOPES = new ScopeSet(new WorldId(new UUID(0, 1)), Set.of());

    public static void main(String[] args) throws Exception {
        var root = (JsonValue.ObjectValue) CanonicalJsonV1.decode(Files.readAllBytes(Path.of(args[0])));
        var values = (JsonValue.ObjectValue) root.values().get("values");
        Map<String, JsonValue.Number> numbers = new HashMap<>();
        values.values().forEach((k, v) -> numbers.put(k, (JsonValue.Number) v));
        config = new ConfigView(new Revision(1), HASH, Calibration.DEFAULT_FOR_TESTING, numbers);
        scores(); selection(); pacing(); credits(); exposures(); advice();
        assertions += DecisionPipelineTest.run(config);
        System.out.println("PASS engine algorithm tests: " + assertions + " assertions");
        System.out.println("NOT_RUN live admission / native GameTests / clients / Gradle");
    }

    private static void scores() {
        var engine = new ScoreEngine(config);
        close(engine.score(2, Map.of(), 0, 0, 0).score(), 2, 1e-14, "neutral score equals base");
        Map<Feature, UnitValue> features = new EnumMap<>(Feature.class);
        double expected = Math.log(3) - .3 - .2 + .1;
        for (Feature feature : Feature.values()) {
            double value = (feature.ordinal() + 1.0) / 12;
            features.put(feature, new UnitValue(value));
            expected += config.values().get("score." + feature).value().doubleValue() * (2 * value - 1);
        }
        var actual = engine.score(3, features, .3, .2, .1);
        close(actual.logScore(), expected, 1e-14, "exact normative log formula");
        close(actual.score(), Math.exp(expected), 1e-14, "exponentiated formula");
        close(actual.contributions().stream().mapToDouble(ScoreContribution::contribution).sum(), expected, 1e-14, "auditable breakdown");
        close(engine.score(Double.MAX_VALUE, Map.of(), 0, 0, 0).score(), Math.exp(20), 1e-6, "upper clamp");
        close(engine.score(1, Map.of(), 100, 0, 0).score(), Math.exp(-20), 1e-20, "lower clamp");
        rejects(() -> engine.score(0, Map.of(), 0, 0, 0), "zero base");
        rejects(() -> engine.score(Double.NaN, Map.of(), 0, 0, 0), "NaN base");
        rejects(() -> engine.score(1, Map.of(), -1, 0, 0), "negative penalty");
        rejects(() -> engine.score(1, Map.of(), 0, 0, .21), "unbounded AI");
        rejects(() -> new ScoreEngine(with("score.log_min", 21)).score(1, Map.of(), 0, 0, 0), "reversed clamps");
        System.out.println("PASS T-SEL formula, finite bounds, audit contributions");
    }

    private static void selection() {
        var selector = new WeightedSelector(with("selection.exploration_probability", 0));
        var candidates = List.of(candidate(1, 1, true), candidate(2, 3, true), candidate(3, 1000, false));
        int low = 0, high = 0;
        int draws = Math.toIntExact(config.values().get("test.statistical_draws").value().longValueExact());
        var random = new DecisionRandom(202603);
        for (int i = 0; i < draws; i++) {
            var choice = selector.select(candidates, random);
            if (choice.chosen().orElseThrow().equals(candidates.get(0).id())) low++;
            else if (choice.chosen().orElseThrow().equals(candidates.get(1).id())) high++;
            else throw new AssertionError("ineligible candidate chosen");
        }
        double tolerance = config.values().get("test.statistical_max_probability_error").value().doubleValue();
        close((double) low / draws, .25, tolerance, "weighted 1:3 distribution");
        check(low + high == draws && low > 0 && high > 0, "both candidates selected");
        System.out.println("PASS T-SEL distribution draws=" + draws + " low=" + low + " high=" + high);
        List<Candidate> reversed = new ArrayList<>(candidates); Collections.reverse(reversed);
        for (int i = 0; i < 100; i++) {
            var a = new DecisionRandom(i); var b = new DecisionRandom(i);
            check(selector.select(candidates, a).equals(selector.select(reversed, b)), "input order independent replay");
            check(a.draws().equals(b.draws()), "same random stream");
        }
        var exploreConfig = with(Map.of("selection.top_k", 1, "selection.exploration_probability", 1));
        var exploration = new WeightedSelector(exploreConfig);
        var explored = exploration.select(candidates, new DecisionRandom(1));
        check(explored.exploration() && explored.chosen().orElseThrow().equals(candidates.get(0).id()), "exploration chooses rest");
        check(!exploration.select(List.of(candidates.get(0)), new DecisionRandom(1)).exploration(), "empty rest falls back");
        check(selector.select(List.of(candidate(4, .0001, true)), new DecisionRandom(1)).chosen().isPresent(), "absolute floor applies only to exploration rest");
        check(!exploration.select(List.of(candidate(4, 1, true), candidate(5, .0001, true)), new DecisionRandom(1)).exploration(), "exploration floor excludes tiny rest");
        check(selector.select(List.of(candidate(4, 1, false)), new DecisionRandom(1)).chosen().isEmpty(), "no eligible pool");
        rejects(() -> selector.select(List.of(candidates.get(0), candidates.get(0)), new DecisionRandom(1)), "duplicate IDs");
        rejects(() -> selector.select(List.of(candidate(4, 0, true)), new DecisionRandom(1)), "zero eligible score");
        var cold = new WeightedSelector(with(Map.of("selection.temperature", Double.MIN_VALUE, "selection.exploration_probability", 0)));
        check(cold.select(candidates, new DecisionRandom(1)).chosen().orElseThrow().equals(candidates.get(1).id()), "small temperature finite");
        var first = new DecisionRandom(123);
        var expected = new SplittableRandom(123);
        close(first.next(), expected.nextDouble(), 0, "Java21 SplittableRandom");
        var explorationDefault = new WeightedSelector(with("selection.top_k", 1));
        var explorationRandom = new DecisionRandom(73);
        var explorationCandidates = List.of(candidate(1, 9, true), candidate(2, 1, true), candidate(3, 3, true));
        int exploredCount = 0, exploredLow = 0;
        for (int i = 0; i < draws; i++) {
            var result = explorationDefault.select(explorationCandidates, explorationRandom);
            if (result.exploration()) {
                exploredCount++;
                if (result.chosen().orElseThrow().equals(explorationCandidates.get(1).id())) exploredLow++;
            }
        }
        close((double) exploredCount / draws, .08, tolerance, "configured exploration frequency");
        close((double) exploredLow / exploredCount, .25, .04, "exploration remains weighted");
        System.out.println("PASS T-SEL exploration draws=" + draws + " explored=" + exploredCount + " exploredLow=" + exploredLow);
    }

    private static void pacing() {
        var controller = new PacingController(config);
        var initial = state(0, 0, 0, 0, Optional.empty());
        var random = new DecisionRandom(42);
        var entered = controller.initialize(PacingState.REST, GlobalTension.CALM, initial, new Tick(0), signals(0, false, HealthMode.NORMAL), random);
        long deadline = entered.control().pacingDeadlineTick().value();
        check(deadline >= 2400 && deadline <= 12000, "rest duration bounded");
        var waiting = controller.advance(PacingState.REST, GlobalTension.CALM, entered.control(), new Tick(1), signals(0, false, HealthMode.NORMAL), random);
        check(waiting.control().pacingDeadlineTick().value() == deadline && random.draws().size() == 1, "no deadline reroll");
        var subtle = controller.advance(PacingState.REST, GlobalTension.CALM, waiting.control(), new Tick(deadline), signals(0, false, HealthMode.NORMAL), random);
        check(subtle.pacing() == PacingState.SUBTLE && subtle.cause() == PacingController.Cause.DEADLINE, "rest expires to subtle");
        var death = controller.advance(PacingState.ACTIVE, GlobalTension.CALM, subtle.control(), new Tick(deadline + 1), signals(0, true, HealthMode.NORMAL), random);
        check(death.pacing() == PacingState.RECOVERY && death.cause() == PacingController.Cause.RECENT_DEATH, "death forces recovery");
        int count = random.draws().size();
        var held = controller.advance(PacingState.RECOVERY, GlobalTension.CALM, death.control(), death.control().pacingDeadlineTick(), signals(0, true, HealthMode.NORMAL), random);
        check(held.control().pacingDeadlineTick().equals(death.control().pacingDeadlineTick()) && random.draws().size() == count, "continued recovery retains deadline");
        var resumed = controller.advance(PacingState.RECOVERY, GlobalTension.CALM, held.control(), held.control().pacingDeadlineTick(), signals(0, false, HealthMode.NORMAL), random);
        check(resumed.pacing() == PacingState.REST, "recovery exits once cause clears");
        var peakController = new PacingController(with("pacing.active_to_peak_probability", 1));
        var peak = peakController.advance(PacingState.ACTIVE, GlobalTension.CALM, state(0, 10, 0, 0, Optional.empty()), new Tick(10), signals(0, false, HealthMode.NORMAL), new DecisionRandom(1));
        check(peak.pacing() == PacingState.PEAK && peak.control().pacingDeadlineTick().value() <= 1810, "peak bounded");
        var zeroPeak = peakController.advance(PacingState.PEAK, GlobalTension.CALM, state(10, 10, 10, 0, Optional.empty()), new Tick(10), signals(0, false, HealthMode.NORMAL), new DecisionRandom(1));
        check(zeroPeak.pacing() == PacingState.RECOVERY, "zero-duration peak expires, never reinitializes");
        var ema = controller.advance(PacingState.REST, GlobalTension.CALM, state(0, 10000, 0, 0, Optional.empty()), new Tick(2400), signals(1, false, HealthMode.NORMAL), new DecisionRandom(1));
        close(ema.control().tensionEma().value(), .5, 1e-14, "EMA half life");
        check(ema.tension() == GlobalTension.UNUSUAL, "upward threshold");
        var lower = controller.advance(PacingState.REST, GlobalTension.TENSE, state(0, 10000, 0, .1, Optional.empty()), new Tick(1), signals(0, false, HealthMode.NORMAL), new DecisionRandom(1));
        check(lower.tension() == GlobalTension.TENSE && lower.control().belowThresholdSince().equals(Optional.of(new Tick(1))), "downward hysteresis starts timer");
        var recovered = controller.advance(lower.pacing(), lower.tension(), lower.control(), new Tick(1201), signals(0, false, HealthMode.NORMAL), new DecisionRandom(1));
        check(recovered.tension() == GlobalTension.CALM, "downward recovery window");
        rejects(() -> controller.advance(PacingState.REST, GlobalTension.CALM, recovered.control(), new Tick(0), signals(0, false, HealthMode.NORMAL), new DecisionRandom(1)), "clock rollback");
        var snapshot = new ModelCodec<>(EngineControlState.class);
        check(snapshot.decode(snapshot.encode(recovered.control())).equals(recovered.control()), "durable state roundtrip");
        Set<Long> deadlines = new HashSet<>();
        for (int i = 0; i < 100; i++) deadlines.add(controller.initialize(PacingState.REST, GlobalTension.CALM, initial, new Tick(0), signals(0, false, HealthMode.NORMAL), new DecisionRandom(i)).control().pacingDeadlineTick().value());
        check(deadlines.size() > 80, "varied seeded deadlines");
        for (HealthMode health : List.of(HealthMode.DEGRADED, HealthMode.CRITICAL, HealthMode.READ_ONLY_SAFE)) {
            var degraded = controller.advance(PacingState.ACTIVE, GlobalTension.CALM, state(0, 10, 0, 0, Optional.empty()), new Tick(10), signals(0, false, health), new DecisionRandom(1));
            check(degraded.pacing() == PacingState.RECOVERY && degraded.cause() == PacingController.Cause.HEALTH, "health closes peak " + health);
        }
        var missingSignals = new PacingController.Signals(Optional.empty(), Optional.empty(), Optional.empty(), 100,
            new UnitValue(0), false, HealthMode.NORMAL, true);
        var unknown = controller.advance(PacingState.REST, GlobalTension.CALM, state(0, 10000, 0, 0, Optional.empty()), new Tick(2400), missingSignals, new DecisionRandom(1));
        close(unknown.control().tensionEma().value(), .325, 1e-14, "unknown ambient signals neutral and narrative bias capped");
        var noBudget = new PacingController.Signals(Optional.of(new UnitValue(0)), Optional.of(new UnitValue(0)), Optional.of(new UnitValue(0)), 0, new UnitValue(0), false, HealthMode.NORMAL, false);
        check(peakController.advance(PacingState.ACTIVE, GlobalTension.CALM, state(0, 10, 0, 0, Optional.empty()), new Tick(10), noBudget, new DecisionRandom(1)).pacing() == PacingState.RECOVERY, "no budget prevents peak even at probability one");
        System.out.println("PASS T-PACE semi-Markov, death recovery, EMA, hysteresis, state codec");
    }

    private static void credits() {
        var scheduler = new RequiredServiceCredit(config);
        UnitValue credit = new UnitValue(0);
        int required = 0, ambient = 0;
        for (int i = 0; i < 30; i++) {
            var next = scheduler.advance(credit, true, true); credit = next.credit();
            if (next.lane() == RequiredServiceCredit.Lane.REQUIRE) required++; else ambient++;
        }
        check(required == 10 && ambient == 20, "0.35 capped credit yields one in three ready windows");
        var opportunistic = scheduler.advance(new UnitValue(0), true, false);
        check(opportunistic.lane() == RequiredServiceCredit.Lane.REQUIRE, "safe ready requirement uses vacant opportunity");
        close(opportunistic.credit().value(), .35, 0, "vacant opportunity preserves sub-unit credit");
        var noReady = scheduler.advance(new UnitValue(.8), false, true);
        check(noReady.lane() == RequiredServiceCredit.Lane.AMBIENT, "no ready requirement cannot monopolize");
        close(noReady.credit().value(), .8, 0, "no infinite idle accumulation");
        check(scheduler.advance(new UnitValue(1), false, false).lane() == RequiredServiceCredit.Lane.NONE, "no invented opportunity");
        var decimal = new RequiredServiceCredit(with("scheduler.required_service_share", .1));
        credit = new UnitValue(0);
        for (int i = 0; i < 9; i++) credit = decimal.advance(credit, true, true).credit();
        check(decimal.advance(credit, true, true).lane() == RequiredServiceCredit.Lane.REQUIRE, "decimal threshold exact");
        System.out.println("PASS T-QST credit arithmetic ONLY; request readiness/priority not qualified");
    }

    private static void exposures() {
        var engine = new ExposureFeatures(config);
        PlayerId a = new PlayerId(new UUID(0, 1)), b = new PlayerId(new UUID(0, 2));
        var first = memory(a); var second = memory(b);
        var solo = engine.evaluate(DEF, Set.of(Family.SOLO), new RoleAssignments(Map.of("witness", List.of(a))), List.of(first), Map.of(), new Tick(0));
        var group = engine.evaluate(DEF, Set.of(Family.SOLO), new RoleAssignments(Map.of("witness", List.of(a, b))), List.of(first, second), Map.of(), new Tick(0));
        check(solo.equals(group), "group mean equals identical solo");
        var soloRoles = new RoleAssignments(Map.of("witness", List.of(a)));
        var mixedRoles = new RoleAssignments(Map.of("witness", List.of(a, b)));
        var unknown = engine.evaluate(DEF, Set.of(Family.SOLO), soloRoles, List.of(), Map.of(), new Tick(0));
        close(unknown.novelty().value(), .5, 0, "missing history uses neutral novelty");
        close(unknown.suitability().value(), .5, 0, "missing history uses neutral suitability");
        close(unknown.repetitionPenalty(), 0, 0, "unknown history invents no repetition evidence");
        var mixed = engine.evaluate(DEF, Set.of(Family.SOLO), mixedRoles, List.of(first), Map.of(), new Tick(0));
        close(mixed.novelty().value(), (solo.novelty().value() + .5) / 2, 1e-14, "mixed history contributes neutral novelty for missing target");
        close(mixed.repetitionPenalty(), solo.repetitionPenalty(), 0, "missing target does not dilute known repetition");
        var empty = new PlayerOperationalMemory(first.header(), first.playerId(), first.eligibleOnlineTicks(),
            first.lastRegionId(), first.lastTargetTick(), List.of(), List.of(), first.cooldownRefs(),
            first.attentionDebt(), first.recentActivity(), first.recentDeathUntil(), first.transitionEpoch());
        var knownEmpty = engine.evaluate(DEF, Set.of(Family.SOLO), soloRoles, List.of(empty), Map.of(), new Tick(0));
        close(knownEmpty.novelty().value(), 1, 0, "known empty history is fully novel");
        close(knownEmpty.repetitionPenalty(), 0, 0, "known empty history has no repetition");
        var emptyMixed = engine.evaluate(DEF, Set.of(Family.SOLO), mixedRoles, List.of(empty), Map.of(), new Tick(0));
        close(emptyMixed.novelty().value(), .75, 0, "known empty and unknown histories remain distinct in group");
        var customNeutral = new ExposureFeatures(with("score.neutral_feature", .25));
        close(customNeutral.evaluate(DEF, Set.of(Family.SOLO), soloRoles, List.of(), Map.of(), new Tick(0)).novelty().value(), .25, 0, "unknown novelty reads configured neutral");
        close(customNeutral.evaluate(DEF, Set.of(Family.SOLO), mixedRoles, List.of(first), Map.of(), new Tick(0)).novelty().value(),
            (solo.novelty().value() + .25) / 2, 1e-14, "mixed novelty reads configured neutral");
        var later = engine.evaluate(DEF, Set.of(Family.SOLO), new RoleAssignments(Map.of("witness", List.of(a))), List.of(first), Map.of(), new Tick(144000));
        close(later.repetitionPenalty(), solo.repetitionPenalty() / 2, 1e-14, "exposure half life");
        check(later.novelty().value() > solo.novelty().value() && solo.novelty().value() > 0, "progressive novelty never bans");
        close(engine.cooldownReadiness(new Tick(100), new Tick(50)).value(), 0, 0, "cooldown not shortened");
        close(engine.cooldownReadiness(new Tick(0), new Tick(12000)).value(), 1, 0, "cooldown horizon");
        System.out.println("PASS exposure decay, novelty, group mean arithmetic");
    }

    private static void advice() {
        CandidateId id = candidate(1, 1, true).id();
        RequestId request = new RequestId(new UUID(0, 4));
        DecisionId decision = new DecisionId(new UUID(0, 5));
        var input = new AiInput(1, request, new Pseudonym("p_0000000000000000"), decision, HASH, HASH, HASH, 1000,
            List.of(new AiCandidate(id, DEF, Category.AMBIENT, Set.of(Family.SOLO), Map.of(), 1, Map.of(), Set.of(), Set.of())),
            List.of(), 4, new AiOutputLimits(8192, 8, 4, 4, .2, BudgetMath.zero()));
        var proposal = new AiProposal(1, request, HASH, List.of(new AiAdjustment(id, .1)), List.of(), Set.of(AiRationale.NOVELTY));
        var valid = accepted(proposal, decision, 999);
        check(RecordedAdvice.validate(input, valid, Set.of(id)).equals(valid), "recorded accepted advice retained");
        check(RecordedAdvice.validate(input, valid, Set.of()).status() == AiStatus.FALLBACK, "fresh ineligible target rejects all advice");
        check(RecordedAdvice.validate(input, accepted(proposal, decision, 1000), Set.of(id)).status() == AiStatus.FALLBACK, "deadline exclusive");
        check(RecordedAdvice.validate(input, accepted(proposal, new DecisionId(new UUID(0, 99)), 1), Set.of(id)).status() == AiStatus.FALLBACK, "wrong decision");
        var excessive = new AiProposal(1, request, HASH, List.of(new AiAdjustment(id, .21)), List.of(), Set.of());
        check(RecordedAdvice.validate(input, accepted(excessive, decision, 1), Set.of(id)).status() == AiStatus.FALLBACK, "excessive adjustment rejects entire advice");
        var unknownArc = new AiProposal(1, request, HASH, List.of(), List.of(new AiArcProposal(new ChainId(new UUID(0, 9)), List.of(new ResourceId("proutlost:unknown")))), Set.of());
        check(RecordedAdvice.validate(input, accepted(unknownArc, decision, 1), Set.of(id)).status() == AiStatus.FALLBACK, "unknown arc rejected");
        var fallback = new AiProposalResult(AiStatus.FALLBACK, Optional.empty(), Optional.of(ReasonCode.AI_TIMEOUT));
        check(RecordedAdvice.validate(input, fallback, Set.of(id)).equals(fallback), "timeout remains fallback");
        var codec = new ModelCodec<>(AiProposalResult.class);
        check(RecordedAdvice.validate(input, codec.decode(codec.encode(valid)), Set.of(id)).equals(valid), "stored advice replay");
        var a = valid.accepted().orElseThrow();
        var wrongHash = new AiProposalResult(AiStatus.VALID_PROPOSAL, Optional.of(new AiAcceptedProposal(a.proposal(), HASH, HASH, HASH, decision, a.adapterId(), a.modelVersion(), 1)), Optional.empty());
        check(RecordedAdvice.validate(input, wrongHash, Set.of(id)).status() == AiStatus.FALLBACK, "corrupted stored proposal hash rejected");
        rejects(() -> new AiAdjustment(id, Double.NaN), "NaN cannot enter advice DTO");
        System.out.println("PASS T-AI recorded advisory validation/replay; no transport invoked");
    }

    private static AiProposalResult accepted(AiProposal proposal, DecisionId decision, long time) {
        var bytes = new ModelCodec<>(AiProposal.class).encode(proposal);
        return new AiProposalResult(AiStatus.VALID_PROPOSAL, Optional.of(new AiAcceptedProposal(proposal, RecordedAdvice.hash(bytes), HASH, HASH, decision, new ResourceId("proutlost:test"), "test", time)), Optional.empty());
    }
    private static PlayerOperationalMemory memory(PlayerId player) {
        var exposure = new Exposure(DEF, Family.SOLO, "witness", new Tick(0), new UnitValue(1));
        return new PlayerOperationalMemory(new AggregateHeader(1, player.value(), new Revision(1), new Sequence(0), new Tick(0)), player,
            new DurationTicks(1000), Optional.empty(), Optional.empty(), List.of(exposure), List.of(exposure), Set.of(),
            new UnitValue(.5), Map.of(), Optional.empty(), new Epoch(0));
    }
    private static PacingController.Signals signals(double level, boolean death, HealthMode health) {
        var value = Optional.of(new UnitValue(level));
        return new PacingController.Signals(value, value, value, 0, new UnitValue(0), death, health, true);
    }
    private static EngineControlState state(long entered, long deadline, long last, double ema, Optional<Tick> below) {
        return new EngineControlState(1, new Revision(0), new Tick(entered), new Tick(deadline), new UnitValue(ema), new Tick(last), below, new UnitValue(0));
    }
    private static Candidate candidate(long id, double score, boolean eligible) {
        return new Candidate(new CandidateId(new UUID(0, id)), new DefinitionRef(DEF, new Revision(1), HASH), eligible,
            eligible ? List.of() : List.of(ReasonCode.SAFETY_COOLDOWN), List.of(), score, ROLES, SCOPES);
    }
    private static ConfigView with(String key, double value) { return with(Map.of(key, value)); }
    private static ConfigView with(Map<String, ? extends Number> changes) {
        var values = new HashMap<>(config.values());
        changes.forEach((key, value) -> values.put(key, new JsonValue.Number(new BigDecimal(value.toString()))));
        return new ConfigView(config.revision(), config.provenanceHash(), config.calibration(), values);
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void close(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + message);
    }
}
