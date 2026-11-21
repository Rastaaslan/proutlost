package fr.proutlost.director.api.model;
import java.util.*;
/** Captured blob bytes accompany metadata only until the existing J04 writer forces them. */
public record PhysicalSiteOffer(PhysicalSitePin pin,List<BlobContent> blobs) {
    public PhysicalSiteOffer {
        Objects.requireNonNull(pin);blobs=List.copyOf(blobs);
        var plans=blobs.stream().filter(b->b.reference().codecId().equals(PhysicalSitePin.PLAN_CODEC)&&b.reference().codecVersion()==1).toList();
        if(blobs.size()!=3||plans.size()!=1||plans.getFirst().reference().bytes()<1||plans.getFirst().reference().bytes()>1_048_576
                ||!plans.getFirst().reference().hash().equals(pin.planHash()))
            throw new IllegalArgumentException("exact ancillary plan blob required");
        var expected=Set.of(pin.need(),pin.selection(),plans.getFirst().reference());
        if(!blobs.stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(expected))
            throw new IllegalArgumentException("exact need/selection/plan blob bytes required");
        pin.requirePlan(new fr.proutlost.director.api.codec.ModelCodec<>(ExecutionPlan.class).decode(plans.getFirst().bytes()));
    }
    public BlobContent planBlob(){return blobs.stream().filter(b->b.reference().codecId().equals(PhysicalSitePin.PLAN_CODEC)).findFirst().orElseThrow();}
    public ExecutionPlan plan(){return new fr.proutlost.director.api.codec.ModelCodec<>(ExecutionPlan.class).decode(planBlob().bytes());}
}
