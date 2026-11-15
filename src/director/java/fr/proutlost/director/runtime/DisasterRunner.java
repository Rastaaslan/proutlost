package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure phase decisions, to be journaled by the coordinator before any associated action.
 * Never confers impact or stabilization authority. All five recipe families share these guards.
 */
public final class DisasterRunner {
    public enum Family { LANDSLIDE, FLOOD, FIRE, STORM, COLLAPSE }
    public record Conditions(Tick now, HealthMode health, Set<PlayerId> audience, Epoch audienceRevision,
                             boolean contextSafe, boolean impactComplete, boolean stabilizationVerified) {
        public Conditions { Objects.requireNonNull(now);Objects.requireNonNull(health);audience=Set.copyOf(audience);Objects.requireNonNull(audienceRevision); }
    }
    private DisasterRunner() {}
    public static boolean warningValid(WarningEvidence warning,Conditions live) {
        if(!warning.readable() || !warning.persistentPerimeter() || !warning.audience().containsAll(live.audience())
                || !warning.audienceRevision().equals(live.audienceRevision()) || live.now().compareTo(warning.deployedTick())<0)return false;
        return live.now().value()-warning.deployedTick().value()>=warning.leadTime().value();
    }
    /** A warning from WATCH or an earlier attempt cannot authorize a new IMPACT.
     * The WARN-entry tick is the revision's last durable updatedTick. A real
     * warning must be deployed STRICTLY AFTER the entry, not in the same tick
     * as a stale WATCH-era warning. This supplements audience and lead-time.
     */
    public static boolean warningValidSince(WarningEvidence warning,Conditions live,Tick warnEnteredAt) {
        Objects.requireNonNull(warnEnteredAt);
        return warning.deployedTick().compareTo(warnEnteredAt)>0 && warningValid(warning,live);
    }
    public static DisasterStage next(DisasterStage stage,UnitValue risk,UnitValue threshold,WarningEvidence warning,Conditions live) {
        Objects.requireNonNull(stage);Objects.requireNonNull(risk);Objects.requireNonNull(threshold);
        Objects.requireNonNull(warning);Objects.requireNonNull(live);
        boolean healthy=live.health()==HealthMode.NORMAL;
        return switch(stage) {
            case WATCH -> healthy && live.contextSafe() && risk.value()>=threshold.value()?DisasterStage.WARN:DisasterStage.WATCH;
            case WARN -> !healthy || !live.contextSafe()?DisasterStage.STABILIZE
                    : warningValid(warning,live)?DisasterStage.IMPACT:DisasterStage.WARN;
            case IMPACT -> !healthy || !live.contextSafe() || live.impactComplete()?DisasterStage.STABILIZE
                    : !warningValid(warning,live)?DisasterStage.WARN:DisasterStage.IMPACT;
            case STABILIZE -> live.stabilizationVerified()?DisasterStage.RESOLVED:DisasterStage.STABILIZE;
            case RESOLVED, ABORTED_SAFE -> stage;
        };
    }
    /** Old WARN is not evidence for an audience after a reboot. First stop existing hazards. */
    public static DisasterStage recover(DisasterStage persisted) {
        return switch(persisted) {
            case IMPACT, STABILIZE -> DisasterStage.STABILIZE;
            case WARN -> DisasterStage.WARN;
            default -> persisted;
        };
    }
}
