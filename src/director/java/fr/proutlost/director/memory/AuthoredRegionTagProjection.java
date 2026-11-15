package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure projection of committed, operator-authored location semantics.
 * Region and location tags stay regional; only ImportedAnchor.tags belongs
 * on an AnchorRecord. No physical survey, participant or event fact is made.
 */
public final class AuthoredRegionTagProjection {
    private AuthoredRegionTagProjection(){}
    private static void require(boolean value,String why){
        if(!value)throw new IllegalArgumentException(why);
    }
    public static Map<RegionId,Set<TagId>> fromCommittedImports(
            Collection<LocationImport> imports,MemoryView memory){
        Map<RegionId,Set<TagId>> result=new TreeMap<>();
        Set<RegionId> declared=new HashSet<>();
        for(var input:imports){
            require(input.worldId().equals(memory.scopes().worldId()),"authored semantics world");
            Map<RegionId,ImportedRegion> own=new HashMap<>();
            for(var region:input.regions()){
                require(declared.add(region.regionId()),"authored region source collision");
                own.put(region.regionId(),region);
                var actual=memory.regionRecords().get(region.regionId());
                require(actual!=null&&actual.dimension().equals(region.dimension()),
                    "committed authored region missing/dimension changed");
                if(actual.status()==RegionStatus.ACTIVE||actual.status()==RegionStatus.DORMANT)
                    result.put(region.regionId(),new HashSet<>(region.tags()));
            }
            for(var location:input.locations()){
                var actual=memory.locations().get(location.locationId());
                var b=location.bounds();
                var expected=new Aabb(location.dimension(),b.minX(),b.minY(),b.minZ(),
                    b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
                require(actual!=null&&actual.sourceNamespace().equals(input.sourceNamespace())
                    &&actual.importRevision().equals(input.exportRevision())
                    &&actual.dimension().equals(location.dimension())
                    &&actual.bounds().equals(expected)&&actual.tags().equals(location.tags()),
                    "committed authored location does not match source");
                if(!actual.active())continue;
                for(var id:location.regionIds()){
                    require(own.containsKey(id),"location references foreign authored region");
                    var tags=result.get(id);
                    if(tags!=null)tags.addAll(location.tags());
                }
            }
        }
        Map<RegionId,Set<TagId>> immutable=new TreeMap<>();
        result.forEach((id,tags)->immutable.put(id,Set.copyOf(tags)));
        return Map.copyOf(immutable);
    }
    /** Only existing real region snapshots can receive authored tags.
     * Unrelated IDs never manufacture region records, memberships or anchors. */
    public static Map<RegionId,RegionSnapshot> merge(
            Map<RegionId,RegionSnapshot> real,Map<RegionId,Set<TagId>> authored){
        Objects.requireNonNull(real);Objects.requireNonNull(authored);
        Map<RegionId,RegionSnapshot> result=new TreeMap<>();
        for(var entry:real.entrySet()){
            var value=entry.getValue();
            require(entry.getKey().equals(value.region().regionId()),"region snapshot identity");
            Set<TagId> tags=new HashSet<>(value.tags());
            tags.addAll(authored.getOrDefault(entry.getKey(),Set.of()));
            result.put(entry.getKey(),new RegionSnapshot(value.region(),tags,
                value.presentPlayers(),value.camps(),value.risks()));
        }
        return Map.copyOf(result);
    }
}
