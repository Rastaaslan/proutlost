package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.effects.ServerEffectPort;
import fr.proutlost.director.adapter.neoforge.server.effects.InstalledEffectPort;
import java.util.*;

/**
 * Fail-closed eligibility diagnostic for the G5 qualification candidate.
 * This report NEVER grants a gameplay execution/physical mutation permit.
 * Native activation requires separate qualified server and client evidence.
 */
public final class DirectorReleaseDiagnostic {
    private DirectorReleaseDiagnostic() {}

    public record Report(List<String> blockers, boolean qualificationCandidate) {
        public Report {
            blockers = List.copyOf(blockers);
            if (qualificationCandidate != blockers.isEmpty())
                throw new IllegalArgumentException("diagnostic verdict mismatch");
        }
        /** Not a gameplay permission: success permits more qualification only. */
        public boolean mayProceedToQualification() { return qualificationCandidate; }
    }

    /**
     * Pure read-only gate. Optional Quest/AI integrations are NOT required.
     * The mandatory nine ports must be provided by the PRODUCTION graph.
     */
    public static Report inspect(DirectorBootstrap.Composition graph,
                                 ConfigView configuration,
                                 CatalogSnapshot catalog,
                                 Set<CapabilityId> requiredCapabilities) {
        // Legacy callers have no native binding evidence: never infer physical readiness.
        return inspectBindings(graph, configuration, catalog, requiredCapabilities, Map.of());
    }

    /** Only a physical J05 port already installed in this PRODUCTION graph can
     * attest the actual registered Minecraft bindings. An arbitrary map would
     * allow metadata to masquerade as a wired J05 implementation.
     * This remains a diagnostic, never an execution permit.
     */
    public static Report inspect(DirectorBootstrap.Composition graph,
                                 ConfigView configuration,
                                 CatalogSnapshot catalog,
                                 Set<CapabilityId> requiredCapabilities,
                                 InstalledEffectPort installedPort) {
        Objects.requireNonNull(graph);
        Map<PrimitiveId, ServerEffectPort.BindingView> installed = installedPort == null
            ? Map.of() : installedPort.installedBindingDescriptors();
        var result = inspectBindings(graph, configuration, catalog, requiredCapabilities, installed);
        if (installedPort != null && graph.ports().worldEffects().orElse(null) == installedPort)
            return result;
        var faults = new TreeSet<>(result.blockers());
        faults.add("NATIVE_EFFECT_PORT_NOT_INSTALLED");
        return new Report(List.copyOf(faults), false);
    }

