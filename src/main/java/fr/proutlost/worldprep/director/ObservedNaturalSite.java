package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Admission of physically observed grassland, not a claim about historical terrain origin.
 * Native caller must bind all observations to live and disk evidence. No narrative capability.
 */
public final class ObservedNaturalSite {
    public static PhysicalCatalogExporter.Site create(Id dimension,int x,int y,int z,int radius,Revision revision,
            SemanticRegionId region,boolean walkingVerified,boolean grassland,boolean structureMetadata,
            boolean protectedTerrain,List<EvidenceRef> live,List<EvidenceRef> disk) {
        if(radius<2||radius>4||Math.abs((long)x)>29999990||Math.abs((long)z)>29999990||y< -2048||y>2047
            ||!walkingVerified||!grassland||structureMetadata||protectedTerrain||live.isEmpty()||disk.isEmpty())
            throw new IllegalArgumentException("Natural site NOT_READY: unproven access, habitat, metadata or protection");
        String key=Digests.sha256(dimension.value()+":"+x+":"+y+":"+z+":"+radius).value();
        var id=new Id("proutlost:observed_grassland/"+key);
        var anchor=new Anchor(new Id("proutlost:walking_access/"+key),new Id("proutlost:walking_access"),dimension,
            new Vec3(x+0.5,y,z+0.5),null,1,Set.of());
        return new PhysicalCatalogExporter.Site(id,revision,SourceType.worldprep_natural_site,null,
            new Id("proutlost:observed_open_grassland"),List.of(new Bounds(dimension,new BlockPos(x-radius,y-1,z-radius),new BlockPos(x+radius,y+2,z+radius))),
            Set.of(region),List.of(anchor),List.of(),List.of(),List.of(),List.of(),live,disk);
    }
    private ObservedNaturalSite(){}
}
