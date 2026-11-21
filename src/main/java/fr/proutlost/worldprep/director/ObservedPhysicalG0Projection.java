package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.Bounds;
import fr.proutlost.contract.director.CatalogSnapshot;
import fr.proutlost.contract.director.CatalogValidation;
import fr.proutlost.contract.director.Digests;
import fr.proutlost.contract.director.Id;
import fr.proutlost.contract.director.StrictCodec;
import fr.proutlost.contract.director.Vocabulary;
import fr.proutlost.director.api.codec.LocationTransferContract;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.DimensionId;
import fr.proutlost.director.api.model.Fingerprint;
import fr.proutlost.director.api.model.ImportBounds;
import fr.proutlost.director.api.model.ImportedLocation;
import fr.proutlost.director.api.model.ImportedRegion;
import fr.proutlost.director.api.model.LocationId;
import fr.proutlost.director.api.model.LocationImport;
import fr.proutlost.director.api.model.RegionId;
import fr.proutlost.director.api.model.ResourceId;
import fr.proutlost.director.api.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure geometry metadata projection, without physical observation, author
 * semantics, runtime admission or a new persistence authority. Each D0 box is
 * mapped separately; no bounding union may add unwitnessed cells. */
public final class ObservedPhysicalG0Projection {
    public static final ResourceId SOURCE_NAMESPACE=new ResourceId("proutlost:observed_physical_geometry");
    public record Mapping(Id sourceWorld,Id sourceBuild,fr.proutlost.contract.director.Revision catalogRevision,
            Id sourceLocation,fr.proutlost.contract.director.Revision locationRevision,Bounds geometry,
            RegionId regionId,LocationId locationId) {
        public Mapping{Objects.requireNonNull(sourceWorld);Objects.requireNonNull(sourceBuild);Objects.requireNonNull(catalogRevision);
            Objects.requireNonNull(sourceLocation);Objects.requireNonNull(locationRevision);Objects.requireNonNull(geometry);
            Objects.requireNonNull(regionId);Objects.requireNonNull(locationId);}
    }
    public record Projection(LocationImport input,List<Mapping> mappings) {
        public Projection{Objects.requireNonNull(input);mappings=List.copyOf(mappings);}
    }
    private ObservedPhysicalG0Projection(){}
    private static void require(boolean yes,String why){if(!yes)throw new IllegalArgumentException(why);}
    private static UUID identity(String stem,String kind){return UUID.nameUUIDFromBytes((stem+"/"+kind).getBytes(StandardCharsets.UTF_8));}
    public static Projection project(UUID directorWorldId,long g0Revision,CatalogSnapshot sealed,String exactCatalogSha){
        Objects.requireNonNull(directorWorldId);Objects.requireNonNull(sealed);Objects.requireNonNull(exactCatalogSha);
        require(g0Revision>=0,"G0 revision must be nonnegative");
        CatalogValidation.requireSealed(sealed);
        require(sealed.state()==Vocabulary.CatalogState.READY,"observed catalogue must be READY");
        String canonical=StrictCodec.encode(sealed);
        require(Digests.sha256(canonical).value().equals(exactCatalogSha),"exact canonical physical catalogue SHA differs");
        require(sealed.regions().stream().allMatch(region->region.tags().isEmpty()&&region.priority()==0),"authored semantic regions cannot become inferred G0 metadata");
        require(!sealed.entries().isEmpty()&&sealed.entries().size()<=ObservedPhysicalCatalogProducer.MAX_TARGETS,"observed target capacity");
        var regions=new ArrayList<ImportedRegion>();var locations=new ArrayList<ImportedLocation>();var mappings=new ArrayList<Mapping>();
        Set<String> boxes=new HashSet<>();Set<UUID> ids=new HashSet<>();long cells=0;
        // Sorting also makes repeated publication independent of discovery order.
        for(var entry:sealed.entries().stream().sorted(Comparator.comparing(e->e.location_id().value())).toList()){
            require(entry.source_type()==Vocabulary.SourceType.worldprep_prepared_site&&entry.structure_instance_id()==null,
                "only observed geometry source entries may be projected");
            require(entry.capabilities().isEmpty()&&entry.anchors().isEmpty()&&entry.surfaces().isEmpty()&&entry.protection().isEmpty()
                &&entry.presentation_channels().isEmpty()&&entry.recovery_anchors().isEmpty(),"physical semantics cannot become inferred G0 metadata");
            require(entry.geometry().size()<=8,"observed geometry box capacity");
            for(var box:entry.geometry().stream().sorted(Comparator.comparing(StrictCodec::encode)).toList()){
                String geometry=StrictCodec.encode(box);require(boxes.add(geometry),"duplicate physical box; aliases are not inferred");
                require(box.min().x()>=-29999984&&box.min().z()>=-29999984&&box.max().x()<=29999984&&box.max().z()<=29999984
                    &&box.min().y()>=-2048&&box.max().y()<=2047,"observed geometry exceeds finite native bounds");
                cells=Math.addExact(cells,ObservedPhysicalCatalogProducer.volume(List.of(box)));
                require(cells<=ObservedPhysicalCatalogProducer.MAX_CELLS,"complete observed cell capacity");
                String stem="physical-survey-g0-v1/"+sealed.world_id().value()+"/"+entry.location_id().value()+"/"+geometry;
                var region=new RegionId(identity(stem,"region"));var location=new LocationId(identity(stem,"location"));
                require(ids.add(region.value())&&ids.add(location.value()),"projected identity collision");
                var dimension=new DimensionId(box.dimension_id().value());
                var bounds=new ImportBounds(box.min().x(),box.min().y(),box.min().z(),Math.addExact(box.max().x(),1),Math.addExact(box.max().y(),1),Math.addExact(box.max().z(),1));
                regions.add(new ImportedRegion(region,dimension,bounds,Set.of()));
                locations.add(new ImportedLocation(location,stem,Set.of(region),dimension,bounds,Set.of()));
                mappings.add(new Mapping(sealed.world_id(),sealed.build_id(),sealed.catalog_revision(),entry.location_id(),entry.revision(),box,region,location));
            }
        }
        var unsigned=new LocationImport(1,new WorldId(directorWorldId),SOURCE_NAMESPACE,new fr.proutlost.director.api.model.Revision(g0Revision),
            new Fingerprint("0".repeat(64)),regions,locations,List.of());
        var input=new LocationImport(1,unsigned.worldId(),unsigned.sourceNamespace(),unsigned.exportRevision(),LocationTransferContract.digest(unsigned),regions,locations,List.of());
        require(new ModelCodec<>(LocationImport.class).encode(input).length<=LocationTransferContract.SOURCE_BYTES,"complete projected G0 source capacity");
        return new Projection(input,mappings);
    }
}