    private static Report inspectBindings(DirectorBootstrap.Composition graph,
                                         ConfigView configuration,
                                         CatalogSnapshot catalog,
                                         Set<CapabilityId> requiredCapabilities,
                                         Map<PrimitiveId, ServerEffectPort.BindingView> installedBindings) {
        installedBindings = Map.copyOf(installedBindings);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(configuration);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(requiredCapabilities);
        var faults = new TreeSet<String>();

        if (graph.mode() != ProviderMode.PRODUCTION)
            faults.add("PROVIDER_MODE_NOT_PRODUCTION");

        Set<String> mandatory = Set.of(
            "ObservationPort", "MemoryPort", "CatalogPort", "DecisionPort",
            "AdmissionPort", "WorldEffectPort", "DurableStore", "FactBusPort",
            "AdminPort");
        for (String missing : graph.missingPorts()) {
            if (mandatory.contains(missing)) faults.add("MISSING_PORT:" + missing);
        }
        // Composition is a public record: its missingPorts metadata can be
        // inconsistent with the actual slots if it did not come from compose().
        // A release diagnostic must not accept claims about ports it cannot see.
        var ports = Objects.requireNonNull(graph.ports(), "composition ports");
        if (ports.observation().isEmpty()) faults.add("MISSING_PORT:ObservationPort");
        if (ports.memory().isEmpty()) faults.add("MISSING_PORT:MemoryPort");
        if (ports.catalog().isEmpty()) faults.add("MISSING_PORT:CatalogPort");
        if (ports.decision().isEmpty()) faults.add("MISSING_PORT:DecisionPort");
        if (ports.admission().isEmpty()) faults.add("MISSING_PORT:AdmissionPort");
        if (ports.worldEffects().isEmpty()) faults.add("MISSING_PORT:WorldEffectPort");
        if (ports.durableStore().isEmpty()) faults.add("MISSING_PORT:DurableStore");
        if (ports.factBus().isEmpty()) faults.add("MISSING_PORT:FactBusPort");
        if (ports.admin().isEmpty()) faults.add("MISSING_PORT:AdminPort");
        if (configuration.calibration() != Calibration.APPROVED)
            faults.add("UNAPPROVED_GLOBAL_CALIBRATION");

        // G5 is the *complete* Proutlost S2 candidate gate. A graph with all
        // nine ports but a token event or a partially loaded catalogue is not
        // eligible for qualification. These are minimum inventory fences,
        // never substitutes for validating identities, calibration or effects.
        if (catalog.events().size() < 9)
            faults.add("INCOMPLETE_G5_EVENTS:" + catalog.events().size() + "/9");
        if (catalog.chains().size() < 2)
            faults.add("INCOMPLETE_G5_CHAINS:" + catalog.chains().size() + "/2");
        if (catalog.primitives().size() < 10)
            faults.add("INCOMPLETE_G5_PRIMITIVES:" + catalog.primitives().size() + "/10");
        long qualifiedCapabilities = graph.capabilities().values().stream().filter(capability ->
            capability.mode() == ProviderMode.PRODUCTION
                && capability.status() == CapabilityStatus.AVAILABLE
                && !capability.qualificationTests().isEmpty()).count();
        if (qualifiedCapabilities < 13)
            faults.add("INCOMPLETE_G5_CAPABILITIES:" + qualifiedCapabilities + "/13");

        // CatalogSnapshot is a public immutable record; it does not verify
        // that map keys match contained definitions. A forged alias otherwise
        // breaks chain routing and physical primitive lookup at admission.
        for (var entry : catalog.events().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id()))
                faults.add("CATALOG_EVENT_ID_MISMATCH:" + entry.getKey().value()
                    + ":" + entry.getValue().id().value());
        }
        for (var entry : catalog.chains().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id()))
                faults.add("CATALOG_CHAIN_ID_MISMATCH:" + entry.getKey().value()
                    + ":" + entry.getValue().id().value());
        }
        for (var entry : catalog.primitives().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id()))
                faults.add("CATALOG_PRIMITIVE_ID_MISMATCH:" + entry.getKey().value()
                    + ":" + entry.getValue().id().value());
        }
        if (catalog.events().values().stream().noneMatch(EventDefinition::enabled))
            faults.add("NO_ENABLED_EVENT");
        for (var event : catalog.events().values()) {
            if (!event.enabled()) continue;
            if (event.calibration() != Calibration.APPROVED)
                faults.add("UNAPPROVED_EVENT:" + event.id().value());
            // An approved flag on the event is not evidence that the
            // referenced physical effects were even declared in J02.
            for (var step : event.steps()) {
                if (!catalog.primitives().containsKey(step.primitive()))
                    faults.add("MISSING_EVENT_PRIMITIVE:" + event.id().value()
                        + ":" + step.id() + ":" + step.primitive().value());
            }
            // An effect whose predecessor vanished (or whose steps cycle)
            // cannot prove WARN-before-effect ordering in a real world.
            checkEventTopology(event, faults);
            checkDangerWarningOrder(event, faults);
        }
        // Every chain must carry its own explicit operator approval.
        for (var chain : catalog.chains().values()) {
            if (chain.calibration() != Calibration.APPROVED)
                faults.add("UNAPPROVED_CHAIN:" + chain.id().value());
            for (var transition : chain.transitions()) {
                transition.eventId().ifPresent(id -> {
                    var target = catalog.events().get(id);
                    if (target == null || !target.enabled())
                        faults.add("UNREACHABLE_CHAIN_EVENT:" + chain.id().value()
                            + ":" + transition.id().value() + ":" + id.value());
                });
            }
        }
        var capabilitiesToVerify = new TreeSet<CapabilityId>(Comparator.comparing(CapabilityId::value));
        capabilitiesToVerify.addAll(requiredCapabilities);
        for (var primitive : catalog.primitives().values()) {
            if (primitive.mode() != ProviderMode.PRODUCTION)
                faults.add("UNQUALIFIED_PRIMITIVE:" + primitive.id().value());
            // Describing a primitive in J02 is not binding its real J05 effect.
            // The composition must carry the concrete provider, not just a
            // matching ID or an optimistic capability flag in a catalogue.
            var implementation = graph.physicalPrimitives().get(primitive.id());
            try {
                if (implementation == null ||
                    !primitive.equals(implementation.descriptor()))
                    faults.add("MISSING_PHYSICAL_PRIMITIVE:" + primitive.id().value());
            } catch (RuntimeException badImplementation) {
                faults.add("MISSING_PHYSICAL_PRIMITIVE:" + primitive.id().value());
            }
            // A matching DirectorPrimitive descriptor is NOT a Minecraft adapter.
            // Runtime J05 must actually register a physical binding under this ID.
            var nativeBinding = installedBindings.get(primitive.id());
            if (nativeBinding == null) {
                faults.add("MISSING_NATIVE_EFFECT_BINDING:" + primitive.id().value());
            } else {
                try {
                    if (nativeBinding.mode() != ProviderMode.PRODUCTION
                            || !nativeBinding.primitives().contains(primitive.id())
                            || !nativeBinding.capabilities().containsAll(primitive.capabilities()))
                        faults.add("INVALID_NATIVE_EFFECT_BINDING:" + primitive.id().value());
                    if (!nativeBinding.qualified())
                        faults.add("UNQUALIFIED_NATIVE_EFFECT_BINDING:" + primitive.id().value());
                } catch (RuntimeException badBinding) {
                    faults.add("INVALID_NATIVE_EFFECT_BINDING:" + primitive.id().value());
                }
            }
            capabilitiesToVerify.addAll(primitive.capabilities());
        }
        for (var id : installedBindings.keySet()) {
            if (!catalog.primitives().containsKey(id))
                faults.add("UNDECLARED_NATIVE_EFFECT_BINDING:" + id.value());
        }
        // A production physical effect not listed in the reviewed J02
        // catalogue must not be silently hidden from the G5 audit.
        for (var id : graph.physicalPrimitives().keySet()) {
            if (!catalog.primitives().containsKey(id))
                faults.add("UNDECLARED_PHYSICAL_PRIMITIVE:" + id.value());
        }
        for (var id : capabilitiesToVerify) {
            var capability = graph.capabilities().get(id);
            if (capability == null ||
                capability.mode() != ProviderMode.PRODUCTION ||
                capability.status() != CapabilityStatus.AVAILABLE ||
                capability.qualificationTests().isEmpty())
                faults.add("MISSING_QUALIFIED_CAPABILITY:" + id.value());
        }
        return new Report(List.copyOf(faults), faults.isEmpty());
    }

    /** G5 safety-only check: a disaster must have an actual warning before
     * any other physical step. Topological validity alone is not an audience
     * warning or a causal ordering proof. Runtime WARN evidence stays mandatory.
     */
    private static void checkDangerWarningOrder(EventDefinition event, Set<String> faults) {
        if (event.safety().risk() != Risk.CONTROLLED_DANGER
                && !event.families().contains(Family.DISASTER)) return;
        var steps = new HashMap<String, EventStep>();
        var warnings = new HashSet<String>();
        var warningPrimitive = new PrimitiveId("proutlost:warning");
        for (var step : event.steps()) {
            steps.putIfAbsent(step.id(), step);
            if (step.primitive().equals(warningPrimitive)) warnings.add(step.id());
        }
        if (warnings.isEmpty()) {
            faults.add("MISSING_DANGER_WARNING:" + event.id().value());
            return;
        }
        for (var step : event.steps()) {
            if (step.primitive().equals(warningPrimitive)) continue;
            var open = new ArrayDeque<String>(step.after());
            var seen = new HashSet<String>();
            boolean warned = false;
            while (!open.isEmpty()) {
                var predecessor = open.removeFirst();
                if (!seen.add(predecessor)) continue;
                if (warnings.contains(predecessor)) { warned = true; break; }
                var parent = steps.get(predecessor);
                if (parent != null) open.addAll(parent.after());
            }
            if (!warned) faults.add("UNWARNED_DANGER_STEP:"
                + event.id().value() + ":" + step.id());
        }
    }

    /** Topology-only G5 gate; never executes or schedules event steps. */
    private static void checkEventTopology(EventDefinition event, Set<String> faults) {
        var steps = new HashMap<String, EventStep>();
        for (var step : event.steps()) {
            if (steps.putIfAbsent(step.id(), step) != null)
                faults.add("DUPLICATE_EVENT_STEP:" + event.id().value() + ":" + step.id());
        }
        var successors = new HashMap<String, List<String>>();
        var indegree = new HashMap<String, Integer>();
        for (var step : steps.values()) {
            indegree.put(step.id(), 0);
            successors.put(step.id(), new ArrayList<>());
        }
        for (var step : steps.values()) {
            for (var predecessor : step.after()) {
                if (!steps.containsKey(predecessor)) {
                    faults.add("UNKNOWN_EVENT_STEP_DEPENDENCY:" + event.id().value()
                        + ":" + step.id() + ":" + predecessor);
                } else {
                    successors.get(predecessor).add(step.id());
                    indegree.merge(step.id(), 1, Integer::sum);
                }
            }
        }
        // Iterative topological sort prevents unbounded recursion on
        // cyclic or adversarially deep event definitions.
        var ready = new ArrayDeque<String>();
        for (var entry : indegree.entrySet()) {
            if (entry.getValue() == 0) ready.addLast(entry.getKey());
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            var resolved = ready.removeFirst();
            visited++;
            for (var next : successors.get(resolved)) {
                int remaining = indegree.merge(next, -1, Integer::sum);
                if (remaining == 0) ready.addLast(next);
            }
        }
        if (visited != steps.size())
            faults.add("CYCLIC_EVENT_STEPS:" + event.id().value());
    }
}
