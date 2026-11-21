package fr.proutlost.worldprep.recovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Read-only presence audit. A missing historical manifest is never reconstructed as an empty journal. */
public final class ReferenceInventory {
    public record Reference(String kind, UUID id) {
        public Reference {
            if (!List.of("plans", "journals", "biome-journals").contains(kind) || id == null)
                throw new IllegalArgumentException("Unknown reference kind");
        }
    }
    public record Finding(String kind, UUID id, String status) {}
    public static List<Finding> inspect(Path worldprepRoot, List<Reference> refs) {
        if (refs.size() > 64) throw new IllegalArgumentException("Audit page exceeds 64 references");
        return refs.stream().map(ref -> {
            var path = worldprepRoot.resolve(ref.kind()).resolve(ref.id().toString())
                    .resolve(ref.kind().equals("plans") ? "manifest.wpm" : "manifest.wjm");
            String status = Files.isRegularFile(path) && !Files.isSymbolicLink(path)
                    ? "PRESENT_CONTENT_NOT_VERIFIED" : "ORPHANED_HISTORY_UNAVAILABLE";
            return new Finding(ref.kind(), ref.id(), status);
        }).toList();
    }
    private ReferenceInventory() {}
}
