package fr.proutlost.director.catalog;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Immutable descriptors injected by composition; registering content never registers an effect. */
public final class PrimitiveRegistry {
    private final Map<PrimitiveId, PrimitiveDescriptor> descriptors;
    public PrimitiveRegistry(Collection<PrimitiveDescriptor> entries, ProviderMode mode) {
        Map<PrimitiveId, PrimitiveDescriptor> result = new TreeMap<>();
        for (var entry : entries) {
            Objects.requireNonNull(entry);
            // Both directions are security boundaries: a real physical provider
            // must never be silently injected into a disposable TEST_ONLY lane.
            if (entry.mode() != mode) throw new IllegalArgumentException("primitive provider mode mismatch");
            if (result.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("duplicate primitive " + entry.id());
        }
        for (var entry : result.values()) entry.inverse().ifPresent(id -> {
            if (!result.containsKey(id)) throw new IllegalArgumentException("unknown inverse " + id);
        });
        descriptors = Map.copyOf(result);
    }
    public Map<PrimitiveId, PrimitiveDescriptor> descriptors() { return descriptors; }
}
