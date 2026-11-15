package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * A separate, complete, bounded native Anvil fauna audit:
 * each step verifies ONE independently persisted sector/species WAL (<=64
 * groups) and real Anvil entity UUIDs. Strict global cross-species uniqueness
 * and actual observed individuals are required. No new fauna may be spawned.
 * Never substitutes for native ecology, actual population or V5 certification.
 */
public final class ContraV5SegmentedFaunaColdAudit {
    private static final Gson JSON=new Gson();

    public static final class Reader {
        private final Path world,rootPath,qualification;
        private final NativeV5QualificationIntent request;
        private final ContraV5SegmentedManifest.Manifest manifest;
        private final String frozen,sha;
        private final boolean phaseSealed;
        private final PhysicalStore root;
        private final Set<UUID> batches=new HashSet<>(),animals=new HashSet<>();
        private final LinkedHashMap<String,Long> totals=new LinkedHashMap<>();
        private int sector,species;
        private long observed,groups;
        private ContraV5SegmentedFauna.Summary completed;

        public Reader(Path world,Path qualificationDirectory,
                      NativeV5QualificationIntent request,String frozen,
                      ContraV5SegmentedManifest.Manifest manifest)throws IOException {
            this(world,qualificationDirectory,request,frozen,manifest,true);
        }
        /** Only the native FAUNA owner may use this mode before its phase is sealed. */
        static Reader forNativePreseal(Path world,Path qualificationDirectory,
                NativeV5QualificationIntent request,String frozen,
                ContraV5SegmentedManifest.Manifest manifest)throws IOException {
            return new Reader(world,qualificationDirectory,request,frozen,manifest,false);
        }
        private Reader(Path world,Path qualificationDirectory,
                      NativeV5QualificationIntent request,String frozen,
                      ContraV5SegmentedManifest.Manifest manifest,boolean phaseSealed)throws IOException {
            this.phaseSealed=phaseSealed;
            if(world==null||qualificationDirectory==null||request==null
                ||frozen==null||manifest==null)
                throw new IOException("Explicit Contra fauna proof inputs required");
            try {ContraV5SegmentedManifest.verify(manifest);}
            catch(RuntimeException bad){throw new IOException("Changed 17-sector native manifest",bad);}
            sha=PhysicalStore.sha(frozen);
            if(!request.batch().equals(manifest.qualification())
                ||!request.ecology().batchId().equals(manifest.ecology())
                ||!sha.equals(manifest.frozenRequestSha256())
                ||!frozen.equals(QualificationBinding.canonical(QualificationBinding.object(frozen)))
                ||!manifest.species().equals(request.multiSpecies().stages().stream()
                    .map(MultiSpeciesFaunaPlan.Stage::species).toList()))
                throw new IOException("Changed frozen Contra global fauna identity");
            this.world=world;
            this.request=request;
            this.frozen=frozen;
            this.manifest=manifest;
            qualification=qualificationDirectory;
            rootPath=qualification.resolve("contra-v5-segmented-fauna");
            if(!Files.isDirectory(rootPath,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing native fauna WAL, never create fake proof");
            root=new PhysicalStore(rootPath);
            if(!manifest.equals(root.read("manifest",ContraV5SegmentedManifest.Manifest.class))
                ||!request.multiSpecies().equals(root.read(
                    "global-species",MultiSpeciesFaunaPlan.Manifest.class)))
                throw new IOException("Pinned parent/species native fauna WAL changed");
        }
        public Optional<ContraV5SegmentedFauna.Summary> step()throws IOException{
            if(completed!=null)return Optional.of(completed);
            int stageCount=request.multiSpecies().stages().size();
            if(species==stageCount) {
                var summary=new ContraV5SegmentedFauna.Summary(
                    1,request.batch(),request.ecology().batchId(),sha,
                    manifest.partitionSha256(),17,stageCount,1024,totals,
                    "17_SECTORS_PER_SPECIES_NATIVE_SETTLEMENTS_RECHECKED_COLD_GLOBAL_ANVIL_AND_V5_PENDING");
                if(phaseSealed){
                    if(!summary.equals(root.read("global-fauna-phase",ContraV5SegmentedFauna.Summary.class)))
                        throw new IOException("Native 17-sector fauna terminal changed");
                    Path artifacts=qualification.resolve("artifacts");
                    if(!Files.isDirectory(artifacts,LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("No FAUNA phase artifact");
                    var phase=JSON.fromJson(new PhysicalStore(artifacts).read(
                        "evidence-FAUNA",String.class),ContraV5SegmentedFauna.Summary.class);
                    if(!summary.equals(phase))
                        throw new IOException("FAUNA SHA-backed native phase evidence changed");
                }else if(root.exists("global-fauna-phase")){
                    throw new IOException("Preseal attempted over an already sealed native fauna terminal");
                }
                completed=summary;
                return Optional.of(summary);
            }
            MultiSpeciesFaunaPlan.Stage stage=request.multiSpecies().stages().get(species);
            var child=manifest.children().get(sector);
            UUID batch=child.faunaBatches().get(stage.species());
            if(batch==null||!batches.add(batch))
                throw new IOException("Missing or duplicate Contra sector/species UUID");
            var pin=new ContraV5SegmentedFauna.Intent(
                1,request.batch(),request.ecology().batchId(),sector,species,
                stage.species(),batch,sha,stage.ruleSha256());
            var retained=root.read("settlement-"+sector+"-"+species,
                ContraV5SegmentedFauna.ChildProof.class);
            if(!pin.equals(retained.intent())
                ||!"SECTOR_NATIVE_ANVIL_UUIDS_READ_BACK_NOT_GLOBAL_CERTIFIED".equals(
                    retained.status()))
                throw new IOException("Original native species sector proof absent or changed");
            Path childRoot=rootPath.resolve(String.format(Locale.ROOT,"sector-%02d",sector))
                .resolve(stage.directory());
            if(!Files.isDirectory(childRoot,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing native Anvil UUID sector WAL");
            MultiSpeciesFaunaPlan.Stage stageChild=new MultiSpeciesFaunaPlan.Stage(
                species,stage.species(),batch,stage.directory(),stage.ruleSha256(),stage.rule());
            var fresh=NativeV5SpeciesSettlement.verify(
                world,childRoot,child.area(),stageChild,frozen);
            if(!fresh.equals(retained.nativeSummary())
                ||fresh.groups()!=child.groups()
                ||fresh.groupsPopulated()+fresh.groupsSkipped()!=fresh.groups()
                ||!fresh.childBatch().equals(batch)
                ||!fresh.species().equals(stage.species()))
                throw new IOException("Native Anvil species UUIDs no longer match sealed child proof");
            var sectorIndex=new PhysicalStore(childRoot.resolve("index"));
            if(!retained.retainedAggregateSha256().equals(
                PhysicalStore.sha(sectorIndex.readText("aggregate"))))
                throw new IOException("Native sector species aggregate changed");
            long counted=0;
            for(long i=0;i<fresh.groups();i++){
                Path group=childRoot.resolve("group-"+i);
                if(!Files.isDirectory(group,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Missing native fauna group WAL");
                var store=new PhysicalStore(group);
                if(!store.exists("plan"))continue;
                var plan=store.read("plan",NativeFaunaPass.Plan.class);
                for(var animal:plan.animals()){
                    if(!animals.add(animal.uuid()))
                        throw new IOException("Duplicate real Anvil UUID across sectors/species");
                    counted++;
                }
            }
            if(counted!=fresh.animalsVerified())
                throw new IOException("Native Anvil count differs from SHA-backed physical plans");
            observed=Math.addExact(observed,fresh.animalsVerified());
            groups=Math.addExact(groups,fresh.groups());
            sector++;
            if(sector==17){
                if(groups!=1024||observed<1)
                    throw new IOException("No global native animal for "+stage.species()
                        +" or 1024-group coverage incomplete");
                totals.put(stage.species(),observed);
                sector=0;species++;observed=0;groups=0;
            }
            return Optional.empty();
        }
    }
    /** Offline blocking equivalent of all bounded steps. */
    public static ContraV5SegmentedFauna.Summary audit(Path world,Path dir,
            NativeV5QualificationIntent request,String frozen,
            ContraV5SegmentedManifest.Manifest manifest)throws IOException {
        Reader reader=new Reader(world,dir,request,frozen,manifest);
        while(true){
            var result=reader.step();
            if(result.isPresent())return result.orElseThrow();
        }
    }
    private ContraV5SegmentedFaunaColdAudit(){}
}
