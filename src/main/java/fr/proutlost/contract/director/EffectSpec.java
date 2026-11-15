package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record EffectSpec(
    Id effect_key,
    Action action,
    Id adapter_capability,
    AudienceSelector audience_selector,
    @Nullable Id variant_id,
    @Nullable Id anchor_role,
    Id content_ref,
    TypedValue parameters,
    long ttl_ms,
    boolean required) {
    public EffectSpec {
        Objects.requireNonNull(effect_key, "effect_key");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(adapter_capability, "adapter_capability");
        Objects.requireNonNull(audience_selector, "audience_selector");
        Objects.requireNonNull(content_ref, "content_ref");
        Objects.requireNonNull(parameters, "parameters");
        Checks.nonnegative(ttl_ms, "ttl_ms");
        Checks.duration(ttl_ms);
        Checks.require((audience_selector == AudienceSelector.PINNED_VARIANT) == (variant_id != null), "Variant mismatch"); TypedValue.requireAction(action, parameters);
    }
}
