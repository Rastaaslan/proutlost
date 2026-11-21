package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record AudienceScope(
    Id scope_id,
    AudienceKind kind,
    Set<UUID> member_uuids,
    long membership_epoch) {
    public AudienceScope {
        Objects.requireNonNull(scope_id, "scope_id");
        Objects.requireNonNull(kind, "kind");
        member_uuids = Checks.copy(member_uuids);
        Checks.nonnegative(membership_epoch, "membership_epoch");
        Checks.require(kind != AudienceKind.INDIVIDUAL || member_uuids.size() == 1, "Individual audience requires one UUID"); Checks.require(kind != AudienceKind.CONTEXT_GROUP || !member_uuids.isEmpty(), "Empty context group");
    }
}
