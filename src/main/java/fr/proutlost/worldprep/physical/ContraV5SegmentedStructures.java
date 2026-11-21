package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Actual native STRUCTURES task for a FUTURE Contra-only composite V5 parent.
 * It uses ONE physically bound global request, and 17 sequential native child
 * sweeps with at most 64 groups each. Per-sector true native block mutations,
 * backup gates, disk receipts, rollback/refusal evidence and WAL are preserved.
 *
 * Not registered as a server command or production V5 pipeline. In particular,
 * returning an aggregate here is NOT a full nine-phase V5 qualification.
 */
public final class ContraV5SegmentedStructures implements QualificationOrchestrator.Task {
    private static final Gson JSON = new Gson();
    private final ServerLevel level;
    private final NativeV5QualificationIntent request;
    private final WorldPrepSavedData.Area parent;
    private final ContraV5SegmentedManifest.Manifest manifest;
    private final QualificationBinding binding;
    private final String frozen;
    private final Path segmentedRoot;
    private final PhysicalStore index;
    private NativePerimeterStructures active;
    private int cursor;

    public ContraV5SegmentedStructures(ServerLevel level, NativeV5QualificationIntent request,
                                        String exactFrozenRequest, Path globalDirectory)
            throws IOException {
        this.level=java.util.Objects.requireNonNull(level);
        this.request=java.util.Objects.requireNonNull(request);
        NativePhysicalRuntime.requireThread(level);
        if(exactFrozenRequest==null || exactFrozenRequest.isBlank())
            throw new IOException("Missing exact global V5 bound request");
        frozen=QualificationBinding.canonical(QualificationBinding.object(exactFrozenRequest));
        if(!exactFrozenRequest.equals(frozen))
            throw new IOException("Noncanonical V5 request substituted");
        Path world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        parent=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),request.area());
        // This task must never silently run on a different world or arbitrary area.
        if(!"proutlost:contra_s2_ecology".equals(parent.id())
            ||!"minecraft:overworld".equals(parent.dimension())
            ||parent.minX()!=16 || parent.minZ()!=16
            ||parent.maxX()!=4079 || parent.maxZ()!=4079
            ||!request.ecology().area().equals(parent.id())
            ||request.ecology().stages()!=645160L
            ||request.ecology().worldSeed()!=level.getSeed()
            ||level.getSeed()!=27594267L)
            throw new IOException("Not the immutable Contra 645160 global ecology parent");
        NativeV5ContractGate.requireActualSealedBinding(world,globalDirectory,
            frozen,request.multiSpecies(),parent,FMLLoader.isProduction());
        binding=new QualificationBinding(world,globalDirectory,NativeV5QualificationIntent.class);
        if(!binding.verify().request().equals(frozen))
            throw new IOException("Sealed V5 global request differs");
        List<String> species=request.multiSpecies().stages().stream()
            .map(MultiSpeciesFaunaPlan.Stage::species).toList();
        manifest=ContraV5SegmentedManifest.create(request.batch(),
            request.ecology().batchId(),PhysicalStore.sha(frozen),species);
        ContraV5SegmentedManifest.verify(manifest);
        segmentedRoot=globalDirectory.resolve("contra-v5-segmented-structures");
        index=new PhysicalStore(segmentedRoot);
        index.write("manifest",manifest); // one immutable identity, before any native child
    }
    public ContraV5SegmentedManifest.Manifest manifest(){ return manifest; }

    @Override public Optional<String> step() throws Exception {
        NativePhysicalRuntime.requireThread(level);
        if(!binding.verify().request().equals(frozen))
            throw new IOException("Global V5 WAL or snapshot binding changed mid-task");
        NativePhysicalRuntime.guard(level,parent);
        if(cursor==manifest.children().size()) {
            var summary=ContraV5SegmentedStructureProofs.audit(manifest,segmentedRoot,frozen);
            index.write("complete",summary); // immutable: not nine phases, not Director G0
            return Optional.of(JSON.toJson(summary));
        }
        var child=manifest.children().get(cursor);
        if(child.ordinal()!=cursor||child.groups()<1||child.groups()>64)
            throw new IOException("Lost pinned V5 child order or 64-group native bound");
        Path folder=segmentedRoot.resolve(String.format(java.util.Locale.ROOT,
            "sector-%02d",child.ordinal()));
        if(active==null)
            active=new NativePerimeterStructures(level,child.area(),child.structureBatch(),
                frozen,request.template(),folder);
        var result=active.step();
        if(result.isEmpty())return Optional.empty();
        var indexStore=new PhysicalStore(folder.resolve("index"));
        String retained=indexStore.readText("aggregate");
        if(!result.orElseThrow().equals(retained))
            throw new IOException("Completed sector aggregate not retained on disk");
        PerimeterExecution.Aggregate aggregate=indexStore.read("aggregate",
            PerimeterExecution.Aggregate.class);
        if(aggregate.version()!=1||!aggregate.parent().equals(child.structureBatch())
            ||aggregate.groups()!=child.groups()||aggregate.chunks()!=child.chunks()
            ||!"GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY".equals(aggregate.status()))
            throw new IOException("Sector child native aggregate differs");
        long physicallyPlaced=0;
        for(long ordinal=0;ordinal<child.groups();ordinal++){
            var childStore=new PhysicalStore(folder.resolve("group-"+ordinal));
            if(childStore.exists("plan")){
                var plan=childStore.read("plan",NativeStructurePass.Plan.class);
                var nativeDisk=childStore.read("disk-verified",NativeStructureAudit.Receipt.class);
                if(childStore.exists("rollback-intent")
                    ||!plan.operation().equals(nativeDisk.operation())
                    ||!"PLACED_VERIFIED".equals(nativeDisk.status())
                    ||!nativeDisk.planSha256().equals(PhysicalStore.sha(childStore.readText("plan"))))
                    throw new IOException("Unsealed native placement in sector "+cursor);
                physicallyPlaced++;
            }else if(!childStore.exists("empty-survey")){
                throw new IOException("Missing exact no-site refusal sector "+cursor+" group "+ordinal);
            }
        }
        if(physicallyPlaced<1)
            throw new IOException("V5 sector "+cursor+" has no genuinely disk-verified structure");
        active.close();active=null;
        cursor++;
        return Optional.empty();
    }
    @Override public void close() throws IOException {
        if(active!=null){var previous=active;active=null;previous.close();}
    }
}
