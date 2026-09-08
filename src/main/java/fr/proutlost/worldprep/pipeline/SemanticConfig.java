package fr.proutlost.worldprep.pipeline;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.SortedMap;
public record SemanticConfig(SortedMap<String,String> values){public SemanticConfig{values=java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(values));}public String fingerprint(){return PlanFingerprint.of(values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toArray(String[]::new)).value();}}
