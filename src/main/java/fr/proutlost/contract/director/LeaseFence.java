package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LeaseFence(
    Id world_id,
    Id lease_id,
    long fence,
    Id server_epoch) {
    public LeaseFence {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(lease_id, "lease_id");
        Checks.nonnegative(fence, "fence");
        Objects.requireNonNull(server_epoch, "server_epoch");
    }
}
