package fr.proutlost.worldprep.physical;

import com.google.gson.*;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Contra-only FUTURE V5 FAUNA native phase: one actually sealed global parent,
 * 17 exact physical sectors, and distinct immutable species UUIDs per sector.
 * Genuine NativePerimeterFauna + persisted Anvil UUID checks run for every child.
 *
 * IMPORTANT: not wired into production, not a complete V5 certification, and
 * per-sector absence is accepted ONLY if all 1024 groups are independently
 * evidenced and every globally requested species is physically observed.
 */
public final class ContraV5SegmentedFauna implements QualificationOrchestrator.Task {
    private static final Gson JSON=new Gson();
    public record Intent(int version,UUID qualification,UUID ecology,int sector,int stage,
                         String species,UUID batch,String frozenSha256,String ruleSha256) {}
    public record ChildProof(Intent intent,NativeV5SpeciesSettlement.Summary nativeSummary,
                             String retainedAggregateSha256,String status) {}
    public record Summary(int version,UUID qualification,UUID ecology,String frozenSha256,
                          String partitionSha256,int sectors,int species,long groupsPerSpecies,
                          Map<String,Long> nativeAnimalsBySpecies,
                          String status) {
        public Summary { nativeAnimalsBySpecies=Map.copyOf(nativeAnimalsBySpecies); }
    }
    private final ServerLevel level;
    private final Path world;
    private final NativeV5QualificationIntent request;
    private final WorldPrepSavedData.Area parent;
    private final ContraV5SegmentedManifest.Manifest manifest;
    private final QualificationBinding binding;
    private final String frozen;
    private final PhysicalStore root;
    private final Path rootPath;
    private NativePerimeterFauna active;
    private ContraV5SegmentedFaunaColdAudit.Reader nativeAuditReader;
    private int sectorCursor,stageCursor;

