package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record AdapterHealth(
    Id adapter_id,
    Revision revision,
    HealthStatus status,
    Instant observed_at,
    Instant expires_at,
    Set<Id> reason_codes,
    int active_slots,
    int uncertain_slots) {
    public AdapterHealth {
        Objects.requireNonNull(adapter_id, "adapter_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observed_at, "observed_at");
        Objects.requireNonNull(expires_at, "expires_at");
        reason_codes = Checks.copy(reason_codes);
        Checks.nonnegative(active_slots, "active_slots");
        Checks.nonnegative(uncertain_slots, "uncertain_slots");
    }
}
