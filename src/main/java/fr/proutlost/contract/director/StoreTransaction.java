package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record StoreTransaction(
    Id world_id,
    Id transaction_id,
    Id server_epoch,
    long expected_store_sequence,
    TransactionKind kind,
    List<RowChange> changes,
    Set<Id> dedup_keys) {
    public StoreTransaction {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(transaction_id, "transaction_id");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Checks.nonnegative(expected_store_sequence, "expected_store_sequence");
        Objects.requireNonNull(kind, "kind");
        changes = Checks.copy(changes);
        dedup_keys = Checks.copy(dedup_keys);
        Checks.require(!changes.isEmpty(), "Empty transaction"); var keys = new HashSet<RowKey>(); for (var c : changes) { Checks.require(c.key().world_id().equals(world_id), "Cross-world transaction"); Checks.require(keys.add(c.key()), "Duplicate row"); }
    }
}
