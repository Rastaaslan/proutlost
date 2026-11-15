package fr.proutlost.director.catalog;

import fr.proutlost.director.api.model.*;
import java.util.*;
import static fr.proutlost.director.catalog.InvalidContent.require;
import static fr.proutlost.director.catalog.ContentReferences.Kind;

/** Pure validation. Registry availability is not a claim of Minecraft/GameTest qualification. */
public final class DefinitionValidator {
    private final PrimitiveRegistry registry;
    private final ContentReferences refs;
    private final ProviderMode mode;
    public DefinitionValidator(PrimitiveRegistry registry, ContentReferences refs, ProviderMode mode) {
        this.registry = Objects.requireNonNull(registry); this.refs = Objects.requireNonNull(refs); this.mode = Objects.requireNonNull(mode);
        if (mode == ProviderMode.PRODUCTION) {
            require(refs.mode() == ProviderMode.PRODUCTION, "", "TEST_ONLY references in production");
            require(registry.descriptors().values().stream().allMatch(p -> p.mode() == ProviderMode.PRODUCTION), "", "TEST_ONLY primitive in production");
        }
    }
    private void ref(Kind kind, String id, String p) { require(refs.contains(kind, id), p, "unknown " + kind + " reference: " + id); }
    private void tag(TagId id, String p) { ref(Kind.TAG, id.value(), p); }
    private void role(EventDefinition e, String name, String p) {
        require(e.target().roles().containsKey(name), p, "unknown role " + name);
        require(e.target().roles().get(name).max() > 0, p, "role has no possible participant");
    }
    public List<String> event(EventDefinition e, Map<DefinitionId, EventDefinition> events) {
        require(mode != ProviderMode.PRODUCTION || e.calibration() == Calibration.APPROVED, "/calibration", "test content in production");
        require(e.timing().maxDurationTicks().value() > 0 && e.recovery().deadlineTicks().value() > 0, "/timing", "finite positive duration required");
        require(refs.capabilities().containsAll(e.safety().requiredCapabilities()), "/safety/required_capabilities", "capability missing");
        e.target().regionTags().forEach(t -> tag(t, "/target/region_tags"));
        e.conflicts().compatibilityTags().forEach(t -> tag(t, "/conflicts/compatibility_tags"));
        e.completion().durableTrace().ifPresent(t -> tag(t, "/completion/durable_trace"));
        ref(Kind.FACT, e.completion().factType().value(), "/completion/fact_type");
        for (String claim : e.conflicts().claims()) {
            require(refs.claims().contains(claim), "/conflicts/claims", "unknown claim resolver " + claim);
            if (claim.startsWith("PERCEPTION:")) role(e, claim.substring(11).toLowerCase(Locale.ROOT), "/conflicts/claims");
        }
        condition(e.eligibility(), events, "/eligibility");
        if (e.target().scope() == TargetScope.SOLO) {
            long minimum = e.target().roles().values().stream().filter(r -> !r.overlap()).mapToLong(Role::min).sum();
            require(minimum <= 1 && e.target().roles().values().stream().allMatch(r -> r.max() <= 1), "/target/roles", "solo role capacity");
        }
        require(e.target().roles().values().stream().anyMatch(r -> r.max() > 0), "/target/roles", "no audience possible");
        Map<String, EventStep> steps = new LinkedHashMap<>(); Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < e.steps().size(); i++) {
            EventStep s = e.steps().get(i);
            require(steps.putIfAbsent(s.id(), s) == null, "/steps/" + i + "/id", "duplicate step ID"); indices.put(s.id(), i);
        }
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        steps.forEach((id, s) -> dependencies.put(id, s.after()));
        List<String> ordered = topological(dependencies, "/steps");
        Map<String, Long> finishes = new HashMap<>();
        BudgetVector minimum = zero(); long declaredEntities = 0, declaredMutations = 0;
        boolean hasDanger = false;
        for (String id : ordered) {
            EventStep s = steps.get(id); String p = "/steps/" + indices.get(id);
            PrimitiveDescriptor d = registry.descriptors().get(s.primitive());
            require(d != null, p + "/primitive", "unregistered primitive");
            require(d.maximumDuration().value() > 0, p + "/primitive", "primitive lacks bounded lifetime");
            require(refs.capabilities().containsAll(d.capabilities()) && e.safety().requiredCapabilities().containsAll(d.capabilities()), p + "/primitive", "primitive capabilities unavailable or undeclared");
            ref(Kind.CODEC, d.argumentCodec().value(), p + "/primitive");
            ref(Kind.RESOLVER, d.footprintResolver().value(), p + "/primitive");
            ref(Kind.CLEANUP, d.cleanup().value(), p + "/primitive");
            d.capturableCodecs().forEach(c -> ref(Kind.CODEC, c.value(), p + "/primitive"));
            if (!e.conflicts().allowMergeWith().isEmpty()) {
                require(d.merge().isPresent(), "/conflicts/allow_merge_with", "primitive has no registered merge");
                ref(Kind.MERGE, d.merge().orElseThrow().value(), "/conflicts/allow_merge_with");
            }
            OwnershipDomain domain = switch (s.args()) {
                case GlobalWeatherLease ignored -> OwnershipDomain.WEATHER;
                case RegionalWeatherLease ignored -> OwnershipDomain.WEATHER;
                case CampGrowth ignored -> OwnershipDomain.BLOCK;
                case Landslide ignored -> OwnershipDomain.BLOCK;
                case ControlledWater ignored -> OwnershipDomain.BLOCK;
                case ControlledFire ignored -> OwnershipDomain.BLOCK;
                case FaunaMigrate ignored -> OwnershipDomain.ENTITY;
                case GoatVisit ignored -> OwnershipDomain.ENTITY;
                case Trace ignored -> OwnershipDomain.ANCHOR;
                case SharedChoice ignored -> OwnershipDomain.ANCHOR;
                default -> OwnershipDomain.PERCEPTION;
            };
            require(d.ownershipDomains().contains(domain), p + "/primitive", "primitive ownership domain incompatible");
            if (s.args() instanceof TargetSound) require(d.noninvertibleEffects().contains(NoninvertibleEffect.SOUND_EXPOSURE), p + "/primitive", "sound exposure is noninvertible");
            minimum = plus(minimum, d.minimumCost(), p);
            long predecessorFinish = s.after().stream().mapToLong(finishes::get).max().orElse(0);
            long start = add(predecessorFinish, s.delayTicks().value(), p + "/delay_ticks");
            long duration = s.args() instanceof Apparition a ? a.followTicks().value()
                : s.args() instanceof GlobalWeatherLease a ? a.duration().value()
                : s.args() instanceof RegionalWeatherLease a ? a.duration().value() : 0;
            require(duration <= d.maximumDuration().value(), p + "/args", "primitive duration bound exceeded");
            long finish = add(start, duration, p);
            require(finish < e.timing().maxDurationTicks().value(), p + "/delay_ticks", "sequence cannot finish before event deadline");
            finishes.put(id, finish);
            boolean dangerous = s.args() instanceof GlobalWeatherLease a && a.weather().value().equals("minecraft:thunder")
                || s.args() instanceof Landslide || s.args() instanceof ControlledFire
                || s.args() instanceof ControlledWater && e.safety().risk() == Risk.CONTROLLED_DANGER
                || d.noninvertibleEffects().contains(NoninvertibleEffect.DAMAGE) || d.noninvertibleEffects().contains(NoninvertibleEffect.DEATH);
            hasDanger |= dangerous;
            if (dangerous) {
                require(e.safety().risk() == Risk.CONTROLLED_DANGER, "/safety/risk", "danger declared SAFE");
                require(d.pausePolicy() == PausePolicy.STABILIZE, p + "/primitive", "danger lacks stabilization policy");
                require(e.safety().mutationProfile().isPresent(), "/safety/mutation_profile", "danger needs mutation profile");
            }
            // All global leases require a matching WARN, including non-thundering rain.
            if (dangerous || s.args() instanceof GlobalWeatherLease) {
                boolean warned = false;
                for (String ancestor : ancestors(id, dependencies)) {
                    EventStep w = steps.get(ancestor);
                    if (w.args() instanceof Warning warning && w.onFailure() == OnFailure.ABORT_SAFE
                            && e.safety().warningProfile().filter(warning.profile()::equals).isPresent()) {
                        long elapsed = minimumDelay(id, ancestor, steps, ordered);
                        if (elapsed >= warning.minimumLeadTicks().value()) warned = true;
                    }
                }
                require(warned, p + "/after", "danger requires unavoidable matching WARN and sufficient lead time");
            }
            switch (s.args()) {
                case TargetSound a -> { role(e, a.role(), p + "/args/role"); ref(Kind.SOUND, a.sound().value(), p + "/args/sound"); }
                case TargetParticles a -> { role(e, a.role(), p + "/args/role"); ref(Kind.PARTICLE, a.particle().value(), p + "/args/particle"); }
                case Apparition a -> { role(e, a.role(), p + "/args/role"); ref(Kind.MODEL, a.model().value(), p + "/args/model"); }
                case LocalWeather a -> { role(e, a.role(), p + "/args/role"); ref(Kind.LOCAL_WEATHER, a.profile().value(), p + "/args/profile"); }
                case GoatVisit a -> { role(e, a.role(), p + "/args/role"); tag(a.anchorTag(), p + "/args/anchor_tag"); declaredEntities = add(declaredEntities, 1, p); }
                case FaunaMigrate a -> { ref(Kind.ENTITY, a.species().value(), p + "/args/species"); tag(a.corridorTag(), p + "/args/corridor_tag"); declaredEntities = add(declaredEntities, a.cohortSize(), p); }
                case CampGrowth a -> { tag(a.anchorTag(), p + "/args/anchor_tag"); mutation(e, s, d, a.recipe(), p); declaredMutations = add(declaredMutations, a.maxBlocks(), p); }
                case Landslide a -> {
                    mutation(e, s, d, a.recipe(), p); declaredMutations = add(declaredMutations, a.maxBlocks(), p);
                    DamageProfile damage = refs.damages().get(a.damageProfile());
                    require(damage != null, p + "/args/damage_profile", "unknown damage profile");
                    require(!damage.damageTypes().isEmpty() && !damage.pauseReasons().isEmpty() && damage.hitGap().value() > 0, p + "/args/damage_profile", "unbounded damage profile");
                    damage.damageTypes().stream().sorted(Comparator.comparing(ResourceId::value))
                        .forEach(type -> ref(Kind.DAMAGE_TYPE, type.value(), p + "/args/damage_profile"));
                    ref(Kind.CODEC, damage.hitLedgerCodec().value(), p + "/args/damage_profile");
                    require(d.noninvertibleEffects().contains(NoninvertibleEffect.DAMAGE), p + "/primitive", "damage must be declared noninvertible");
                }
                case RegionalWeatherLease a -> {
                    require(a.profile().value().equals("proutlost_director:regional_mist"),p+"/args/profile","installed shared regional weather profile required");
                    require(a.duration().value()>0&&a.duration().compareTo(d.maximumDuration())<=0&&a.duration().value()<=72000,p+"/args/duration","bounded positive regional lease required");
                    require(d.ownershipDomains().contains(OwnershipDomain.WEATHER)&&d.capabilities().contains(new CapabilityId("weather.regional_scope_v1")),p+"/primitive","actual regional ownership/capability descriptor required");
                    require(e.safety().mutationProfile().isPresent(),"/safety/mutation_profile","regional owned-mutation profile required");
                    mutation(e,s,d,e.safety().mutationProfile().orElseThrow(),p);declaredMutations=add(declaredMutations,1,p);
                }
                case GlobalWeatherLease a -> {
                    require(Set.of("minecraft:clear","minecraft:rain","minecraft:thunder").contains(a.weather().value()),
                        p + "/args/weather", "unsupported native weather profile");
                    require(a.duration().value() > 0 && a.duration().compareTo(d.maximumDuration()) <= 0,
                        p + "/args/duration", "bounded positive weather lease required");
                    require(e.safety().mutationProfile().isPresent(), "/safety/mutation_profile", "weather lease profile required");
                    mutation(e,s,d,e.safety().mutationProfile().orElseThrow(),p);
                    declaredMutations = add(declaredMutations,1,p);
                    if(a.weather().value().equals("minecraft:thunder"))
                        require(d.noninvertibleEffects().containsAll(Set.of(NoninvertibleEffect.DAMAGE,NoninvertibleEffect.DEATH)),
                            p + "/primitive", "native lightning damage/death must be declared noninvertible");
                }
                case ControlledWater a -> {
                    mutation(e, s, d, a.recipe(), p);
                    propagationMask(a.propagationMask(), d, p + "/args/propagation_mask");
                    require(a.maxLevel() > 0 && a.maxLevel() <= 8, p + "/args/max_level", "controlled water level bound");
                    // transferUnits is conserved fluid volume, never a number of block writes.
                    declaredMutations = add(declaredMutations, refs.mutations().get(a.recipe()).maximumWork().mutationsInFlight(), p);
                }
                case ControlledFire a -> {
                    mutation(e, s, d, a.recipe(), p);
                    propagationMask(a.propagationMask(), d, p + "/args/propagation_mask");
                    require(a.energy().value() > 0, p + "/args/energy", "positive fire energy required");
                    declaredMutations = add(declaredMutations, refs.mutations().get(a.recipe()).maximumWork().mutationsInFlight(), p);
                    DamageProfile damage = refs.damages().get(a.damageProfile());
                    require(damage != null, p + "/args/damage_profile", "unknown damage profile");
                    require(damage.damageTypes().contains(new ResourceId("minecraft:in_fire")) && damage.perHit() > 0
                        && damage.maxPerPlayer() >= damage.perHit() && !damage.pauseReasons().isEmpty() && damage.hitGap().value() > 0
                        && damage.hitLedgerCodec().equals(new ResourceId("proutlost_director:fire_hit_ledger_v1")),
                        p + "/args/damage_profile", "bounded controlled fire damage profile required");
                    damage.damageTypes().stream().sorted(Comparator.comparing(ResourceId::value))
                        .forEach(type -> ref(Kind.DAMAGE_TYPE, type.value(), p + "/args/damage_profile"));
                    ref(Kind.CODEC, damage.hitLedgerCodec().value(), p + "/args/damage_profile");
                    require(d.noninvertibleEffects().contains(NoninvertibleEffect.DAMAGE), p + "/primitive", "damage must be declared noninvertible");
                }
                case Warning a -> {
                    role(e, a.role(), p + "/args/role"); WarningProfile w = refs.warnings().get(a.profile());
                    require(w != null, p + "/args/profile", "unknown warning profile");
                    require(e.safety().warningProfile().filter(a.profile()::equals).isPresent(), p + "/args/profile", "warning profile mismatch");
                    require(a.minimumLeadTicks().value() > 0 && a.minimumLeadTicks().value() >= w.leadTime().value() && a.minimumLeadTicks().value() < e.timing().maxDurationTicks().value(), p + "/args/minimum_lead_ticks", "insufficient warning lead");
                    require(!w.signs().isEmpty() && !w.perimeterMarkers().isEmpty() && !w.visibilityConstraints().isEmpty(), p + "/args/profile", "warning lacks signs/perimeter/visibility");
                    w.signs().forEach(x -> ref(Kind.MODEL, x.value(), p + "/args/profile"));
                    w.perimeterMarkers().forEach(x -> ref(Kind.MODEL, x.value(), p + "/args/profile"));
                    w.visibilityConstraints().forEach(x -> ref(Kind.RESOLVER, x.value(), p + "/args/profile"));
                    ref(Kind.RESOLVER, w.audienceResolver().value(), p + "/args/profile");
                    ref(Kind.RESOLVER, w.newArrivalHandling().value(), p + "/args/profile");
                    ref(Kind.MODEL, w.cancellationSignal().value(), p + "/args/profile");
                    ref(Kind.CODEC, w.proofCodec().value(), p + "/args/profile");
                }
                case SharedChoice a -> {
                    a.roles().forEach(r -> role(e, r, p + "/args/roles")); tag(a.anchorTag(), p + "/args/anchor_tag");
                    a.choiceIds().forEach(c -> ref(Kind.CHOICE, c.value(), p + "/args/choice_ids"));
                }
                case Trace a -> {
                    tag(a.anchorTag(), p + "/args/anchor_tag"); tag(a.stateTag(), p + "/args/state_tag");
                    require(!a.persistent() || e.completion().persistenceProbability().value() == 1, "/completion/persistence_probability", "persistent primitive cannot be probabilistically deleted");
                }
                default -> throw new InvalidContent(p + "/primitive", "argument codec absent from pinned content schema");
            }
        }
        require(e.safety().risk() != Risk.CONTROLLED_DANGER || hasDanger, "/safety/risk", "danger recipe has no dangerous primitive");
        e.safety().mutationProfile().ifPresent(id -> require(refs.mutations().containsKey(id), "/safety/mutation_profile", "unknown mutation profile"));
        e.safety().warningProfile().ifPresent(id -> require(refs.warnings().containsKey(id), "/safety/warning_profile", "unknown warning profile"));
        BudgetVector b = e.budget();
        require(b.tickMillis() >= minimum.tickMillis() && b.globalIntensity() >= minimum.globalIntensity()
            && b.regionalIntensity() >= minimum.regionalIntensity() && b.clientUnits() >= minimum.clientUnits(), "/budget", "declared cost below primitive aggregate minimum");
        require(b.liveEntities() >= Math.max(minimum.liveEntities(), declaredEntities)
            && b.persistentEntities() >= Math.max(minimum.persistentEntities(), declaredEntities), "/budget/entity_slots", "entity underbudget");
        require(b.mutationsInFlight() >= Math.max(minimum.mutationsInFlight(), declaredMutations), "/budget/mutation_units", "mutation underbudget");
        for (DefinitionId id : e.conflicts().allowMergeWith()) require(events.containsKey(id), "/conflicts/allow_merge_with", "unknown merge event");
        return List.copyOf(ordered);
    }
    /** Extra dimensions have no JSON override: preserve the registry's conservative envelope. */
    public BudgetVector effectiveBudget(EventDefinition e) {
        BudgetVector total = zero();
        for (EventStep s : e.steps()) total = plus(total, registry.descriptors().get(s.primitive()).minimumCost(), "/budget");
        BudgetVector b = e.budget();
        return new BudgetVector(b.tickMillis(), b.globalIntensity(), b.regionalIntensity(), b.liveEntities(), b.persistentEntities(),
            b.mutationsInFlight(), total.queuedIoBytes(), b.clientUnits(), total.payloadBytes(), total.navigationNodes());
    }
    private void mutation(EventDefinition e, EventStep s, PrimitiveDescriptor d, ResourceId recipe, String p) {
        MutationProfile m = refs.mutations().get(recipe);
        require(m != null, p + "/args/recipe", "unknown mutation recipe");
        require(e.safety().mutationProfile().filter(recipe::equals).isPresent(), p + "/args/recipe", "mutation profile mismatch");
        require(m.version() > 0 && m.allowedPrimitives().contains(s.primitive()), p + "/args/recipe", "primitive not allowed by recipe");
        require(d.capturableCodecs().containsAll(m.affectedCodecs()) && !m.affectedCodecs().isEmpty(), p + "/args/recipe", "uncapturable recipe state");
        require(m.ownershipPolicy() == PlayerEditPolicy.YIELD, p + "/args/recipe", "ownership incompatible");
        require(registry.descriptors().containsKey(m.inversePlan()), p + "/args/recipe", "unknown inverse plan");
        require(d.noninvertibleEffects().containsAll(m.noninvertibleEffects()), p + "/args/recipe", "undeclared noninvertible effect");
        require(!m.requiredGameTests().isEmpty(), p + "/args/recipe", "missing recipe qualification contract");
        ref(Kind.RESOLVER, m.footprintResolver().value(), p + "/args/recipe");
        ref(Kind.RESOLVER, m.propagationMaskStrategy().value(), p + "/args/recipe");
        m.constructionImpact().ifPresent(c -> {
            require(c.playerEditPolicy() == PlayerEditPolicy.YIELD && c.warningProfile().equals(e.safety().warningProfile().orElse(null)), p + "/args/recipe", "construction impact ownership/WARN mismatch");
            require(registry.descriptors().containsKey(c.compensation()), p + "/args/recipe", "unknown construction compensation");
            require(!c.affectedConstructionClasses().isEmpty(), p + "/args/recipe", "empty construction impact contract");
            c.affectedConstructionClasses().forEach(t -> tag(t, p + "/args/recipe"));
            c.excludedCodecs().forEach(x -> ref(Kind.CODEC, x.value(), p + "/args/recipe"));
        });
        long work = switch(s.args()) {
            case GlobalWeatherLease ignored -> 1;
            case RegionalWeatherLease ignored -> 1;
            case CampGrowth g -> g.maxBlocks();
            case Landslide a -> a.maxBlocks();
            case ControlledWater ignored -> m.maximumWork().mutationsInFlight();
            case ControlledFire ignored -> m.maximumWork().mutationsInFlight();
            default -> throw new InvalidContent(p + "/primitive", "not a registered mutation argument");
        };
        String bound = s.args() instanceof CampGrowth || s.args() instanceof Landslide ? "/args/max_blocks" : "/args/recipe";
        require(work > 0 && work <= m.maximumWork().mutationsInFlight(), p + bound, "recipe work bound");
        // A global weather lease has no spatial outside. Never fabricate a terrain escape route for it.
        // Its native bridge must warn every affected dimension client and fence external control.
        if (e.safety().risk() == Risk.CONTROLLED_DANGER && !(s.args() instanceof GlobalWeatherLease))
            require(!m.safeExits().isEmpty(), p + "/args/recipe", "danger requires safe exits");
    }
    private void propagationMask(BlobRef mask, PrimitiveDescriptor descriptor, String p) {
        require(mask.bytes() > 0 && mask.codecVersion() == 1 && descriptor.capturableCodecs().contains(mask.codecId()),
            p, "uncapturable propagation mask");
        ref(Kind.CODEC, mask.codecId().value(), p + "/codec_id");
        // A reference proves no geometry or hydration. J05 validates its exact bytes at capture.
    }
    void condition(Condition c, Map<DefinitionId, EventDefinition> events, String p) {
        switch (c) {
            case AllConditions a -> { for (int i = 0; i < a.conditions().size(); i++) condition(a.conditions().get(i), events, p + "/conditions/" + i); }
            case AnyConditions a -> { for (int i = 0; i < a.conditions().size(); i++) condition(a.conditions().get(i), events, p + "/conditions/" + i); }
            case NotCondition a -> condition(a.condition(), events, p + "/condition");
            case RegionTag a -> tag(a.tag(), p + "/tag");
            case MemoryFlag a -> tag(a.flag(), p + "/flag");
            case RiskMinimum a -> ref(Kind.RISK, a.riskId().value(), p + "/risk_id");
            case RequirementPending a -> require(events.containsKey(a.selector()), p + "/selector", "unknown requirement selector");
            default -> { /* Other sealed conditions contain no registry references. */ }
        }
    }
    public void chain(ChainDefinition c, Map<DefinitionId, EventDefinition> events) {
        require(mode != ProviderMode.PRODUCTION || c.calibration() == Calibration.APPROVED, "/calibration", "test chain in production");
        require(c.planningHorizon() > 0 && c.minimumStepGapTicks().value() > 0, "/planning_horizon", "chain horizon and gap must be positive");
        Map<String, List<String>> graph = new TreeMap<>(); c.states().forEach(s -> graph.put(s, new ArrayList<>()));
        Set<ResourceId> ids = new HashSet<>();
        for (int i = 0; i < c.transitions().size(); i++) {
            var t = c.transitions().get(i); String p = "/transitions/" + i;
            require(ids.add(t.id()), p + "/id", "duplicate transition");
            require(c.states().contains(t.from()) && c.states().contains(t.to()), p, "unknown chain state");
            require(!c.terminalStates().contains(t.from()), p + "/from", "terminal emits transition");
            require(!c.terminalStates().contains(t.to()) || t.eventId().isEmpty(), p + "/event_id", "terminal starts event");
            require(t.consumeFactOnce(), p + "/consume_fact_once", "transition must consume a fresh fact");
            t.eventId().ifPresent(id -> require(events.containsKey(id), p + "/event_id", "unknown chain event"));
            ref(Kind.FACT, t.onFact().value(), p + "/on_fact");
            t.settleStateTag().ifPresent(id -> tag(id, p + "/settle_state_tag"));
            condition(t.condition(), events, p + "/condition"); graph.get(t.from()).add(t.to());
        }
        Set<String> reachable = new HashSet<>(); ArrayDeque<String> pending = new ArrayDeque<>(); pending.add(c.entryState());
        while (!pending.isEmpty()) { String s = pending.remove(); if (reachable.add(s)) pending.addAll(graph.get(s)); }
        require(reachable.equals(c.states()), "/states", "unreachable chain state");
        if (c.mode() == ChainMode.SHORT_ARC) topological(graph, "/transitions");
        // Every state must have a possible exit to a terminal; dynamic loops remain legal.
        Set<String> exits = new HashSet<>(c.terminalStates()); boolean changed;
        do { changed = false; for (var edge : graph.entrySet()) if (edge.getValue().stream().anyMatch(exits::contains)) changed |= exits.add(edge.getKey()); } while (changed);
        require(exits.containsAll(c.states()), "/states", "chain has no terminal exit");
    }
    static List<String> topological(Map<String, List<String>> dependencies, String p) {
        Map<String, Integer> degrees = new TreeMap<>(); Map<String, List<String>> successors = new HashMap<>();
        dependencies.forEach((id, parents) -> {
            degrees.put(id, parents.size());
            for (String parent : parents) { require(dependencies.containsKey(parent), p, "unknown predecessor " + parent); successors.computeIfAbsent(parent, k -> new ArrayList<>()).add(id); }
        });
        PriorityQueue<String> ready = new PriorityQueue<>(); degrees.forEach((id, n) -> { if (n == 0) ready.add(id); });
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) { String id = ready.remove(); ordered.add(id); for (String next : successors.getOrDefault(id, List.of())) if (degrees.compute(next, (k, n) -> n - 1) == 0) ready.add(next); }
        require(ordered.size() == dependencies.size(), p, "cyclic graph"); return ordered;
    }
    private static Set<String> ancestors(String id, Map<String, List<String>> deps) {
        Set<String> result = new HashSet<>(); ArrayDeque<String> pending = new ArrayDeque<>(deps.get(id));
        while (!pending.isEmpty()) { String a = pending.remove(); if (result.add(a)) pending.addAll(deps.get(a)); } return result;
    }
    private static long minimumDelay(String target, String warning, Map<String, EventStep> steps, List<String> ordered) {
        Map<String, Long> delays = new HashMap<>(); delays.put(warning, 0L);
        for (String id : ordered) if (!id.equals(warning)) {
            EventStep s = steps.get(id); long parent = s.after().stream().filter(delays::containsKey).mapToLong(delays::get).max().orElse(-1);
            if (parent >= 0) delays.put(id, add(parent, s.delayTicks().value(), "/steps"));
        }
        return delays.getOrDefault(target, -1L);
    }
    private static long add(long a, long b, String p) { try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw new InvalidContent(p, "quantity overflow"); } }
    private static BudgetVector zero() { return new BudgetVector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
    private static BudgetVector plus(BudgetVector a, BudgetVector b, String p) {
        try { return new BudgetVector(a.tickMillis() + b.tickMillis(), a.globalIntensity() + b.globalIntensity(), a.regionalIntensity() + b.regionalIntensity(),
            add(a.liveEntities(), b.liveEntities(), p), add(a.persistentEntities(), b.persistentEntities(), p), add(a.mutationsInFlight(), b.mutationsInFlight(), p),
            add(a.queuedIoBytes(), b.queuedIoBytes(), p), a.clientUnits() + b.clientUnits(), add(a.payloadBytes(), b.payloadBytes(), p), add(a.navigationNodes(), b.navigationNodes(), p)); }
        catch (IllegalArgumentException e) { throw new InvalidContent(p, "budget overflow"); }
    }
}