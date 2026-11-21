package fr.proutlost.worldprep.location;
import fr.proutlost.worldprep.area.WorldPrepArea;
public record LocationZone(String id,WorldPrepArea bounds,Kind kind){public LocationZone{java.util.Objects.requireNonNull(id);java.util.Objects.requireNonNull(bounds);java.util.Objects.requireNonNull(kind);if(!id.equals(bounds.id()))throw new IllegalArgumentException("Zone and bounds IDs differ");}public enum Kind{PROTECTED,MANUAL_ENVIRONMENT,TRANSFORMABLE}public boolean contains(String dimension,int x,int z){return bounds.dimension().equals(dimension)&&bounds.contains(x,z);}}
