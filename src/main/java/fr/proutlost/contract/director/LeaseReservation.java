package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record LeaseReservation(
    StoreTransaction transaction,
    long expected_ledger_revision,
    List<Lease> leases) {
    public LeaseReservation {
        Objects.requireNonNull(transaction, "transaction");
        Checks.nonnegative(expected_ledger_revision, "expected_ledger_revision");
        leases = Checks.copy(leases);
        Checks.require(!leases.isEmpty() && transaction.kind() == TransactionKind.RESERVATION, "Reservation transaction required");
        for (var lease : leases) {
            Checks.require(lease.world_id().equals(transaction.world_id()) && lease.server_epoch().equals(transaction.server_epoch()), "Lease authority mismatch");
            Checks.require(lease.ledger_revision() == expected_ledger_revision, "Lease ledger mismatch");
            Checks.require(transaction.changes().stream().anyMatch(c -> c.replacement().canonical_payload().equals(StrictCodec.encode(lease))), "Lease must be inside transaction");
        }
    }
}
