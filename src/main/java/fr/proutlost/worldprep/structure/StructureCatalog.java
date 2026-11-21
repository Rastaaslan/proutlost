package fr.proutlost.worldprep.structure;
import java.util.*;
public final class StructureCatalog { private final Map<String,StructureCapability> values;
 public StructureCatalog(Collection<StructureCapability> capabilities){var map=new TreeMap<String,StructureCapability>();for(var c:capabilities)if(map.put(c.structureId(),c)!=null)throw new IllegalArgumentException("Duplicate structure id");values=Collections.unmodifiableMap(map);}
 public StructureCapability requireProductionSupported(String id){var c=values.get(id);if(c==null)throw new IllegalStateException("Missing required structure: "+id);if(!c.status().productionSupported())throw new IllegalStateException("Required structure is not safely materializable: "+id);return c;}
 public Collection<StructureCapability> all(){return values.values();}
}
