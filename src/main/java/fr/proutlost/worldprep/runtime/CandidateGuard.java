package fr.proutlost.worldprep.runtime;

import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;

/** Explicit local candidate marker, checked again at every batch checkpoint. Never reads a source world. */
public final class CandidateGuard {
    public static void requireCandidate(ServerLevel level) {
        requireCandidate(level.getServer().getWorldPath(LevelResource.ROOT));
    }
    public static void requireCandidate(java.nio.file.Path world) {
        try {
            var root = world.toRealPath();
            var marker = root.resolve("worldprep-candidate.json");
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker) || Files.size(marker) > 4096)
                throw new IllegalStateException("Candidate marker missing or unsafe");
            var json = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            if (json.get("schemaVersion").getAsInt() != 1 || !json.get("candidateDirectory").getAsString().equals(root.toString())
                    || !json.get("offlineBaseline").getAsBoolean()) throw new IllegalStateException("Candidate marker identity mismatch");
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Batch requires an operator-verified offline candidate marker", e);
        }
    }
    private CandidateGuard() {}
}
