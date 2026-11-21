package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;

/** Companion preparation, API projection and atomic generation regression tests. */
public final class ScoringMetadataTests {
    private static int assertions;
    private static void check(boolean ok, String why) { assertions++; if (!ok) throw new AssertionError(why); }
    private static final String PATH = "data/proutlost/director/scoring/safe_micro.json";
    private static final DefinitionId ID = new DefinitionId("proutlost:safe_micro");
    private static DefinitionLoader.Source source(JsonValue v) { return new DefinitionLoader.Source(PATH, CanonicalJsonV1.encode(v)); }
    private static JsonValue replace(JsonValue root, String key, JsonValue value) {
        Map<String, JsonValue> result = new TreeMap<>(object(root)); if (value == null) result.remove(key); else result.put(key, value); return obj(result);
    }
    private static void invalid(ScoringMetadataCompiler compiler, CatalogSnapshot catalog, ScoringMetadataCompiler.Prepared old,
            List<DefinitionLoader.Source> sources, String pointer, String why) {
        Fingerprint hash = old.hash(); var result = compiler.prepare(sources, catalog, Optional.of(old));
        check(result.prepared().isEmpty() && !result.diagnostics().isEmpty(), why + " rejected");
        check(result.diagnostics().getFirst().pointer().equals(pointer), why + " exact pointer: " + result.diagnostics());
        check(old.hash().equals(hash) && old.catalog() == catalog && old.metadata().containsKey(ID), why + " previous preparation intact");
    }
    public static void main(String[] args) throws Exception {
        var config = TestingConfig.load(Files.readAllBytes(Path.of("src/directorTest/resources/catalog/DEFAULT_FOR_TESTING.json"))).view();
        var limits = new DefinitionLoader.Limits(16, 65536, 1048576, 512, 512);
        var loader = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits);
        var event = new DefinitionLoader.Source("data/proutlost/director/events/safe_micro.json", Files.readAllBytes(Path.of("src/directorTest/resources/catalog/events/safe_micro.json")));
        check(loader.reload(List.of(event)).installed(), "definition baseline"); CatalogSnapshot catalog = loader.current();
        UnitValue neutral = new UnitValue(config.values().get("score.neutral_feature").value().doubleValue());
        Map<Feature, UnitValue> neutrals = Map.of(Feature.contextCompatibility, neutral, Feature.regionPersonality, neutral, Feature.globalTension, neutral, Feature.localIntensityFit, neutral);
        var compiler = new ScoringMetadataCompiler(config, Map.of(new ResourceId("test:players_present"), new ScoringMetadataCompiler.ContextBinding(new PlayersPresent(1), new UnitValue(1))), neutrals, ProviderMode.TEST_ONLY, limits);
        JsonValue full = CanonicalJsonV1.decode(Files.readAllBytes(Path.of("src/directorTest/resources/catalog/scoring/full.json")));
        var result = compiler.prepare(List.of(source(full)), catalog, Optional.empty());
        check(result.prepared().isPresent() && result.diagnostics().isEmpty(), "all declared scoring metadata compiles");
        var prepared = result.prepared().orElseThrow(); var m = prepared.metadata().get(ID);
        check(m.contextAffinities().orElseThrow().get(new ResourceId("test:players_present")).equals(neutral), "explicit context affinity retained");
        check(m.regionTraits().orElseThrow().get(RegionTrait.WILD).value() == 0.65, "known trait evaluated to configured value, not neutral");
        check(m.tensionAffinities().orElseThrow().get(GlobalTension.TENSE).value() == 0.15, "known tension value retained");
        check(m.desiredLocalIntensity().orElseThrow() == neutral.value() && m.habitationSensitive().orElseThrow(), "explicit intensity and habitation retained");
        check(prepared.neutralFeatures().equals(neutrals), "per-feature neutrals explicitly retained");
        var same = compiler.prepare(List.of(source(full)), catalog, Optional.of(prepared)).prepared().orElseThrow();
        check(same.hash().equals(prepared.hash()), "canonical hash deterministic");
        String spaced = new String(CanonicalJsonV1.encode(full), StandardCharsets.UTF_8).replace(",", ", ");
        check(compiler.prepare(List.of(new DefinitionLoader.Source(PATH, spaced.getBytes(StandardCharsets.UTF_8))), catalog, Optional.of(prepared)).prepared().orElseThrow().hash().equals(prepared.hash()), "wire whitespace cannot change hash");
        JsonValue sparse = full;
        for (String key : List.of("context_affinities", "region_traits", "tension_affinities", "desired_local_intensity", "habitation_sensitive")) sparse = replace(sparse, key, null);
        var optional = compiler.prepare(List.of(source(sparse)), catalog, Optional.empty()).prepared().orElseThrow().metadata().get(ID);
        check(optional.contextAffinities().isEmpty() && optional.regionTraits().isEmpty() && optional.tensionAffinities().isEmpty()
            && optional.desiredLocalIntensity().isEmpty() && optional.habitationSensitive().isEmpty(), "absence stays optional; no name or family inference");
        var absent = compiler.prepare(List.of(), catalog, Optional.of(prepared)).prepared().orElseThrow();
        check(absent.metadata().isEmpty() && absent.neutralFeatures().equals(neutrals), "absent companion carries only declared neutral policy");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "command", new JsonValue.Text("run")))), "/command", "unknown field");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "region_traits", obj(Map.of("UNKNOWN", new JsonValue.Number(java.math.BigDecimal.ONE)))))), "/region_traits/UNKNOWN", "unknown trait");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "tension_affinities", obj(Map.of("UNKNOWN", new JsonValue.Number(java.math.BigDecimal.ONE)))))), "/tension_affinities/UNKNOWN", "unknown tension");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "context_affinities", obj(Map.of("test:unknown", new JsonValue.Number(java.math.BigDecimal.ONE)))))), "/context_affinities/test:unknown", "unknown context");
        var unknown = replace(full, "definition_id", new JsonValue.Text("proutlost:unknown"));
        invalid(compiler, catalog, prepared, List.of(new DefinitionLoader.Source("data/proutlost/director/scoring/unknown.json", CanonicalJsonV1.encode(unknown))), "/definition_id", "unknown definition");
        invalid(compiler, catalog, prepared, List.of(source(unknown)), "/definition_id", "path mismatch");
        invalid(compiler, catalog, prepared, List.of(source(full), source(full)), "/definition_id", "duplicate companion");
        invalid(compiler, catalog, prepared, List.of(new DefinitionLoader.Source(PATH, "{\"schema_version\":1,\"schema_version\":1}".getBytes(StandardCharsets.UTF_8))), "", "duplicate JSON key");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "definition_revision", new JsonValue.Number(java.math.BigDecimal.TEN)))), "/definition_revision", "stale definition revision");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "calibration", new JsonValue.Text("APPROVED")))), "/calibration", "test calibration laundering");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "desired_local_intensity", new JsonValue.Text("@test:unknown")))), "/desired_local_intensity", "unknown profile key");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "desired_local_intensity", new JsonValue.Text("@test:score.log_min")))), "/desired_local_intensity", "negative resolved intensity");
        invalid(compiler, catalog, prepared, List.of(source(replace(full, "region_traits", obj(Map.of("WILD", new JsonValue.Text("@test:event.default.global_units")))))), "/region_traits/WILD", "resolved trait outside unit range");
        JsonValue changed = replace(full, "habitation_sensitive", new JsonValue.Bool(false));
        invalid(compiler, catalog, prepared, List.of(source(changed)), "/content_revision", "changed metadata at same revision");
        var incremented = replace(changed, "content_revision", new JsonValue.Number(java.math.BigDecimal.valueOf(2)));
        var newer = compiler.prepare(List.of(source(incremented)), catalog, Optional.of(prepared)).prepared().orElseThrow();
        check(!newer.hash().equals(prepared.hash()) && !newer.metadata().get(ID).habitationSensitive().orElseThrow(), "revision changes whole prepared hash");
        invalid(compiler, catalog, newer, List.of(source(full)), "/content_revision", "metadata revision regression");
        var removed = compiler.prepare(List.of(), catalog, Optional.of(newer)).prepared().orElseThrow();
        check(compiler.prepare(List.of(source(full)), catalog, Optional.of(removed)).prepared().isEmpty(), "removed metadata retains revision tombstone");
        check(compiler.prepare(List.of(source(incremented)), catalog, Optional.of(removed)).prepared().isPresent(), "identical revision can return after removal");
        check(loader.current() == catalog && prepared.catalog() == catalog, "preparation never publishes or alters CatalogPort");
        boolean immutable = false; try { prepared.metadata().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "prepared metadata immutable");
        var bindings = Map.of(new ResourceId("test:players_present"),
            new ScoringMetadataCompiler.ContextBinding(new PlayersPresent(1), new UnitValue(1)));
        var combinedLoader = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits, bindings, neutrals);
        var first = combinedLoader.reload(List.of(event, source(full)));
        check(first.installed(), "combined reload installs: " + first.diagnostics());
        CatalogSnapshot pinned = first.snapshot(); var profile = pinned.scoringProfiles().get(ID);
        check(profile != null && profile.definition().revision().equals(pinned.events().get(ID).contentRevision()), "same-generation definition and profile");
        check(profile.contextAffinities().equals(List.of(new ContextAffinity(new PlayersPresent(1), neutral, new UnitValue(1)))), "exact predicate affinity weight projection");
        check(profile.regionAffinities().get(RegionTrait.WILD).value() == .65 && profile.tensionAffinities().get(GlobalTension.TENSE).value() == .15, "API known trait and tension projection");
        check(profile.desiredLocalIntensity().orElseThrow().equals(neutral) && profile.habitationSensitiveFamilies().equals(pinned.events().get(ID).families()), "explicit intensity and sensitivity projection");
        check(combinedLoader.reload(List.of(source(full), event)).snapshot() == pinned, "combined canonical reload is idempotent");
        JsonValue eventJson = CanonicalJsonV1.decode(event.bytes());
        JsonValue event2 = replace(replace(eventJson, "content_revision", new JsonValue.Number(java.math.BigDecimal.valueOf(2))), "enabled", new JsonValue.Bool(false));
        var eventSource2 = new DefinitionLoader.Source(event.path(), CanonicalJsonV1.encode(event2));
        for (JsonValue bad : List.of(
                replace(full, "extra", new JsonValue.Bool(true)),
                replace(full, "context_affinities", obj(Map.of("missing:context", new JsonValue.Number(java.math.BigDecimal.ONE)))),
                replace(full, "region_traits", obj(Map.of("MISSING", new JsonValue.Number(java.math.BigDecimal.ONE)))),
                replace(full, "tension_affinities", obj(Map.of("MISSING", new JsonValue.Number(java.math.BigDecimal.ONE)))),
                replace(full, "desired_local_intensity", new JsonValue.Number(java.math.BigDecimal.TEN)), full)) {
            var rejected = combinedLoader.reload(List.of(eventSource2, source(bad)));
            check(!rejected.installed() && !rejected.diagnostics().isEmpty(), "invalid metadata rejects combined changes");
            check(combinedLoader.current() == pinned && rejected.snapshot() == pinned, "entire prior generation retained");
        }
        check(!combinedLoader.reload(List.of(event, source(full), source(full))).installed() && combinedLoader.current() == pinned, "duplicate companions preserve entire generation");
        var metadata2 = replace(replace(incremented, "definition_revision", new JsonValue.Number(java.math.BigDecimal.valueOf(2))), "desired_local_intensity", new JsonValue.Number(java.math.BigDecimal.valueOf(.25)));
        var second = combinedLoader.reload(List.of(eventSource2, source(metadata2)));
        check(second.installed(), "valid combined revision installs: " + second.diagnostics());
        check(second.snapshot().generation().value() == pinned.generation().value() + 1 && !second.snapshot().hash().equals(pinned.hash()), "one combined generation and hash change");
        check(!second.snapshot().events().get(ID).enabled() && second.snapshot().scoringProfiles().get(ID).desiredLocalIntensity().orElseThrow().value() == .25, "definition and metadata change together");
        check(pinned.events().get(ID).enabled() && pinned.scoringProfiles().get(ID) == profile && profile.desiredLocalIntensity().orElseThrow().equals(neutral), "pinned old generation retains original metadata and definition");
        check(!profile.profileHash().equals(second.snapshot().scoringProfiles().get(ID).profileHash()), "changed profile hash");
        check(!combinedLoader.reload(List.of(eventSource2, source(full))).installed() && combinedLoader.current() == second.snapshot(), "stale companion cannot replace entire generation");
        var removedSnapshot = combinedLoader.reload(List.of(eventSource2)).snapshot();
        check(removedSnapshot.scoringProfiles().isEmpty() && pinned.scoringProfiles().containsKey(ID), "optional removal publishes atomically without mutating pinned profile");
        check(!combinedLoader.reload(List.of(eventSource2, source(replace(metadata2, "content_revision", new JsonValue.Number(java.math.BigDecimal.ONE))))).installed(), "publication retains removed profile revision tombstone");
        check(combinedLoader.reload(List.of(eventSource2, source(metadata2))).installed(), "same profile revision can return after removal");
        boolean profilesImmutable = false; try { pinned.scoringProfiles().clear(); } catch (UnsupportedOperationException expected) { profilesImmutable = true; }
        check(profilesImmutable, "published profiles immutable");
        var noBindings = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits);
        check(!noBindings.reload(List.of(event, source(full))).installed() && noBindings.current().generation().value() == 0, "unknown registry context fails default loader");
        var changedBindings = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits,
            Map.of(new ResourceId("test:players_present"), new ScoringMetadataCompiler.ContextBinding(new PlayersPresent(2), new UnitValue(.5))), neutrals);
        var rebound = changedBindings.reload(List.of(event, source(full)));
        check(rebound.installed() && !rebound.snapshot().hash().equals(pinned.hash()), "predicate and weight registry bindings enter combined hash");
        var badBindings = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits,
            Map.of(new ResourceId("test:players_present"), new ScoringMetadataCompiler.ContextBinding(new RequirementPending(new DefinitionId("missing:event")), new UnitValue(1))), neutrals);
        var badBindingResult = badBindings.reload(List.of(event, source(full)));
        check(!badBindingResult.installed() && badBindings.current().generation().value() == 0 && !badBindingResult.diagnostics().isEmpty(), "unknown predicate reference prevents publication");
        check(!rebound.snapshot().scoringProfiles().get(ID).profileHash().equals(profile.profileHash()), "compiled profile hash includes resolved predicate and weight");
        var beforeOnly = combinedLoader.current();
        var metadata3 = replace(replace(metadata2, "content_revision", new JsonValue.Number(java.math.BigDecimal.valueOf(3))), "desired_local_intensity", new JsonValue.Number(java.math.BigDecimal.valueOf(.75)));
        var invalidEvent = new DefinitionLoader.Source(event.path(), CanonicalJsonV1.encode(replace(event2, "extra", new JsonValue.Bool(true))));
        check(!combinedLoader.reload(List.of(invalidEvent, source(metadata3))).installed() && combinedLoader.current() == beforeOnly, "invalid definition cannot publish valid newer metadata");
        var onlyMetadata = combinedLoader.reload(List.of(eventSource2, source(metadata3)));
        check(onlyMetadata.installed() && onlyMetadata.snapshot().events().equals(beforeOnly.events()) && !onlyMetadata.snapshot().hash().equals(beforeOnly.hash()), "metadata-only change creates combined hash");
        check(onlyMetadata.snapshot().generation().value() == beforeOnly.generation().value() + 1 && onlyMetadata.snapshot().scoringProfiles().get(ID).desiredLocalIntensity().orElseThrow().value() == .75, "metadata-only change publishes exactly one generation");
        check(beforeOnly.scoringProfiles().get(ID).desiredLocalIntensity().orElseThrow().value() == .25, "metadata-only reload retains pinned prior metadata");
        var raceLoader = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits, bindings, neutrals);
        check(raceLoader.reload(List.of(event, source(full))).installed(), "concurrent test initial generation");
        var ready = new java.util.concurrent.CountDownLatch(2); var start = new java.util.concurrent.CountDownLatch(1);
        var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<DefinitionLoader.ReloadResult>();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        List<Thread> writers = new ArrayList<>();
        for (JsonValue variant : List.of(metadata2, replace(metadata2, "desired_local_intensity", new JsonValue.Number(java.math.BigDecimal.valueOf(.9))))) {
            Thread thread = new Thread(() -> { try { ready.countDown(); start.await(); outcomes.add(raceLoader.reload(List.of(eventSource2, source(variant)))); } catch (Throwable e) { failures.add(e); } });
            writers.add(thread); thread.start();
        }
        ready.await(); start.countDown(); for (Thread thread : writers) thread.join();
        check(failures.isEmpty() && outcomes.size() == 2 && outcomes.stream().filter(DefinitionLoader.ReloadResult::installed).count() == 1, "competing divergent same revisions have exactly one winner");
        var winner = outcomes.stream().filter(DefinitionLoader.ReloadResult::installed).findFirst().orElseThrow().snapshot();
        check(raceLoader.current() == winner && winner.generation().value() == 2 && winner.scoringProfiles().get(ID).definition().revision().equals(winner.events().get(ID).contentRevision()), "concurrent winner is one complete generation");
        System.out.println("PASS scoring companion preparation/projection/atomic publication assertions=" + assertions);
        System.out.println("PREPARED_HASH=" + prepared.hash().value());
        System.out.println("NOT_RUN J03 evaluation, native clients, audio, physics and server");
    }
}
