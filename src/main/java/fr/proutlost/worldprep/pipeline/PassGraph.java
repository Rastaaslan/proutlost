package fr.proutlost.worldprep.pipeline;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical pass dependency and downstream invalidation graph. */
public final class PassGraph {
    private static final Map<PassId, Set<PassId>> DEPENDENCIES = new EnumMap<>(PassId.class);

    static {
        DEPENDENCIES.put(PassId.TERRAIN, Set.of());
        DEPENDENCIES.put(PassId.BIOMES, Set.of(PassId.TERRAIN));
        DEPENDENCIES.put(PassId.GEOLOGY, Set.of(PassId.TERRAIN));
        DEPENDENCIES.put(PassId.ORES, Set.of(PassId.GEOLOGY));
        DEPENDENCIES.put(PassId.SOILS, Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.GEOLOGY));
        DEPENDENCIES.put(PassId.FLORA, Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.SOILS));
        DEPENDENCIES.put(PassId.TREES, Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.SOILS));
        DEPENDENCIES.put(PassId.AQUATIC, Set.of(PassId.TERRAIN, PassId.BIOMES));
        DEPENDENCIES.put(PassId.HABITATS,
                Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.SOILS, PassId.FLORA, PassId.TREES, PassId.AQUATIC));
        DEPENDENCIES.put(PassId.VALIDATION,
                Set.of(PassId.TERRAIN, PassId.BIOMES, PassId.GEOLOGY, PassId.ORES, PassId.SOILS,
                        PassId.FLORA, PassId.TREES, PassId.AQUATIC, PassId.HABITATS));
    }

    public static Set<PassId> dependencies(PassId pass) {
        Set<PassId> dependencies = DEPENDENCIES.get(pass);
        if (dependencies == null) {
            throw new IllegalArgumentException("Unknown WorldPrep pass " + pass);
        }
        return dependencies;
    }

    public static Set<PassId> invalidatedBy(PassId changed) {
        EnumSet<PassId> result = EnumSet.noneOf(PassId.class);
        boolean added;
        do {
            added = false;
            for (var entry : DEPENDENCIES.entrySet()) {
                if (!result.contains(entry.getKey())
                        && (entry.getValue().contains(changed) || entry.getValue().stream().anyMatch(result::contains))) {
                    result.add(entry.getKey());
                    added = true;
                }
            }
        } while (added);
        return Collections.unmodifiableSet(result);
    }

    public static List<PassId> order() {
        return List.of(PassId.TERRAIN, PassId.BIOMES, PassId.GEOLOGY, PassId.ORES, PassId.SOILS,
                PassId.FLORA, PassId.TREES, PassId.AQUATIC, PassId.HABITATS, PassId.VALIDATION);
    }

    private PassGraph() {}
}
