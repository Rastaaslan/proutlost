package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record TuningProfile(
    SchemaVersion schema_version,
    Id profile_id,
    Revision revision,
    Digest digest,
    Map<BudgetCategory,Integer> budgets,
    Map<Id,Integer> weights,
    Map<Id,Long> cooldown_ms,
    Map<Id,Long> grace_ms,
    List<TimeWindow> quiet_periods,
    HealthThresholds health,
    Map<MemoryKind,Long> retention_ms,
    Map<Id,Integer> summary_caps,
    MemoryGridDefinition grid,
    Map<Id,Integer> vote_calibration,
    Map<Id,Integer> contribution_calibration) {
    public TuningProfile {
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(profile_id, "profile_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(digest, "digest");
        budgets = Checks.copy(budgets);
        weights = Checks.copy(weights);
        cooldown_ms = Checks.copy(cooldown_ms);
        grace_ms = Checks.copy(grace_ms);
        quiet_periods = Checks.copy(quiet_periods);
        Objects.requireNonNull(health, "health");
        retention_ms = Checks.copy(retention_ms);
        summary_caps = Checks.copy(summary_caps);
        Objects.requireNonNull(grid, "grid");
        vote_calibration = Checks.copy(vote_calibration);
        contribution_calibration = Checks.copy(contribution_calibration);
        Checks.require(schema_version.equals(SchemaVersion.EXECUTION), "Wrong record schema version");
        Profiles.validate(budgets, weights, cooldown_ms, grace_ms, retention_ms, summary_caps, vote_calibration, contribution_calibration);
    }
}
