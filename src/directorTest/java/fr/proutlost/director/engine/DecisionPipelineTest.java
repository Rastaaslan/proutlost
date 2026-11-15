package fr.proutlost.director.engine;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DecisionPort;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.*;

/** Cross-component API1.0.1 decisions with a TEST_ONLY pure planner; no physical effects. */
public final class DecisionPipelineTest {
    private static int assertions;
    private static final Fingerprint HASH = new Fingerprint("0".repeat(64));
    private static final Tick NOW = new Tick(20000);
    private static final DimensionId DIM = new DimensionId("minecraft:overworld");
    private static final WorldId WORLD = new WorldId(uuid(1));
    private static final RegionId REGION = new RegionId(uuid(2));
    private static final PlayerId PLAYER = new PlayerId(uuid(3));
    private static final ScopeRef WORLD_SCOPE = new ScopeRef(ScopeKind.WORLD, WORLD.value());
    private static final ScopeRef REGION_SCOPE = new ScopeRef(ScopeKind.REGION, REGION.value());
    private static final ScopeRef PLAYER_SCOPE = new ScopeRef(ScopeKind.PLAYER, PLAYER.value());
    private static final PrimitiveId PRIMITIVE = new PrimitiveId("proutlost:target_sound");
    private static final CapabilityId CAPABILITY = new CapabilityId("perception.sound_v1");

