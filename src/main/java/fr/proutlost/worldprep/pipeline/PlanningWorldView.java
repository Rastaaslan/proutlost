package fr.proutlost.worldprep.pipeline;
import java.util.*;
import java.util.function.Function;

/** Read-only overlay: the newest sealed upstream pass wins over actual world state. */
public final class PlanningWorldView<K,V> {
 private final Function<K,V> actual; private final List<Map<K,V>> overlays;
 public PlanningWorldView(Function<K,V> actual,List<Map<K,V>> upstreamInPriorityOrder){this.actual=Objects.requireNonNull(actual);this.overlays=upstreamInPriorityOrder.stream().map(Map::copyOf).toList();}
 public V get(K key){for(Map<K,V> overlay:overlays)if(overlay.containsKey(key))return overlay.get(key);return actual.apply(key);}
}
