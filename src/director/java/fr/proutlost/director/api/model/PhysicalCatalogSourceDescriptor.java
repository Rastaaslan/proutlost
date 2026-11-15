package fr.proutlost.director.api.model;

import java.util.*;

/** Pure description of exact decoded D0 bytes, without import permission. */
public record PhysicalCatalogSourceDescriptor(ResourceId sourceWorld,ResourceId sourceBuild,
        String revision,Fingerprint contentDigest,Fingerprint canonicalSha,
        Set<ResourceId> regions,Set<ResourceId> locations,List<AnchorSource> anchors) {
    public record AnchorSource(ResourceId location,ResourceId anchor){
        public AnchorSource{Objects.requireNonNull(location);Objects.requireNonNull(anchor);}
    }
    public PhysicalCatalogSourceDescriptor {
        Objects.requireNonNull(sourceWorld);Objects.requireNonNull(sourceBuild);
        Objects.requireNonNull(contentDigest);Objects.requireNonNull(canonicalSha);
        regions=Set.copyOf(regions);locations=Set.copyOf(locations);anchors=List.copyOf(anchors);
        if(revision==null||revision.isBlank()||revision.length()>4096||regions.size()>1024
                ||locations.isEmpty()||locations.size()>1024||anchors.size()>4096
                ||new HashSet<>(anchors).size()!=anchors.size())
            throw new IllegalArgumentException("bounded complete physical descriptor");
        for(var a:anchors)if(!locations.contains(a.location()))throw new IllegalArgumentException("foreign physical anchor");
    }
}
