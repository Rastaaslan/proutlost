package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EffectIntent(
    Id world_id,
    SchemaVersion schema_version,
    Id intent_id,
    Id event_instance_id,
    Id effect_key,
    DefinitionRef definition,
    Id adapter_id,
    AudienceScope audience,
    @Nullable LocationRef location,
    @Nullable AnchorRef anchor,
    Channel channel,
    TypedValue parameters,
    Id content_ref,
    Instant expires_at,
    IntentStatus status,
    EffectToken token,
    long lease_fence,
    @Nullable Id last_error) {
    public EffectIntent {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(intent_id, "intent_id");
        Objects.requireNonNull(event_instance_id, "event_instance_id");
        Objects.requireNonNull(effect_key, "effect_key");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(adapter_id, "adapter_id");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(content_ref, "content_ref");
        Objects.requireNonNull(expires_at, "expires_at");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(token, "token");
        Checks.nonnegative(lease_fence, "lease_fence");
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Checks.require(world_id.equals(token.world_id()) && event_instance_id.equals(token.event_instance_id()) && effect_key.equals(token.effect_key()), "Token identity mismatch");
    }
}
