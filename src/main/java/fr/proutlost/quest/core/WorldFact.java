package fr.proutlost.quest.core;

import java.util.*;

/** Server-side integration contract, never accepted directly from a client packet. */
public record WorldFact(String factId, String factType, String sourceSystem,
                        Set<UUID> playerScope, String groupScope, String regionId,
                        String locationId, long timestamp, Map<String,String> payload,
                        boolean persistence, Category narrativeVisibility) {
    public enum Category { AMBIENT, NARRATIVE, HYBRID }
    public WorldFact {
        Objects.requireNonNull(factId); Objects.requireNonNull(factType);
        Objects.requireNonNull(sourceSystem); Objects.requireNonNull(narrativeVisibility);
        if (factId.isBlank() || factType.isBlank() || sourceSystem.isBlank() || timestamp < 0)
            throw new IllegalArgumentException("Invalid fact");
        playerScope = Set.copyOf(playerScope); payload = Map.copyOf(payload);
        groupScope = Objects.requireNonNullElse(groupScope, "");
        regionId = Objects.requireNonNullElse(regionId, "");
        locationId = Objects.requireNonNullElse(locationId, "");
    }
}
