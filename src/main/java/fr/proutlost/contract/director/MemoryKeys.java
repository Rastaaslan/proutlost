package fr.proutlost.contract.director;

import java.util.*;

/** Canonical row keys. IDs remain opaque; these encodings never derive identity from coordinates. */
public final class MemoryKeys {
    private MemoryKeys() {}
    private static RowKey key(Id world, String kind, Id id) { return new RowKey(world, new Id("proutlost:" + kind), id); }
    public static RowKey world(Id world) { return key(world, "world", world); }
    public static RowKey player(Id world, UUID player) { return key(world, "player", new Id("proutlost:player/" + player)); }
    public static RowKey group(Id world, Id scope, long membershipEpoch) {
        Checks.nonnegative(membershipEpoch, "membership epoch");
        return key(world, "context_group", compound("group", List.of(scope.value(), membershipEpoch)));
    }
    public static RowKey location(Id world, Id location) { return key(world, "location", location); }
    public static RowKey arc(Id world, Id arc) { return key(world, "arc", arc); }
    public static RowKey event(Id world, Id event) { return key(world, "event", event); }
    public static RowKey history(Id world, Id history) { return key(world, "history", history); }
    public static RowKey cooldown(Id world, Id subjectKind, Id subject, Long epoch, Id category) {
        if (epoch != null) Checks.nonnegative(epoch, "membership epoch");
        return key(world, "cooldown", compound("cooldown", Arrays.asList(subjectKind.value(), subject.value(), epoch, category.value())));
    }
    private static Id compound(String kind, List<?> parts) { return new Id("proutlost:" + kind + "/" + Digests.sha256(StrictCodec.encode(parts)).value()); }
}
