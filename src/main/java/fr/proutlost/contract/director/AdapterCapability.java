package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record AdapterCapability(
    Id adapter_id,
    Revision revision,
    Id capability_id,
    int protocol_version,
    Set<Id> supported_actions,
    Set<Channel> channels,
    Id parameter_codec,
    int max_slots,
    boolean idempotent_start,
    boolean ownership_reconcile,
    boolean non_block_only,
    Set<Id> required_registry_ids,
    Set<Id> required_tags) {
    public AdapterCapability {
        Objects.requireNonNull(adapter_id, "adapter_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(capability_id, "capability_id");
        Checks.require(protocol_version > 0, "Positive protocol_version required");
        supported_actions = Checks.copy(supported_actions);
        channels = Checks.copy(channels);
        Objects.requireNonNull(parameter_codec, "parameter_codec");
        Checks.require(max_slots > 0, "Positive max_slots required");
        required_registry_ids = Checks.copy(required_registry_ids);
        required_tags = Checks.copy(required_tags);
        Checks.require(non_block_only, "Ordinary effect must be non-block only");
    }
}
