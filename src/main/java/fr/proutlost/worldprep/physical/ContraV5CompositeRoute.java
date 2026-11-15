package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Exact Contra-only V5 route: one real 645160-receipt parent, 17 exact sector
 * children; other worlds always use the unchanged V5 physical path.
 * Pure selection/path mapping. Does not qualify, launch or change terrain.
 */
public final class ContraV5CompositeRoute {
    public static boolean exactArea(WorldPrepSavedData.Area area,long seed) {
        return area!=null
            && ContraV5SectorLineage.PARENT.equals(area.id())
            && "minecraft:overworld".equals(area.dimension())
            && area.minX()==16 && area.minZ()==16
            && area.maxX()==4079 && area.maxZ()==4079
            && seed==ContraV5SectorLineage.SEED;
    }
    public static boolean exactParent(WorldPrepSavedData.Area area,
                                      BatchManifestStore.Header ecology,long seed) {
        return ecology!=null && exactArea(area,seed)
            && ecology.schemaVersion()==1 && ecology.batchId()!=null
            && ContraV5SectorLineage.PARENT.equals(ecology.area())
            && "minecraft:overworld".equals(ecology.dimension())
            && ecology.minX()==16 && ecology.minZ()==16
            && ecology.maxX()==4079 && ecology.maxZ()==4079
            && ecology.stages()==645160L
            && "ecology-batch-v3".equals(ecology.pipelineVersion())
            && ecology.worldSeed()==ContraV5SectorLineage.SEED
            && seed==ContraV5SectorLineage.SEED;
    }
    public static ContraV5SegmentedManifest.Manifest manifest(
            NativeV5QualificationIntent request,String canonicalFrozen,
            WorldPrepSavedData.Area area,long seed) throws IOException {
        if(request==null || !exactParent(area,request.ecology(),seed)
            ||!area.id().equals(request.area())
            ||canonicalFrozen==null
            ||!canonicalFrozen.equals(QualificationBinding.canonical(
                QualificationBinding.object(canonicalFrozen))))
            throw new IOException("Not the exact sealed Contra global parent");
        List<String> species=request.multiSpecies().stages().stream()
            .map(fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan.Stage::species).toList();
        var m=ContraV5SegmentedManifest.create(request.batch(),request.ecology().batchId(),
            PhysicalStore.sha(canonicalFrozen),species);
        ContraV5SegmentedManifest.verify(m);
        return m;
    }
    /** Deterministic full-1024-group coverage, each group inside a 1..64 child. */
    public static Path structureGroup(Path qualificationDirectory,
            ContraV5SegmentedManifest.Manifest manifest,long ordinal)throws IOException {
        if(qualificationDirectory==null || manifest==null
            ||manifest.totalGroups()!=1024 || manifest.totalChunks()!=64516
            ||manifest.children().size()!=17 || ordinal<0 || ordinal>=1024)
            throw new IOException("Not an exact global Contra group ordinal");
        long local=ordinal;
        for(var child:manifest.children()) {
            if(child.groups()<1 || child.groups()>64)
                throw new IOException("Unsafe child over native 64-group limit");
            if(local<child.groups())
                return qualificationDirectory.resolve("contra-v5-segmented-structures")
                    .resolve(String.format(java.util.Locale.ROOT,"sector-%02d",child.ordinal()))
                    .resolve("group-"+local);
            local-=child.groups();
        }
        throw new IOException("Missing native group from pinned 1024-group partition");
    }
    public static String expectedChildStructureRoot(){
        return "contra-v5-segmented-structures";
    }
    private ContraV5CompositeRoute(){}
}
