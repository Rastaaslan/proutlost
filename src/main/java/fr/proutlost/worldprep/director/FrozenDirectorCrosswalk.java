package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import fr.proutlost.contract.director.Vocabulary.CatalogState;
import java.io.IOException;
import java.util.*;

/** Strict D0 catalog -> G0 Director identity preflight, NOT the importer.
 *
 * D0 uses resource Ids such as proutlost:site/..., while the G0 Director
 * LocationImport requires canonical UUIDs (WorldId, RegionId, LocationId).
 * Every identity crosswalk MUST be supplied by the Director integration
 * authority, pinned to the exact D0 ready catalog digest. Never infer UUIDs
 * or silently discard unsupported semantic/physical capabilities.
 */
public final class FrozenDirectorCrosswalk {
    public record Pair(Id worldPrepId,UUID directorId) {
        public Pair {
            Objects.requireNonNull(worldPrepId);
            Objects.requireNonNull(directorId);
        }
    }
    public record Manifest(int version,Id worldPrepWorldId,UUID directorWorldId,
                           Digest catalogDigest,List<Pair> regions,List<Pair> locations,
                           String status) {
        public Manifest {
            regions=List.copyOf(regions);
            locations=List.copyOf(locations);
            if(version!=1||worldPrepWorldId==null||directorWorldId==null||catalogDigest==null
                ||!"IDENTITY_CROSSWALK_VALIDATED_IMPORT_NOT_ADMITTED".equals(status))
                throw new IllegalArgumentException("No implicit D0/G0 identity translation");
        }
    }

    /** D0 Bounds are INCLUSIVE; G0 ImportBounds are half-open / exclusive.
     * Only a future real Director consumer may authorize the completed import.
     */
    public record G0ExclusiveBounds(String dimension,int minX,int minY,int minZ,
                    int maxXExclusive,int maxYExclusive,int maxZExclusive) {
        public G0ExclusiveBounds {
            if(dimension==null||dimension.isBlank()
                ||minX>=maxXExclusive||minY>=maxYExclusive||minZ>=maxZExclusive)
                throw new IllegalArgumentException("Invalid half-open G0 import box");
            long width=(long)maxXExclusive-minX;
            long height=(long)maxYExclusive-minY;
            long depth=(long)maxZExclusive-minZ;
            Math.multiplyExact(Math.multiplyExact(width,height),depth);
        }
    }
    public static G0ExclusiveBounds halfOpen(Bounds d0)throws IOException {
        if(d0==null)throw new IOException("Frozen D0 bounds required");
        try {
            return new G0ExclusiveBounds(d0.dimension_id().value(),
                d0.min().x(),d0.min().y(),d0.min().z(),
                Math.addExact(d0.max().x(),1),
                Math.addExact(d0.max().y(),1),
                Math.addExact(d0.max().z(),1));
        }catch(ArithmeticException|IllegalArgumentException failure) {
            throw new IOException("D0 inclusive geometry cannot be safely represented in G0",failure);
        }
    }

    private static List<Pair> exact(Set<Id> required,Map<Id,UUID> supplied,
                                     Set<UUID> used,String kind)throws IOException {
        if(required==null||supplied==null
            ||supplied.size()!=required.size()||!supplied.keySet().equals(required))
            throw new IOException("Exact "+kind+" identity mapping is missing or substituted");
        var result=new ArrayList<Pair>();
        for(var id:new TreeSet<>(required)) {
            var mapped=supplied.get(id);
            if(mapped==null||!used.add(mapped))
                throw new IOException("Missing/colliding Director UUID for "+kind+": "+id);
            result.add(new Pair(id,mapped));
        }
        return List.copyOf(result);
    }

    public static Manifest preflight(Id sourceWorld,UUID directorWorld,Digest exactDigest,
                                     Set<Id> regions,Set<Id> locations,
                                     Map<Id,Set<Id>> locationMembership,
                                     Map<Id,UUID> directorRegions,Map<Id,UUID> directorLocations)
            throws IOException {
        if(sourceWorld==null||directorWorld==null||exactDigest==null
            ||regions==null||locations==null||locationMembership==null
            ||!locationMembership.keySet().equals(locations))
            throw new IOException("Explicit frozen D0 world, digest and memberships required");
        for(var entry:locationMembership.entrySet()) {
            if(entry.getValue()==null||entry.getValue().isEmpty()
                ||!regions.containsAll(entry.getValue()))
                throw new IOException("Location references unknown or absent D0 semantic regions");
        }
        var used=new HashSet<UUID>();
        used.add(directorWorld);
        var mappedRegions=exact(regions,directorRegions,used,"region");
        var mappedLocations=exact(locations,directorLocations,used,"location");
        return new Manifest(1,sourceWorld,directorWorld,exactDigest,
            mappedRegions,mappedLocations,
            "IDENTITY_CROSSWALK_VALIDATED_IMPORT_NOT_ADMITTED");
    }

    /** Full validation against the actual sealed WorldPrep D0 catalog.
     * This deliberately refuses a lossy conversion of unsupported fields.
     * ImportedAnchor/LocationImport serialization must be implemented and
     * admitted by the actual consumer later, with its distinct G0 schema.
     */
    public static Manifest requireExactD0(CatalogSnapshot snapshot,
            UUID directorWorld,Map<Id,UUID> directorRegions,
            Map<Id,UUID> directorLocations)throws IOException {
        if(snapshot==null||snapshot.state()!=CatalogState.READY
            ||snapshot.catalog_revision()==null)
            throw new IOException("Only an actually READY D0 catalog can be crosswalked");
        // READY is a claim, not evidence: pin both the catalog and each entry.
        try {
            CatalogValidation.requireSealed(snapshot);
        } catch (RuntimeException unsealed) {
            throw new IOException("D0 catalog or nested entry digest is not sealed", unsealed);
        }
        var regions=new HashSet<Id>();
        for(var region:snapshot.regions()) {
            if(!regions.add(region.region_id().value()))
                throw new IOException("Duplicated D0 semantic region identity");
            if(region.bounds().size()!=1)
                throw new IOException("Multi-geometry region requires explicit native G0 representation");
            if(region.priority()!=0)
                throw new IOException("D0 region priority has no G0 LocationImport equivalent");
        }
        var locations=new HashSet<Id>();
        var membership=new HashMap<Id,Set<Id>>();
        for(var entry:snapshot.entries()) {
            if(!entry.world_id().equals(snapshot.world_id())
                ||!entry.build_id().equals(snapshot.build_id())
                ||!locations.add(entry.location_id()))
                throw new IOException("Foreign/duplicated D0 location in frozen catalog");
            if(entry.geometry().size()!=1 || !entry.anchors().isEmpty()
                || !entry.capabilities().isEmpty()
                ||!entry.protection().isEmpty()||!entry.surfaces().isEmpty()
                ||!entry.recovery_anchors().isEmpty()
                ||!entry.presentation_channels().isEmpty())
                throw new IOException("D0 gameplay/protection semantics cannot be silently erased in G0");
            var refs=new HashSet<Id>();
            for(var regionId:entry.semantic_region_ids())refs.add(regionId.value());
            membership.put(entry.location_id(),refs);
        }
        return preflight(snapshot.world_id(),directorWorld,snapshot.digest(),
            regions,locations,membership,directorRegions,directorLocations);
    }

    private FrozenDirectorCrosswalk() {}
}
