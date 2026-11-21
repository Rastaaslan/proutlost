package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import static fr.proutlost.director.catalog.ContentJson.*;
import static fr.proutlost.director.catalog.InvalidContent.require;

/** Pure compilation of companions for DefinitionLoader's single atomic publication.
 * No engine feature is inferred from an event name, tag or family.
 */
public final class ScoringMetadataCompiler {
    public record ContextBinding(Condition predicate, UnitValue weight) {
        public ContextBinding { Objects.requireNonNull(predicate); Objects.requireNonNull(weight); }
    }
    public record Metadata(DefinitionId definitionId, Revision definitionRevision, Revision contentRevision,
            Calibration calibration, Optional<Map<ResourceId, UnitValue>> contextAffinities,
            Optional<Map<RegionTrait, UnitValue>> regionTraits, Optional<Map<GlobalTension, UnitValue>> tensionAffinities,
            Optional<Double> desiredLocalIntensity, Optional<Boolean> habitationSensitive,
            JsonValue.ObjectValue canonicalContent, Fingerprint contentHash) {
        public Metadata {
            contextAffinities = contextAffinities.map(Map::copyOf);
            regionTraits = regionTraits.map(Map::copyOf);
            tensionAffinities = tensionAffinities.map(Map::copyOf);
        }
    }
    private record Version(long revision, Fingerprint hash) {}
    /** Immutable staging value held inside the same CAS state as the published API snapshot. */
    public static final class Prepared {
        private final CatalogSnapshot catalog;
        private final Map<DefinitionId, Metadata> metadata;
        private final Map<Feature, UnitValue> neutralFeatures;
        private final Map<DefinitionId, Version> history;
        private final Fingerprint hash;
        private Prepared(CatalogSnapshot catalog, Map<DefinitionId, Metadata> metadata,
                Map<Feature, UnitValue> neutrals, Map<DefinitionId, Version> history, Fingerprint hash) {
            this.catalog = catalog; this.metadata = Map.copyOf(metadata); neutralFeatures = Map.copyOf(neutrals);
            this.history = Map.copyOf(history); this.hash = hash;
        }
        public CatalogSnapshot catalog() { return catalog; }
        public Map<DefinitionId, Metadata> metadata() { return metadata; }
        public Map<Feature, UnitValue> neutralFeatures() { return neutralFeatures; }
        public Fingerprint hash() { return hash; }
    }
    public record Result(Optional<Prepared> prepared, List<CatalogDiagnostic> diagnostics) {
        public Result { diagnostics = List.copyOf(diagnostics); }
    }
    private static final Pattern PATH = Pattern.compile("data/([a-z0-9_.-]+)/director/scoring/([a-z0-9_./-]+)\\.json");
    private static final Set<Feature> FEATURES = Set.of(Feature.contextCompatibility, Feature.regionPersonality,
        Feature.globalTension, Feature.localIntensityFit);
    private final ContentSchema schema = ContentSchema.bundled("scoring-metadata");
    private final ConfigView config;
    private final Map<ResourceId, ContextBinding> knownContexts;
    private final Map<Feature, UnitValue> neutrals;
    private final ProviderMode mode;
    private final DefinitionLoader.Limits limits;
    public ScoringMetadataCompiler(ConfigView config, Map<ResourceId, ContextBinding> knownContexts,
            Map<Feature, UnitValue> neutralFeatures, ProviderMode mode, DefinitionLoader.Limits limits) {
        this.config = Objects.requireNonNull(config); this.knownContexts = Map.copyOf(knownContexts);
        neutrals = Map.copyOf(neutralFeatures); this.mode = Objects.requireNonNull(mode); this.limits = Objects.requireNonNull(limits);
        require(neutrals.keySet().equals(FEATURES), "", "declare a neutral for each scoring feature");
        require(mode != ProviderMode.PRODUCTION || config.calibration() == Calibration.APPROVED, "", "test scoring profile in production");
    }
    public Result prepare(List<DefinitionLoader.Source> input, CatalogSnapshot catalog, Optional<Prepared> previous) {
        Objects.requireNonNull(catalog); Objects.requireNonNull(previous);
        List<CatalogDiagnostic> errors = new ArrayList<>(); Map<DefinitionId, Metadata> metadata = new TreeMap<>();
        Map<DefinitionId, Version> history = new HashMap<>(previous.map(p -> p.history).orElse(Map.of()));
        if (input.size() > limits.maxDocuments()) return failure("", "", "scoring document count limit");
        long total = 0;
        for (var source : input.stream().sorted(Comparator.comparing(DefinitionLoader.Source::path)).toList()) {
            try {
                byte[] bytes = source.bytes(); total = Math.addExact(total, bytes.length);
                require(bytes.length <= limits.maxDocumentBytes() && total <= limits.maxTotalBytes(), "", "scoring byte limit");
                var match = PATH.matcher(source.path()); require(match.matches(), "/definition_id", "invalid scoring companion path");
                DefinitionId expected = new DefinitionId(match.group(1) + ":" + match.group(2));
                JsonValue raw = CanonicalJsonV1.decode(bytes); schema.validate(raw);
                boolean[] testing = {false}; JsonValue resolved = resolve(raw, "", testing); schema.validate(resolved);
                var content = (JsonValue.ObjectValue) resolved; var fields = content.values();
                DefinitionId id = new DefinitionId(text(fields.get("definition_id")));
                require(expected.equals(id), "/definition_id", "scoring ID differs from path");
                require(!metadata.containsKey(id), "/definition_id", "duplicate scoring definition");
                EventDefinition event = catalog.events().get(id);
                require(event != null, "/definition_id", "unknown scoring event definition (API 1.0.1 profiles target events)");
                Revision definitionRevision = new Revision(number(fields.get("definition_revision")).longValueExact());
                require(definitionRevision.equals(event.contentRevision()), "/definition_revision", "scoring definition revision mismatch");
                Revision revision = new Revision(number(fields.get("content_revision")).longValueExact());
                Calibration calibration = Calibration.valueOf(text(fields.get("calibration")));
                require(!testing[0] || calibration == Calibration.DEFAULT_FOR_TESTING, "/calibration", "test scoring references require test calibration");
                require(mode != ProviderMode.PRODUCTION || calibration == Calibration.APPROVED, "/calibration", "test metadata in production");
                var contexts = contexts(fields.get("context_affinities"));
                var traits = enumMap(fields.get("region_traits"), RegionTrait.class);
                var tension = enumMap(fields.get("tension_affinities"), GlobalTension.class);
                Optional<Double> intensity = Optional.ofNullable(fields.get("desired_local_intensity")).map(v -> number(v).doubleValue());
                require(intensity.isEmpty() || Double.isFinite(intensity.orElseThrow()), "/desired_local_intensity", "finite intensity required");
                Optional<Boolean> habitation = Optional.ofNullable(fields.get("habitation_sensitive")).map(v -> ((JsonValue.Bool) v).value());
                Fingerprint contentHash = hash(CanonicalJsonV1.encode(content));
                Version old = history.get(id);
                require(old == null || revision.value() > old.revision() || revision.value() == old.revision() && contentHash.equals(old.hash()),
                    "/content_revision", "scoring revision regression or changed content at same revision");
                metadata.put(id, new Metadata(id, definitionRevision, revision, calibration, contexts, traits, tension, intensity, habitation, content, contentHash));
                history.put(id, new Version(revision.value(), contentHash));
            } catch (IllegalArgumentException | ArithmeticException e) {
                errors.add(new CatalogDiagnostic(source.path(), e instanceof InvalidContent i ? i.pointer : "", ReasonCode.CATALOG_INVALID,
                    Objects.toString(e.getMessage(), "invalid scoring metadata")));
            }
        }
        if (history.size() > limits.maxDocuments()) errors.add(new CatalogDiagnostic("", "", ReasonCode.CATALOG_INVALID, "scoring history limit"));
        if (!errors.isEmpty()) return new Result(Optional.empty(), errors);
        MessageDigest digest = digest(); frame(digest, catalog.hash().value().getBytes(StandardCharsets.UTF_8));
        frame(digest, new ModelCodec<>(ConfigView.class).encode(config));
        for (Feature feature : Feature.values()) if (neutrals.containsKey(feature)) {
            frame(digest, feature.name().getBytes(StandardCharsets.UTF_8)); frame(digest, new ModelCodec<>(UnitValue.class).encode(neutrals.get(feature)));
        }
        knownContexts.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::value))).forEach(entry -> {
            frame(digest, entry.getKey().value().getBytes(StandardCharsets.UTF_8));
            frame(digest, new ModelCodec<>(ContextAffinity.class).encode(new ContextAffinity(entry.getValue().predicate(), new UnitValue(0), entry.getValue().weight())));
        });
        metadata.values().forEach(m -> frame(digest, CanonicalJsonV1.encode(m.canonicalContent())));
        return new Result(Optional.of(new Prepared(catalog, metadata, neutrals, history, new Fingerprint(HexFormat.of().formatHex(digest.digest())))), List.of());
    }
    /** Exact API projection; explicit true applies sensitivity to this event's declared families. */
    Map<DefinitionId, DefinitionScoringProfile> project(Prepared prepared, DefinitionValidator validator) {
        Map<DefinitionId, DefinitionScoringProfile> result = new TreeMap<>();
        for (Metadata m : prepared.metadata().values()) {
            EventDefinition event = prepared.catalog().events().get(m.definitionId());
            List<ContextAffinity> contexts = new ArrayList<>();
            m.contextAffinities().orElse(Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::value))).forEach(entry -> {
                    ContextBinding binding = knownContexts.get(entry.getKey());
                    validator.condition(binding.predicate(), prepared.catalog().events(), "/context_affinities/" + entry.getKey().value());
                    contexts.add(new ContextAffinity(binding.predicate(), entry.getValue(), binding.weight()));
                });
            MessageDigest projected = digest();
            frame(projected, CanonicalJsonV1.encode(m.canonicalContent()));
            frame(projected, new ModelCodec<>(EventDefinition.class).encode(event));
            contexts.forEach(context -> frame(projected, new ModelCodec<>(ContextAffinity.class).encode(context)));
            Fingerprint profileHash = new Fingerprint(HexFormat.of().formatHex(projected.digest()));
            result.put(m.definitionId(), new DefinitionScoringProfile(1,
                new DefinitionRef(event.id(), event.contentRevision(), hash(new ModelCodec<>(EventDefinition.class).encode(event))),
                profileHash, contexts, m.regionTraits().orElse(Map.of()), m.tensionAffinities().orElse(Map.of()),
                m.desiredLocalIntensity().map(UnitValue::new), m.habitationSensitive().orElse(false) ? event.families() : Set.of()));
        }
        return Map.copyOf(result);
    }
    private Optional<Map<ResourceId, UnitValue>> contexts(JsonValue value) {
        if (value == null) return Optional.empty();
        Map<ResourceId, UnitValue> result = new HashMap<>();
        for (var entry : new TreeMap<>(object(value)).entrySet()) {
            ResourceId id = new ResourceId(entry.getKey());
            require(knownContexts.containsKey(id), pointer("/context_affinities", entry.getKey()), "unknown context affinity " + id.value());
            result.put(id, new UnitValue(number(entry.getValue()).doubleValue()));
        }
        return Optional.of(Map.copyOf(result));
    }
    private static <T extends Enum<T>> Optional<Map<T, UnitValue>> enumMap(JsonValue value, Class<T> type) {
        if (value == null) return Optional.empty();
        Map<T, UnitValue> result = new EnumMap<>(type);
        object(value).forEach((key, v) -> result.put(Enum.valueOf(type, key), new UnitValue(number(v).doubleValue())));
        return Optional.of(Map.copyOf(result));
    }
    private JsonValue resolve(JsonValue v, String p, boolean[] testing) {
        if (v instanceof JsonValue.Text t && t.value().startsWith("@test:")) {
            testing[0] = true; require(config.calibration() == Calibration.DEFAULT_FOR_TESTING, p, "test scoring reference requires test profile");
            JsonValue value = config.values().get(t.value().substring(6)); require(value != null, p, "unknown scoring parameter"); return value;
        }
        if (v instanceof JsonValue.ObjectValue o) {
            Map<String, JsonValue> result = new TreeMap<>();
            o.values().forEach((k, value) -> result.put(k, resolve(value, pointer(p, k), testing))); return obj(result);
        }
        return v;
    }
    private static Result failure(String file, String p, String message) {
        return new Result(Optional.empty(), List.of(new CatalogDiagnostic(file, p, ReasonCode.CATALOG_INVALID, message)));
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private static Fingerprint hash(byte[] bytes) { return new Fingerprint(HexFormat.of().formatHex(digest().digest(bytes))); }
    private static void frame(MessageDigest digest, byte[] bytes) { digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
}
