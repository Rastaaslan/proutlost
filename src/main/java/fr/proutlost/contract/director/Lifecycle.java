package fr.proutlost.contract.director;

import static fr.proutlost.contract.director.Vocabulary.*;

/** Validation only. No lifecycle coordinator or side effects. */
public final class Lifecycle {
    private Lifecycle() {}
    public static boolean isTerminal(LifecycleState state) {
        return switch (state) { case COMPLETED, CANCELLED, EXPIRED, FAILED -> true; default -> false; };
    }
    public static void requireTransition(EventInstance before, EventInstance after) {
        Checks.require(before.world_id().equals(after.world_id()) && before.event_instance_id().equals(after.event_instance_id()), "Instance identity changed");
        Checks.require(after.state_revision() == Math.addExact(before.state_revision(), 1), "State CAS mismatch");
        Checks.require(before.definition().equals(after.definition()) && before.catalog_revision().equals(after.catalog_revision())
            && before.tuning_revision().equals(after.tuning_revision()) && before.audience().equals(after.audience()), "Active pins changed");
        Checks.require(after.fact_ids().containsAll(before.fact_ids()) && after.completed_result_keys().containsAll(before.completed_result_keys()), "Committed evidence cannot be removed");
        Checks.require(before.location_refs().equals(after.location_refs()) && java.util.Objects.equals(before.opportunity(), after.opportunity()), "Binding pins changed");
        Checks.require(!isTerminal(before.state()), "Terminal history is immutable");
        // Once an outcome is requested, cleanup/recovery may not clear or replace it.
        if (before.requested_outcome() != null)
            Checks.require(before.requested_outcome() == after.requested_outcome(), "Requested outcome changed");
        if (after.requested_outcome() == Outcome.COMPLETED && before.requested_outcome() != Outcome.COMPLETED)
            Checks.require(before.state() == LifecycleState.COMPLETING && after.state() == LifecycleState.CLEANING,
                "Success may only be committed by COMPLETING to CLEANING");
        if (after.state() == LifecycleState.CLEANING)
            Checks.require(after.requested_outcome() != null, "Cleanup requires a requested outcome");
        boolean valid = after.state() == LifecycleState.CLEANING || after.state() == LifecycleState.RECOVERY_REQUIRED;
        valid |= switch (before.state()) {
            case OFFERED -> after.state() == LifecycleState.RESERVED;
            case RESERVED -> after.state() == LifecycleState.STARTING;
            case STARTING -> after.state() == LifecycleState.ACTIVE;
            case ACTIVE -> after.state() == LifecycleState.COMPLETING;
            case COMPLETING -> false;
            case CLEANING -> isTerminal(after.state()) && before.requested_outcome() != null
                && after.state().name().equals(before.requested_outcome().name());
            // Recovery requires evidence-based revalidation by D4; no generic ACTIVE shortcut.
            default -> false;
        };
        Checks.require(valid, "Illegal lifecycle transition");
    }
}
