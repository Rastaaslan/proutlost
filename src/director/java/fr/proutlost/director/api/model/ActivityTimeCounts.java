package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Configured indexed counts, not inferred from DayPhase. Index i denotes bin i of binCount.
 * The bound is a transport limit, not a gameplay calibration. Overflow is rejected.
 */
public record ActivityTimeCounts(int binCount, List<Long> counts) {
 public ActivityTimeCounts { require(binCount>0&&binCount<=4096,"time bin bound"); counts=list(counts); require(counts.size()==binCount,"time bin count"); long sum=0;for(long count:counts){nonnegative(count);sum=Math.addExact(sum,count);} }
}
