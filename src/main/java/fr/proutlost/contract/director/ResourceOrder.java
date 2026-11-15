package fr.proutlost.contract.director;

import java.util.Comparator;

/** CB-165 lock acquisition order, PHYSICAL_FOOTPRINT appended last. ASCII IDs preserve UTF-8 order. */
public final class ResourceOrder {
    private ResourceOrder() {}
    public static final Comparator<ResourceKey> CANONICAL = Comparator.comparing(ResourceKey::kind)
        .thenComparing(ResourceKey::dimension_id, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(ResourceKey::scope_id)
        .thenComparing(ResourceKey::membership_epoch, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(ResourceKey::sub_id, Comparator.nullsFirst(Comparator.naturalOrder()));
}
