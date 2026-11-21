package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record TargetedPerceptionPayload(
    int protocol_version,
    Id world_id,
    Id server_id,
    Id server_epoch,
    Id session_epoch,
    Id backup_generation,
    UUID recipient_uuid,
    Id audience_id,
    long membership_epoch,
    Id event_instance_id,
    Id ownership_id,
    long sequence,
    Id dimension_id,
    Operation operation,
    Channel channel,
    long ttl_ms,
    Instant deadline,
    Id content_ref,
    TypedValue payload) {
    public TargetedPerceptionPayload {
        Checks.require(protocol_version > 0, "Positive protocol_version required");
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(server_id, "server_id");
        Objects.requireNonNull(server_epoch, "server_epoch");
        Objects.requireNonNull(session_epoch, "session_epoch");
        Objects.requireNonNull(backup_generation, "backup_generation");
        Objects.requireNonNull(recipient_uuid, "recipient_uuid");
        Objects.requireNonNull(audience_id, "audience_id");
        Checks.nonnegative(membership_epoch, "membership_epoch");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(ownership_id, "ownership_id");
        Checks.nonnegative(sequence, "sequence");
        Objects.requireNonNull(dimension_id, "dimension_id");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(channel, "channel");
        Checks.nonnegative(ttl_ms, "ttl_ms");
        Checks.duration(ttl_ms);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(content_ref, "content_ref");
        Objects.requireNonNull(payload, "payload");
        Checks.require(protocol_version == 1, "Unknown protocol");
    }
}
