package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable plan publication record. Only coherent SEALED manifests may be applied. */
public record PlanManifest(int planFormatVersion, UUID planId, PassId passId, String areaId,
        String dimension, String profile, String semanticInputDigest, String engineVersion,
        String plannerVersion, State state, List<PageReference> pages, String rootFingerprint) {
    public enum State { WRITING, SEALED }

    public record PageReference(long sequence, int chunkX, int chunkZ, int entryCount, String checksum) {
        public PageReference {
            Objects.requireNonNull(checksum, "checksum");
            if (sequence < 0 || entryCount < 0) {
                throw new IllegalArgumentException("Invalid page reference");
            }
            new PlanFingerprint(checksum);
        }
    }

    public PlanManifest {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(passId, "passId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(semanticInputDigest, "semanticInputDigest");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(plannerVersion, "plannerVersion");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(rootFingerprint, "rootFingerprint");
        pages = List.copyOf(pages);
        if (planFormatVersion != DurablePageStore.FORMAT_VERSION || areaId.isBlank() || dimension.isBlank()
                || profile.isBlank() || engineVersion.isBlank() || plannerVersion.isBlank()) {
            throw new IllegalArgumentException("Invalid manifest identity");
        }
        new PlanFingerprint(semanticInputDigest);
        new PlanFingerprint(rootFingerprint);
        validatePages(pages);
        String computed = computeRoot(planFormatVersion, planId, passId, areaId, dimension, profile,
                semanticInputDigest, engineVersion, plannerVersion, pages);
        if (!computed.equals(rootFingerprint)) {
            throw new IllegalArgumentException("Manifest root fingerprint mismatch");
        }
    }

    public static PlanManifest create(int planFormatVersion, UUID planId, PassId passId, String areaId,
            String dimension, String profile, String semanticInputDigest, String engineVersion,
            String plannerVersion, State state, List<PageReference> pages) {
        String root = computeRoot(planFormatVersion, planId, passId, areaId, dimension, profile,
                semanticInputDigest, engineVersion, plannerVersion, pages);
        return new PlanManifest(planFormatVersion, planId, passId, areaId, dimension, profile,
                semanticInputDigest, engineVersion, plannerVersion, state, pages, root);
    }

    public void requireApplicable() {
        if (state != State.SEALED) {
            throw new IllegalStateException("Plan is not sealed");
        }
        String computed = computeRoot(planFormatVersion, planId, passId, areaId, dimension, profile,
                semanticInputDigest, engineVersion, plannerVersion, pages);
        if (!computed.equals(rootFingerprint)) {
            throw new IllegalStateException("Sealed plan manifest is inconsistent");
        }
    }

    public static String computeRoot(int planFormatVersion, UUID planId, PassId passId, String areaId,
            String dimension, String profile, String semanticInputDigest, String engineVersion,
            String plannerVersion, List<PageReference> pages) {
        Objects.requireNonNull(pages, "pages");
        validatePages(pages);
        CanonicalDigest digest = CanonicalDigest.sha256()
                .putString("worldprep-plan-manifest")
                .putInt(planFormatVersion)
                .putUuid(planId)
                .putString(passId.name())
                .putString(areaId)
                .putString(dimension)
                .putString(profile)
                .putString(semanticInputDigest)
                .putString(engineVersion)
                .putString(plannerVersion)
                .putInt(pages.size());
        for (PageReference page : pages) {
            digest.putLong(page.sequence())
                    .putInt(page.chunkX())
                    .putInt(page.chunkZ())
                    .putInt(page.entryCount())
                    .putString(page.checksum());
        }
        return digest.finish();
    }

    private static void validatePages(List<PageReference> pages) {
        long expected = 0;
        for (PageReference page : pages) {
            Objects.requireNonNull(page, "page");
            if (page.sequence() != expected++) {
                throw new IllegalArgumentException("Non-canonical page sequence");
            }
        }
    }
}
