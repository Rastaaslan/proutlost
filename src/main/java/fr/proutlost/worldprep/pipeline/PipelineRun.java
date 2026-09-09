package fr.proutlost.worldprep.pipeline;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact immutable collection used by preview/apply-all; never reconstructed from latest plans. */
public record PipelineRun(UUID id, String areaId, String dimension, String profile, String baseWorldFingerprint,
        Map<PassId, PlanReference> plans, String rootFingerprint, State state) {
    public enum State { PLANNING, SEALED, APPLYING, APPLIED, STALE, FAILED }

    public record PlanReference(UUID planId, String fingerprint) {
        public PlanReference {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            new PlanFingerprint(fingerprint);
        }
    }

    public PipelineRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(baseWorldFingerprint, "baseWorldFingerprint");
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(rootFingerprint, "rootFingerprint");
        Objects.requireNonNull(state, "state");
        if (areaId.isBlank() || dimension.isBlank() || profile.isBlank()) {
            throw new IllegalArgumentException("Invalid pipeline identity");
        }
        new PlanFingerprint(baseWorldFingerprint);
        new PlanFingerprint(rootFingerprint);
        EnumMap<PassId, PlanReference> copy = new EnumMap<>(PassId.class);
        copy.putAll(plans);
        copy.forEach((pass, reference) -> Objects.requireNonNull(reference, "plan reference for " + pass));
        plans = Collections.unmodifiableMap(copy);
        String computed = computeRoot(id, areaId, dimension, profile, baseWorldFingerprint, plans);
        if (!computed.equals(rootFingerprint)) {
            throw new IllegalArgumentException("Pipeline root fingerprint mismatch");
        }
    }

    public static PipelineRun create(UUID id, String areaId, String dimension, String profile,
            String baseWorldFingerprint, Map<PassId, PlanReference> plans, State state) {
        String root = computeRoot(id, areaId, dimension, profile, baseWorldFingerprint, plans);
        return new PipelineRun(id, areaId, dimension, profile, baseWorldFingerprint, plans, root, state);
    }

    public static String computeRoot(UUID id, String areaId, String dimension, String profile,
            String baseWorldFingerprint, Map<PassId, PlanReference> plans) {
        CanonicalDigest digest = CanonicalDigest.sha256()
                .putString("worldprep-pipeline-run")
                .putUuid(id)
                .putString(areaId)
                .putString(dimension)
                .putString(profile)
                .putString(baseWorldFingerprint);
        long count = PassGraph.order().stream().filter(plans::containsKey).count();
        digest.putInt(Math.toIntExact(count));
        for (PassId pass : PassGraph.order()) {
            PlanReference reference = plans.get(pass);
            if (reference != null) {
                digest.putString(pass.name())
                        .putUuid(reference.planId())
                        .putString(reference.fingerprint());
            }
        }
        return digest.finish();
    }

    public void requireSealed() {
        if (state != State.SEALED) {
            throw new IllegalStateException("Pipeline run is not sealed");
        }
        if (!computeRoot(id, areaId, dimension, profile, baseWorldFingerprint, plans).equals(rootFingerprint)) {
            throw new IllegalStateException("Sealed pipeline run is inconsistent");
        }
    }
}