    public ContraV5SegmentedFauna(ServerLevel level,NativeV5QualificationIntent request,
                                  String exactFrozenRequest,Path globalDirectory)throws IOException {
        this.level=Objects.requireNonNull(level);
        this.request=Objects.requireNonNull(request);
        NativePhysicalRuntime.requireThread(level);
        frozen=QualificationBinding.canonical(QualificationBinding.object(exactFrozenRequest));
        if(!frozen.equals(exactFrozenRequest))
            throw new IOException("Changed global V5 frozen request");
        world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        parent=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),request.area());
        if(!"proutlost:contra_s2_ecology".equals(parent.id())
            ||!"minecraft:overworld".equals(parent.dimension())
            ||parent.minX()!=16||parent.minZ()!=16||parent.maxX()!=4079||parent.maxZ()!=4079
            ||request.ecology().stages()!=645160L
            ||request.ecology().worldSeed()!=27594267L||level.getSeed()!=27594267L)
            throw new IOException("Fauna parent is not the pinned Contra global candidate");
        NativeV5ContractGate.requireActualSealedBinding(world,globalDirectory,
            frozen,request.multiSpecies(),parent,FMLLoader.isProduction());
        binding=new QualificationBinding(world,globalDirectory,NativeV5QualificationIntent.class);
        if(!binding.verify().request().equals(frozen))
            throw new IOException("Immutable V5 parent WAL cannot be reopened");
        var stages=request.multiSpecies().stages();
        manifest=ContraV5SegmentedManifest.create(request.batch(),request.ecology().batchId(),
            PhysicalStore.sha(frozen),stages.stream().map(MultiSpeciesFaunaPlan.Stage::species).toList());
        ContraV5SegmentedManifest.verify(manifest);
        // The SAME global cold-backup census as the existing native V5 session.
        // A child never creates a fresh fake backup/census or refreshes its baseline.
        var evidenceStore=new PhysicalStore(globalDirectory.resolve("artifacts"));
        String backupText=evidenceStore.read("evidence-INTEGRITY_AND_BACKUP",String.class);
        JsonElement backed;
        try {backed=JsonParser.parseString(backupText);}
        catch(JsonParseException e){throw new IOException("Unparseable native cold-backup census",e);}
        var baseline=new PhysicalStore(globalDirectory.resolve("disk-fauna-census"))
            .read("baseline",NativeBackupFaunaCensus.Baseline.class);
        if(!backed.isJsonObject()
            ||!JSON.toJsonTree(baseline).equals(backed.getAsJsonObject().get("census")))
            throw new IOException("Global A/B cold backup census is absent or changed");
        new PhysicalStore(world.resolve("worldprep-v2/physical-population-budget"))
            .write("disk-baseline",baseline);
        rootPath=globalDirectory.resolve("contra-v5-segmented-fauna");
        root=new PhysicalStore(rootPath);
        root.write("manifest",manifest);
        root.write("global-species",request.multiSpecies());
    }
    public ContraV5SegmentedManifest.Manifest manifest(){return manifest;}

    private MultiSpeciesFaunaPlan.Stage stage(int sector,int ordinal) {
        var pinned=request.multiSpecies().stages().get(ordinal);
        var child=manifest.children().get(sector);
        UUID uuid=child.faunaBatches().get(pinned.species());
        if(uuid==null)throw new IllegalStateException("Missing pinned Contra sector species UUID");
        return new MultiSpeciesFaunaPlan.Stage(ordinal,pinned.species(),uuid,
            pinned.directory(),pinned.ruleSha256(),pinned.rule());
    }
    private Intent intent(int sector,int ordinal){
        var s=stage(sector,ordinal);
        return new Intent(1,request.batch(),request.ecology().batchId(),sector,ordinal,
            s.species(),s.childBatch(),PhysicalStore.sha(frozen),s.ruleSha256());
    }
    private String key(int sector,int ordinal){
        return "settlement-"+sector+"-"+ordinal;
    }
    private Path childPath(int sector,int ordinal){
        return rootPath.resolve(String.format(Locale.ROOT,"sector-%02d",sector))
            .resolve(stage(sector,ordinal).directory());
    }
    /**
     * Rebuild/check the complete 17*species coverage from immutable original
     * native settlement receipts; per-sector no habitat is NOT globally success.
     */
    private Summary globalCheck()throws IOException {
        var seen=new HashSet<UUID>();
        var animalUuids=new HashSet<UUID>();
        var counts=new LinkedHashMap<String,Long>();
        int stages=request.multiSpecies().stages().size();
        for(int j=0;j<stages;j++) {
            var species=request.multiSpecies().stages().get(j).species();
            long observed=0,groups=0;
            for(int i=0;i<manifest.children().size();i++) {
                var pinned=intent(i,j);
                if(!seen.add(pinned.batch()))
                    throw new IOException("Duplicate native fauna sector batch");
                var retained=root.read(key(i,j),ChildProof.class);
                if(!pinned.equals(retained.intent())
                    ||!"SECTOR_NATIVE_ANVIL_UUIDS_READ_BACK_NOT_GLOBAL_CERTIFIED".equals(retained.status()))
                    throw new IOException("Retained sector-fauna intent/status mismatch");
                var summary=retained.nativeSummary();
                var fresh=NativeV5SpeciesSettlement.verify(world,childPath(i,j),
                    manifest.children().get(i).area(),stage(i,j),frozen);
                if(!fresh.equals(summary))
                    throw new IOException("Native species Anvil UUIDs or group receipts changed post-placement");
                if(summary.version()!=1||!summary.species().equals(species)
                    ||!summary.childBatch().equals(pinned.batch())
                    ||summary.groups()!=manifest.children().get(i).groups()
                    ||summary.groupsPopulated()+summary.groupsSkipped()!=summary.groups()
                    ||!"NATIVE_SPECIES_ANVIL_FULL_PERIMETER_VERIFIED_NOT_MAP_READY".equals(summary.status()))
                    throw new IOException("Native fauna settlement sector coverage mismatch");
                // The underlying native stage retains the exact aggregate byte hash.
                var childIndex=new PhysicalStore(childPath(i,j).resolve("index"));
                if(!PhysicalStore.sha(childIndex.readText("aggregate"))
                    .equals(retained.retainedAggregateSha256()))
                    throw new IOException("Native fauna child aggregate changed after settlement");
                long countedAnimals=0;
                for(long ordinal=0;ordinal<summary.groups();ordinal++) {
                    var retainedGroup=new PhysicalStore(childPath(i,j).resolve("group-"+ordinal));
                    if(!retainedGroup.exists("plan"))continue;
                    var actual=retainedGroup.read("plan",NativeFaunaPass.Plan.class);
                    for(var animal:actual.animals()){
                        if(!animalUuids.add(animal.uuid()))
                            throw new IOException("Duplicate native Anvil UUID across sectors or species");
                        countedAnimals++;
                    }
                }
                if(countedAnimals!=summary.animalsVerified())
                    throw new IOException("Fauna animal count differs from retained physical plans");
                observed=Math.addExact(observed,summary.animalsVerified());
                groups=Math.addExact(groups,summary.groups());
            }
            if(groups!=1024 || observed<1)
                throw new IOException("No 1024-group observed global fauna for "+species);
            counts.put(species,observed);
        }
        return new Summary(1,request.batch(),request.ecology().batchId(),
            PhysicalStore.sha(frozen),manifest.partitionSha256(),17,stages,1024,
            counts,"17_SECTORS_PER_SPECIES_NATIVE_SETTLEMENTS_RECHECKED_COLD_GLOBAL_ANVIL_AND_V5_PENDING");
    }
    @Override public Optional<String> step()throws Exception {
        NativePhysicalRuntime.requireThread(level);
        if(!binding.verify().request().equals(frozen))
            throw new IOException("Global immutable V5 WAL changed during fauna child");
        NativePhysicalRuntime.guard(level,parent);
        if(sectorCursor==17){
            // One native 64-group sector/species recheck per tick. The terminal
            // cannot be published until ALL 17 x every requested species have
            // their exact original WAV receipts + live-independent Anvil UUIDs.
            if(nativeAuditReader==null)nativeAuditReader=
                ContraV5SegmentedFaunaColdAudit.Reader.forNativePreseal(
                    world,rootPath.getParent(),request,frozen,manifest);
            var progress=nativeAuditReader.step();
            if(progress.isEmpty())return Optional.empty();
            var summary=progress.orElseThrow();
            root.write("global-fauna-phase",summary);
            return Optional.of(JSON.toJson(summary));
        }
        var child=manifest.children().get(sectorCursor);
        var species=stage(sectorCursor,stageCursor);
        var stageIntent=intent(sectorCursor,stageCursor);
        root.write("intent-"+sectorCursor+"-"+stageCursor,stageIntent);
        Path location=childPath(sectorCursor,stageCursor);
        String configuration=frozen+"/species/"+species.species()+"/"+species.ruleSha256();
        if(active==null)
            active=new NativePerimeterFauna(level,child.area(),species.childBatch(),
                configuration,species.rule(),location);
        var result=active.step();
        if(result.isEmpty())return Optional.empty();
        var index=new PhysicalStore(location.resolve("index"));
        if(!result.orElseThrow().equals(index.readText("aggregate")))
            throw new IOException("Native fauna child returned unretained aggregate");
        var summary=NativeV5SpeciesSettlement.verify(world,location,child.area(),
            species,frozen);
        if(summary.groups()!=child.groups())
            throw new IOException("Truncated species per-sector native verification");
        root.write(key(sectorCursor,stageCursor),new ChildProof(stageIntent,summary,
            PhysicalStore.sha(index.readText("aggregate")),
            "SECTOR_NATIVE_ANVIL_UUIDS_READ_BACK_NOT_GLOBAL_CERTIFIED"));
        active.close();active=null;
        stageCursor++;
        if(stageCursor==request.multiSpecies().stages().size()){
            stageCursor=0;sectorCursor++;
        }
        return Optional.empty();
    }
    @Override public void close()throws IOException {
        if(active!=null){var previous=active;active=null;previous.close();}
    }
}
