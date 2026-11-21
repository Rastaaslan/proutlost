package fr.proutlost.contract.director;

import java.util.*;

/** Pure fail-closed evaluation on one caller-pinned snapshot. Never acquires locks or mutates state. */
public final class Compatibility {
    private Compatibility() {}
    public static boolean permits(LocationConstraint constraint, Map<StateAddress, StateValue> snapshot,
                                  Map<StateAddress, Long> expectedRevisions) {
        return permits(constraint.requires_state(), constraint.forbids_state(), constraint.locks_location_until(), snapshot, expectedRevisions);
    }
    public static boolean permits(List<StatePredicate> requires, List<StatePredicate> forbids, StatePredicate until,
                                  Map<StateAddress, StateValue> snapshot, Map<StateAddress, Long> expectedRevisions) {
        for (var p : requires) if (!known(p, snapshot, expectedRevisions) || !matches(p, snapshot)) return false;
        for (var p : forbids) if (!known(p, snapshot, expectedRevisions) || matches(p, snapshot)) return false;
        return until == null || known(until, snapshot, expectedRevisions) && matches(until, snapshot);
    }
    private static boolean known(StatePredicate p, Map<StateAddress, StateValue> states, Map<StateAddress, Long> revisions) {
        var value = states.get(p.address()); var revision = revisions.get(p.address());
        return value != null && revision != null && value.revision() == revision;
    }
    private static boolean matches(StatePredicate p, Map<StateAddress, StateValue> states) { return p.expected().equals(states.get(p.address()).value()); }
}