    public static int run(ConfigView config) {
        assertions = 0;
        var input = fixture(config, 1);
        positivePlanning(input); pausedPacing(input); adminTension(input); catalogueRegionalBudget(input); regionalRiskKnowledge(input); reviewNine(input); conditions(input); rotation(config); interactingRotations(config); roles(input); safety(input); decisions(input); preferences(input); groupsAndRegions(input);
        System.out.println("PASS API1.0.1 decision pipeline: " + assertions + " assertions (test-only planner; live admission NOT_RUN)");
        return assertions;
    }
    private static void positivePlanning(DecisionInput input) {
        var candidate = generated(input);
        var step = candidate.definition().steps().getFirst();
        var footprint = new Footprint(DIM, input.context().loadedChunks(), Set.of(), List.of());
        var bindings = new PlanningBindings(candidate.id(), candidate.reference(), new EventInstanceId(uuid(101)), new OperationToken(uuid(102)),
            step.id(), candidate.roles(), candidate.scopes(), footprint, Set.of(PLAYER), true);
        var snapshot = new PlanningSnapshot(input.context(), input.state().ownership(), input.state().budgets(), new BlobBundle(List.of()), Optional.of(bindings));
        var blob = BlobContent.capture(new ResourceId("proutlost:test_plan"), 1, new byte[]{1});
        fr.proutlost.director.api.port.PlanningPort port = request -> {
            var b = request.snapshot().bindings().orElseThrow();
            return new PlannedPrimitive(b, new PreparedEffect(new EffectIntent(b.operationToken(), request.step().primitive(), 1,
                request.step().args(), b.footprint(), b.instanceId()), request.snapshot().snapshot(), List.of(),
                new ReadSet(input.context().scopeRevisions(), Map.of(), Map.of()), Map.of(), candidate.definition().budget(), List.of(), Set.of(CAPABILITY),
                new EffectBlobs(Set.of(), blob.reference(), blob.reference(), Map.of(), Map.of())));
        };
        var director = new WorldDirector(ProviderMode.TEST_ONLY, new CandidatePlanner(port, Map.of(candidate.id(), List.of(snapshot))));
        var proposal = director.evaluate(input);
        check(proposal.selectedPlan().isPresent(), "captured role-bound planning selects safely");
        check(proposal.selectedPlan().orElseThrow().effects().size() == 1, "planned effect retained");
        check(proposal.audit().chosen().equals(Optional.of(candidate.id())), "audit chosen matches planned candidate");
        check(director.replay(input, proposal.audit()).equals(proposal), "positive proposal deterministic replay");
        var session=new StoreSession(input.state().world().worldId(),input.state().world().storeGeneration(),input.state().world().bootEpoch(),new SessionId(uuid(990)));
        var prepared=((PlannedPrimitive)port.plan(new PlanningRequest(step,snapshot))).effect();
        var archive=fr.proutlost.director.bootstrap.RecordedDecisionArchive.record(input,proposal,Map.of(candidate.id(),List.of(snapshot)),Map.of(bindings.operationToken(),prepared),session,ProviderMode.TEST_ONLY);
        check(DurableBlobReferences.collect(new DurableFact(archive.getKey())).equals(archive.getValue().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet())),"archive references are typed WAL/retention roots");
        check(fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(archive.getKey(),proposal.audit(),archive.getValue(),session,ProviderMode.TEST_ONLY,false).equals(proposal),"persisted input and planning reproduce exact selected audit");
        var reboot=new StoreSession(session.worldId(),session.storeGeneration(),session.bootEpoch().plus(1),new SessionId(uuid(991)));
        check(fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(archive.getKey(),proposal.audit(),archive.getValue(),reboot,ProviderMode.TEST_ONLY,true).equals(proposal),"archive is replayable after boot rotation without AI/world access");
        rejects(()->fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(archive.getKey(),proposal.audit(),List.of(),session,ProviderMode.TEST_ONLY,false),"missing archive blob denied");
        var foreign=new StoreSession(new WorldId(uuid(992)),session.storeGeneration(),session.bootEpoch(),session.authoritySession());
        rejects(()->fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(archive.getKey(),proposal.audit(),archive.getValue(),foreign,ProviderMode.TEST_ONLY,false),"cross-world archive denied");
        var payload=new HashMap<>(archive.getKey().payload());payload.put("audit_hash",new JsonValue.Text("f".repeat(64)));
        var badHash=replace(archive.getKey(),"payload",Map.copyOf(payload));
        rejects(()->fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(badHash,proposal.audit(),archive.getValue(),session,ProviderMode.TEST_ONLY,false),"archive audit hash mismatch denied");
        var paused = replace(input, "state", replace(input.state(), "world", replace(input.state().world(), "runControl", RunControl.PAUSED)));
        check(director.evaluate(paused).selectedPlan().isEmpty(), "captured plan cannot bypass pause");
        var incomplete = replace(snapshot, "bindings", Optional.of(replace(bindings, "audienceComplete", false)));
        check(new WorldDirector(ProviderMode.TEST_ONLY, new CandidatePlanner(port, Map.of(candidate.id(), List.of(incomplete)))).evaluate(input).selectedPlan().isEmpty(), "incomplete incidental audience rejected");
        check(new WorldDirector(ProviderMode.PRODUCTION, new CandidatePlanner(port, Map.of(candidate.id(), List.of(snapshot)))).evaluate(input).selectedPlan().isEmpty(), "production refuses test-only provider");
        var requirementInput = replace(input, "requests", List.of(request(input, false)));
        requirementInput = replace(requirementInput, "state", replace(requirementInput.state(), "world", replace(requirementInput.state().world(), "engineControl",
            replace(requirementInput.state().world().engineControl(), "requiredServiceCredit", new UnitValue(.9)))));
        var required = director.evaluate(requirementInput);
        check(required.selectedPlan().isPresent(), "ready REQUIRE produces a plan");
        var changedWorld = (WorldAggregate) required.stateChanges().getFirst().next();
        check(changedWorld.value().engineControl().requiredServiceCredit().value() == 0, "REQUIRE consumes capped credit");
        // A matching execution selected in the AMBIENT opportunity still needs
        // an actual SCHEDULED request change in this same audited proposal.
        var matching = request(input, false);
        var ambientInput = replace(input, "requests", List.of(matching));
        ambientInput = replace(ambientInput, "state", replace(ambientInput.state(), "world",
            replace(ambientInput.state().world(), "engineControl",
                replace(ambientInput.state().world().engineControl(), "requiredServiceCredit", new UnitValue(0)))));
        var ambient = director.evaluate(ambientInput);
        check(ambient.selectedPlan().isPresent(), "ambient opportunity preserves the genuinely selected matching plan");
        var scheduledAmbient = ambient.stateChanges().stream().map(AggregateChange::next)
            .filter(RequestAggregate.class::isInstance).map(RequestAggregate.class::cast).toList();
        check(scheduledAmbient.size() == 1 && scheduledAmbient.getFirst().value().requestId().equals(matching.state().requestId())
            && scheduledAmbient.getFirst().value().status() == RequestStatus.SCHEDULED
            && scheduledAmbient.getFirst().header().revision().equals(matching.revision().plus(1)),
            "actual WorldDirector schedules the exact matching request once even below REQUIRE credit");
        check(scheduledAmbient.getFirst().value().resultFactIds().isEmpty(),
            "SCHEDULED association is not a fulfilled fact");
        var ambientWorld = (WorldAggregate) ambient.stateChanges().getFirst().next();
        close(ambientWorld.value().engineControl().requiredServiceCredit().value(),
            input.config().values().get("scheduler.required_service_share").value().doubleValue(),
            "coincident matching selection preserves the unspent ambient fairness credit");
        check(director.replay(ambientInput, ambient.audit()).equals(ambient),
            "ambient request association replays deterministically from the real decision input");
        var noPlan = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(ambientInput);
        check(noPlan.selectedPlan().isEmpty() && noPlan.stateChanges().stream().map(AggregateChange::next)
            .filter(RequestAggregate.class::isInstance).map(RequestAggregate.class::cast)
            .noneMatch(r -> r.value().status() == RequestStatus.SCHEDULED),
            "no-plan decisions never fabricate a scheduled execution");
        var otherScope = new ScopeRef(ScopeKind.PLAYER, uuid(391));
        var outside = new RequestView(replace(matching.request(), "scope", otherScope),
            replace(matching.state(), "scope", otherScope), matching.revision());
        var outsideInput = replace(ambientInput, "requests", List.of(outside));
        var outsideProposal = director.evaluate(outsideInput);
        check(outsideProposal.selectedPlan().isPresent() && outsideProposal.stateChanges().stream().map(AggregateChange::next)
            .filter(RequestAggregate.class::isInstance).map(RequestAggregate.class::cast)
            .noneMatch(r -> r.value().status() == RequestStatus.SCHEDULED),
            "an unrelated request scope cannot borrow the selected ambient execution");
        var missingPlayer = new TargetConstraints(matching.state().constraints().regionIds(), Set.of(new PlayerId(uuid(392))));
        var missingPlayerPayload = replace((DirectorRequirement) matching.request().payload(), "targetConstraints", missingPlayer);
        var playerMismatch = new RequestView(replace(matching.request(), "payload", missingPlayerPayload),
            replace(matching.state(), "constraints", missingPlayer), matching.revision());
        var playerProposal = director.evaluate(replace(ambientInput, "requests", List.of(playerMismatch)));
        check(playerProposal.selectedPlan().isPresent() && playerProposal.stateChanges().stream().map(AggregateChange::next)
            .filter(RequestAggregate.class::isInstance).map(RequestAggregate.class::cast)
            .noneMatch(r -> r.value().status() == RequestStatus.SCHEDULED),
            "actual request player constraints remain exact on an ambient opportunity");
        var missingRegion = new TargetConstraints(Set.of(new RegionId(uuid(393))), matching.state().constraints().playerIds());
        var missingRegionPayload = replace((DirectorRequirement) matching.request().payload(), "targetConstraints", missingRegion);
        var regionMismatch = new RequestView(replace(matching.request(), "payload", missingRegionPayload),
            replace(matching.state(), "constraints", missingRegion), matching.revision());
        var regionProposal = director.evaluate(replace(ambientInput, "requests", List.of(regionMismatch)));
        check(regionProposal.selectedPlan().isPresent() && regionProposal.stateChanges().stream().map(AggregateChange::next)
            .filter(RequestAggregate.class::isInstance).map(RequestAggregate.class::cast)
            .noneMatch(r -> r.value().status() == RequestStatus.SCHEDULED),
            "actual request region constraints remain exact on an ambient opportunity");
        var deterministic = proposal.candidates().getFirst();
        var definition = candidate.definition();
        var aiCandidate = new AiCandidate(candidate.id(), definition.id(), definition.category(), definition.families(), Map.of(), deterministic.score(), Map.of(), Set.of(), Set.of());
        var contextHash = RecordedAdvice.hash(new ModelCodec<>(SnapshotBatch.class).encode(input.context()));
        var original = new AiInput(1, new RequestId(uuid(151)), new Pseudonym("p_0000000000000000"), proposal.proposalId(), input.catalog().hash(), contextHash, HASH,
            1000, List.of(aiCandidate), List.of(), 1, new AiOutputLimits(4096, 1, 0, 0, .1, BudgetMath.zero()));
        var aiProposal = new AiProposal(1, original.requestId(), HASH, List.of(new AiAdjustment(candidate.id(), .1)), List.of(), Set.of());
        var accepted = new AiAcceptedProposal(aiProposal, RecordedAdvice.hash(new ModelCodec<>(AiProposal.class).encode(aiProposal)), input.catalog().hash(), contextHash,
            proposal.proposalId(), new ResourceId("proutlost:test_advisor"), "test", 1);
        var advisedInput = replace(input, "advisorySnapshot", Optional.of(new AiProposalResult(AiStatus.VALID_PROPOSAL, Optional.of(accepted), Optional.empty())));
        var advisedDirector = new WorldDirector(ProviderMode.TEST_ONLY, new CandidatePlanner(port, Map.of(candidate.id(), List.of(snapshot))), Optional.of(original));
        var advised = advisedDirector.evaluate(advisedInput);
        check(advised.audit().advisor().orElseThrow().status() == AiStatus.VALID_PROPOSAL, "recorded advice accepted against captured input");
        check(advised.candidates().getFirst().score() > deterministic.score(), "accepted advice changes weight");
        check(advisedDirector.replay(advisedInput, advised.audit()).equals(advised), "accepted advice positive replay");
        var microInput = event(input, replace(definition, "families", Set.of(Family.SAFE_MICRO_EVENT)));
        var micro = generated(microInput);
        var microBindings = replace(replace(bindings, "candidateId", micro.id()), "definition", micro.reference());
        var microPlanner = new CandidatePlanner(port, Map.of(micro.id(), List.of(replace(snapshot, "bindings", Optional.of(microBindings)))));
        var microProposal = new WorldDirector(ProviderMode.TEST_ONLY, microPlanner).evaluate(microInput);
        check(microProposal.selectedPlan().isPresent(), "safe micro fallback can plan when presence is due");
        check(microProposal.audit().timings().get("MIN_PRESENCE_DEFERRED") == 0, "successful micro is not audited deferred");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> director.evaluate(input));
            var second = executor.submit(() -> advisedDirector.evaluate(advisedInput));
            check(first.get().equals(proposal), "concurrent valid proposal isolated from advisor");
            check(second.get().equals(advised), "concurrent advised proposal independent");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        } catch (java.util.concurrent.ExecutionException failed) { throw new AssertionError(failed.getCause()); }
        var profile = new DefinitionScoringProfile(1, candidate.reference(), HASH, List.of(new ContextAffinity(new PlayersPresent(1), new UnitValue(.8), new UnitValue(1))),
            Map.of(), Map.of(GlobalTension.CALM, new UnitValue(.9)), Optional.of(new UnitValue(.2)), Set.of(Family.AMBIENCE));
        var profiled = replace(input, "catalog", replace(input.catalog(), "scoringProfiles", Map.of(definition.id(), profile)));
        var features = new FeatureExtractor().extract(candidate, profiled);
        check(features.values().get(Feature.contextCompatibility).value() == .8, "definition context affinity consumed");
        check(features.values().get(Feature.globalTension).value() == .9, "definition tension affinity consumed");
        check(features.values().get(Feature.localIntensityFit).value() == .8, "local intensity affinity consumed");
        check(features.habitationPenalty() > 0, "unknown habitation uses configured neutral for sensitive family");
        var sample = new SampleRequest(new RequestId(uuid(170)), candidate.scopes(), Set.of(SensorKind.TERRAIN), BudgetMath.zero());
        var deferred = new DeferredPlanning(List.of(sample), List.of(ReasonCode.CHUNK_UNAVAILABLE));
        var deferring = new WorldDirector(ProviderMode.TEST_ONLY, new CandidatePlanner(r -> deferred, Map.of(candidate.id(), List.of(snapshot))));
        var details = deferring.evaluateDetailed(input);
        check(details.proposal().selectedPlan().isEmpty(), "targeted missing terrain does not select");
        check(details.planningDeferrals().get(candidate.id()).equals(deferred), "exact targeted samples and original reasons retained");
        check(details.proposal().candidates().getFirst().reasons().equals(deferred.reasons()), "planning reason not replaced by capability missing");
        check(details.proposal().audit().errors().getFirst().code() == ReasonCode.CHUNK_UNAVAILABLE, "audit retains planning reason");
        check(deferring.evaluateDetailed(input).equals(details), "targeted deferral deterministic replay");
        System.out.println("PASS positive role-bound selection, captured evidence, pause, incomplete audience, production refusal and replay");
    }
    /** Root clarification: an administrative pause is not a measured HEALTH recovery.
     * Exercise the real decision path; PacingController's RUNNING rules are unchanged. */
    private static void pausedPacing(DecisionInput input) {
        var engine = new WorldDirector(ProviderMode.TEST_ONLY);
        java.util.function.Function<DecisionInput, DirectorWorldState> after = source ->
            ((WorldAggregate) engine.evaluate(source).stateChanges().getFirst().next()).value();
        java.util.function.BiFunction<DecisionInput, Tick, DecisionInput> at = (source, atTick) -> {
            var players = new HashMap<PlayerId, PlayerSnapshot>();
            source.context().context().players().forEach((id, player) -> players.put(id,
                replace(replace(player, "capturedTick", atTick), "position", replace(player.position(), "tick", atTick))));
            var snapshot = replace(replace(source.context(), "capturedTick", atTick), "context",
                replace(source.context().context(), "players", players));
            return replace(replace(source, "context", snapshot), "state", replace(source.state(), "world",
                replace(source.state().world(), "simTick", atTick)));
        };
        var before = new EngineControlState(1, new Revision(7), new Tick(NOW.value() - 100), NOW.plus(600),
            new UnitValue(.23), new Tick(NOW.value() - 40), Optional.of(new Tick(NOW.value() - 50)), new UnitValue(.35));
        var pausedWorld = replace(replace(replace(replace(input.state().world(),
            "pacingState", PacingState.REST), "tension", GlobalTension.UNUSUAL),
            "engineControl", before), "runControl", RunControl.PAUSED);
        pausedWorld = replace(pausedWorld, "healthMode", HealthMode.READ_ONLY_SAFE);
        var paused = replace(input, "state", replace(input.state(), "world", pausedWorld));
        var proposal = engine.evaluate(paused);
        var held = ((WorldAggregate) proposal.stateChanges().getFirst().next()).value();
        check(proposal.selectedPlan().isEmpty() && proposal.candidates().stream()
            .allMatch(c -> c.reasons().contains(ReasonCode.AUTH_DENIED)),
            "PAUSED still produces a real rejected/no-plan decision");
        check(held.pacingState() == PacingState.REST && held.tension() == GlobalTension.UNUSUAL
            && held.engineControl().equals(before),
            "administrative READ_ONLY pause preserves phase, tension and complete pacing control");
        check(held.header().revision().equals(pausedWorld.header().revision().plus(1))
            && held.simTick().equals(pausedWorld.simTick()) && proposal.audit().context().equals(paused.context()),
            "paused audit/world publication remains durable-state compatible at the actual simTick");
        check(engine.replay(paused, proposal.audit()).equals(proposal), "paused no-plan decision remains deterministic");
        var later = after.apply(at.apply(paused, NOW.plus(50)));
        check(later.simTick().equals(NOW.plus(50)) && later.engineControl().equals(before),
            "server simTick progresses during pause without rerolling or shifting its deadline");
        var activePause = replace(paused, "state", replace(paused.state(), "world",
            replace(pausedWorld, "pacingState", PacingState.ACTIVE)));
        check(after.apply(activePause).pacingState() == PacingState.ACTIVE
            && after.apply(activePause).engineControl().equals(before),
            "pause preserves an existing active phase instead of injecting administrative recovery");
        var runningWorld = replace(replace(pausedWorld, "runControl", RunControl.RUNNING), "healthMode", HealthMode.NORMAL);
        var running = replace(input, "state", replace(input.state(), "world", runningWorld));
        var beforeDeadline = after.apply(running);
        check(beforeDeadline.pacingState() == PacingState.REST
            && beforeDeadline.engineControl().pacingDeadlineTick().equals(before.pacingDeadlineTick())
            && beforeDeadline.engineControl().revision().equals(before.revision().plus(1)),
            "healthy RUNNING resumes ordinary pacing before the unchanged original deadline");
        var expired = at.apply(running, before.pacingDeadlineTick());
        var advanced = after.apply(expired);
        check(advanced.pacingState() == PacingState.SUBTLE
            && advanced.engineControl().pacingEnteredTick().equals(before.pacingDeadlineTick())
            && advanced.engineControl().pacingDeadlineTick().compareTo(before.pacingDeadlineTick()) > 0,
            "a genuinely expired original REST deadline takes its normal RUNNING transition");
        for (var health : List.of(HealthMode.DEGRADED, HealthMode.CRITICAL, HealthMode.READ_ONLY_SAFE)) {
            var unhealthy = replace(running, "state", replace(running.state(), "world",
                replace(runningWorld, "healthMode", health)));
            check(after.apply(unhealthy).pacingState() == PacingState.RECOVERY,
                "RUNNING retains its real HEALTH recovery guard " + health);
        }
        var player = input.memory().operationalPlayers().getFirst();
        var dead = replace(player, "recentDeathUntil", Optional.of(NOW.plus(300)));
        var recentDeath = replace(running, "memory", replace(input.memory(), "operationalPlayers", List.of(dead)));
        check(after.apply(recentDeath).pacingState() == PacingState.RECOVERY,
            "RUNNING retains its actual recent-death recovery guard");
        var initialControl = input.state().world().engineControl();
        check(initialControl.revision().value() == 0, "fixture has an actual uninitialized pacing control");
        var initialPausedWorld = replace(pausedWorld, "engineControl", initialControl);
        var initialPaused = replace(paused, "state", replace(paused.state(), "world", initialPausedWorld));
        check(after.apply(initialPaused).engineControl().equals(initialControl),
            "PAUSED leaves revision0 uninitialized instead of inventing a first HEALTH entry");
        var initialRunning = replace(input, "state", replace(input.state(), "world",
            replace(replace(initialPausedWorld, "runControl", RunControl.RUNNING), "healthMode", HealthMode.NORMAL)));
        var initialized = after.apply(initialRunning);
        long restMin = input.config().values().get("pacing.rest_min_ticks").value().longValueExact();
        long restMax = input.config().values().get("pacing.rest_max_ticks").value().longValueExact();
        check(initialized.pacingState() == PacingState.REST && initialized.engineControl().revision().value() == 1
            && initialized.engineControl().pacingEnteredTick().equals(NOW)
            && initialized.engineControl().pacingDeadlineTick().value() >= NOW.value() + restMin
            && initialized.engineControl().pacingDeadlineTick().value() <= NOW.value() + restMax,
            "first healthy RUNNING entry keeps the configured initialization duration");
        var historicalWorld = replace(runningWorld, "pacingState", PacingState.RECOVERY);
        var historical = replace(running, "state", replace(running.state(), "world", historicalWorld));
        var recoveryHeld = after.apply(historical);
        check(recoveryHeld.pacingState() == PacingState.RECOVERY
            && recoveryHeld.engineControl().pacingDeadlineTick().equals(before.pacingDeadlineTick()),
            "an existing durable recovery deadline is never reset or shortened");
        var stillUnhealthyInput = replace(historical, "state", replace(historical.state(), "world",
            replace(historicalWorld, "healthMode", HealthMode.READ_ONLY_SAFE)));
        var stillUnhealthy = after.apply(at.apply(stillUnhealthyInput, before.pacingDeadlineTick()));
        check(stillUnhealthy.pacingState() == PacingState.RECOVERY
            && stillUnhealthy.engineControl().pacingDeadlineTick().equals(before.pacingDeadlineTick()),
            "a continuing true health cause keeps even an expired historical recovery deadline");
        var recovered = after.apply(at.apply(historical, before.pacingDeadlineTick()));
        check(recovered.pacingState() == PacingState.REST
            && recovered.engineControl().pacingEnteredTick().equals(before.pacingDeadlineTick()),
            "historical recovery exits only on its original deadline after the cause clears");
    }
    private static void reviewNine(DecisionInput input) {
        // Only the role player is a candidate target. The second player is physical bystander evidence.
        var incident = new PlayerId(uuid(199));
        var incidentScope = new ScopeRef(ScopeKind.PLAYER, incident.value());
        var player = replace(input.context().context().players().get(PLAYER), "playerId", incident);
        var players = new HashMap<>(input.context().context().players()); players.put(incident, player);
        var revisions = new HashMap<>(input.context().scopeRevisions()); revisions.put(incidentScope, new Revision(1));
        var scopes = new HashMap<>(input.context().context().scopes()); scopes.put(incidentScope, new ScopeSnapshot(incidentScope, new Revision(1), EnumSet.allOf(SensorKind.class), Set.of(), Map.of()));
        var context = replace(replace(input.context().context(), "players", players), "scopes", scopes);
        var batch = replace(replace(input.context(), "context", context), "scopeRevisions", revisions);
        var originalMemory = input.memory().operationalPlayers().getFirst();
        var incidentMemory = replace(replace(originalMemory, "playerId", incident), "header", header(incident.value()));
        var memory = replace(replace(input.memory(), "operationalPlayers", List.of(originalMemory, incidentMemory)), "revisions", revisions);
        var definition = input.catalog().events().values().iterator().next();
        var danger = replace(definition, "safety", new Safety(Risk.CONTROLLED_DANGER, Set.of(CAPABILITY), Optional.empty(), Optional.of(new ResourceId("proutlost:warning")),
            PlayerEditPolicy.YIELD, JournalPolicy.PLAYER_OPT_IN, ParticipantLoss.ABORT_IF_MIN_LOST));
        var rich = event(replace(replace(input, "context", batch), "memory", memory), danger);
        var candidate = new CandidateGenerator().generate(rich, new DecisionRandom(rich.seed())).candidates().stream().filter(c -> ConditionEvaluator.players(c).equals(Set.of(PLAYER))).findFirst().orElseThrow();
        var step = danger.steps().getFirst();
        var bindings = new PlanningBindings(candidate.id(), candidate.reference(), new EventInstanceId(uuid(201)), new OperationToken(uuid(202)), step.id(), candidate.roles(), candidate.scopes(),
            new Footprint(DIM, batch.loadedChunks(), Set.of(), List.of()), Set.of(PLAYER, incident), true);
        var capture = new PlanningSnapshot(batch, rich.state().ownership(), rich.state().budgets(), new BlobBundle(List.of()), Optional.of(bindings));
        var blob = BlobContent.capture(new ResourceId("proutlost:test_plan"), 1, new byte[]{1});
        fr.proutlost.director.api.port.PlanningPort port = r -> new PlannedPrimitive(r.snapshot().bindings().orElseThrow(),
            new PreparedEffect(new EffectIntent(bindings.operationToken(), step.primitive(), 1, step.args(), bindings.footprint(), bindings.instanceId()), batch, List.of(),
                new ReadSet(revisions, Map.of(), Map.of()), Map.of(), danger.budget(), List.of(), Set.of(CAPABILITY), new EffectBlobs(Set.of(), blob.reference(), blob.reference(), Map.of(), Map.of())));
        var planner = new CandidatePlanner(port, Map.of(candidate.id(), List.of(capture)));
        check(new WorldDirector(ProviderMode.TEST_ONLY, planner).evaluate(rich).selectedPlan().isPresent(), "controlled danger with complete safe incidental evidence plans");
        var cooldown = new CooldownRecord(header(uuid(203)), new ResourceId("proutlost:incident"), "safety", "incident", NOW.plus(100), new EventInstanceId(uuid(204)), Risk.CONTROLLED_DANGER, Optional.empty(),
            Optional.of(new CooldownBinding(new EventCooldownSelector(danger.id()), new ScopeSet(WORLD, Set.of(incidentScope)), Set.of(), true, CooldownReleaseMode.AFTER_TICK, Optional.empty())));
        var blocked = replace(rich, "state", replace(rich.state(), "cooldowns", List.of(cooldown)));
        check(!new CooldownPolicy().blocks(cooldown, candidate, blocked), "regression cooldown is incidental-only, not a role cooldown");
        var proposal = new WorldDirector(ProviderMode.TEST_ONLY, planner).evaluate(blocked);
        check(proposal.selectedPlan().isEmpty(), "incidental cooldown prevents controlled danger selection");
        check(proposal.candidates().stream().filter(c -> c.id().equals(candidate.id())).findFirst().orElseThrow().reasons().contains(ReasonCode.SAFETY_COOLDOWN), "incidental cooldown reason preserved");
        var gate = new EligibilityEngine(ProviderMode.TEST_ONLY);
        var noMemory = replace(rich, "memory", replace(memory, "operationalPlayers", List.of(originalMemory)));
        check(!gate.validateAudience(candidate, Set.of(incident), noMemory).valid(), "missing incidental memory fails closed");
        var dead = replace(incidentMemory, "recentDeathUntil", Optional.of(NOW.plus(10)));
        check(gate.validateAudience(candidate, Set.of(incident), replace(rich, "memory", replace(memory, "operationalPlayers", List.of(originalMemory, dead)))).reasons().contains(ReasonCode.SAFETY_COOLDOWN), "incidental death gated");
        schedulerIntervals(input);
        var humour = event(input, replace(definition, "families", Set.of(Family.HUMOUR)));
        var calm = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(humour).candidates().getFirst();
        var tenseWorld = replace(replace(humour.state().world(), "tension", GlobalTension.TENSE), "engineControl", replace(humour.state().world().engineControl(), "tensionEma", new UnitValue(.8)));
        var tenseInput = replace(humour, "state", replace(humour.state(), "world", tenseWorld));
        var tense = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(tenseInput).candidates().getFirst();
        close(tense.score() / calm.score(), new EngineParameters(input.config()).unit("pacing.humour_tense_multiplier"), "tense humour uses configured multiplier");
        var custom = new HashMap<>(input.config().values()); custom.put("pacing.humour_tense_multiplier", new JsonValue.Number(new BigDecimal("0.37")));
        var customInput = replace(tenseInput, "config", replace(input.config(), "values", custom));
        var changed = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(customInput).candidates().getFirst();
        close(changed.score() / calm.score(), .37, "nondefault humour multiplier consumed");
        check(changed.contributions().stream().anyMatch(c -> c.feature().equals("humourTensionMultiplier") && Math.abs(c.contribution() - Math.log(.37)) < 1e-14), "humour log contribution audited");
        System.out.println("PASS review9: incidental cooldown/safety, proven request intervals/bounded aging, targeted deferrals, humour tension calibration");
    }
    private static void schedulerIntervals(DecisionInput input) {
        var base = request(input, false);
        var waiting = replace(replace(base.state(), "status", RequestStatus.WAITING_SAFETY), "header", replace(base.state().header(), "updatedTick", new Tick(100)));
        var scheduler = new RequestScheduler();
        var candidate = generated(input);
        var ready = new Candidate(candidate.id(), candidate.reference(), true, List.of(), List.of(), 1, candidate.roles(), candidate.scopes());
        var rejected = replace(replace(ready, "eligible", false), "reasons", List.of(ReasonCode.SAFETY_COOLDOWN));
        for (var status : List.of(RequestStatus.WAITING_SAFETY, RequestStatus.WAITING_CONTEXT)) {
            var state = replace(waiting, "status", status);
            var at = replace(input, "requests", List.of(new RequestView(base.request(), state, state.header().revision())));
            var renewed = ((RequestAggregate)scheduler.defer(at, List.of(ready)).getFirst().next()).value();
            check(renewed.eligibleWaitTicks().value() == 0 && renewed.status() == RequestStatus.ACCEPTED, "renewed readiness excludes prolonged " + status);
            var now = NOW.plus(1);
            var next = replace(replace(input, "requests", List.of(new RequestView(base.request(), renewed, renewed.header().revision()))), "state", replace(input.state(), "world", replace(input.state().world(), "simTick", now)));
            var counted = ((RequestAggregate)scheduler.defer(next, List.of(ready)).getFirst().next()).value();
            check(counted.eligibleWaitTicks().value() == 1, "only fresh adjacent ready interval counted");
            var blocked = ((RequestAggregate)scheduler.defer(next, List.of(rejected)).getFirst().next()).value();
            check(blocked.eligibleWaitTicks().value() == 0, "blocked interval never increments wait");
            var distant = replace(next, "state", replace(next.state(), "world", replace(next.state().world(), "simTick", NOW.plus(10000))));
            var unproven = ((RequestAggregate)scheduler.defer(distant, List.of(ready)).getFirst().next()).value();
            check(unproven.eligibleWaitTicks().value() == 0, "unobserved long gap never increments wait");
        }
        var repeatedlyBlocked = replace(waiting, "lastReasonCodes", List.of(ReasonCode.SAFETY_COOLDOWN));
        var repeatedInput = replace(input, "requests", List.of(new RequestView(base.request(), repeatedlyBlocked, repeatedlyBlocked.header().revision())));
        var checkpoint = ((RequestAggregate)scheduler.defer(repeatedInput, List.of(rejected)).getFirst().next()).value();
        check(checkpoint.header().updatedTick().equals(NOW) && checkpoint.eligibleWaitTicks().value() == 0, "unchanged safety deferral checkpoints without accumulating wait");
        var older = replace(base.request(), "createdTick", new Tick(100));
        var rivalId = new RequestId(uuid(299));
        var rivalState = replace(replace(replace(base.state(), "requestId", rivalId), "header", header(rivalId.value())), "eligibleWaitTicks", new DurationTicks(5));
        var rivalEnvelope = replace(base.request(), "payload", replace((DirectorRequirement)base.request().payload(), "requestId", rivalId));
        var rival = new RequestView(rivalEnvelope, rivalState, rivalState.header().revision());
        var competition = replace(input, "requests", List.of(new RequestView(older, waiting, waiting.header().revision()), rival));
        competition = replace(competition, "state", replace(competition.state(), "world", replace(competition.state().world(), "engineControl", replace(competition.state().world().engineControl(), "requiredServiceCredit", new UnitValue(1)))));
        check(scheduler.service(competition, List.of(ready)).request().equals(Optional.of(rivalId)), "prolonged blocked age cannot overtake proven eligible competitor");
        var zeroValues = new HashMap<>(input.config().values()); zeroValues.put("scheduler.require_age_boost_cap", new JsonValue.Number(BigDecimal.ZERO));
        var noAge = replace(competition, "config", replace(input.config(), "values", zeroValues));
        check(scheduler.service(noAge, List.of(ready)).request().equals(Optional.of(base.state().requestId())), "zero aging cap changes competition to canonical creation order");
        var values = new HashMap<>(input.config().values()); values.put("scheduler.require_age_boost_cap", new JsonValue.Number(new BigDecimal("0.17")));
        var custom = replace(input, "config", replace(input.config(), "values", values));
        close(scheduler.ageBoost(replace(base.state(), "eligibleWaitTicks", new DurationTicks(1)), custom), .085, "aging uses configured cap");
        check(scheduler.ageBoost(replace(base.state(), "eligibleWaitTicks", new DurationTicks(Long.MAX_VALUE)), custom) <= .17, "aging bounded at extreme wait");
    }
    private static void conditions(DecisionInput input) {
        var generated = generated(input);
        var evaluator = new ConditionEvaluator();
        check(evaluator.evaluate(new PlayersPresent(1), generated, input) == ConditionEvaluator.Truth.TRUE, "present target");
        check(evaluator.evaluate(new PlayersPresent(2), generated, input) == ConditionEvaluator.Truth.FALSE, "insufficient targets");
        check(evaluator.evaluate(new RegionTag(new TagId("proutlost:camp")), generated, input) == ConditionEvaluator.Truth.TRUE, "region tags scoped");
        var missingRisk = new RiskMinimum(new ResourceId("proutlost:unknown"), new UnitValue(.1));
        check(evaluator.evaluate(new NotCondition(missingRisk), generated, input) == ConditionEvaluator.Truth.UNKNOWN, "NOT unknown risk remains unknown");
        check(evaluator.evaluate(new AllConditions(List.of(new PlayersPresent(1), missingRisk)), generated, input) == ConditionEvaluator.Truth.UNKNOWN, "ALL preserves unknown");
        check(evaluator.evaluate(new AnyConditions(List.of(new PlayersPresent(1), missingRisk)), generated, input) == ConditionEvaluator.Truth.TRUE, "ANY known true");
        check(evaluator.evaluate(new SoloReady(new DurationTicks(400)), generated, input) == ConditionEvaluator.Truth.TRUE, "isolation evidence");
        check(evaluator.evaluate(new MemoryFlag(new TagId("proutlost:absent"), false), generated, input) == ConditionEvaluator.Truth.UNKNOWN, "absent memory is not negative fact");
        Condition deep = new PlayersPresent(1);
        for (int i = 0; i < 150; i++) deep = new NotCondition(deep);
        check(evaluator.evaluate(deep, generated, input) == ConditionEvaluator.Truth.UNKNOWN, "condition work bounded");
    }
    private static void rotation(ConfigView original) {
        var values = new HashMap<>(original.values());
        values.put("scheduler.candidates_per_window", new JsonValue.Number(BigDecimal.valueOf(2)));
        var config = new ConfigView(original.revision(), original.provenanceHash(), original.calibration(), values);
        var input = fixture(config, 5);
        Set<DefinitionId> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            var window = new CandidateGenerator().generate(input, new DecisionRandom(1));
            check(window.examined() == 2 && window.candidates().size() == 2 && window.partitions() == 5, "window bounded");
            window.candidates().forEach(c -> seen.add(c.definition().id()));
            var proposal = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(input);
            var next = ((WorldAggregate) proposal.stateChanges().getFirst().next()).value();
            input = replace(input, "state", replace(input.state(), "world", next));
        }
        check(seen.size() == 5, "persisted rotation reaches tail of catalogue");
        var reversed = new LinkedHashMap<DefinitionId, EventDefinition>();
        input.catalog().events().values().stream().sorted(Comparator.comparing((EventDefinition d) -> d.id().value()).reversed()).forEach(d -> reversed.put(d.id(), d));
        var reordered = replace(input, "catalog", replace(input.catalog(), "events", reversed));
        check(new CandidateGenerator().generate(input, new DecisionRandom(9)).equals(new CandidateGenerator().generate(reordered, new DecisionRandom(9))), "canonical index independent of map insertion");
    }
    private static void interactingRotations(ConfigView original) {
        var values = new HashMap<>(original.values());
        values.put("scheduler.candidates_per_window", new JsonValue.Number(BigDecimal.valueOf(2)));
        var config = new ConfigView(original.revision(), original.provenanceHash(), original.calibration(), values);
        var input = fixture(config, 8);
        var definitions = new HashMap<DefinitionId, EventDefinition>();
        input.catalog().events().forEach((id, definition) -> definitions.put(id, replace(definition, "target",
            new Target(TargetScope.REGION, Set.of(), PlayerPolicy.FAIR, Map.of("witness", new Role(1, 1, false))))));
        input = replace(input, "catalog", replace(input.catalog(), "events", definitions));
        input = replace(input, "memory", replace(input.memory(), "operationalPlayers", List.of()));
        var prototype = input.context().context().players().get(PLAYER);
        List<PlayerId> audience = List.of(new PlayerId(uuid(100)), new PlayerId(uuid(101)), new PlayerId(uuid(102)), new PlayerId(uuid(103)));
        // First the review case, then sole-eligible probes prove that every audience position
        // is reached by every definition, including positions ineligible in the review case.
        for (int probe = -1; probe < audience.size(); probe++) {
            Map<PlayerId, PlayerSnapshot> players = new HashMap<>();
            Set<PlayerId> expected = probe == -1 ? Set.of(audience.get(2), audience.get(3)) : Set.of(audience.get(probe));
            for (var player : audience) players.put(player, replace(replace(prototype, "playerId", player), "eligibility",
                expected.contains(player) ? PlayerEligibility.ELIGIBLE : PlayerEligibility.DEAD));
            var context = replace(input.context().context(), "players", players);
            context = replace(context, "regions", Map.of(REGION, replace(context.regions().get(REGION), "presentPlayers", Set.copyOf(audience))));
            var scenario = replace(input, "context", replace(input.context(), "context", context));
            Map<DefinitionId, Set<PlayerId>> covered = new HashMap<>();
            definitions.keySet().forEach(id -> covered.put(id, new HashSet<>()));
            for (long revision = 0; revision < 16; revision++) {
                var control = replace(scenario.state().world().engineControl(), "revision", new Revision(revision));
                var visit = replace(scenario, "state", replace(scenario.state(), "world", replace(scenario.state().world(), "engineControl", control)));
                var random = new DecisionRandom(visit.seed());
                var window = new CandidateGenerator().generate(visit, random);
                var replayRandom = new DecisionRandom(visit.seed());
                check(window.equals(new CandidateGenerator().generate(visit, replayRandom)) && random.draws().equals(replayRandom.draws()), "interacting rotations deterministic replay");
                check(window.partitions() == 8 && window.examined() == 2 && window.candidates().size() == 2 && random.draws().size() <= 4,
                    "interacting rotations preserve candidate and audience work bounds");
                for (var candidate : window.candidates()) if (candidate.completeRoles()) {
                    var bound = ConditionEvaluator.players(candidate);
                    check(expected.containsAll(bound), "ineligible audience member never bound");
                    covered.get(candidate.definition().id()).addAll(bound);
                }
            }
            for (var entry : covered.entrySet()) check(entry.getValue().equals(expected),
                "audience traversal reaches every eligible member for " + entry.getKey().value() + " probe=" + probe);
        }
        System.out.println("PASS interacting rotations: cap=2, 8 REGION definitions, 4 players; eligible binding and all-member probes within 16 revisions");
    }

    private static void roles(DecisionInput input) {
        var definition = input.catalog().events().values().iterator().next();
        var twoRoles = replace(definition, "target", new Target(TargetScope.SOLO, Set.of(), PlayerPolicy.FAIR,
            Map.of("a", new Role(1, 1, false), "b", new Role(1, 1, false))));
        var invalid = event(input, twoRoles);
        check(!generated(invalid).completeRoles(), "one player cannot occupy exclusive roles twice");
        var overlap = replace(twoRoles, "target", new Target(TargetScope.SOLO, Set.of(), PlayerPolicy.FAIR,
            Map.of("a", new Role(1, 1, true), "b", new Role(1, 1, true))));
        var shared = generated(event(input, overlap));
        check(shared.completeRoles() && shared.roles().roles().get("a").equals(shared.roles().roles().get("b")), "explicit overlap supported");
        var oneSide = replace(overlap, "target", new Target(TargetScope.SOLO, Set.of(), PlayerPolicy.FAIR,
            Map.of("a", new Role(1, 1, true), "b", new Role(1, 1, false))));
        check(!generated(event(input, oneSide)).completeRoles(), "overlap requires both roles");
        Set<PlayerId> chosen = new HashSet<>();
        var context = input.context().context();
        PlayerId second = new PlayerId(uuid(30));
        var first = context.players().get(PLAYER);
        var secondSnapshot = replace(first, "playerId", second);
        var region = replace(context.regions().get(REGION), "presentPlayers", Set.of(PLAYER, second));
        var expanded = replace(context, "players", Map.of(PLAYER, first, second, secondSnapshot));
        expanded = replace(expanded, "regions", Map.of(REGION, region));
        var regionDefinition = replace(definition, "target", new Target(TargetScope.REGION, Set.of(), PlayerPolicy.FAIR, Map.of("witness", new Role(1, 1, false))));
        var equal = event(replace(input, "context", replace(input.context(), "context", expanded)), regionDefinition);
        // No memories for either target means equal ranking: ties use recorded RNG, not low UUID.
        equal = replace(equal, "memory", replace(equal.memory(), "operationalPlayers", List.of()));
        for (int seed = 0; seed < 40; seed++) chosen.add(generated(replace(equal, "seed", (long) seed)).roles().roles().get("witness").getFirst());
        check(chosen.size() == 2, "role ties do not permanently favor one player");
    }
    private static void safety(DecisionInput input) {
        var checker = new EligibilityEngine(ProviderMode.TEST_ONLY);
        check(checker.validateKnown(generated(input), input, 1).valid(), "complete known evidence passes preplanning checks");
        var definition = input.catalog().events().values().iterator().next();
        var firstStep = definition.steps().getFirst();
        var nextStep = replace(replace(firstStep, "id", "second"), "after", List.of(firstStep.id()));
        var sequential = event(input, replace(definition, "steps", List.of(firstStep, nextStep)));
        check(checker.validateKnown(generated(sequential), sequential, 1).valid(), "sequential step minima are not invented concurrent cost");
        check(!new EligibilityEngine(ProviderMode.PRODUCTION).validateKnown(generated(input), input, 1).valid(), "production rejects test profile/provider");
        var stale = replace(input, "context", replace(input.context(), "capturedTick", new Tick(0)));
        reason(stale, ReasonCode.STALE_CONTEXT);
        var paused = replace(input, "state", replace(input.state(), "world", replace(input.state().world(), "runControl", RunControl.PAUSED)));
        reason(paused, ReasonCode.AUTH_DENIED);
        var critical = replace(input, "context", replace(input.context(), "serverHealth", HealthMode.CRITICAL));
        reason(critical, ReasonCode.WAL_UNAVAILABLE);
        var unloaded = replace(input, "context", replace(input.context(), "loadedChunks", Set.of()));
        reason(unloaded, ReasonCode.CHUNK_UNAVAILABLE);
        var noMemory = replace(input, "memory", replace(input.memory(), "operationalPlayers", List.of()));
        reason(noMemory, ReasonCode.STALE_CONTEXT);
        var memory = input.memory().operationalPlayers().getFirst();
        var futureExposure = new Exposure(input.catalog().events().keySet().iterator().next(), Family.AMBIENCE, "witness", NOW.plus(1), new UnitValue(1));
        var future = replace(input, "memory", replace(input.memory(), "operationalPlayers", List.of(replace(memory, "eventExposureRing", List.of(futureExposure)))));
        reason(future, ReasonCode.STALE_CONTEXT);
        close(new FeatureExtractor().extract(generated(future), future).values().get(Feature.novelty).value(), .5, "future exposure is unavailable, not zero exposure");
        var cooldown = new CooldownRecord(header(uuid(10)), new ResourceId("proutlost:opaque"), "safety", "death", NOW.plus(1), new EventInstanceId(uuid(11)), Risk.SAFE, Optional.empty());
        var unrelated = replace(cooldown, "binding", Optional.of(new CooldownBinding(new EventCooldownSelector(new DefinitionId("proutlost:other")),
            input.context().scopes(), Set.of(), true, CooldownReleaseMode.AFTER_TICK, Optional.empty())));
        check(!new CooldownPolicy().blocks(unrelated, generated(input), input), "typed unrelated event cooldown does not suppress candidate");
        var matching = replace(cooldown, "binding", Optional.of(new CooldownBinding(new EventCooldownSelector(generated(input).definition().id()),
            input.context().scopes(), Set.of(), true, CooldownReleaseMode.AFTER_TICK, Optional.empty())));
        check(new CooldownPolicy().blocks(matching, generated(input), input), "matching typed cooldown blocks");
        check(!new CooldownPolicy().blocks(replace(matching, "notBeforeTick", NOW), generated(input), input), "matching cooldown expires exactly at deadline");

        var cooling = replace(input, "state", replace(input.state(), "cooldowns", List.of(cooldown)));
        reason(cooling, ReasonCode.SAFETY_COOLDOWN);
        var exhausted = replace(input, "state", replace(input.state(), "budgets", new BudgetView(BudgetMath.zero(), BudgetMath.zero(), Map.of(REGION_SCOPE, BudgetMath.zero()))));
        reason(exhausted, ReasonCode.BUDGET_EXHAUSTED);
        var uniqueDefinition = replace(input.catalog().events().values().iterator().next(), "uniqueness", new Uniqueness(UniquenessScope.ONCE_PER_PLAYER, ConsumeOn.EFFECT_CONFIRMED));
        var use = new UniqueUseRecord(header(uuid(12)), uniqueDefinition.id(), UniquenessScope.ONCE_PER_PLAYER, PLAYER.value(), UniqueStatus.SPENT_POSSIBLE,
            Optional.empty(), new EventInstanceId(uuid(13)), Optional.empty(), Set.of());
        var unique = replace(event(input, uniqueDefinition), "state", replace(input.state(), "uniqueness", List.of(use)));
        reason(unique, ReasonCode.UNIQUE_CONSUMED);
        var queuedEvent=new EventInstanceId(uuid(14));
        for(var status:List.of(UniqueStatus.RESERVED,UniqueStatus.SPENT_POSSIBLE,UniqueStatus.SPENT_CONFIRMED)){
            var held=replace(unique,"state",replace(unique.state(),"uniqueness",List.of(replace(use,"status",status))));
            var waiting=checker.validateAudience(generated(held),Set.of(PLAYER),held,queuedEvent);
            check(!waiting.valid()&&waiting.reasons().contains(ReasonCode.UNIQUE_CONSUMED),
                "audited queued event rechecks another event's current unique "+status+" before native invocation");
            check(checker.validateAudience(generated(held),Set.of(PLAYER),held,use.instanceId()).valid(),
                "original next step can reuse its own exact unique event "+status);
            check(!checker.validateKnown(generated(held),held,1).valid(),
                "fresh selection never treats an existing event as its own continuation");
        }
        var released=replace(unique,"state",replace(unique.state(),"uniqueness",List.of(replace(use,"status",UniqueStatus.RELEASED_BEFORE_EFFECT))));
        check(checker.validateAudience(generated(released),Set.of(PLAYER),released,queuedEvent).valid(),
            "proved release before effect permits a different queued event");
        var unrelatedUnique=replace(unique,"state",replace(unique.state(),"uniqueness",List.of(replace(use,"canonicalScopeId",uuid(15)))));
        check(checker.validateAudience(generated(unrelatedUnique),Set.of(PLAYER),unrelatedUnique,queuedEvent).valid(),
            "different player's unique use does not block this audience");
        System.out.println("PASS T-SEL known safety: stale/paused/WAL/chunk/memory/cooldown/budget/uniqueness/production gates");
    }
    private static void decisions(DecisionInput input) {
        WorldDirector director = new WorldDirector(ProviderMode.TEST_ONLY);
        DecisionPort port = director;
        var result = port.evaluate(input);
        check(result.candidates().size() == 1, "DecisionPort returns evaluated candidate");
        check(result.selectedPlan().isEmpty() && result.audit().chosen().isEmpty(), "missing planner cannot create empty executable plan");
        check(result.audit().timings().get("role_bound_planner_deferred") == 1, "planner gap explicitly audited after known checks");
        check(result.candidates().getFirst().reasons().contains(ReasonCode.CAPABILITY_MISSING), "explicit planning refusal");
        check(!result.audit().errors().isEmpty() && result.audit().effects().isEmpty(), "deferral audit has reason and zero effects");
        check(result.stateChanges().size() == 1 && result.stateChanges().getFirst().expectedRevision().orElseThrow().equals(input.state().world().header().revision()), "world state CAS");
        var next = ((WorldAggregate) result.stateChanges().getFirst().next()).value();
        check(next.engineControl().revision().value() == input.state().world().engineControl().revision().value() + 1, "persistent rotation advances");
        check(next.engineControl().requiredServiceCredit().equals(input.state().world().engineControl().requiredServiceCredit()), "no service credit spent without ready plan");
        var codec = new ModelCodec<>(DecisionInput.class);
        check(director.replay(codec.decode(codec.encode(input)), result.audit()).equals(result), "full deferred decision input/audit replay");
        var proposalCodec = new ModelCodec<>(DecisionProposal.class);
        byte[] encoded = proposalCodec.encode(result);
        var restored = proposalCodec.decode(encoded);
        check(Arrays.equals(proposalCodec.encode(restored), encoded) && restored.stateChanges().equals(result.stateChanges()), "canonical proposal serializes with CAS updates (JSON normalizes signed zero)");
        rejects(() -> director.replay(replace(input, "seed", input.seed() + 1), result.audit()), "changed replay seed rejected");
        var fallback = new AiProposalResult(AiStatus.FALLBACK, Optional.empty(), Optional.of(ReasonCode.AI_TIMEOUT));
        var advised = replace(input, "advisorySnapshot", Optional.of(fallback));
        var advisedResult = director.evaluate(advised);
        check(advisedResult.audit().advisor().orElseThrow().equals(fallback), "recorded timeout retained");
        check(director.replay(codec.decode(codec.encode(advised)), advisedResult.audit()).equals(advisedResult), "recorded advisor replay no transport");
        var micro = replace(input.catalog().events().values().iterator().next(), "families", Set.of(Family.SAFE_MICRO_EVENT, Family.AMBIENCE));
        var paused = replace(event(input, micro), "state", replace(input.state(), "world", replace(input.state().world(), "runControl", RunControl.PAUSED)));
        var deferred = director.evaluate(paused);
        check(deferred.audit().timings().get("MIN_PRESENCE_DEFERRED") == 1 && deferred.selectedPlan().isEmpty(), "micro presence deferred while paused");
        check(deferred.candidates().getFirst().reasons().contains(ReasonCode.AUTH_DENIED), "micro cannot waive pause");
        var requirement = request(input, false);
        var requested = replace(input, "requests", List.of(requirement));
        var required = director.evaluate(requested);
        var change = required.stateChanges().stream().filter(c -> c.next() instanceof RequestAggregate).findFirst().orElseThrow();
        var requestState = ((RequestAggregate) change.next()).value();
        check(requestState.status() == RequestStatus.WAITING_SAFETY && requestState.resultFactIds().isEmpty(), "REQUIRE durably waits, never fulfills from score");
        check(requestState.lastReasonCodes().contains(ReasonCode.CAPABILITY_MISSING), "REQUIRE exposes missing planning capability");
        var secondInput = replace(input, "seed", 93L);
        var second = director.evaluate(secondInput);
        check(!second.proposalId().equals(result.proposalId()) && director.evaluate(input).equals(result), "independent immutable proposals, no active singleton");
        System.out.println("PASS T-PACE/T-QST/T-AI/T-CONC deferred proposals, CAS, micro refusal and stored-input replay; live admission NOT_RUN");
    }
    private static void preferences(DecisionInput input) {
        var view = request(input, true);
        var requested = replace(input, "requests", List.of(view));
        var features = new FeatureExtractor().extract(generated(requested), requested);
        close(features.values().get(Feature.storyPreference).value(), .5, "approved legacy PREFER linear TTL");
        var expiredPayload = replace((DirectorPreference) view.request().payload(), "expiresTick", NOW);
        var expired = replace(view, "request", replace(view.request(), "payload", expiredPayload));
        var expiredInput = replace(input, "requests", List.of(expired));
        close(new FeatureExtractor().extract(generated(expiredInput), expiredInput).values().get(Feature.storyPreference).value(), 0, "expired PREFER has no influence");
        check(features.missing().contains(Feature.regionPersonality) && features.missing().contains(Feature.globalTension), "absent affinities explicitly missing");
        check(!new WorldDirector(ProviderMode.TEST_ONLY).evaluate(requested).candidates().getFirst().eligible(), "PREFER never lifts planning barrier");
    }
    private static void groupsAndRegions(DecisionInput input) {
        PlayerId second = new PlayerId(uuid(60));
        RegionId secondRegion = new RegionId(uuid(61));
        UUID groupId = uuid(62);
        var context = input.context().context();
        var first = replace(context.players().get(PLAYER), "groupIds", Set.of(groupId));
        var other = replace(first, "playerId", second);
        var group = new GroupSnapshot(groupId, Set.of(PLAYER, second), Set.of(REGION), NOW, new Revision(1));
        ScopeRef secondScope = new ScopeRef(ScopeKind.PLAYER, second.value());
        var scopes = new HashMap<>(context.scopes());
        scopes.put(secondScope, new ScopeSnapshot(secondScope, new Revision(1), EnumSet.allOf(SensorKind.class), Set.of(), Map.of()));
        var revisions = new HashMap<>(input.context().scopeRevisions()); revisions.put(secondScope, new Revision(1));
        var together = replace(context, "players", Map.of(PLAYER, first, second, other));
        together = replace(together, "groups", Map.of(groupId, group));
        together = replace(together, "scopes", scopes);
        together = replace(together, "regions", Map.of(REGION, replace(context.regions().get(REGION), "presentPlayers", Set.of(PLAYER, second))));
        var snapshot = replace(input.context(), "context", together);
        snapshot = replace(snapshot, "scopeRevisions", revisions);
        var memory = input.memory().operationalPlayers().getFirst();
        var secondMemory = replace(replace(memory, "playerId", second), "header", header(second.value()));
        var memories = replace(input.memory(), "operationalPlayers", List.of(memory, secondMemory));
        memories = replace(memories, "revisions", revisions);
        var definition = input.catalog().events().values().iterator().next();
        var groupDefinition = replace(definition, "target", new Target(TargetScope.GROUP, Set.of(), PlayerPolicy.FAIR, Map.of("witness", new Role(2, 2, false))));
        var groupInput = event(replace(replace(input, "context", snapshot), "memory", memories), groupDefinition);
        var generated = generated(groupInput);
        check(generated.completeRoles() && ConditionEvaluator.players(generated).size() == 2, "group roles come from observed group");
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(generated, groupInput, 1).valid(), "group known safety passes");
        var staleGroup = replace(together, "groups", Map.of(groupId, replace(group, "capturedTick", new Tick(0))));
        reason(replace(groupInput, "context", replace(snapshot, "context", staleGroup)), ReasonCode.STALE_CONTEXT);
        // Separate the same two targets into distinct regions without any singleton candidate slot.
        var record = context.regions().get(REGION).region();
        var secondRecord = replace(replace(record, "regionId", secondRegion), "header", header(secondRegion.value()));
        var regionSnapshot = new RegionSnapshot(secondRecord, Set.of(new TagId("proutlost:camp")), Set.of(second), List.of(), Map.of());
        other = replace(other, "regionIds", Set.of(secondRegion));
        other = replace(other, "position", new PositionSnapshot(new BlockPosition(DIM, 32, 64, 0), NOW));
        var split = replace(together, "players", Map.of(PLAYER, first, second, other));
        split = replace(split, "regions", Map.of(REGION, context.regions().get(REGION), secondRegion, regionSnapshot));
        ScopeRef secondRegionScope = new ScopeRef(ScopeKind.REGION, secondRegion.value());
        scopes.put(secondRegionScope, new ScopeSnapshot(secondRegionScope, new Revision(1), EnumSet.allOf(SensorKind.class), Set.of(), Map.of()));
        revisions.put(secondRegionScope, new Revision(1));
        split = replace(split, "scopes", scopes);
        snapshot = replace(snapshot, "context", split);
        snapshot = replace(snapshot, "scopeRevisions", revisions);
        snapshot = replace(snapshot, "loadedChunks", Set.of(new ChunkRef(DIM, 0, 0), new ChunkRef(DIM, 2, 0)));
        memories = replace(memories, "regionRecords", Map.of(REGION, record, secondRegion, secondRecord));
        memories = replace(memories, "revisions", revisions);
        var state = replace(input.state(), "budgets", replace(input.state().budgets(), "scopes", Map.of(REGION_SCOPE, BudgetMath.zero(), secondRegionScope, BudgetMath.zero())));
        var splitInput = replace(replace(replace(input, "context", snapshot), "memory", memories), "state", state);
        var window = new CandidateGenerator().generate(splitInput, new DecisionRandom(1));
        check(window.candidates().size() == 2 && window.candidates().stream().map(CandidateGenerator.Generated::region).distinct().count() == 2, "two regions retain independent candidates");
        for (var candidate : window.candidates()) check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(candidate, splitInput, 1).valid(), "independent regional known checks");
        var proposal = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(splitInput);
        check(proposal.candidates().size() == 2 && proposal.audit().timings().get("role_bound_planner_deferred") == 2, "both regions reach explicit planning deferral");
    }
    /** Pure committed-head fixtures reproduce the actual actor+region STALE_CONTEXT gap. */
    private static void catalogueRegionalBudget(DecisionInput input) {
        var heads = new ArrayList<AggregateState>();
        var region = input.memory().regionRecords().get(REGION);
        heads.add(new RegionAggregate(region));
        var dormantId = new RegionId(uuid(311));
        var dormant = replace(replace(replace(region, "regionId", dormantId), "header", header(dormantId.value())), "status", RegionStatus.DORMANT);
        heads.add(new RegionAggregate(dormant));
        var entity = new EntityOwnershipRecord(header(uuid(312)), new OwnerToken(uuid(313)), uuid(314),
            new EventInstanceId(uuid(315)), new CohortId(uuid(316)), "pure-budget-fixture", EntityPhase.RESERVED,
            OwnershipDisposition.DIRECTOR_ACTIVE, new ChunkRef(DIM, 0, 0), NOW, ArrivalPolicy.SETTLE_WORLD,
            CleanupReason.EXPIRED, Optional.empty(), new Epoch(1));
        heads.add(new EntityOwnershipAggregate(entity));
        var removed = replace(replace(replace(entity, "header", header(uuid(317))), "entityUuid", uuid(318)), "phase", EntityPhase.REMOVED);
        heads.add(new EntityOwnershipAggregate(replace(removed, "disposition", OwnershipDisposition.RELEASED)));
        var resource = new ResourceKey(OwnershipDomain.BLOCK, DIM, "0,64,0");
        var owner = new OwnershipRecord(header(uuid(319)), resource, Set.of(new OwnerToken(uuid(320))), new Epoch(1),
            OwnershipDisposition.DIRECTOR_ACTIVE, new MutationId(uuid(321)), HASH, new Epoch(0), Optional.empty());
        heads.add(new OwnershipAggregate(owner));
        var yielded = replace(replace(replace(owner, "header", header(uuid(322))), "resourceKey",
            new ResourceKey(OwnershipDomain.BLOCK, DIM, "1,64,0")), "disposition", OwnershipDisposition.YIELDED_TO_PLAYER);
        heads.add(new OwnershipAggregate(yielded));
        var budget = fr.proutlost.director.runtime.CatalogueBudgetProjection.from(input.config(), heads);
        check(budget.reserved().liveEntities() == 1 && budget.reserved().persistentEntities() == 1
            && budget.reserved().mutationsInFlight() == 1, "actual retained ownership debits include RESERVED and exclude released/yielded rows");
        check(budget.scopes().keySet().equals(Set.of(REGION_SCOPE)), "only actual ACTIVE regional heads publish budget scopes");
        check(budget.scopes().get(REGION_SCOPE).equals(budget.reserved()), "regional scope conservatively retains physical debits");
        check(budget.capacity().tickMillis() == input.config().values().get("budget.tick_ms").value().doubleValue()
            && budget.capacity().regionalIntensity() == input.config().values().get("budget.region_intensity").value().doubleValue(),
            "regional availability does not enlarge configured capacity");
        var ready = replace(input, "state", replace(input.state(), "budgets", budget));
        var candidate = generated(ready);
        check(candidate.region().equals(Optional.of(REGION)) && ConditionEvaluator.players(candidate).equals(Set.of(PLAYER)),
            "regression candidate is the exact actor inside the actual fixture region");
        var missing = replace(ready, "state", replace(ready.state(), "budgets", replace(budget, "scopes", Map.of())));
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(candidate, missing, 1).reasons().contains(ReasonCode.STALE_CONTEXT),
            "missing regional budget reproduces the observed pre-preview refusal");
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(candidate, ready, 1).valid(),
            "projected actual regional budget reaches normal role-bound planning eligibility");
        var withoutRegion = new ArrayList<>(heads);withoutRegion.removeIf(s -> s instanceof RegionAggregate r && r.value().regionId().equals(REGION));
        var absent = fr.proutlost.director.runtime.CatalogueBudgetProjection.from(input.config(), withoutRegion);
        check(!absent.scopes().containsKey(REGION_SCOPE), "a context/player reference never fabricates an absent regional budget");
        var definition = candidate.definition();
        var tooLarge = replace(definition, "budget", replace(definition.budget(), "regionalIntensity", budget.capacity().regionalIntensity() + 1));
        var over = event(ready, tooLarge);
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(generated(over), over, 1).reasons().contains(ReasonCode.BUDGET_EXHAUSTED),
            "real regional intensity cap still rejects an oversized event");
        var duplicate = new ArrayList<>(heads);duplicate.add(heads.getFirst());
        rejects(() -> fr.proutlost.director.runtime.CatalogueBudgetProjection.from(input.config(), duplicate), "duplicate aggregate heads are not a valid committed-cut budget");
    }
    /** Partial native region coverage must not masquerade as global risk coverage. */
    private static void regionalRiskKnowledge(DecisionInput input) {
        var risk = new ResourceId("proutlost:landslide");
        var minimum = new RiskMinimum(risk, new UnitValue(.7));
        var context = input.context().context();
        var actual = replace(context.regions().get(REGION), "risks", Map.of(risk, new UnitValue(1)));
        var otherId = new RegionId(uuid(331));
        var otherScope = new ScopeRef(ScopeKind.REGION, otherId.value());
        var otherRecord = replace(replace(actual.region(), "regionId", otherId), "header", header(otherId.value()));
        var unknown = new RegionSnapshot(otherRecord, Set.of(), Set.of(), List.of(), Map.of());
        var available = EnumSet.allOf(SensorKind.class);available.remove(SensorKind.RISK);
        var scopes = new HashMap<>(context.scopes());
        scopes.put(otherScope, new ScopeSnapshot(otherScope, new Revision(1), available, Set.of(SensorKind.RISK), Map.of()));
        var versions = new HashMap<>(input.context().scopeRevisions());versions.put(otherScope, new Revision(1));
        var scopeSet = new HashSet<>(input.context().scopes().scopes());scopeSet.add(otherScope);
        var regions = Map.of(REGION, actual, otherId, unknown);
        var frame = replace(replace(replace(replace(input.context(), "context",
            replace(replace(context, "regions", regions), "scopes", scopes)), "scopeRevisions", versions),
            "scopes", new ScopeSet(WORLD, scopeSet)), "missingData", Set.of(SensorKind.RISK));
        var partial = replace(input, "context", frame);
        var candidate = new CandidateGenerator().generate(partial, new DecisionRandom(42)).candidates().stream()
            .filter(c -> c.region().equals(Optional.of(REGION))).findFirst().orElseThrow();
        var evaluator = new ConditionEvaluator();
        check(evaluator.evaluate(minimum, candidate, partial) == ConditionEvaluator.Truth.TRUE,
            "measured target region is known despite another unmeasured region");
        check(!ConditionEvaluator.known(candidate, partial, SensorKind.RISK) && partial.context().missingData().contains(SensorKind.RISK),
            "generic/global RISK remains missing; the scoped query never clears it");
        var admitted = event(partial, replace(candidate.definition(), "eligibility", minimum));
        var scoped = new CandidateGenerator().generate(admitted, new DecisionRandom(42)).candidates().stream()
            .filter(c -> c.region().equals(Optional.of(REGION))).findFirst().orElseThrow();
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(scoped, admitted, 1).valid(),
            "actual scoped risk reaches ordinary eligibility without a fabricated global sensor");
        var otherCandidate = replace(replace(candidate, "region", Optional.of(otherId)), "scopes",
            new ScopeSet(WORLD, Set.of(WORLD_SCOPE, PLAYER_SCOPE, otherScope)));
        check(evaluator.evaluate(minimum, otherCandidate, partial) == ConditionEvaluator.Truth.UNKNOWN,
            "the unmeasured region stays UNKNOWN");
        var worldOnly = replace(replace(candidate, "region", Optional.empty()), "scopes",
            new ScopeSet(WORLD, Set.of(WORLD_SCOPE, PLAYER_SCOPE)));
        check(evaluator.evaluate(minimum, worldOnly, partial) == ConditionEvaluator.Truth.UNKNOWN,
            "WORLD-only input cannot borrow a regional risk");
        var staleScopes = new HashMap<>(scopes);
        staleScopes.put(REGION_SCOPE, replace(scopes.get(REGION_SCOPE), "revision", new Revision(2)));
        var stale = replace(partial, "context", replace(frame, "context", replace(frame.context(), "scopes", staleScopes)));
        check(evaluator.evaluate(minimum, candidate, stale) == ConditionEvaluator.Truth.UNKNOWN,
            "mismatched scoped CAS remains UNKNOWN");
        var missingScopes = new HashMap<>(scopes);
        missingScopes.put(REGION_SCOPE, new ScopeSnapshot(REGION_SCOPE, new Revision(1), available, Set.of(SensorKind.RISK), Map.of()));
        var localMissing = replace(partial, "context", replace(frame, "context", replace(frame.context(), "scopes", missingScopes)));
        check(evaluator.evaluate(minimum, candidate, localMissing) == ConditionEvaluator.Truth.UNKNOWN,
            "explicit local sensor absence is never overridden");
        var absentRisk = replace(partial, "context", replace(frame, "context", replace(frame.context(), "regions",
            Map.of(REGION, replace(actual, "risks", Map.of()), otherId, unknown))));
        check(evaluator.evaluate(minimum, candidate, absentRisk) == ConditionEvaluator.Truth.UNKNOWN,
            "a sensor descriptor cannot invent a missing risk value");
        var low = replace(partial, "context", replace(frame, "context", replace(frame.context(), "regions",
            Map.of(REGION, replace(actual, "risks", Map.of(risk, new UnitValue(.2))), otherId, unknown))));
        check(evaluator.evaluate(minimum, candidate, low) == ConditionEvaluator.Truth.FALSE,
            "a measured low risk remains FALSE rather than UNKNOWN or eligible");
        java.util.function.Function<DecisionInput,Double> pacingEma = source -> {
            var control = replace(source.state().world().engineControl(), "tensionLastUpdateTick", new Tick(NOW.value() - 1200));
            var world = replace(source.state().world(), "engineControl", control);
            var proposal = new WorldDirector(ProviderMode.TEST_ONLY).evaluate(replace(source, "state", replace(source.state(), "world", world)));
            return ((WorldAggregate)proposal.stateChanges().getFirst().next()).value().engineControl().tensionEma().value();
        };
        close(pacingEma.apply(partial), pacingEma.apply(low), "global pacing ignores differing regional values while GLOBAL RISK is missing");
        var completeScopes = new HashMap<>(scopes);
        completeScopes.put(otherScope, new ScopeSnapshot(otherScope, new Revision(1), EnumSet.allOf(SensorKind.class), Set.of(), Map.of()));
        var complete = replace(partial, "context", replace(replace(frame, "missingData", Set.of()), "context",
            replace(replace(frame.context(), "scopes", completeScopes), "regions",
                Map.of(REGION, actual, otherId, replace(unknown, "risks", Map.of(risk, new UnitValue(1)))))));
        check(pacingEma.apply(complete) > pacingEma.apply(partial),
            "positive control proves world pacing consumes genuinely complete global risk");
    }
    private static RequestView request(DecisionInput input, boolean prefer) {
        RequestId id = new RequestId(uuid(prefer ? 21 : 20));
        var selector = new AnyOfEvents(input.catalog().events().keySet());
        var constraints = new TargetConstraints(Set.of(), Set.of());
        RequestPayload payload = prefer ? new DirectorPreference(id, selector, 1, NOW.plus(100))
            : new DirectorRequirement(id, selector, constraints, new FactMatch(new FactType("proutlost:observed"), WORLD_SCOPE, EvidenceStatus.CONFIRMED, Map.of()), FulfillmentMode.NEW_EXECUTION);
        var request = new DirectorRequest(new MessageId(uuid(22)), WORLD, new ResourceId("proutlost:quest"), "request", WORLD_SCOPE,
            new CorrelationId(uuid(23)), Optional.empty(), new Sequence(1), new Tick(NOW.value() - 100), payload);
        var state = new DirectorRequestState(header(id.value()), id, request.producerId(), request.producerKey(), prefer ? RequestKind.PREFER : RequestKind.REQUIRE,
            selector, WORLD_SCOPE, constraints, FulfillmentMode.NEW_EXECUTION, RequestStatus.ACCEPTED, new DurationTicks(0), prefer ? Optional.of(NOW.plus(100)) : Optional.empty(), Set.of(), List.of());
        return new RequestView(request, state, state.header().revision());
    }
    /** Shared test DTO fixture; no native authority or file IO. */
    public static DecisionInput archiveInput(ConfigView config){return fixture(config,1);}
    private static DecisionInput fixture(ConfigView config, int definitions) {
        var player = new PlayerSnapshot(PLAYER, new Revision(1), NOW, PlayerEligibility.ELIGIBLE,
            new PositionSnapshot(new BlockPosition(DIM, 0, 64, 0), NOW), Set.of(REGION), Set.of(), new DurationTicks(1000), Set.of(),
            new ClientCapabilitySnapshot(1, Set.of(new ResourceId("proutlost:sound")), Map.of(), true), Set.of());
        var region = new RegionRecord(header(REGION.value()), REGION, DIM, RegionStatus.ACTIVE, new SupportShape(List.of(), List.of(), List.of()), Set.of(),
            new UnitValue(.5), Set.of(), Set.of(), new Revision(1), new RegionPersonality(Map.of()), Set.of());
        Set<ScopeRef> scopes = Set.of(WORLD_SCOPE, REGION_SCOPE, PLAYER_SCOPE);
        Map<ScopeRef, ScopeSnapshot> snapshots = new HashMap<>();
        Map<ScopeRef, Revision> revisions = new HashMap<>();
        for (var scope : scopes) {
            revisions.put(scope, new Revision(1));
            snapshots.put(scope, new ScopeSnapshot(scope, new Revision(1), EnumSet.allOf(SensorKind.class), Set.of(), Map.of()));
        }
        var capability = new CapabilityDescriptor(CAPABILITY, 1, ProviderMode.TEST_ONLY, CapabilityStatus.AVAILABLE, new ResourceId("proutlost:test"), Set.of("test"));
        var context = new ObservationContext(snapshots, Map.of(PLAYER, player), Map.of(), Map.of(REGION, new RegionSnapshot(region, Set.of(new TagId("proutlost:camp")), Set.of(PLAYER), List.of(), Map.of())), Map.of(), Map.of(), List.of(), Map.of(CAPABILITY, capability));
        var scopeSet = new ScopeSet(WORLD, scopes);
        var snapshot = new SnapshotBatch(new RequestId(uuid(4)), scopeSet, NOW, revisions, context, Set.of(), Set.of(new ChunkRef(DIM, 0, 0)), HealthMode.NORMAL);
        var memory = new PlayerOperationalMemory(header(PLAYER.value()), PLAYER, new DurationTicks(20000), Optional.of(REGION), Optional.empty(), List.of(), List.of(), Set.of(), new UnitValue(.5), Map.of(), Optional.empty(), new Epoch(0));
        var memories = new MemoryView(scopeSet, revisions, List.of(), List.of(memory), List.of(), Map.of(REGION, region), Map.of(), Map.of());
        BudgetVector cost = new BudgetVector(.1, .25, .25, 0, 0, 0, 0, 1, 0, 0);
        var primitive = new PrimitiveDescriptor(PRIMITIVE, 1, new ResourceId("proutlost:args"), Set.of(CAPABILITY), cost, new ResourceId("proutlost:footprint"), Set.of(), Set.of(), Set.of(),
            new DurationTicks(100), PausePolicy.CLEANUP, RecoveryMode.CLEANUP, new ResourceId("proutlost:cleanup"), Optional.empty(), Optional.empty(), ProviderMode.TEST_ONLY);
        Map<DefinitionId, EventDefinition> events = new HashMap<>();
        for (int i = 0; i < definitions; i++) {
            DefinitionId id = new DefinitionId("proutlost:event_" + i);
            var definition = new EventDefinition(1, new Revision(1), id, Calibration.DEFAULT_FOR_TESTING, Category.AMBIENT, Set.of(Family.AMBIENCE), true,
                new Selection(1, .001), new Target(TargetScope.SOLO, Set.of(), PlayerPolicy.FAIR, Map.of("witness", new Role(1, 1, false))), new PlayersPresent(1),
                new Timing(new DurationTicks(100), new Cooldowns(new DurationTicks(0), new DurationTicks(0), new DurationTicks(0))),
                new Uniqueness(UniquenessScope.REPEATABLE, ConsumeOn.EFFECT_CONFIRMED), cost,
                new Conflicts(Set.of(), Set.of(), Set.of(), Priority.AMBIENT, Preemption.YIELD_BEFORE_COMMIT),
                new Safety(Risk.SAFE, Set.of(CAPABILITY), Optional.empty(), Optional.empty(), PlayerEditPolicy.YIELD, JournalPolicy.PLAYER_OPT_IN, ParticipantLoss.ABORT_IF_MIN_LOST),
                List.of(new EventStep("sound", List.of(), new DurationTicks(0), PRIMITIVE, new TargetSound("witness", new ResourceId("minecraft:block.gravel.step"), 1, 4, 1), OnFailure.ABORT_SAFE)),
                new RecoveryPolicy(RecoveryMode.CLEANUP, new DurationTicks(100)), new Completion(new FactType("proutlost:observed"), Optional.empty(), new UnitValue(0)));
            events.put(id, definition);
        }
        var catalogue = new CatalogSnapshot(new Epoch(1), HASH, events, Map.of(), Map.of(PRIMITIVE, primitive));
        var control = new EngineControlState(1, new Revision(0), NOW, NOW, new UnitValue(0), NOW, Optional.empty(), new UnitValue(.5));
        var world = new DirectorWorldState(header(WORLD.value()), WORLD, new Epoch(1), NOW, RunControl.RUNNING, HealthMode.NORMAL, PacingState.REST, GlobalTension.CALM,
            new ResourceId("proutlost:test"), HASH, config.provenanceHash(), new Epoch(1), new Sequence(0), control);
        var budgets = new BudgetView(new BudgetVector(10, 24, 8, 100, 100, 100, 1000, 100, 1000, 100), BudgetMath.zero(), Map.of(REGION_SCOPE, BudgetMath.zero()));
        var state = new DecisionState(world, budgets, new OwnershipView(Map.of()), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new DecisionInput(snapshot, memories, catalogue, config, List.of(), List.of(), 42L, state, Optional.empty());
    }
    private static void adminTension(DecisionInput input) {
        var engine=new WorldDirector(ProviderMode.TEST_ONLY);
        var old=input.state().world();
        var header=new AggregateHeader(1,old.header().id(),new Revision(5),old.header().lastSequence(),new Tick(NOW.value()-10));
        var world=replace(old,"header",header);
        var active=replace(input,"state",replace(replace(input.state(),"world",world),"facts",
                List.of(tensionFact(901,5,NOW.value()+20,GlobalTension.EXCEPTIONAL))));
        var normalized=AdminTensionOverride.normalize(active);
        check(normalized.state().world().tension()==GlobalTension.EXCEPTIONAL,"durable override enters decision snapshot");
        check(normalized.state().world().engineControl().equals(world.engineControl()),"override does not replace measured EMA/pacing");
        var candidate=generated(normalized);
        var scoring=new DefinitionScoringProfile(1,candidate.reference(),HASH,List.of(),Map.of(),
                Map.of(GlobalTension.EXCEPTIONAL,new UnitValue(.9),GlobalTension.CALM,new UnitValue(.1)),Optional.empty(),Set.of());
        var profiled=replace(normalized,"catalog",replace(normalized.catalog(),"scoringProfiles",Map.of(candidate.definition().id(),scoring)));
        check(new FeatureExtractor().extract(candidate,profiled).values().get(Feature.globalTension).value()==.9,
                "real score extractor consumes forced tension");
        var proposal=engine.evaluate(active);
        var next=((WorldAggregate)proposal.stateChanges().getFirst().next()).value();
        check(next.tension()==GlobalTension.EXCEPTIONAL,"pacing publication retains active override");
        check(engine.replay(active,proposal.audit()).equals(proposal),"administrative override replay is deterministic");
        var forced=replace(world,"tension",GlobalTension.EXCEPTIONAL);
        var expired=replace(input,"state",replace(replace(input.state(),"world",forced),"facts",
                List.of(tensionFact(902,5,NOW.value(),GlobalTension.EXCEPTIONAL))));
        check(AdminTensionOverride.active(expired).isEmpty(),"expiry is exclusive at exact tick");
        check(AdminTensionOverride.normalize(expired).state().world().tension()==GlobalTension.CALM,
                "expiry releases forced enum using measured EMA");
        check(((WorldAggregate)engine.evaluate(expired).stateChanges().getFirst().next()).value().tension()==GlobalTension.CALM,
                "expired override no longer controls real decision output");
        var superseded=replace(expired,"state",replace(expired.state(),"facts",List.of(
                tensionFact(903,4,NOW.value()+100,GlobalTension.TENSE),tensionFact(902,5,NOW.value(),GlobalTension.EXCEPTIONAL))));
        check(AdminTensionOverride.active(superseded).isEmpty(),"expired latest override never revives older override");
        var future=replace(active,"state",replace(active.state(),"facts",List.of(tensionFact(904,6,NOW.value()+20,GlobalTension.TENSE))));
        rejects(()->engine.evaluate(future),"future revision override rejected");
    }
    private static DirectorFact tensionFact(long id,long revision,long expires,GlobalTension tension) {
        return new DirectorFact(new FactId(uuid(id)),AdminTensionOverride.TYPE,List.of(WORLD_SCOPE),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("tension",new JsonValue.Text(tension.name()),"created_tick",new JsonValue.Text(Long.toString(NOW.value()-10)),
                        "expires_tick",new JsonValue.Text(Long.toString(expires)),"control_revision",new JsonValue.Text(Long.toString(revision))),true,Optional.empty());
    }
    private static CandidateGenerator.Generated generated(DecisionInput input) { return new CandidateGenerator().generate(input, new DecisionRandom(input.seed())).candidates().getFirst(); }
    private static DecisionInput event(DecisionInput input, EventDefinition definition) { return replace(input, "catalog", replace(input.catalog(), "events", Map.of(definition.id(), definition))); }
    private static void reason(DecisionInput input, ReasonCode reason) {
        check(new EligibilityEngine(ProviderMode.TEST_ONLY).validateKnown(generated(input), input, 1).reasons().contains(reason), "hard reason " + reason);
        check(new WorldDirector(ProviderMode.TEST_ONLY).evaluate(input).candidates().getFirst().reasons().contains(reason), "decision preserves hard reason " + reason);
    }
    private static AggregateHeader header(UUID id) { return new AggregateHeader(1, id, new Revision(1), new Sequence(0), NOW); }
    private static UUID uuid(long id) { return new UUID(0, id); }
    /** Test fixture copy only; no production reflection or altered API types. */
    private static <T> T replace(T record, String field, Object replacement) {
        try {
            RecordComponent[] components = record.getClass().getRecordComponents();
            Object[] values = new Object[components.length]; Class<?>[] types = new Class<?>[components.length]; boolean found = false;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                boolean match = components[i].getName().equals(field); found |= match;
                values[i] = match ? replacement : components[i].getAccessor().invoke(record);
            }
            if (!found) throw new AssertionError("Unknown fixture field " + field);
            @SuppressWarnings("unchecked") T copied = (T) record.getClass().getConstructor(types).newInstance(values);
            return copied;
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void close(double value, double expected, String message) { check(Math.abs(value - expected) < 1e-14, message); }
    private static void rejects(Runnable task, String message) { try { task.run(); } catch (IllegalArgumentException expected) { assertions++; return; } throw new AssertionError(message); }
}
