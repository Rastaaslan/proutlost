package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import static fr.proutlost.director.catalog.ContentJson.*;

public final class CatalogTests {
    private static int assertions;
    private static final List<String> cases = new ArrayList<>();
    private static ConfigView config;
    private static List<DefinitionLoader.Source> sources;
    private static final DefinitionLoader.Limits LIMITS = new DefinitionLoader.Limits(256, 65536, 4 * 1024 * 1024, 512, 512);
    private static void check(boolean result, String why) { assertions++; if (!result) throw new AssertionError(why); }
    private static void rejects(Runnable task, String why) { boolean failed = false; try { task.run(); } catch (IllegalArgumentException | UnsupportedOperationException e) { failed = true; } check(failed, why); cases.add(why); }
    private static DefinitionLoader loader() { return new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, LIMITS); }
    private static JsonValue number(long v) { return new JsonValue.Number(java.math.BigDecimal.valueOf(v)); }
    private static JsonValue str(String v) { return new JsonValue.Text(v); }
    private static JsonValue edit(JsonValue root, String pointer, JsonValue replacement) {
        if (pointer.isEmpty()) return replacement;
        String[] path = pointer.substring(1).split("/", 2); String key = path[0]; String rest = path.length == 1 ? "" : "/" + path[1];
        if (root instanceof JsonValue.ObjectValue o) {
            var next = new TreeMap<>(o.values());
            if (rest.isEmpty() && replacement == null) next.remove(key); else next.put(key, edit(next.get(key), rest, replacement)); return obj(next);
        }
        var next = new ArrayList<>(array(root)); int index = Integer.parseInt(key); next.set(index, edit(next.get(index), rest, replacement)); return new JsonValue.Array(next);
    }
    private static List<DefinitionLoader.Source> mutate(String name, UnaryOperator<JsonValue> change) {
        return sources.stream().map(s -> s.path().endsWith("/" + name + ".json") ? new DefinitionLoader.Source(s.path(), CanonicalJsonV1.encode(change.apply(CanonicalJsonV1.decode(s.bytes())))) : s).toList();
    }
    private static void invalid(String caseName, String name, String pointer, JsonValue replacement) { invalid(caseName, mutate(name, root -> edit(root, pointer, replacement))); }
    private static void invalid(String name, List<DefinitionLoader.Source> bad) {
        DefinitionLoader loader = loader(); var installed = loader.reload(sources); check(installed.installed(), "baseline " + installed.diagnostics());
        CatalogSnapshot before = loader.current(); var rejected = loader.reload(bad);
        check(!rejected.installed() && !rejected.diagnostics().isEmpty(), name + " rejected");
        check(loader.current() == before && rejected.snapshot() == before, name + " atomic rejection");
        check(rejected.diagnostics().stream().allMatch(d -> d.pointer() != null && d.file() != null && d.code() == fr.proutlost.director.api.error.ReasonCode.CATALOG_INVALID), name + " diagnostics"); cases.add(name);
    }
    public static void main(String[] args) throws Exception {
        Path fixtureRoot = Path.of("src/directorTest/resources/catalog");
        config = TestingConfig.load(Files.readAllBytes(fixtureRoot.resolve("DEFAULT_FOR_TESTING.json"))).view();
        List<DefinitionLoader.Source> input = new ArrayList<>();
        for (String kind : List.of("events", "chains")) try (var paths = Files.list(fixtureRoot.resolve(kind))) {
            for (Path path : paths.sorted().toList()) input.add(new DefinitionLoader.Source("data/proutlost/director/" + kind + "/" + path.getFileName(), Files.readAllBytes(path)));
        }
        sources = List.copyOf(input);
        DefinitionLoader loader = loader(); var loaded = loader.reload(sources);
        check(loaded.installed(), "nine events/two chains: " + loaded.diagnostics()); CatalogSnapshot first = loader.current();
        check(first.events().size() == 9 && first.chains().size() == 2 && first.generation().value() == 1, "MVP counts");
        Set<Family> families = new HashSet<>(); first.events().values().forEach(e -> families.addAll(e.families()));
        check(families.containsAll(EnumSet.allOf(Family.class)), "ten families plus micro");
        check(first.events().values().stream().map(EventDefinition::category).collect(java.util.stream.Collectors.toSet()).equals(EnumSet.allOf(Category.class)), "three categories");
        check(first.events().values().stream().map(e -> e.uniqueness().scope()).collect(java.util.stream.Collectors.toSet()).equals(EnumSet.allOf(UniquenessScope.class)), "four uniqueness scopes");
        check(loader.reload(sources).snapshot() == first, "identical reload idempotent");
        List<DefinitionLoader.Source> reverse = new ArrayList<>(sources); Collections.reverse(reverse);
        check(loader().reload(reverse).snapshot().hash().equals(first.hash()), "source order deterministic hash");
        EventDefinition micro = first.events().get(new DefinitionId("proutlost:safe_micro"));
        check(micro.selection().baseWeight() == 0.5 && micro.timing().maxDurationTicks().value() == 80, "typed test parameters");
        check(((TargetSound) micro.steps().getFirst().args()).sound().value().equals("minecraft:block.campfire.crackle"), "typed primitive codec");
        check(micro.budget().queuedIoBytes() == 64 && micro.budget().payloadBytes() == 32, "registry extra budgets retained");
        check(first.chains().get(new DefinitionId("proutlost:living_scar")).mode() == ChainMode.DYNAMIC, "dynamic loop compiled");
        check(first.events().get(new DefinitionId("proutlost:controlled_landslide")).steps().getFirst().id().equals("warn"), "WARN topological order");
        for (EventDefinition e : first.events().values()) {
            var codec = new ModelCodec<>(EventDefinition.class); byte[] bytes = codec.encode(e);
            byte[] golden = Files.readString(fixtureRoot.resolve("golden/" + e.id().value().split(":")[1] + ".json")).strip().getBytes(StandardCharsets.UTF_8);
            check(Arrays.equals(bytes, golden), "fixed event golden " + e.id());
            check(codec.decode(golden).equals(e), "golden decodes to compiled event " + e.id());
            check(codec.decode(bytes).equals(e), "event persistence roundtrip " + e.id());
            check(Arrays.equals(codec.encode(codec.decode(bytes)), bytes), "event golden canonical stability " + e.id());
        }
        for (ChainDefinition c : first.chains().values()) {
            var codec = new ModelCodec<>(ChainDefinition.class);
            byte[] golden = Files.readString(fixtureRoot.resolve("golden/" + c.id().value().split(":")[1] + ".json")).strip().getBytes(StandardCharsets.UTF_8);
            check(Arrays.equals(codec.encode(c), golden), "fixed chain golden " + c.id());
            check(codec.decode(golden).equals(c), "golden decodes to compiled chain");
            check(codec.decode(codec.encode(c)).equals(c), "chain persistence roundtrip");
        }
        cases.add("eleven independently constructed codec goldens");
        var revised = mutate("safe_micro", root -> edit(edit(root, "/content_revision", number(2)), "/selection/base_weight", number(2)));
        check(loader.reload(revised).installed(), "revision increment installs");
        check(loader.current().generation().value() == 2 && loader.current().events().get(micro.id()).selection().baseWeight() == 2, "new admissions new definition");
        check(first.events().get(micro.id()) == micro && micro.contentRevision().value() == 1 && micro.selection().baseWeight() == 0.5, "pinned old instance unchanged");
        check(!loader.reload(sources).installed(), "revision regression rejected");
        check(loader.reload(List.of()).installed() && loader.current().events().isEmpty(), "definition removal");
        check(!loader.reload(sources).installed(), "removed revision tombstone retained");
        cases.add("T-CAT-02 reload/pinning/revisions");
        invalid("unknown field", "safe_micro", "/command", str("run"));
        var pointerResult = loader().reload(mutate("safe_micro", root -> edit(root, "/steps/0/args/role", str("absent"))));
        check(pointerResult.diagnostics().getFirst().pointer().equals("/steps/0/args/role"), "semantic diagnostic exact pointer");
        var primitivePointer = loader().reload(mutate("safe_micro", root -> edit(root, "/steps/0/primitive", str("proutlost:absent"))));
        check(primitivePointer.diagnostics().getFirst().pointer().equals("/steps/0/primitive"), "schema diagnostic exact discriminator pointer");
        invalid("unknown primitive", "safe_micro", "/steps/0/primitive", str("proutlost:unknown"));
        invalid("unknown nested argument", "safe_micro", "/steps/0/args/command", str("run"));
        invalid("missing duration", "safe_micro", "/timing/max_duration_ticks", null);
        invalid("missing role", "safe_micro", "/steps/0/args/role", str("absent"));
        invalid("role range", "safe_micro", "/target/roles/witness/min", number(2));
        invalid("solo role capacity", "safe_micro", "/target/roles/witness/max", number(2));
        invalid("cyclic steps", "safe_micro", "/steps/0/after", new JsonValue.Array(List.of(str("sound"))));
        invalid("unknown predecessor", "safe_micro", "/steps/0/after", new JsonValue.Array(List.of(str("absent"))));
        invalid("fractional ticks", "safe_micro", "/timing/max_duration_ticks", new JsonValue.Number(new java.math.BigDecimal("1.5")));
        invalid("typed fractional test parameter", "safe_micro", "/steps/0/delay_ticks", str("@test:event.micro.volume"));
        invalid("negative parameter", "safe_micro", "/steps/0/delay_ticks", str("@test:score.log_min"));
        invalid("probability overflow", "required_signal", "/completion/persistence_probability", number(2));
        invalid("unknown parameter", "safe_micro", "/selection/base_weight", str("@test:missing"));
        invalid("test calibration laundering", "safe_micro", "/calibration", str("APPROVED"));
        invalid("unknown asset", "safe_micro", "/steps/0/args/sound", str("minecraft:absent"));
        invalid("wrong reference kind", "safe_micro", "/steps/0/args/sound", str("proutlost:clearing"));
        invalid("unknown capability", "safe_micro", "/safety/required_capabilities", new JsonValue.Array(List.of(str("missing.capability"))));
        invalid("omitted primitive capability", "safe_micro", "/safety/required_capabilities", new JsonValue.Array(List.of()));
        invalid("unknown claim", "safe_micro", "/conflicts/claims", new JsonValue.Array(List.of(str("UNKNOWN:COMMAND"))));
        invalid("unknown tag", "camp_regrowth", "/steps/0/args/anchor_tag", str("proutlost:missing"));
        invalid("unknown recipe", "camp_regrowth", "/steps/0/args/recipe", str("proutlost:missing"));
        invalid("unknown fact", "safe_micro", "/completion/fact_type", str("proutlost:missing"));
        invalid("unknown selector", "required_signal", "/eligibility/selector", str("proutlost:missing"));
        invalid("unknown merge", "safe_micro", "/conflicts/allow_merge_with", new JsonValue.Array(List.of(str("proutlost:local_mist"))));
        invalid("entity underbudget", "animal_migration", "/budget/entity_slots", number(0));
        invalid("mutation underbudget", "controlled_landslide", "/budget/mutation_units", number(0));
        invalid("growth underbudget", "camp_regrowth", "/budget/mutation_units", number(0));
        invalid("primitive minimum budget", "safe_micro", "/budget/server_ms_per_tick", number(0));
        invalid("missing WARN", "controlled_landslide", "/steps/1/after", new JsonValue.Array(List.of()));
        invalid("insufficient warning lead", "controlled_landslide", "/steps/1/delay_ticks", number(399));
        invalid("warning below profile", "controlled_landslide", "/steps/0/args/minimum_lead_ticks", number(0));
        invalid("skippable WARN", "controlled_landslide", "/steps/0/on_failure", str("SKIP_SAFE"));
        invalid("danger declared safe", "controlled_landslide", "/safety/risk", str("SAFE"));
        invalid("missing warning profile", "controlled_landslide", "/safety/warning_profile", JsonValue.Nil.INSTANCE);
        invalid("unknown damage profile", "controlled_landslide", "/steps/1/args/damage_profile", str("proutlost:missing"));
        invalid("persistent trace deletion", "required_signal", "/completion/persistence_probability", number(0));
        invalid("aggregate entity budget", mutate("goat_guest", root -> {
            JsonValue step = array(object(root).get("steps")).getFirst();
            return edit(root, "/steps", new JsonValue.Array(List.of(step, edit(step, "/id", str("second")))));
        }));
        invalid("duplicate step ID", mutate("safe_micro", root -> {
            JsonValue step = array(object(root).get("steps")).getFirst(); return edit(root, "/steps", new JsonValue.Array(List.of(step, step)));
        }));
        invalid("cumulative delay", mutate("parallel_steps", root -> edit(edit(root, "/steps/0/delay_ticks", number(1000)), "/steps/1/delay_ticks", number(300))));
        invalid("duration overflow", "parallel_steps", "/steps/1/delay_ticks", number(Long.MAX_VALUE));
        invalid("duration path", "parallel_steps", "/steps/1/delay_ticks", number(1150));
        invalid("content revision reused", "safe_micro", "/selection/base_weight", number(3));
        invalid("ID mismatch", "safe_micro", "/id", str("proutlost:other"));
        invalid("ID traversal", "safe_micro", "/id", str("proutlost:../other"));
        invalid("unknown chain event", "camp_short_arc", "/transitions/0/event_id", str("proutlost:missing"));
        invalid("unknown chain fact", "camp_short_arc", "/transitions/0/on_fact", str("proutlost:missing"));
        invalid("unknown chain state", "camp_short_arc", "/transitions/0/to", str("MISSING"));
        invalid("duplicate transition", "camp_short_arc", "/transitions/1/id", str("proutlost:camp_to_witness"));
        invalid("terminal starts event", "camp_short_arc", "/transitions/2/event_id", str("proutlost:safe_micro"));
        invalid("short arc cycle", "camp_short_arc", "/transitions/1/to", str("CAMP_CHANGED"));
        invalid("zero chain gap", "living_scar", "/minimum_step_gap_ticks", number(0));
        invalid("excess planning horizon", "living_scar", "/planning_horizon", number(513));
        invalid("zero planning horizon", "living_scar", "/planning_horizon", number(0));
        invalid("reconsumed fact", "living_scar", "/transitions/2/consume_fact_once", new JsonValue.Bool(false));
        invalid("unreachable state", "camp_short_arc", "/states", new JsonValue.Array(List.of(str("CAMP_CHANGED"), str("WITNESS_READY"), str("SHARED_CHOICE"), str("COMPLETE"), str("ISOLATED"))));
        List<DefinitionLoader.Source> duplicate = new ArrayList<>(sources); duplicate.add(sources.getFirst()); invalid("duplicate document", duplicate);
        invalid("duplicate JSON key", replaceRaw("safe_micro", "{\"id\":1,\"id\":2}".getBytes(StandardCharsets.UTF_8)));
        invalid("trailing text", replaceRaw("safe_micro", "{} false".getBytes(StandardCharsets.UTF_8)));
        invalid("invalid UTF8", replaceRaw("safe_micro", new byte[]{(byte)0xc0, (byte)0xaf}));
        invalid("nonfinite", replaceRaw("safe_micro", "{\"a\":NaN}".getBytes(StandardCharsets.UTF_8)));
        invalid("excess depth", replaceRaw("safe_micro", ("[".repeat(34) + "0" + "]".repeat(34)).getBytes(StandardCharsets.UTF_8)));
        invalid("document byte limit", replaceRaw("safe_micro", new byte[65537]));
        invalid("escaped duplicate JSON key", replaceRaw("safe_micro", "{\"a\":1,\"\\u0061\":2}".getBytes(StandardCharsets.UTF_8)));
        invalid("huge exponent", replaceRaw("safe_micro", "{\"a\":1e99999}".getBytes(StandardCharsets.UTF_8)));
        rejects(() -> new ContentSchema("{\"unsupported\":true}".getBytes(StandardCharsets.UTF_8)), "unknown schema keyword fails closed");
        rejects(() -> new ContentSchema("{\"$ref\":\"https://example.invalid/schema\"}".getBytes(StandardCharsets.UTF_8)), "external schema references rejected");
        rejects(() -> first.events().clear(), "snapshot immutable"); rejects(() -> micro.steps().clear(), "steps immutable");
        rejects(() -> CatalogFixtures.primitives().descriptors().clear(), "registry immutable");
        var primitive = CatalogFixtures.primitives().descriptors().values().iterator().next();
        rejects(() -> new PrimitiveRegistry(List.of(primitive, primitive), ProviderMode.TEST_ONLY), "duplicate primitive registration");
        rejects(() -> new PrimitiveRegistry(List.of(primitive), ProviderMode.PRODUCTION), "production rejects fake primitive");
        var productionPrimitive = new PrimitiveDescriptor(primitive.id(),primitive.version(),primitive.argumentCodec(),primitive.capabilities(),primitive.minimumCost(),primitive.footprintResolver(),primitive.capturableCodecs(),primitive.ownershipDomains(),primitive.noninvertibleEffects(),primitive.maximumDuration(),primitive.pausePolicy(),primitive.recovery(),primitive.cleanup(),primitive.inverse(),primitive.merge(),ProviderMode.PRODUCTION);
        rejects(() -> new PrimitiveRegistry(List.of(productionPrimitive), ProviderMode.TEST_ONLY), "testing rejects production primitive");
        rejects(() -> new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.PRODUCTION, LIMITS), "production rejects test config");
        ConfigView approved = new ConfigView(config.revision(), config.provenanceHash(), Calibration.APPROVED, config.values());
        rejects(() -> new DefinitionLoader(approved, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.PRODUCTION, LIMITS), "production rejects test registries");
        var absent = new DefinitionLoader(config, new PrimitiveRegistry(List.of(), ProviderMode.TEST_ONLY), CatalogFixtures.references(), ProviderMode.TEST_ONLY, LIMITS);
        check(!absent.reload(sources).installed(), "no primitive provider never no-op");
        damageTypeReferences();
        ControlledDisasterCatalogTests.run(config,sources);
        GlobalWeatherCatalogTests.run(config,sources);
        RegionalWeatherCatalogTests.run(config,sources);
        var refs = CatalogFixtures.references();
        var missingCapabilities = new ContentReferences(refs.mode(), refs.known(), refs.mutations(), refs.warnings(), refs.damages(), Set.of(), refs.claims());
        check(!new DefinitionLoader(config, CatalogFixtures.primitives(), missingCapabilities, ProviderMode.TEST_ONLY, LIMITS).reload(sources).installed(), "capability absence rejected");
        var small = new DefinitionLoader(config, CatalogFixtures.primitives(), refs, ProviderMode.TEST_ONLY, new DefinitionLoader.Limits(1, 65536, 65536, 1, 1));
        check(!small.reload(sources).installed(), "document count bound");
        var totalBound = new DefinitionLoader(config, CatalogFixtures.primitives(), refs, ProviderMode.TEST_ONLY, new DefinitionLoader.Limits(256, 4096, 4096, 512, 512));
        check(!totalBound.reload(sources).installed(), "aggregate byte bound");
        var stepBound = new DefinitionLoader(config, CatalogFixtures.primitives(), refs, ProviderMode.TEST_ONLY, new DefinitionLoader.Limits(256, 65536, 4 * 1024 * 1024, 1, 1));
        check(!stepBound.reload(sources).installed(), "step/transition bound");
        Path pack = Path.of("build/J02/pack"); Path document = pack.resolve("data/proutlost/director/events/safe_micro.json"); Files.createDirectories(document.getParent());
        byte[] microSource = sources.stream().filter(s -> s.path().endsWith("/safe_micro.json")).findFirst().orElseThrow().bytes();
        Files.write(document, microSource);
        check(Arrays.equals(loader.readDocument(pack, "data/proutlost/director/events/safe_micro.json").bytes(), microSource), "bounded pack document read");
        rejects(() -> { try { loader.readDocument(pack, "../outside.json"); } catch (java.io.IOException e) { throw new AssertionError(e); } }, "pack traversal rejected");
        Path link = document.resolveSibling("symlink.json");
        boolean symlinkFixtureAvailable = Files.isSymbolicLink(link);
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createSymbolicLink(link, document.getFileName());
                symlinkFixtureAvailable = true;
            } catch (java.nio.file.FileSystemException | UnsupportedOperationException e) {
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) throw e;
                System.out.println("SKIP T-CAT symlink adversarial case: Windows denied creation of the test link");
            }
        }
        if (symlinkFixtureAvailable)
            rejects(() -> { try { loader.readDocument(pack, "data/proutlost/director/events/symlink.json"); } catch (java.io.IOException e) { throw new AssertionError(e); } }, "pack symlink rejected");
        Files.write(document, new byte[65537]);
        rejects(() -> { try { loader.readDocument(pack, "data/proutlost/director/events/safe_micro.json"); } catch (java.io.IOException e) { throw new AssertionError(e); } }, "bounded stream read rejects excess bytes");
        byte[] mutable = sources.getFirst().bytes(); var sourceCopy = new DefinitionLoader.Source(sources.getFirst().path(), mutable); mutable[0] = 0;
        check(sourceCopy.bytes()[0] == '{', "source defensive copy"); byte[] leaked = sourceCopy.bytes(); leaked[0] = 0; check(sourceCopy.bytes()[0] == '{', "source accessor copy");
        System.out.println("PASS T-CAT assertions=" + assertions + " named_cases=" + cases.size());
        cases.forEach(name -> System.out.println("PASS " + name));
        System.out.println("CATALOG_HASH=" + first.hash().value());
        System.out.println("NOT_RUN Gradle/NeoForge/GameTest/native clients/real physical capabilities (no Work slot; J02 pure domain only)");
    }
    private static void damageTypeReferences() {
        var refs = CatalogFixtures.references();
        var profile = refs.damages().get(CatalogFixtures.id("test_debris_damage"));
        check(loader().reload(sources).installed(), "registered damage type accepted");
        cases.add("registered damage type accepted");
        // Include a known entry alongside the unknown one: every entry must resolve,
        // and an ID registered only as a sound must not qualify as a damage type.
        for (Set<ResourceId> types : List.of(
                Set.of(new ResourceId("missing:damage")),
                Set.of(new ResourceId("minecraft:falling_block"), new ResourceId("missing:damage")),
                Set.of(new ResourceId("minecraft:block.gravel.step")))) {
            var badProfile = new DamageProfile(profile.id(), types, profile.perHit(), profile.maxPerPlayer(),
                profile.hitGap(), profile.excludedZones(), profile.pauseReasons(), profile.hitLedgerCodec());
            var badRefs = new ContentReferences(refs.mode(), refs.known(), refs.mutations(), refs.warnings(),
                Map.of(badProfile.id(), badProfile), refs.capabilities(), refs.claims());
            var candidateLoader = new DefinitionLoader(config, CatalogFixtures.primitives(), badRefs, ProviderMode.TEST_ONLY, LIMITS);
            var baseline = candidateLoader.reload(sources.stream().filter(s -> s.path().endsWith("/safe_micro.json")).toList());
            check(baseline.installed(), "non-danger baseline installs with unused damage profile");
            var before = candidateLoader.current();
            var rejected = candidateLoader.reload(sources);
            check(!rejected.installed(), "unknown type inside registered damage profile rejected");
            check(candidateLoader.current() == before && rejected.snapshot() == before, "damage type rejection preserves prior snapshot");
            check(before.events().size() == 1 && before.generation().value() == 1, "prior catalogue remains populated and unchanged");
            check(rejected.diagnostics().size() == 1, "single damage type diagnostic");
            var diagnostic = rejected.diagnostics().getFirst();
            check(diagnostic.file().equals("data/proutlost/director/events/controlled_landslide.json")
                && diagnostic.pointer().equals("/steps/1/args/damage_profile")
                && diagnostic.code() == fr.proutlost.director.api.error.ReasonCode.CATALOG_INVALID, "damage type diagnostic location and reason");
            String unknown = types.contains(new ResourceId("missing:damage")) ? "missing:damage" : "minecraft:block.gravel.step";
            check(diagnostic.detail().equals("unknown DAMAGE_TYPE reference: " + unknown), "damage type diagnostic identifies unresolved ID");
            cases.add("damage type rejection and atomic preservation: " + types.stream().map(ResourceId::value).sorted().toList());
        }
    }
    private static List<DefinitionLoader.Source> replaceRaw(String name, byte[] bytes) { return sources.stream().map(s -> s.path().endsWith("/" + name + ".json") ? new DefinitionLoader.Source(s.path(), bytes) : s).toList(); }
}
