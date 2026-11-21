package fr.proutlost.director.api.model;

import fr.proutlost.director.api.codec.ModelCodec;
import java.util.*;

/** Exact ancillary plan pin. Data never grants a reservation or J05 permit. */
public record PhysicalSitePin(PhysicalCatalogModels.Readback catalog,DefinitionRef definition,
        CandidateId candidate,EventInstanceId event,Fingerprint planHash,BlobRef need,
        BlobRef selection,Set<LocationId> locations,Set<AnchorId> anchors,Set<RegionId> regions,
        Map<OperationToken,Footprint> footprints,List<PhysicalResourceClaim> resources) {
    public static final ResourceId NEED_CODEC=new ResourceId("proutlost_director:d0_event_need");
    public static final ResourceId SELECTION_CODEC=new ResourceId("proutlost_director:d0_site_selection");
    public static final ResourceId PLAN_CODEC=new ResourceId("proutlost_director:physical_site_plan");
    public PhysicalSitePin(PhysicalCatalogModels.Readback catalog,DefinitionRef definition,CandidateId candidate,EventInstanceId event,Fingerprint planHash,BlobRef need,BlobRef selection,Set<LocationId> locations,Set<AnchorId> anchors,Set<RegionId> regions,Map<OperationToken,Footprint> footprints){this(catalog,definition,candidate,event,planHash,need,selection,locations,anchors,regions,footprints,List.of());}
    public PhysicalSitePin {
        Objects.requireNonNull(catalog);Objects.requireNonNull(definition);Objects.requireNonNull(candidate);
        Objects.requireNonNull(event);Objects.requireNonNull(planHash);Objects.requireNonNull(need);Objects.requireNonNull(selection);
        locations=Set.copyOf(locations);anchors=Set.copyOf(anchors);regions=Set.copyOf(regions);footprints=Map.copyOf(footprints);resources=List.copyOf(resources);
        if(resources.size()>256||resources.stream().map(PhysicalResourceClaim::resource).distinct().count()!=resources.size()||locations.isEmpty()||locations.size()>128||anchors.size()>4096||regions.size()>1024
                ||footprints.isEmpty()||footprints.size()>256||!need.codecId().equals(NEED_CODEC)
                ||!selection.codecId().equals(SELECTION_CODEC)||need.codecVersion()!=1||selection.codecVersion()!=1
                ||need.bytes()<1||selection.bytes()<1||need.bytes()>1_048_576||selection.bytes()>1_048_576)
            throw new IllegalArgumentException("bounded complete physical site pin required");
    }
    public static Fingerprint hash(ExecutionPlan plan){return BlobContent.hash(new ModelCodec<>(ExecutionPlan.class).encode(plan));}
    /** No read, permission check or physical mutation: exact original plan identity only. */
    public void requirePlan(ExecutionPlan plan){
        Objects.requireNonNull(plan);
        if(!candidate.equals(plan.candidateId())||!definition.equals(plan.definition())||!planHash.equals(hash(plan))
                ||!catalog.key().directorWorld().equals(plan.scopes().worldId()))
            throw new IllegalArgumentException("physical site pin plan identity changed");
        Map<OperationToken,Footprint> actual=new HashMap<>();
        for(var effect:plan.effects()){
            if(!effect.instanceId().equals(event)||actual.put(effect.operationToken(),effect.expectedFootprint())!=null)
                throw new IllegalArgumentException("physical site pin event/operation changed");
        }
        if(!actual.equals(footprints))throw new IllegalArgumentException("physical site pin footprint changed");
    }
}
