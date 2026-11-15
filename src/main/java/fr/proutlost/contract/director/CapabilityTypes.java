package fr.proutlost.contract.director;

import java.util.Set;

/** Frozen WD-007 capability vocabulary, plus the sealed D0 synthetic fixture. */
public final class CapabilityTypes {
    private CapabilityTypes() {}
    private static final Set<String> NAMES = Set.of("fixture_capability", "group_arrival", "safe_shore", "fallback_point",
        "motif_trace", "functional_camp", "routine_memory", "signal_vantage", "old_camp_evidence", "testimony_record",
        "arrangement_recording", "old_arrangement_trace", "split_perception_scene", "routine_scene", "departure_route",
        "player_passage_options", "regional_signal", "message_comparison", "transmission_site", "temporary_perturbation", "testimony_contribution");
    public static Id canonical(Id id) {
        return id.equals(new Id("proutlost:camp_routine_memory")) ? new Id("proutlost:routine_memory") : id;
    }
    public static void requireKnown(Id id) {
        Checks.require(id.value().startsWith("proutlost:") && NAMES.contains(id.value().substring("proutlost:".length())), "Unknown required capability");
    }
}
