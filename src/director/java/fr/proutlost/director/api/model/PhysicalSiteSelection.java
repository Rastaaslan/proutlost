package fr.proutlost.director.api.model;
import java.util.*;
/** Actor-captured full D0 selection before CandidatePlanner assigns its exact ReadSet. */
public record PhysicalSiteSelection(PhysicalCatalogModels.Readback catalog,DefinitionRef definition,
        CandidateId candidate,EventInstanceId event,BlobContent need,BlobContent selection,
        Set<LocationId> locations,Set<AnchorId> anchors,Set<RegionId> regions,
        Map<OperationToken,Footprint> footprints,List<PhysicalResourceClaim> resources) {
    public PhysicalSiteSelection(PhysicalCatalogModels.Readback catalog,DefinitionRef definition,CandidateId candidate,EventInstanceId event,BlobContent need,BlobContent selection,Set<LocationId> locations,Set<AnchorId> anchors,Set<RegionId> regions,Map<OperationToken,Footprint> footprints){this(catalog,definition,candidate,event,need,selection,locations,anchors,regions,footprints,List.of());}
    public PhysicalSiteSelection {
        Objects.requireNonNull(catalog);Objects.requireNonNull(definition);Objects.requireNonNull(candidate);
        Objects.requireNonNull(event);Objects.requireNonNull(need);Objects.requireNonNull(selection);
        locations=Set.copyOf(locations);anchors=Set.copyOf(anchors);regions=Set.copyOf(regions);footprints=Map.copyOf(footprints);resources=List.copyOf(resources);
    }
    public PhysicalSiteOffer pin(ExecutionPlan plan){
        var pin=new PhysicalSitePin(catalog,definition,candidate,event,PhysicalSitePin.hash(plan),need.reference(),
            selection.reference(),locations,anchors,regions,footprints,resources);
        var planBlob=BlobContent.capture(PhysicalSitePin.PLAN_CODEC,1,new fr.proutlost.director.api.codec.ModelCodec<>(ExecutionPlan.class).encode(plan));
        pin.requirePlan(plan);return new PhysicalSiteOffer(pin,List.of(need,selection,planBlob));
    }
}
