package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ActivityHistogram(Map<Sector,Long> sectors, Map<DistanceBand,Long> rings, Map<DayPhase,Long> dayPhases, Map<GroupSizeBucket,Long> groups, Optional<ActivityTimeCounts> activityTime) {
 /** Legacy inputs have no indexed time evidence; never spread phase totals into bins. */
 public ActivityHistogram(Map<Sector,Long> sectors,Map<DistanceBand,Long> rings,Map<DayPhase,Long> dayPhases,Map<GroupSizeBucket,Long> groups){this(sectors,rings,dayPhases,groups,Optional.empty());}
 public ActivityHistogram { Objects.requireNonNull(activityTime); sectors=map(sectors); rings=map(rings); dayPhases=map(dayPhases); groups=map(groups); sectors.values().forEach(Checks::nonnegative); rings.values().forEach(Checks::nonnegative); dayPhases.values().forEach(Checks::nonnegative); groups.values().forEach(Checks::nonnegative); }
}
