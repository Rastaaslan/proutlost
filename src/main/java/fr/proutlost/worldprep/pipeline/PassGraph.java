package fr.proutlost.worldprep.pipeline;
import java.util.*;

/** Canonical pass dependency and downstream invalidation graph. */
public final class PassGraph {
 private static final Map<PassId,Set<PassId>> DEPENDENCIES=new EnumMap<>(PassId.class);
 static {
  DEPENDENCIES.put(PassId.TERRAIN,Set.of());
  DEPENDENCIES.put(PassId.BIOMES,Set.of(PassId.TERRAIN));
  DEPENDENCIES.put(PassId.GEOLOGY,Set.of(PassId.TERRAIN));
  DEPENDENCIES.put(PassId.ORES,Set.of(PassId.GEOLOGY));
  DEPENDENCIES.put(PassId.SOILS,Set.of(PassId.TERRAIN,PassId.BIOMES,PassId.GEOLOGY));
  DEPENDENCIES.put(PassId.FLORA,Set.of(PassId.TERRAIN,PassId.BIOMES,PassId.SOILS));
  DEPENDENCIES.put(PassId.TREES,Set.of(PassId.TERRAIN,PassId.BIOMES,PassId.SOILS));
  DEPENDENCIES.put(PassId.AQUATIC,Set.of(PassId.TERRAIN,PassId.BIOMES));
  DEPENDENCIES.put(PassId.HABITATS,Set.of(PassId.TERRAIN,PassId.BIOMES,PassId.SOILS,PassId.FLORA,PassId.TREES,PassId.AQUATIC));
  DEPENDENCIES.put(PassId.VALIDATION,Set.of(PassId.HABITATS));
 }
 public static Set<PassId> dependencies(PassId pass){return DEPENDENCIES.get(pass);}
 public static Set<PassId> invalidatedBy(PassId changed){
  EnumSet<PassId> result=EnumSet.noneOf(PassId.class); boolean added;
  do { added=false; for(var e:DEPENDENCIES.entrySet()) if(!result.contains(e.getKey())&&(e.getValue().contains(changed)||e.getValue().stream().anyMatch(result::contains))){result.add(e.getKey());added=true;} } while(added);
  return Collections.unmodifiableSet(result);
 }
 public static List<PassId> order(){return List.of(PassId.TERRAIN,PassId.BIOMES,PassId.GEOLOGY,PassId.ORES,PassId.SOILS,PassId.FLORA,PassId.TREES,PassId.AQUATIC,PassId.HABITATS,PassId.VALIDATION);}
 private PassGraph(){}
}
