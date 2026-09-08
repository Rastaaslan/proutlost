package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.List;
import java.util.UUID;

/** Immutable plan publication record. Only SEALED manifests may be applied. */
public record PlanManifest(int planFormatVersion, UUID planId, PassId passId, String areaId,
        String dimension, String profile, String semanticInputDigest, String engineVersion,
        String plannerVersion, State state, List<PageReference> pages, String rootFingerprint) {
    public enum State { WRITING, SEALED }
    public record PageReference(long sequence, int chunkX, int chunkZ, int entryCount, String checksum) { }
    public PlanManifest {
        pages = List.copyOf(pages);
        if (planFormatVersion != DurablePageStore.FORMAT_VERSION || areaId.isBlank() || dimension.isBlank())
            throw new IllegalArgumentException("Invalid manifest identity");
        long expected=0;
        for (PageReference page:pages) {
            if (page.sequence()!=expected++ || page.entryCount()<0) throw new IllegalArgumentException("Non-canonical pages");
            new PlanFingerprint(page.checksum());
        }
        new PlanFingerprint(semanticInputDigest); new PlanFingerprint(rootFingerprint);
    }
    public void requireApplicable() { if (state != State.SEALED) throw new IllegalStateException("Plan is not sealed"); }
    public static String rootFingerprint(List<PageReference> pages) {
        return PlanFingerprint.of(pages.stream().map(p->p.sequence()+":"+p.chunkX()+":"+p.chunkZ()+":"+p.entryCount()+":"+p.checksum()).toArray(String[]::new)).value();
    }
}
