package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StateAddress(
    ScopeKind scope_kind,
    Id subject_id,
    @Nullable Long membership_epoch,
    Id state_key) {
    public StateAddress {
        Objects.requireNonNull(scope_kind, "scope_kind");
        Objects.requireNonNull(subject_id, "subject_id");
        if (membership_epoch != null) Checks.nonnegative(membership_epoch, "membership_epoch");
        Objects.requireNonNull(state_key, "state_key");
        Checks.require((scope_kind == ScopeKind.CONTEXT_GROUP) == (membership_epoch != null), "Group epoch required only for context groups");
    }
}
