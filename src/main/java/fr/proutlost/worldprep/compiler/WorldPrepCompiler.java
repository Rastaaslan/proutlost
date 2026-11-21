package fr.proutlost.worldprep.compiler;

import fr.proutlost.worldprep.pipeline.PassGraph;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import fr.proutlost.worldprep.storage.PlanManifest;
import java.util.ArrayList;
import java.util.List;

/** Seals a pipeline from exact sealed pass manifests; no pass name or "latest" lookup is sufficient. */
public final class WorldPrepCompiler {
    public record SealedPass(PassId passId, java.util.UUID planId, String manifestFingerprint,
            String upstreamChainFingerprint) {}
    public record SealedPipeline(String pipelineId, String sourceFingerprint, String profileFingerprint,
            String semanticPackFingerprint, long seed, List<SealedPass> passes) {
        public SealedPipeline { passes = List.copyOf(passes); }
    }

    public SealedPipeline compile(String sourceFingerprint, String profileFingerprint,
            String semanticPackFingerprint, long seed, List<PlanManifest> manifests) {
        requireIdentity(sourceFingerprint, "source");
        requireIdentity(profileFingerprint, "profile");
        requireIdentity(semanticPackFingerprint, "semantic pack");
        if (manifests.size() != PassGraph.order().size()) throw new IllegalArgumentException("Every pass requires one exact manifest");
        var pipelineDigest = CanonicalDigest.sha256().putString("worldprep-v2-pipeline-v2")
                .putString(sourceFingerprint).putString(profileFingerprint).putString(semanticPackFingerprint).putLong(seed);
        var chainDigest = CanonicalDigest.sha256().putString("worldprep-v2-upstream-root")
                .putString(sourceFingerprint).putString(profileFingerprint).putString(semanticPackFingerprint).putLong(seed);
        var sealed = new ArrayList<SealedPass>();
        for (int index = 0; index < manifests.size(); index++) {
            PlanManifest manifest = manifests.get(index);
            PassId expected = PassGraph.order().get(index);
            if (manifest.passId() != expected) throw new IllegalArgumentException("Expected " + expected + " manifest");
            manifest.requireApplicable();
            chainDigest.putString(manifest.planId().toString()).putString(manifest.rootFingerprint());
            String upstream = chainDigest.finish();
            sealed.add(new SealedPass(expected, manifest.planId(), manifest.rootFingerprint(), upstream));
            pipelineDigest.putString(expected.name()).putUuid(manifest.planId()).putString(manifest.rootFingerprint()).putString(upstream);
            chainDigest = CanonicalDigest.sha256().putString("worldprep-v2-chain").putString(upstream);
        }
        return new SealedPipeline(pipelineDigest.finish(), sourceFingerprint, profileFingerprint,
                semanticPackFingerprint, seed, sealed);
    }

    public void requireExact(SealedPipeline sealed, String requestedPipelineId) {
        if (!sealed.pipelineId().equals(requestedPipelineId)) throw new IllegalStateException("Exact sealed pipeline id required");
    }

    private static void requireIdentity(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " identity required");
    }
}
