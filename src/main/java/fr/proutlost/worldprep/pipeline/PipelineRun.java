package fr.proutlost.worldprep.pipeline;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.*;

/** Exact immutable collection used by preview/apply-all; never reconstructed from latest plans. */
public record PipelineRun(UUID id,String areaId,String dimension,String profile,String baseWorldFingerprint,
 Map<PassId,UUID> plans,String rootFingerprint,State state){
 public enum State { PLANNING, SEALED, APPLYING, APPLIED, STALE, FAILED }
 public PipelineRun { plans=Collections.unmodifiableMap(new EnumMap<>(plans));new PlanFingerprint(baseWorldFingerprint);new PlanFingerprint(rootFingerprint); }
 public static String fingerprint(Map<PassId,UUID> plans){return PlanFingerprint.of(PassGraph.order().stream().filter(plans::containsKey).map(p->p.name()+"="+plans.get(p)).toArray(String[]::new)).value();}
 public void requireSealed(){if(state!=State.SEALED)throw new IllegalStateException("Pipeline run is not sealed");}
}
