package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.CatalogPort;
import fr.proutlost.director.api.port.ProviderProvenance;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static fr.proutlost.director.catalog.InvalidContent.require;

/** Compile/reload on a bounded CPU worker, file reads on an IO worker. Publication is one CAS.
 * Callers pin current() in their instance; publication never mutates previously returned DTOs.
 */
public final class DefinitionLoader implements CatalogPort,ProviderProvenance {
    public record Limits(int maxDocuments, int maxDocumentBytes, long maxTotalBytes, int maxSteps, int maxTransitions) {
        public Limits {
            if (maxDocuments < 1 || maxDocuments > 65536 || maxDocumentBytes < 1 || maxDocumentBytes > CanonicalJsonV1.MAX_BYTES
                || maxTotalBytes < maxDocumentBytes || maxSteps < 1 || maxSteps > 4096 || maxTransitions < 1 || maxTransitions > 4096)
                throw new IllegalArgumentException("catalog limits");
        }
    }
    public record Source(String path, byte[] bytes) {
        public Source { Objects.requireNonNull(path); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record ReloadResult(boolean installed, CatalogSnapshot snapshot, List<CatalogDiagnostic> diagnostics) {
        public ReloadResult { diagnostics = List.copyOf(diagnostics); }
    }
    private record RevisionEntry(long revision, Fingerprint hash) {}
    private record State(CatalogSnapshot snapshot, Map<String, RevisionEntry> history, Optional<ScoringMetadataCompiler.Prepared> scoring) {}
    private static final Pattern PATH = Pattern.compile("data/([a-z0-9_.-]+)/director/(events|chains|scoring)/([a-z0-9_./-]+)\\.json");
    private final ScoringMetadataCompiler scoring;
    private final ProviderMode mode;
    private final Limits limits;
    private final DefinitionCodec codec;
    private final DefinitionValidator validator;
    private final PrimitiveRegistry primitives;
    private final Fingerprint environmentHash;
    private final AtomicReference<State> state;
    public DefinitionLoader(ConfigView config, PrimitiveRegistry primitives, ContentReferences references, ProviderMode mode, Limits limits) {
        this(config, primitives, references, mode, limits, Map.of(), defaultNeutrals(config));
    }
    public DefinitionLoader(ConfigView config, PrimitiveRegistry primitives, ContentReferences references, ProviderMode mode, Limits limits,
            Map<ResourceId, ScoringMetadataCompiler.ContextBinding> contexts, Map<Feature, UnitValue> neutrals) {
        this.mode=Objects.requireNonNull(mode);
        scoring = new ScoringMetadataCompiler(config, contexts, neutrals, mode, limits);
        this.limits = Objects.requireNonNull(limits); this.primitives = Objects.requireNonNull(primitives);
        if (mode == ProviderMode.PRODUCTION && config.calibration() != Calibration.APPROVED) throw new IllegalArgumentException("test config in production");
        codec = new DefinitionCodec(config); validator = new DefinitionValidator(primitives, references, mode);
        MessageDigest digest = digest(); frame(digest, new ModelCodec<>(ConfigView.class).encode(config));
        frame(digest, mode.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var entry : new TreeMap<>(primitives.descriptors()).entrySet()) frame(digest, new ModelCodec<>(PrimitiveDescriptor.class).encode(entry.getValue()));
        for (var kind : ContentReferences.Kind.values()) {
            frame(digest, kind.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            references.known().getOrDefault(kind, Set.of()).stream().sorted(Comparator.comparing(ResourceId::value)).forEach(id -> frame(digest, id.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        references.claims().stream().sorted().forEach(claim -> frame(digest, claim.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        references.capabilities().stream().sorted(Comparator.comparing(CapabilityId::value)).forEach(id -> frame(digest, id.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        references.mutations().values().stream().sorted(Comparator.comparing(x -> x.recipeId().value())).forEach(x -> frame(digest, new ModelCodec<>(MutationProfile.class).encode(x)));
        references.warnings().values().stream().sorted(Comparator.comparing(x -> x.id().value())).forEach(x -> frame(digest, new ModelCodec<>(WarningProfile.class).encode(x)));
        references.damages().values().stream().sorted(Comparator.comparing(x -> x.id().value())).forEach(x -> frame(digest, new ModelCodec<>(DamageProfile.class).encode(x)));
        environmentHash = new Fingerprint(HexFormat.of().formatHex(digest.digest()));
        state = new AtomicReference<>(new State(new CatalogSnapshot(new Epoch(0), environmentHash, Map.of(), Map.of(), primitives.descriptors()), Map.of(), Optional.empty()));
    }
    @Override public Set<ProviderMode> supportedModes(){return Set.of(mode);}
    @Override public CatalogSnapshot current() { return state.get().snapshot(); }
    public ReloadResult reload(List<Source> input) {
        State before = state.get(); List<CatalogDiagnostic> errors = new ArrayList<>();
        Map<DefinitionId, EventDefinition> events = new TreeMap<>(); Map<DefinitionId, ChainDefinition> chains = new TreeMap<>();
        Map<DefinitionId, String> files = new HashMap<>();
        List<Source> companions = new ArrayList<>();
        long total = 0;
        if (input.size() > limits.maxDocuments()) return failure(before, "", "", "catalog document limit");
        for (Source source : input.stream().sorted(Comparator.comparing(Source::path)).toList()) {
            try {
                total = Math.addExact(total, source.bytes.length);
                require(source.bytes.length <= limits.maxDocumentBytes() && total <= limits.maxTotalBytes(), "", "catalog byte limit");
                var path = PATH.matcher(source.path()); require(path.matches(), "/id", "invalid datapack path");
                if (path.group(2).equals("scoring")) { companions.add(source); continue; }
                DefinitionId expected = new DefinitionId(path.group(1) + ":" + path.group(3));
                require(!files.containsKey(expected), "/id", "duplicate definition ID (including event/chain collision)");
                if (path.group(2).equals("events")) {
                    EventDefinition event = codec.event(source.bytes);
                    require(event.id().equals(expected), "/id", "ID differs from datapack path");
                    require(event.steps().size() <= limits.maxSteps(), "/steps", "step limit"); events.put(expected, event);
                } else {
                    ChainDefinition chain = codec.chain(source.bytes);
                    require(chain.id().equals(expected), "/id", "ID differs from datapack path");
                    require(chain.planningHorizon() <= limits.maxTransitions(), "/planning_horizon", "planning horizon limit");
                    require(chain.transitions().size() <= limits.maxTransitions(), "/transitions", "transition limit"); chains.put(expected, chain);
                }
                files.put(expected, source.path());
            } catch (IllegalArgumentException | ArithmeticException e) { errors.add(diagnostic(source.path(), e)); }
        }
        if (!errors.isEmpty()) return new ReloadResult(false, before.snapshot(), errors);
        for (var entry : new ArrayList<>(events.entrySet())) {
            try {
                EventDefinition e = entry.getValue(); List<String> order = validator.event(e, events);
                Map<String, EventStep> byId = new HashMap<>(); e.steps().forEach(s -> byId.put(s.id(), s));
                events.put(entry.getKey(), new EventDefinition(e.schemaVersion(), e.contentRevision(), e.id(), e.calibration(), e.category(), e.families(),
                    e.enabled(), e.selection(), e.target(), e.eligibility(), e.timing(), e.uniqueness(), validator.effectiveBudget(e), e.conflicts(), e.safety(),
                    order.stream().map(byId::get).toList(), e.recovery(), e.completion()));
            } catch (IllegalArgumentException e) { errors.add(diagnostic(files.get(entry.getKey()), e)); }
        }
        for (var entry : chains.entrySet()) {
            try { validator.chain(entry.getValue(), events); }
            catch (IllegalArgumentException e) { errors.add(diagnostic(files.get(entry.getKey()), e)); }
        }
        if (!errors.isEmpty()) return new ReloadResult(false, before.snapshot(), errors);
        Map<String, RevisionEntry> history = new HashMap<>(before.history()); MessageDigest digest = digest();
        frame(digest, environmentHash.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var e : events.values()) revision("event:" + e.id().value(), e.contentRevision().value(), new ModelCodec<>(EventDefinition.class).encode(e), files.get(e.id()), history, digest, errors);
        for (var c : chains.values()) revision("chain:" + c.id().value(), c.contentRevision().value(), new ModelCodec<>(ChainDefinition.class).encode(c), files.get(c.id()), history, digest, errors);
        if (history.size() > limits.maxDocuments()) errors.add(new CatalogDiagnostic("", "", ReasonCode.CATALOG_INVALID, "revision history limit; retain tombstones"));
        if (!errors.isEmpty()) return new ReloadResult(false, before.snapshot(), errors);
        Fingerprint hash = new Fingerprint(HexFormat.of().formatHex(digest.digest()));
        CatalogSnapshot definitions = new CatalogSnapshot(before.snapshot().generation(), hash, events, chains, primitives.descriptors());
        var prepared = scoring.prepare(companions, definitions, before.scoring());
        if (prepared.prepared().isEmpty()) return new ReloadResult(false, before.snapshot(), prepared.diagnostics());
        var combined = prepared.prepared().orElseThrow();
        Map<DefinitionId, DefinitionScoringProfile> profiles;
        try { profiles = scoring.project(combined, validator); }
        catch (IllegalArgumentException e) { return new ReloadResult(false, before.snapshot(), List.of(diagnostic("scoring", e))); }
        hash = combined.hash();
        if (hash.equals(before.snapshot().hash())) return new ReloadResult(true, before.snapshot(), List.of());
        if (before.snapshot().generation().value() == Long.MAX_VALUE) return failure(before, "", "", "generation overflow");
        CatalogSnapshot next = new CatalogSnapshot(new Epoch(before.snapshot().generation().value() + 1), hash, events, chains, primitives.descriptors(), profiles);
        if (!state.compareAndSet(before, new State(next, Map.copyOf(history), Optional.of(combined)))) return failure(state.get(), "", "", "concurrent reload; retry from current generation");
        return new ReloadResult(true, next, List.of());
    }
    private static Map<Feature, UnitValue> defaultNeutrals(ConfigView config) {
        JsonValue.Number value = config.values().get("score.neutral_feature");
        if (value == null) throw new IllegalArgumentException("missing configured score.neutral_feature");
        UnitValue neutral = new UnitValue(value.value().doubleValue());
        return Map.of(Feature.contextCompatibility, neutral, Feature.regionPersonality, neutral,
            Feature.globalTension, neutral, Feature.localIntensityFit, neutral);
    }
    /** NOFOLLOW_LINKS rejects symlink documents. This method never traverses worlds. */
    public Source readDocument(Path packRoot, String relative) throws IOException {
        require(PATH.matcher(relative).matches(), "", "invalid datapack path");
        Path root = packRoot.toRealPath(); Path candidate = root.resolve(relative).normalize();
        require(candidate.startsWith(root), "", "path escape");
        for (Path part = candidate; !part.equals(root); part = part.getParent()) require(!Files.isSymbolicLink(part), "", "symlink content path");
        try (InputStream in = Files.newInputStream(candidate, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(limits.maxDocumentBytes() + 1);
            require(bytes.length <= limits.maxDocumentBytes(), "", "document byte limit"); return new Source(relative, bytes);
        }
    }
    private static void revision(String key, long revision, byte[] bytes, String file, Map<String, RevisionEntry> history, MessageDigest digest, List<CatalogDiagnostic> errors) {
        Fingerprint hash = new Fingerprint(HexFormat.of().formatHex(digest().digest(bytes))); RevisionEntry old = history.get(key);
        if (old != null && (revision < old.revision() || revision == old.revision() && !hash.equals(old.hash()))) {
            errors.add(new CatalogDiagnostic(file, "/content_revision", ReasonCode.CATALOG_INVALID, "revision regression or same revision with different content")); return;
        }
        history.put(key, new RevisionEntry(revision, hash)); frame(digest, bytes);
    }
    private static CatalogDiagnostic diagnostic(String file, Exception e) { return new CatalogDiagnostic(file, e instanceof InvalidContent i ? i.pointer : "", ReasonCode.CATALOG_INVALID, Objects.toString(e.getMessage(), "invalid content")); }
    private static ReloadResult failure(State state, String file, String p, String message) { return new ReloadResult(false, state.snapshot(), List.of(new CatalogDiagnostic(file, p, ReasonCode.CATALOG_INVALID, message))); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private static void frame(MessageDigest d, byte[] bytes) { d.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); d.update(bytes); }
}
