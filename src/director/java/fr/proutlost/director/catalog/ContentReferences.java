package fr.proutlost.director.catalog;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Registry snapshots from composition, separated by reference type (a tag is not an asset). */
public record ContentReferences(ProviderMode mode, Map<Kind, Set<ResourceId>> known,
        Map<ResourceId, MutationProfile> mutations, Map<ResourceId, WarningProfile> warnings,
        Map<ResourceId, DamageProfile> damages, Set<CapabilityId> capabilities, Set<String> claims) {
    public enum Kind { TAG, FACT, SOUND, PARTICLE, MODEL, LOCAL_WEATHER, ENTITY, CHOICE, RISK, CODEC, RESOLVER, CLEANUP, MERGE, DAMAGE_TYPE }
    public ContentReferences {
        Objects.requireNonNull(mode);
        Map<Kind, Set<ResourceId>> copy = new EnumMap<>(Kind.class);
        known.forEach((k, v) -> copy.put(k, Set.copyOf(v))); known = Map.copyOf(copy);
        mutations = Map.copyOf(mutations); warnings = Map.copyOf(warnings); damages = Map.copyOf(damages);
        capabilities = Set.copyOf(capabilities); claims = Set.copyOf(claims);
        mutations.forEach((id, v) -> { if (!id.equals(v.recipeId())) throw new IllegalArgumentException("mutation registry key"); });
        warnings.forEach((id, v) -> { if (!id.equals(v.id())) throw new IllegalArgumentException("warning registry key"); });
        damages.forEach((id, v) -> { if (!id.equals(v.id())) throw new IllegalArgumentException("damage registry key"); });
    }
    boolean contains(Kind kind, String id) { return known.getOrDefault(kind, Set.of()).contains(new ResourceId(id)); }
}
