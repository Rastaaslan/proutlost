package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Closed payload registry. Extensions require a reviewed code/schema change. No arbitrary JSON. */
public record TypedValue(Id type_id, Revision version, Map<String, Scalar> fields) {
    public sealed interface Scalar permits Bool, Number, Text, Identifier, Identifiers, Names, Scope {}
    public record Bool(boolean value) implements Scalar {}
    public record Number(long value) implements Scalar {}
    public record Text(String value) implements Scalar { public Text { Checks.text(value); } }
    public record Identifier(Id value) implements Scalar { public Identifier { Objects.requireNonNull(value); } }
    public record Identifiers(List<Id> value) implements Scalar { public Identifiers { value = Checks.copy(value); } }

    public record Names(List<String> value) implements Scalar {
        public Names { value = Checks.copy(value); value.forEach(Checks::text); }
    }
    public record Scope(AudienceScope value) implements Scalar { public Scope { Objects.requireNonNull(value); } }

    private static final Map<String, Map<String, Class<? extends Scalar>>> REGISTRY = Map.ofEntries(
        Map.entry("boolean", Map.of("value", Bool.class)),
        Map.entry("integer", Map.of("value", Number.class)),
        Map.entry("text", Map.of("value", Text.class)),
        Map.entry("id", Map.of("value", Identifier.class)),
        Map.entry("ids", Map.of("value", Identifiers.class)),
        Map.entry("range", Map.of("min", Number.class, "max", Number.class)),
        Map.entry("sound", Map.of("sound_id", Identifier.class, "volume_milli", Number.class, "radius", Number.class)),
        Map.entry("symbol", Map.of("content", Identifier.class, "offset_milli", Number.class)),
        Map.entry("particle", Map.of("count", Number.class, "spread_milli", Number.class)),
        Map.entry("ambience", Map.of("intensity_milli", Number.class)),
        Map.entry("apparition", Map.of("style", Identifier.class)),
        Map.entry("text_ui", Map.of("message_key", Identifier.class, "arguments", Identifiers.class)),
        Map.entry("cue", Map.of("style", Identifier.class)),
        Map.entry("controlled_entity", Map.of("entity_id", Identifier.class, "safe_behavior_profile", Identifier.class)),
        // Sealed synthetic payloads for D1/D2. These confer no production capability or fact authority.
        Map.entry("fixture_observation", Map.of("evidence_id", Identifier.class)),
        Map.entry("fixture_fact", Map.of("evidence_id", Identifier.class)),
        Map.entry("fixture_capability", Map.of("capacity", Number.class)),
        Map.entry("group_arrival", Map.of("capacity", Number.class)),
        Map.entry("safe_shore", Map.of("capacity", Number.class)),
        Map.entry("fallback_point", Map.of("capacity", Number.class)),
        Map.entry("motif_trace", Map.of("motif_id", Identifier.class)),
        Map.entry("functional_camp", Map.of("owner_scope", Scope.class)),
        Map.entry("routine_memory", Map.of("channels", Names.class)),
        Map.entry("signal_vantage", Map.of("direction_precision", Text.class, "response_origin", Text.class)),
        Map.entry("old_camp_evidence", Map.of("trace_kinds", Identifiers.class)),
        Map.entry("testimony_record", Map.of("record_ids", Identifiers.class, "context_ids", Identifiers.class)),
        Map.entry("arrangement_recording", Map.of("slot_count", Number.class)),
        Map.entry("old_arrangement_trace", Map.of("slot_count", Number.class, "trace_ref", Identifier.class)),
        Map.entry("split_perception_scene", Map.of("sound_count", Number.class, "symbol_count", Number.class, "movable_cue_positions", Bool.class)),
        Map.entry("routine_scene", Map.of("channels", Names.class, "detail_replay", Bool.class)),
        Map.entry("departure_route", Map.of("trace_kinds", Identifiers.class, "segment_order", Identifiers.class)),
        Map.entry("player_passage_options", Map.of("repair_points", Number.class, "detour_points", Number.class)),
        Map.entry("regional_signal", Map.of("signal_id", Identifier.class, "physical_source", Bool.class)),
        Map.entry("message_comparison", Map.of("version_record_ids", Identifiers.class)),
        Map.entry("transmission_site", Map.of("modes", Names.class)),
        Map.entry("temporary_perturbation", Map.of("channels", Names.class, "fallback_required", Bool.class)),
        Map.entry("testimony_contribution", Map.of("media", Identifiers.class))
    );
    public TypedValue {
        Objects.requireNonNull(type_id); Objects.requireNonNull(version);
        if (type_id.equals(new Id("proutlost:camp_routine_memory"))) type_id = new Id("proutlost:routine_memory");
        fields = Checks.copy(fields);
        Checks.require(version.value().equals("1"), "Unknown typed payload version");
        String name = type_id.value().replaceFirst("^proutlost:", "");
        var expected = REGISTRY.get(name);
        Checks.require(type_id.value().startsWith("proutlost:") && expected != null, "Unknown required payload codec");
        Checks.require(fields.keySet().equals(expected.keySet()), "Unknown/missing payload fields");
        for (var entry : expected.entrySet()) Checks.require(entry.getValue().isInstance(fields.get(entry.getKey())), "Payload field type");
        if (fields.containsKey("channels")) for (var channel : ((Names)fields.get("channels")).value()) Channel.valueOf(channel);
        if (fields.containsKey("modes")) Checks.require(!((Names)fields.get("modes")).value().isEmpty() && Set.of("FILTER", "AMPLIFY", "MASTERED").containsAll(((Names)fields.get("modes")).value()), "Unknown transmission mode");
        if (fields.containsKey("slot_count")) Checks.require(number(fields,"slot_count") >= 3 && number(fields,"slot_count") <= 5, "Slot count");
        if (fields.containsKey("capacity")) Checks.require(number(fields,"capacity") > 0, "Capacity");
        if (name.equals("signal_vantage")) Checks.require(fields.get("direction_precision").equals(new Text("APPROXIMATE")) && fields.get("response_origin").equals(new Text("ISLAND_INTERIOR")), "Signal contract");
        if (name.equals("split_perception_scene")) Checks.require(number(fields,"sound_count") == 3 && number(fields,"symbol_count") == 3 && fields.get("movable_cue_positions").equals(new Bool(true)), "Split perception contract");
        if (name.equals("player_passage_options")) for (String key : List.of("repair_points","detour_points")) Checks.require(number(fields,key) >= 2 && number(fields,key) <= 3, "Player passage count");
        for (String key : List.of("detail_replay","fallback_required")) if (fields.containsKey(key)) Checks.require(fields.get(key).equals(new Bool(true)), "Required capability support");
        if (fields.containsKey("physical_source")) Checks.require(fields.get("physical_source").equals(new Bool(false)), "Regional signal is nonphysical");
        if (name.equals("range")) Checks.require(number(fields, "min") <= number(fields, "max"), "Reversed range");
        for (var e : fields.entrySet()) if (e.getValue() instanceof Number n && !Set.of("value", "min", "max", "offset_milli").contains(e.getKey()))
            Checks.require(n.value() >= 0 && n.value() <= 4096, "Payload numeric cap");
        if (fields.containsKey("offset_milli")) Checks.require(Math.abs(number(fields, "offset_milli")) <= 4096 && number(fields, "offset_milli") != Long.MIN_VALUE, "Offset cap");
        if (fields.containsKey("volume_milli")) Checks.require(number(fields, "volume_milli") <= 1000, "Volume cap");
        if (fields.containsKey("intensity_milli")) Checks.require(number(fields, "intensity_milli") <= 1000, "Intensity cap");
    }
    private static long number(Map<String, Scalar> fields, String key) { return ((Number)fields.get(key)).value(); }
    public static TypedValue bool(boolean value) { return new TypedValue(new Id("proutlost:boolean"), new Revision("1"), Map.of("value", new Bool(value))); }
    static void requireAction(Action action, TypedValue payload) {
        String codec = switch (action) {
            case PLAY_SOUND -> "sound"; case SHOW_SYMBOL -> "symbol"; case EMIT_PARTICLE -> "particle";
            case RENDER_AMBIENCE -> "ambience"; case SHOW_APPARITION -> "apparition";
            case SHOW_TEXT -> "text_ui"; case SHOW_CUE -> "cue"; case SPAWN_CONTROLLED_ENTITY -> "controlled_entity";
        };
        Checks.require(payload.type_id().equals(new Id("proutlost:" + codec)), "Effect/action codec mismatch");
    }
}
