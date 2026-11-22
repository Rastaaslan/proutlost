package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.file.*;
import java.util.Objects;
import java.util.Optional;

/** Mandatory terrain input for the versioned planner; published before its SEALED commit record. */
public final class TerrainInputTrace {
    public static final String PLANNER_VERSION = "paged-plan-3-terrain-input-selection-trace";
    /** A distinct plan envelope makes an older reader refuse these semantics. Page payloads keep their format. */
    public static final int PLAN_FORMAT_VERSION = 4;
    private static final int MAGIC = 0x57505449;
    private static final int FORMAT = 1;

    public record Binding(String baseSemanticDigest, TerrainInputFingerprint.Bounds bounds, String terrainDigest) {
        public Binding {
            Objects.requireNonNull(bounds);
            new PlanFingerprint(baseSemanticDigest);
            new PlanFingerprint(terrainDigest);
        }
        public String semanticInputDigest() {
            return PlanFingerprint.of("worldprep-terrain-semantic-input-v1", baseSemanticDigest,
                Integer.toString(bounds.minX()), Integer.toString(bounds.minY()), Integer.toString(bounds.minZ()),
                Integer.toString(bounds.maxX()), Integer.toString(bounds.maxYExclusive()), Integer.toString(bounds.maxZ()),
                terrainDigest).value();
        }
        public void requireTerrain(String actual) {
            if (!terrainDigest.equals(actual)) throw new IllegalStateException("PREVIEW STALE: terrain input changed");
        }
    }

    public static boolean required(String planner) { return PLANNER_VERSION.equals(planner); }
    public static Path path(Path directory) { return directory.resolve("terrain-input.wpti"); }

    public static void publish(Path directory, PlanManifest manifest, Binding binding) throws IOException {
        requireBinding(manifest, binding);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(FORMAT);
            out.writeUTF(manifest.rootFingerprint()); out.writeUTF(manifest.semanticInputDigest());
            out.writeUTF(binding.baseSemanticDigest());
            var b = binding.bounds();
            out.writeInt(b.minX()); out.writeInt(b.minY()); out.writeInt(b.minZ());
            out.writeInt(b.maxX()); out.writeInt(b.maxYExclusive()); out.writeInt(b.maxZ());
            out.writeUTF(binding.terrainDigest());
        }
        var page = DurablePageStore.page(DurablePageStore.StorageKind.PLAN, manifest.planId(), manifest.passId(),
            manifest.dimension(), 0, 0, 0, 1, bytes.toByteArray());
        Path target = path(directory);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            var existing = read(directory, manifest).orElseThrow();
            if (!existing.equals(binding) || !DurablePageStore.read(target).checksum().equals(page.checksum()))
                throw new IOException("Existing terrain input binding mismatch");
        } else DurablePageStore.publish(target, page);
        if (!read(directory, manifest).orElseThrow().equals(binding)) throw new IOException("Terrain input readback mismatch");
    }

    public static Optional<Binding> read(Path directory, PlanManifest manifest) throws IOException {
        if (!required(manifest.plannerVersion())) return Optional.empty();
        manifest.requireApplicable();
        Path target = path(directory);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > 4096)
            throw new IOException("Missing or oversized sealed terrain input");
        var page = DurablePageStore.read(target);
        if (page.storageKind() != DurablePageStore.StorageKind.PLAN || !page.ownerId().equals(manifest.planId())
            || page.passId() != manifest.passId() || !page.dimension().equals(manifest.dimension())
            || page.chunkX() != 0 || page.chunkZ() != 0 || page.sequence() != 0 || page.entryCount() != 1)
            throw new IOException("Terrain input binding owner mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(page.payload()))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT
                || !in.readUTF().equals(manifest.rootFingerprint())
                || !in.readUTF().equals(manifest.semanticInputDigest()))
                throw new IOException("Terrain input sealed root mismatch");
            String base = in.readUTF();
            var bounds = new TerrainInputFingerprint.Bounds(in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt());
            var binding = new Binding(base, bounds, in.readUTF());
            if (in.read() != -1) throw new IOException("Trailing terrain input data");
            requireBinding(manifest, binding);
            return Optional.of(binding);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid terrain input binding", invalid);
        }
    }

    private static void requireBinding(PlanManifest manifest, Binding binding) throws IOException {
        manifest.requireApplicable();
        if (!required(manifest.plannerVersion()) || !manifest.semanticInputDigest().equals(binding.semanticInputDigest()))
            throw new IOException("Terrain input semantic identity mismatch");
    }
    private TerrainInputTrace() {}
}
