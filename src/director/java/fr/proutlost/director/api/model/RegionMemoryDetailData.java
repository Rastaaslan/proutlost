package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Explicit section of an exact region read. Optional bodies are absent because
 * they belong to another section, never because a cold head is known absent. */
public record RegionMemoryDetailData(RegionId regionId,Section section,int offset,int total,
        Optional<RegionRecord> region,Optional<RegionMemory> memory,Set<TagId> tags,
        List<AnchorRecord> anchors,List<LocationRecord> locations) implements AdminData {
    public enum Section { REGION, MEMORY, TAGS, ANCHORS, LOCATIONS }
    public RegionMemoryDetailData {
        Objects.requireNonNull(regionId);Objects.requireNonNull(section);Objects.requireNonNull(region);Objects.requireNonNull(memory);
        tags=set(tags);anchors=list(anchors);locations=list(locations);
        require(offset>=0&&total>=offset&&anchors.size()<=96&&locations.size()<=96,"memory section bounds");
        require(region.isPresent()==(section==Section.REGION)&&memory.isPresent()==(section==Section.MEMORY),"memory section bodies");
        require(section==Section.TAGS||tags.isEmpty(),"memory section tags");
        require(section==Section.ANCHORS||anchors.isEmpty(),"memory section anchors");
        require(section==Section.LOCATIONS||locations.isEmpty(),"memory section locations");
        if(region.isPresent())require(region.get().regionId().equals(regionId)&&offset==0&&total==1,"region section identity");
        if(memory.isPresent())require(memory.get().regionId().equals(regionId)&&offset==0&&total==1,"memory section identity");
        if(section==Section.ANCHORS)require(offset+anchors.size()<=total,"anchor section range");
        if(section==Section.LOCATIONS)require(offset+locations.size()<=total,"location section range");
        if(section==Section.TAGS)require(offset==0&&total==tags.size(),"tag section range");
    }
}
