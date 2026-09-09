package fr.proutlost.worldprep.pipeline;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/** Immutable semantic configuration whose canonical identity is independent of insertion order. */
public record SemanticConfig(SortedMap<String, String> values) {
    public SemanticConfig {
        values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    public String fingerprint() {
        CanonicalDigest digest = CanonicalDigest.sha256()
                .putString("worldprep-semantic-config")
                .putInt(values.size());
        values.forEach((key, value) -> digest.putString(key).putString(value));
        return digest.finish();
    }
}
