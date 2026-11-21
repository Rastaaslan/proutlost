package fr.proutlost.worldprep.orchestration;

import java.util.*;

/** Finite census accounting, independent of Minecraft classes. A large existing population
 * refuses augmentation instead of truncating counts. Empty chunks never grow the maps.
 */
public record DiskPopulationCounts(int total,Map<String,Integer> species,Map<String,Integer> regions,Map<String,Integer> chunks) {
    public DiskPopulationCounts {
        if(total<0||total>256)throw new IllegalArgumentException("Existing population exceeds supported global policy");
        species=checked(species,total);regions=checked(regions,total);chunks=checked(chunks,total);
    }
    private static Map<String,Integer> checked(Map<String,Integer> map,int total) {
        if(map==null||map.size()>256)throw new IllegalArgumentException("Bounded census map required");
        long sum=0;
        for(var entry:map.entrySet()) {
            if(entry.getKey()==null||entry.getKey().isBlank()||entry.getKey().length()>512||entry.getValue()==null||entry.getValue()<1||entry.getValue()>256)
                throw new IllegalArgumentException("Invalid census count");
            sum+=entry.getValue();
        }
        if(sum!=total)throw new IllegalArgumentException("Census totals disagree");
        return Collections.unmodifiableMap(new TreeMap<>(map));
    }
    public static DiskPopulationCounts empty(){return new DiskPopulationCounts(0,Map.of(),Map.of(),Map.of());}
    public DiskPopulationCounts plus(DiskPopulationCounts other) {
        return new DiskPopulationCounts(Math.addExact(total,other.total),merge(species,other.species),merge(regions,other.regions),merge(chunks,other.chunks));
    }
    private static Map<String,Integer> merge(Map<String,Integer> a,Map<String,Integer> b) {
        var result=new TreeMap<>(a);b.forEach((k,v)->result.merge(k,v,Math::addExact));return result;
    }
}
