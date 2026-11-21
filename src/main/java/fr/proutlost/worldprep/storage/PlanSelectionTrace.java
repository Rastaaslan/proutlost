package fr.proutlost.worldprep.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded immutable diagnostic record, published before the SEALED commit record.
 * Counts actual entry-selection decisions; this is not an inventory of configured candidates. */
public final class PlanSelectionTrace {
    public static final String PLANNER_VERSION = "paged-plan-2-selection-trace";
    private static final int MAGIC = 0x57505354;
    public enum Selection { DIRECT, FALLBACK }
    public record Metrics(long direct, long fallback, String checksum) {
        public Metrics {
            if (direct < 0 || fallback < 0) throw new IllegalArgumentException("Negative selection count");
            Math.addExact(direct, fallback);
            Objects.requireNonNull(checksum);
        }
        public long entries() { return Math.addExact(direct, fallback); }
        public Map<String,Object> metadata() {
            return Map.of("status","MEASURED_AT_PLAN_PUBLICATION","unit","planned entry selections",
                "direct",direct,"fallback",fallback,"entries",entries(),"checksum",checksum);
        }
    }
    private PlanSelectionTrace() {}
    public static boolean required(String planner) { return PLANNER_VERSION.equals(planner); }
    public static Path path(Path directory) { return directory.resolve("selection-trace.wpt"); }
    private static long entries(PlanManifest manifest) {
        return manifest.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).reduce(0,Math::addExact);
    }
    public static void publish(Path directory, PlanManifest manifest, long direct, long fallback) throws IOException {
        manifest.requireApplicable();
        if (!required(manifest.plannerVersion())) throw new IOException("Not a traced planner version");
        var counts = new Metrics(direct,fallback,"");
        if (counts.entries()!=entries(manifest)) throw new IOException("Selection count does not match sealed entries");
        var bytes = new ByteArrayOutputStream();
        try (var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeUTF(manifest.rootFingerprint());
            out.writeUTF(manifest.semanticInputDigest());out.writeUTF(manifest.plannerVersion());
            out.writeLong(direct);out.writeLong(fallback);
        }
        var page=DurablePageStore.page(DurablePageStore.StorageKind.PLAN,manifest.planId(),manifest.passId(),
            manifest.dimension(),0,0,0,1,bytes.toByteArray());
        Path target=path(directory);
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            var existing=read(directory,manifest).orElseThrow();
            if (existing.direct()!=direct || existing.fallback()!=fallback || !existing.checksum().equals(page.checksum()))
                throw new IOException("Existing selection trace mismatch");
        } else DurablePageStore.publish(target,page);
        read(directory,manifest).orElseThrow();
    }
    public static Optional<Metrics> read(Path directory, PlanManifest manifest) throws IOException {
        manifest.requireApplicable();
        if (!required(manifest.plannerVersion())) return Optional.empty(); // Legacy is explicitly unknown.
        Path target=path(directory);
        if (!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)>4096)
            throw new IOException("Missing or oversized sealed selection trace");
        var page=DurablePageStore.read(target);
        if (page.storageKind()!=DurablePageStore.StorageKind.PLAN || !page.ownerId().equals(manifest.planId())
                || page.passId()!=manifest.passId() || !page.dimension().equals(manifest.dimension())
                || page.chunkX()!=0 || page.chunkZ()!=0 || page.sequence()!=0 || page.entryCount()!=1)
            throw new IOException("Selection trace identity mismatch");
        try (var in=new DataInputStream(new ByteArrayInputStream(page.payload()))) {
            if (in.readInt()!=MAGIC || !in.readUTF().equals(manifest.rootFingerprint())
                    || !in.readUTF().equals(manifest.semanticInputDigest()) || !in.readUTF().equals(manifest.plannerVersion()))
                throw new IOException("Selection trace sealed input/root mismatch");
            var counts=new Metrics(in.readLong(),in.readLong(),page.checksum());
            if (in.read()!=-1 || counts.entries()!=entries(manifest)) throw new IOException("Selection trace entry count mismatch");
            return Optional.of(counts);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid selection trace counts",invalid);
        }
    }
}
