package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DirectorPrimitive;
import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.testkit.TestDurableStore;
import java.util.*;

/** Explicit G5 negative qualification tests: no game world, no physical effects. */
public final class DirectorReleaseDiagnosticTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final Fingerprint HASH = new Fingerprint("a".repeat(64));
    private static final CapabilityId CAP = new CapabilityId("director.real_audio");

    private static ConfigView config(Calibration mode) {
        return new ConfigView(new Revision(1), HASH, mode, Map.of());
    }
    private static CatalogSnapshot emptyCatalog() {
        return new CatalogSnapshot(new Epoch(1), HASH, Map.of(), Map.of(), Map.of());
    }
    private static boolean has(DirectorReleaseDiagnostic.Report report, String value) {
        return report.blockers().contains(value);
    }
    private static EventDefinition withSteps(EventDefinition source, List<EventStep> steps) {
        return new EventDefinition(source.schemaVersion(), source.contentRevision(),
            source.id(), source.calibration(), source.category(), source.families(),
            source.enabled(), source.selection(), source.target(), source.eligibility(),
            source.timing(), source.uniqueness(), source.budget(), source.conflicts(),
            source.safety(), steps, source.recovery(), source.completion());
    }
    private static EventDefinition withDangerSteps(EventDefinition source, List<EventStep> steps) {
        var safety = new Safety(Risk.CONTROLLED_DANGER, source.safety().requiredCapabilities(),
            source.safety().mutationProfile(),
            Optional.of(new ResourceId("proutlost:warning_profile")),
            source.safety().playerEditPolicy(), source.safety().journalPolicy(),
            source.safety().participantLoss());
        return new EventDefinition(source.schemaVersion(), source.contentRevision(),
            source.id(), source.calibration(), source.category(), source.families(),
            source.enabled(), source.selection(), source.target(), source.eligibility(),
            source.timing(), source.uniqueness(), source.budget(), source.conflicts(),
            safety, steps, source.recovery(), source.completion());
    }
    private static ServerEffectPort.Binding binding(ProviderMode mode,
                                                     Set<PrimitiveId> ids,
                                                     Set<CapabilityId> capabilities) {
        return (ServerEffectPort.Binding) java.lang.reflect.Proxy.newProxyInstance(
            ServerEffectPort.Binding.class.getClassLoader(),
            new Class<?>[]{ServerEffectPort.Binding.class},
            (proxy, method, params) -> switch (method.getName()) {
                case "mode" -> mode;
                case "supportedPrimitives" -> ids;
                case "capabilities" -> capabilities;
                default -> throw new UnsupportedOperationException("metadata fixture only");
            });
    }
    /** Test-only dependencies cannot authorize J04; no effects are ever invoked. */
    private static ServerEffectPort port(Map<PrimitiveId, ServerEffectPort.Binding> bindings) {
        return new ServerEffectPort(new CommitPermit.Authority(new TestDurableStore(), 4),
            intent -> { throw new AssertionError("no native planner"); },
            slice -> { throw new AssertionError("no live native snapshot"); },
            bindings, Set.of(), 4,
            slice -> { throw new AssertionError("no stop snapshot"); },
            marker -> { throw new AssertionError("no physical stop"); },
            (marker, result) -> { throw new AssertionError("no physical result"); });
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        var empty = DirectorBootstrap.compose(ProviderMode.PRODUCTION, List.of());
        var denied = DirectorReleaseDiagnostic.inspect(
            empty, config(Calibration.DEFAULT_FOR_TESTING), emptyCatalog(), Set.of(CAP));
        check(!denied.mayProceedToQualification(), "no factory graph must be refused");
        check(has(denied, "MISSING_PORT:DurableStore"), "durable store absent");
        // A public Composition record can be supplied without compose():
        // an empty claimed missingPorts set must never conceal empty physical ports.
        var fakeComplete = new DirectorBootstrap.Composition(ProviderMode.PRODUCTION,
            new DirectorBootstrap.Ports(Optional.empty(),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()),
            Map.of(),Map.of(),Set.of());
        var forged = DirectorReleaseDiagnostic.inspect(fakeComplete,
            config(Calibration.APPROVED),emptyCatalog(),Set.of());
        check(has(forged, "MISSING_PORT:DurableStore"),
            "forged composition with missing ports omitted from its metadata cannot pass");
        check(has(forged, "MISSING_PORT:WorldEffectPort"),
            "diagnostic must consult actual physical port slots");
        check(has(denied, "MISSING_PORT:WorldEffectPort"), "physical port absent");
        check(has(denied, "MISSING_PORT:AdminPort"), "admin port absent");
        check(has(denied, "MISSING_QUALIFIED_CAPABILITY:director.real_audio"),
            "real qualified audio absent");
        check(has(denied, "UNAPPROVED_GLOBAL_CALIBRATION"), "testing is not approved");
        check(has(denied, "NO_ENABLED_EVENT"), "empty catalogue is no runnable product");
        // G5 requires the complete S2 candidate inventory, not one token
        // approved event with otherwise plausible production bindings.
        check(has(denied, "INCOMPLETE_G5_EVENTS:0/9"), "nine event definitions must be reviewed");
        check(has(denied, "INCOMPLETE_G5_CHAINS:0/2"), "both story chains must be reviewed");
        check(has(denied, "INCOMPLETE_G5_PRIMITIVES:0/10"), "ten physical primitives must be reviewed");
        check(has(denied, "INCOMPLETE_G5_CAPABILITIES:0/13"), "thirteen production capabilities must be qualified");
        check(denied.blockers().stream().noneMatch(s -> s.contains("QuestDirectorPort")),
            "optional Quest provider must remain optional");
        check(denied.blockers().stream().noneMatch(s -> s.contains("AiAdvisorPort")),
            "optional AI provider must remain optional");
        check(denied.blockers().equals(denied.blockers().stream().sorted().toList()),
            "stable sorted diagnostics required");
        var testGraph = DirectorBootstrap.compose(ProviderMode.TEST_ONLY, List.of());
        var testRejected = DirectorReleaseDiagnostic.inspect(
            testGraph, config(Calibration.APPROVED), emptyCatalog(), Set.of());
        check(has(testRejected, "PROVIDER_MODE_NOT_PRODUCTION"), "test backend cannot launch");
        var c = new CapabilityDescriptor(CAP, 1, ProviderMode.PRODUCTION,
            CapabilityStatus.AVAILABLE, new ResourceId("proutlost_director:audio"),
            Set.of("G3_AUDIO"));
        var partial = DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new DirectorBootstrap.Provider(new Object(), ProviderMode.PRODUCTION,
                List.of(), List.of(c))));
        var result = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), emptyCatalog(), Set.of(CAP));
        check(!has(result, "MISSING_QUALIFIED_CAPABILITY:director.real_audio"),
            "qualified capability diagnostic is resolved");
        check(!result.mayProceedToQualification(), "capability alone never allows G5");
        check(result.blockers().size() >= 9, "core ports remain required");
        // A catalogue descriptor is not a physical J05 provider. In particular,
        // a caller may omit all requiredCapabilities and still list a primitive.
        var physical = new PrimitiveDescriptor(new PrimitiveId("proutlost:physical_sound"),
            1, new ResourceId("proutlost:args"), Set.of(CAP),
            new BudgetVector(0.01,0.01,0.01,0,0,0,64,0.01,32,0),
            new ResourceId("proutlost:footprint"), Set.of(),
            Set.of(OwnershipDomain.PERCEPTION), Set.of(),
            new DurationTicks(100), PausePolicy.CLEANUP, RecoveryMode.RECOVER,
            new ResourceId("proutlost:cleanup"), Optional.empty(), Optional.empty(),
            ProviderMode.PRODUCTION);
        var declaredOnly = new CatalogSnapshot(new Epoch(1), HASH, Map.of(),
            Map.of(), Map.of(physical.id(), physical));
        var missingPhysical = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), declaredOnly, Set.of());
        check(has(missingPhysical, "MISSING_PHYSICAL_PRIMITIVE:" + physical.id().value()),
            "catalogue-only primitive must never satisfy G5 without a physical implementation");
        var missingCapability = DirectorReleaseDiagnostic.inspect(empty,
            config(Calibration.APPROVED), declaredOnly, Set.of());
        check(has(missingCapability, "MISSING_QUALIFIED_CAPABILITY:" + CAP.value()),
            "primitive capability must be checked even if caller omitted it");
        // Positive structural control: a distinct provider object carrying the
        // matching descriptor is tracked, but other missing ports still deny G5.
        DirectorPrimitive<?> synthetic = (DirectorPrimitive<?>) java.lang.reflect.Proxy
            .newProxyInstance(DirectorPrimitive.class.getClassLoader(),
                new Class<?>[]{DirectorPrimitive.class},
                (proxy, method, params) -> {
                    if (method.getName().equals("descriptor")) return physical;
                    throw new UnsupportedOperationException("no physical effects in pure test");
                });
        var bound = DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new DirectorBootstrap.Provider(synthetic, ProviderMode.PRODUCTION,
                List.of(), List.of(c))));
        var boundResult = DirectorReleaseDiagnostic.inspect(bound,
            config(Calibration.APPROVED), declaredOnly, Set.of());
        check(!has(boundResult, "MISSING_PHYSICAL_PRIMITIVE:" + physical.id().value()),
            "composition must recognize the exact bound primitive descriptor");
        check(!boundResult.mayProceedToQualification(),
            "one bound primitive without nine physical ports is never G5-ready");
        check(has(boundResult, "MISSING_NATIVE_EFFECT_BINDING:" + physical.id().value()),
            "J02 descriptor does not certify a real J05 Minecraft effect adapter");
        // Diagnostic native evidence must come from explicit installed bindings.
        // These proxy metadata objects are NEGATIVE structural fixtures only.
        ServerEffectPort.Binding compatible = binding(ProviderMode.PRODUCTION,
            Set.of(physical.id()), Set.of(CAP));
        var installed = port(Map.of(physical.id(), compatible));
        check(installed.supportedModes().equals(Set.of(ProviderMode.TEST_ONLY)),
            "synthetic durable authority cannot attest a production J05 backend");
        rejected(() -> DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new DirectorBootstrap.Provider(synthetic, ProviderMode.PRODUCTION,
                List.of(), List.of(c)),
                new DirectorBootstrap.Provider(installed, ProviderMode.PRODUCTION,
                    List.of(), List.of()))),
            "synthetic J05 authority must be refused before release diagnostics");
        check(installed.installedBindingDescriptors().get(physical.id())
                .capabilities().equals(Set.of(CAP)),
            "native inventory exposes metadata only");
        try {
            installed.installedBindingDescriptors().clear();
            throw new AssertionError("native metadata map should be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }
        var detached = DirectorReleaseDiagnostic.inspect(bound,
            config(Calibration.APPROVED), declaredOnly, Set.of(), installed);
        check(has(detached, "NATIVE_EFFECT_PORT_NOT_INSTALLED"),
            "native adapter not in the actual graph must be refused");
        rejected(() -> port(Map.of(physical.id(), binding(ProviderMode.PRODUCTION,
                Set.of(new PrimitiveId("proutlost:foreign_effect")), Set.of(CAP)))),
            "foreign native implementation cannot be installed under a catalogue alias");
        rejected(() -> port(Map.of(physical.id(), binding(ProviderMode.TEST_ONLY,
                Set.of(physical.id()), Set.of(CAP)))),
            "test-only Minecraft adapter cannot masquerade as production");
        var incomplete = port(Map.of(physical.id(), binding(ProviderMode.PRODUCTION,
            Set.of(physical.id()), Set.of())));
        rejected(() -> DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new DirectorBootstrap.Provider(incomplete, ProviderMode.PRODUCTION,
                List.of(), List.of()))),
            "incomplete synthetic J05 authority must not acquire production provenance");
        var undocumented = new PrimitiveId("proutlost:undocumented");
        var expanded = port(Map.of(physical.id(), compatible, undocumented,
            binding(ProviderMode.PRODUCTION, Set.of(undocumented), Set.of(CAP))));
        rejected(() -> DirectorBootstrap.compose(ProviderMode.PRODUCTION,
            List.of(new DirectorBootstrap.Provider(expanded, ProviderMode.PRODUCTION,
                List.of(), List.of()))),
            "expanded synthetic J05 authority must be rejected at composition");
        // Real gap: an APPROVED event must not reference a primitive that is
        // absent from the catalogue even if the event's own flag is approved.
        var zero = new DurationTicks(0);
        var duration = new DurationTicks(100);
        var missingStep = new PrimitiveId("proutlost:missing_warning");
        var event = new EventDefinition(1, new Revision(1),
            new DefinitionId("proutlost:approved_but_unbound"), Calibration.APPROVED,
            Category.AMBIENT, Set.of(Family.MEMORY), true, new Selection(1, 0),
            new Target(TargetScope.SOLO, Set.of(), PlayerPolicy.FAIR,
                Map.of("witness", new Role(1, 1, false))),
            new PlayersPresent(1), new Timing(duration, new Cooldowns(zero, zero, zero)),
            new Uniqueness(UniquenessScope.REPEATABLE, ConsumeOn.EFFECT_CONFIRMED),
            new BudgetVector(0,0,0,0,0,0,0,0,0,0),
            new Conflicts(Set.of(),Set.of(),Set.of(),Priority.AMBIENT,Preemption.YIELD_BEFORE_COMMIT),
            new Safety(Risk.SAFE,Set.of(),Optional.empty(),Optional.empty(),
                PlayerEditPolicy.YIELD,JournalPolicy.PLAYER_OPT_IN,ParticipantLoss.ABORT_IF_MIN_LOST),
            List.of(new EventStep("warn",List.of(),zero,missingStep,
                new Trace(new TagId("proutlost:test"),new TagId("proutlost:warn"),true),
                OnFailure.ABORT_SAFE)),
            new RecoveryPolicy(RecoveryMode.RECOVER,duration),
            new Completion(new FactType("proutlost:seen"),Optional.empty(),new UnitValue(1)));
        var eventOnly = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(event.id(), event), Map.of(), Map.of());
        var missingStepResult = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), eventOnly, Set.of());
        check(has(missingStepResult, "MISSING_EVENT_PRIMITIVE:" + event.id().value()
            + ":warn:" + missingStep.value()),
            "approved event with undeclared effect must veto G5");
        // A J05 sequence must have an unambiguous DAG. Model validation
        // intentionally permits arbitrary predecessor strings in API 1.0.2,
        // so G5 must veto broken WARN-before-effect step ordering.
        var warn = event.steps().getFirst();
        var apply = new EventStep("apply", List.of("warn"), zero,
            missingStep, warn.args(), OnFailure.ABORT_SAFE);
        var goodSteps = withSteps(event, List.of(warn, apply));
        var goodTopology = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(goodSteps.id(), goodSteps), Map.of(), Map.of()), Set.of());
        check(goodTopology.blockers().stream().noneMatch(s -> s.contains("EVENT_STEP_")),
            "legitimate WARN then apply dependency must not be flagged");
        var dangling = withSteps(event, List.of(new EventStep("apply",
            List.of("missing_warn"), zero, missingStep, warn.args(), OnFailure.ABORT_SAFE)));
        var danglingResult = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(dangling.id(), dangling), Map.of(), Map.of()), Set.of());
        check(has(danglingResult, "UNKNOWN_EVENT_STEP_DEPENDENCY:" + event.id().value()
            + ":apply:missing_warn"), "orphaned WARN dependency must veto G5");
        var cycle = withSteps(event, List.of(
            new EventStep("warn", List.of("apply"), zero, missingStep, warn.args(), OnFailure.ABORT_SAFE),
            apply));
        var cycleResult = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(cycle.id(), cycle), Map.of(), Map.of()), Set.of());
        check(has(cycleResult, "CYCLIC_EVENT_STEPS:" + event.id().value()),
            "cycle between WARN and physical effect must veto G5");
        var duplicate = withSteps(event, List.of(warn,warn));
        var duplicateResult = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(duplicate.id(), duplicate), Map.of(), Map.of()), Set.of());
        check(has(duplicateResult, "DUPLICATE_EVENT_STEP:" + event.id().value()
            + ":warn"), "duplicate physical step ID must veto G5");
        // The chain model currently admits only DEFAULT_FOR_TESTING. A
        // candidate diagnostic must report it, not silently treat it as approved.
        var transition = new ChainTransition(new ResourceId("proutlost:continue"),
            "WAIT", "DONE", new FactType("proutlost:seen"), Optional.of(event.id()),
            new PlayersPresent(1), true, Optional.empty());
        var chain = new ChainDefinition(1, new DefinitionId("proutlost:chain"),
            new Revision(1), Calibration.DEFAULT_FOR_TESTING,
            ChainMode.SHORT_ARC, ScopeKind.REGION, "WAIT",
            Set.of("WAIT", "DONE"), 2, new DurationTicks(20), new UnitValue(.5),
            List.of(transition), Set.of("DONE"), RecoveryMode.RECOVER);
        var chainCatalog = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(event.id(), event), Map.of(chain.id(), chain), Map.of());
        var unapprovedChain = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), chainCatalog, Set.of());
        check(has(unapprovedChain, "UNAPPROVED_CHAIN:" + chain.id().value()),
            "test-only chain cannot masquerade as approved production chain");
        var orphanTransition = new ChainTransition(new ResourceId("proutlost:orphan"),
            "WAIT", "DONE", new FactType("proutlost:seen"),
            Optional.of(new DefinitionId("proutlost:unknown_event")),
            new PlayersPresent(1), true, Optional.empty());
        var orphanChain = new ChainDefinition(1, chain.id(), chain.contentRevision(),
            chain.calibration(), chain.mode(), chain.scope(), chain.entryState(),
            chain.states(), chain.planningHorizon(), chain.minimumStepGapTicks(),
            chain.relevanceMinimum(), List.of(orphanTransition), chain.terminalStates(),
            chain.recovery());
        var orphanCatalog = new CatalogSnapshot(new Epoch(1), HASH, Map.of(),
            Map.of(orphanChain.id(), orphanChain), Map.of());
        var orphanResult = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), orphanCatalog, Set.of());
        check(has(orphanResult, "UNREACHABLE_CHAIN_EVENT:" + chain.id().value()
            + ":" + orphanTransition.id().value() + ":proutlost:unknown_event"),
            "chain cannot silently jump to an unknown event");
        // A bound but undeclared physical effect is equally unsafe: an
        // invisible capability must not be hidden from the release audit.
        var undeclared = DirectorReleaseDiagnostic.inspect(bound,
            config(Calibration.APPROVED), emptyCatalog(), Set.of());
        check(has(undeclared, "UNDECLARED_PHYSICAL_PRIMITIVE:" + physical.id().value()),
            "physical provider absent from the reviewed catalogue must veto G5");
        // A catalogue map key is not proof of the contained definition ID.
        // Forged aliases must not satisfy chain routing or J05 lookup.
        var eventAlias = new DefinitionId("proutlost:alias_event");
        var forgedEventCatalog = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(eventAlias, event), Map.of(), Map.of());
        check(has(DirectorReleaseDiagnostic.inspect(partial, config(Calibration.APPROVED),
            forgedEventCatalog, Set.of()), "CATALOG_EVENT_ID_MISMATCH:" +
            eventAlias.value() + ":" + event.id().value()),
            "event map key must equal embedded event ID");
        var chainAlias = new DefinitionId("proutlost:alias_chain");
        var forgedChainCatalog = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(event.id(), event), Map.of(chainAlias, chain), Map.of());
        check(has(DirectorReleaseDiagnostic.inspect(partial, config(Calibration.APPROVED),
            forgedChainCatalog, Set.of()), "CATALOG_CHAIN_ID_MISMATCH:" +
            chainAlias.value() + ":" + chain.id().value()),
            "chain map key must equal embedded chain ID");
        var primitiveAlias = new PrimitiveId("proutlost:alias_primitive");
        var forgedPrimitiveCatalog = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(), Map.of(), Map.of(primitiveAlias, physical));
        check(has(DirectorReleaseDiagnostic.inspect(bound, config(Calibration.APPROVED),
            forgedPrimitiveCatalog, Set.of()), "CATALOG_PRIMITIVE_ID_MISMATCH:" +
            primitiveAlias.value() + ":" + physical.id().value()),
            "primitive map key must equal physical descriptor ID");
        // A topology-valid CONTROLLED_DANGER event with an unguarded impact
        // must not pass G5 merely because it contains some unrelated warning.
        var realWarning = new EventStep("warn", List.of(), zero,
            new PrimitiveId("proutlost:warning"), warn.args(), OnFailure.ABORT_SAFE);
        var unsafeImpact = new EventStep("impact", List.of(), zero,
            missingStep, warn.args(), OnFailure.ABORT_SAFE);
        var danger = withDangerSteps(event, List.of(realWarning, unsafeImpact));
        var dangerCatalog = new CatalogSnapshot(new Epoch(1), HASH,
            Map.of(danger.id(), danger), Map.of(), Map.of());
        var unguarded = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), dangerCatalog, Set.of());
        check(has(unguarded, "UNWARNED_DANGER_STEP:" + danger.id().value() + ":impact"),
            "dangerous effect not ordered AFTER a warning must veto G5");
        var missingWarning = withDangerSteps(event, List.of(unsafeImpact));
        var unwarned = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(missingWarning.id(), missingWarning), Map.of(), Map.of()), Set.of());
        check(has(unwarned, "MISSING_DANGER_WARNING:" + danger.id().value()),
            "a danger event must declare a real warning primitive");
        var safeImpact = new EventStep("impact", List.of("warn"), zero,
            missingStep, warn.args(), OnFailure.ABORT_SAFE);
        var guarded = withDangerSteps(event, List.of(realWarning, safeImpact));
        var guardedReport = DirectorReleaseDiagnostic.inspect(partial,
            config(Calibration.APPROVED), new CatalogSnapshot(new Epoch(1), HASH,
                Map.of(guarded.id(), guarded), Map.of(), Map.of()), Set.of());
        check(guardedReport.blockers().stream().noneMatch(s ->
                s.contains("DANGER_WARNING") || s.contains("UNWARNED_DANGER_STEP")),
            "WARN dependency must be accepted; other production gates remain closed");
        try {
            new DirectorReleaseDiagnostic.Report(List.of("failure"), true);
            throw new AssertionError("invalid verdict admitted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        System.out.println("PASS DirectorReleaseDiagnosticTests assertions=" + checks
            + "; G5 diagnostic only, never a gameplay permit");
    }
}
