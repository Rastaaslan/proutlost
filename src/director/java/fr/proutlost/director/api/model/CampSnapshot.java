package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record CampSnapshot(AnchorId anchorId, long independentVisits, DurationTicks occupiedTicks, Map<String,Long> sectorCounts, Map<String,Long> dayPhaseCounts, int groupSizeBucket, UnitValue confidence, Optional<ActivityTimeCounts> activityTime) {
 public CampSnapshot(AnchorId anchorId,long independentVisits,DurationTicks occupiedTicks,Map<String,Long> sectorCounts,Map<String,Long> dayPhaseCounts,int groupSizeBucket,UnitValue confidence){this(anchorId,independentVisits,occupiedTicks,sectorCounts,dayPhaseCounts,groupSizeBucket,confidence,Optional.empty());}
 public CampSnapshot { Objects.requireNonNull(activityTime); Objects.requireNonNull(anchorId); nonnegative(independentVisits); Objects.requireNonNull(occupiedTicks); sectorCounts=map(sectorCounts); dayPhaseCounts=map(dayPhaseCounts); nonnegative(groupSizeBucket); Objects.requireNonNull(confidence);  }
}
