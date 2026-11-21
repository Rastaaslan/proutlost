package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import java.io.*;
import java.security.*;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;

/** Exercises shipped classpath resources; TEST_ONLY providers attest format contracts, not effects. */
public final class RuntimeCandidateTests {
    private static int assertions;
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static byte[] resource(String path) throws IOException {
        try (InputStream in = RuntimeCandidateTests.class.getResourceAsStream("/" + path)) {
            if (in == null) throw new IOException("missing classpath resource " + path);
            byte[] bytes = in.readNBytes(CanonicalJsonV1.MAX_BYTES + 1);
            check(bytes.length <= CanonicalJsonV1.MAX_BYTES, "resource bounded"); return bytes;
        }
    }
    private static String hash(byte[] bytes) throws NoSuchAlgorithmException { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static void main(String[] args) throws Exception {
        var manifest = object(CanonicalJsonV1.decode(resource("data/proutlost_director/director/candidate-manifest.json")));
        check(text(manifest.get("status")).equals("CANDIDATE") && text(manifest.get("calibration")).equals("DEFAULT_FOR_TESTING"), "candidate test provenance");
        check(manifest.get("production_approved").equals(new JsonValue.Bool(false)), "not gameplay approved");
        byte[] profileBytes = resource(text(manifest.get("profile_resource")));
        check(hash(profileBytes).equals(text(manifest.get("profile_sha256"))), "profile manifest digest");
        check(hash(profileBytes).equals(TestingConfig.SOURCE_SHA256), "original test profile byte exact");
        ConfigView config = TestingConfig.load(profileBytes).view();
        List<DefinitionLoader.Source> sources = new ArrayList<>(); Set<DefinitionId> ids = new HashSet<>();
        for (var item : array(manifest.get("definitions"))) {
            var entry = object(item); String path = text(entry.get("resource")); byte[] bytes = resource(path);
            check(path.startsWith("data/proutlost_director/director/"), "runtime namespace");
            check(hash(bytes).equals(text(entry.get("sha256"))), "runtime resource manifest hash");
            var document = object(CanonicalJsonV1.decode(bytes)); var id = new DefinitionId(text(document.get("id")));
            check(id.value().equals(text(entry.get("definition_id"))) && ids.add(id), "unique declared ID");
            check(text(document.get("calibration")).equals("DEFAULT_FOR_TESTING"), "test calibration on each definition");
            sources.add(new DefinitionLoader.Source(path, bytes));
        }
        var limits = new DefinitionLoader.Limits(64, 65536, 1048576, 512, 512);
        var loader = new DefinitionLoader(config, CatalogFixtures.primitives(), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits);
        var loaded = loader.reload(sources); check(loaded.installed(), "runtime resources load: " + loaded.diagnostics());
        var snapshot = loader.current(); check(snapshot.events().size() == 9 && snapshot.chains().size() == 2, "candidate counts");
        Set<Family> families = EnumSet.noneOf(Family.class);
        snapshot.events().values().forEach(e -> families.addAll(e.families()));
        check(families.containsAll(EnumSet.allOf(Family.class)), "ten runtime families plus micro");
        for (var chain : snapshot.chains().values()) for (var transition : chain.transitions()) {
            transition.eventId().ifPresent(id -> check(snapshot.events().containsKey(id) && id.value().startsWith("proutlost_director:candidate/"), "candidate chain event binding"));
        }
        var required = snapshot.events().get(new DefinitionId("proutlost_director:candidate/required_signal"));
        check(((RequirementPending) required.eligibility()).selector().equals(required.id()), "candidate requirement self reference");
        for (var event : snapshot.events().values()) for (var step : event.steps()) {
            check(step.primitive().value().startsWith("proutlost:"), "normative primitive IDs retained for Work binding");
        }
        check(loader.reload(sources).snapshot() == snapshot, "candidate reload idempotent");
        var missing = new DefinitionLoader(config, new PrimitiveRegistry(List.of(), ProviderMode.TEST_ONLY), CatalogFixtures.references(), ProviderMode.TEST_ONLY, limits);
        check(!missing.reload(sources).installed(), "candidate does not invent providers");
        var references = CatalogFixtures.references(); var known = new EnumMap<ContentReferences.Kind, Set<ResourceId>>(ContentReferences.Kind.class);
        known.putAll(references.known()); known.put(ContentReferences.Kind.MODEL, Set.of());
        var missingAssets = new ContentReferences(references.mode(), known, references.mutations(), references.warnings(), references.damages(), references.capabilities(), references.claims());
        check(!new DefinitionLoader(config, CatalogFixtures.primitives(), missingAssets, ProviderMode.TEST_ONLY, limits).reload(sources).installed(), "candidate rejects unavailable asset contracts");
        List<DefinitionLoader.Source> invalid = new ArrayList<>(sources);
        var original = invalid.getFirst(); var broken = new TreeMap<>(object(CanonicalJsonV1.decode(original.bytes())));
        broken.put("id", new JsonValue.Text("proutlost_director:unknown"));
        invalid.set(0, new DefinitionLoader.Source(original.path(), CanonicalJsonV1.encode(obj(broken))));
        check(!loader.reload(invalid).installed() && loader.current() == snapshot, "runtime failure preserves prior snapshot");
        boolean productionRejected = false;
        try { new DefinitionLoader(config, CatalogFixtures.primitives(), references, ProviderMode.PRODUCTION, limits); }
        catch (IllegalArgumentException expected) { productionRejected = true; }
        check(productionRejected, "candidate TEST_ONLY cannot activate in production");
        System.out.println("PASS runtime candidate assertions=" + assertions + " definitions=" + sources.size());
        System.out.println("CANDIDATE_CATALOG_HASH=" + snapshot.hash().value());
        System.out.println("NOT_RUN real J05/J06 providers, server, physical effects, audio and clients");
    }
}
