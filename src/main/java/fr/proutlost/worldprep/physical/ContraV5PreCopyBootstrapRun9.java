package fr.proutlost.worldprep.physical;

import com.google.gson.JsonObject;
import fr.proutlost.worldprep.director.CatalogImportProbe;
import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Isolated Contra-only DEV bootstrap. Two explicit commands, NO Minecraft start:
 * FREEZE prior to first physical cold backup and SEAL only after both SHA-copied
 * 645160-receipt snapshots, with the same parent UUID and exact V2 page hashes.
 * In no circumstances does this authorize a Director G0/G5 publication.
 * Never copy into source-next or a production jar.
 */
public final class ContraV5PreCopyBootstrapRun9 {
    private static final Path PROJECT=Path.of(
        "/srv/storage/proutlost-s2-candidates/contra-20261027");
    private static final Path WORLD=Path.of(
        "/home/damien/contra-ecology-run46-parallel/Contra-worldprep-modern-run46");
    private static final String ZERO="0".repeat(64);

    private static void require(boolean ok,String message) throws IOException {
        if(!ok)throw new IOException("CONTRA_V5_RUN9_FAIL_CLOSED: "+message);
    }
    private static String sha(Path path)throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(path)){
                byte[] buf=new byte[1024*1024];int n;
                while((n=in.read(buf))>=0)if(n>0)digest.update(buf,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static JsonObject json(Path file,long cap)throws IOException {
        require(!Files.isSymbolicLink(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                &&Files.size(file)<=cap,"Missing/unsafe/big file "+file);
        return QualificationBinding.object(Files.readString(file));
    }
    public static Path journal(Path world,UUID batch){
        if(world==null||batch==null)throw new IllegalArgumentException("No source parent or batch");
        return world.resolve("worldprep-v2/physical")
            .resolve(PhysicalStore.sha("minecraft:overworld"))
            .resolve("qualification-"+batch);
    }
    private static NativeFaunaPass.Rule cow(){
        return new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:plains","minecraft:forest","minecraft:birch_forest",
                   "minecraft:taiga","minecraft:beach"),
            Set.of("minecraft:grass_block","minecraft:dirt"),
            -64,319,-10.0f,10.0f,0,64,16,2,16,true,8,32);
    }
    private static NativeFaunaPass.Rule cod(){
        return new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:river"),
            Set.of("minecraft:sand","minecraft:gravel","minecraft:dirt","minecraft:clay"),
            -64,319,-10.0f,10.0f,4,64,16,2,16,true,8,32);
    }
    /** Pure, exact identity construction; doesn't touch a world or create proofs. */
    public static NativeV5QualificationIntent plan(Path world,Path backups,
            UUID batch,BatchManifestStore.Header ecology,String importerClassSHA,Instant validatedAt) {
        if(world==null||backups==null||batch==null||ecology==null
            ||validatedAt==null||importerClassSHA==null
            ||!importerClassSHA.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("All exact cold bootstrap inputs required");
        var parentArea=new WorldPrepSavedData.Area(ContraV5SectorLineage.PARENT,
            "minecraft:overworld",16,16,4079,4079);
        if(!ContraV5CompositeRoute.exactParent(parentArea,ecology,ContraV5SectorLineage.SEED)
            ||batch.equals(ecology.batchId()))
            throw new IllegalArgumentException("Not exact 645160 Contra parent / separate V5 UUID");
        Path dir=journal(world,batch);
        String backupHeader=world.relativize(dir.resolve("backup-a.json")).toString();
        var species=MultiSpeciesFaunaPlan.prepare(batch,List.of(cow(),cod()));
        return new NativeV5QualificationIntent(5,true,batch,
            ContraV5SectorLineage.PARENT,ecology,backupHeader,null,
            backups.resolve("backup-a").toString(),backups.resolve("backup-b").toString(),
            "proutlost:worldprep/stone_platform_v1",species,
            "proutlost:contra_s2","proutlost:contra_worldprep_v5_dev_run9",
            "contra-v5-dev-"+batch,validatedAt.toString(),
            "DEV_SERVICE_PROBE",importerClassSHA,
            new NativeSpawnAudit.Spec("CONTRA_S2",2048,127,2048,4,1,5));
    }
    private static String runtimeImporterSHA()throws IOException {
        var found=ServiceLoader.load(CatalogImportProbe.class).stream().limit(2).toList();
        require(found.size()==1,"No exactly one real DEV Director importer in runtime classpath");
        var provider=found.getFirst().get();
        require(provider.getClass().getName().equals(
            "fr.proutlost.director.catalog.QualificationImportProbe"),
            "Wrong/unknown DEV importer identity");
        String resource="/"+provider.getClass().getName().replace('.','/')+".class";
        try(var stream=provider.getClass().getResourceAsStream(resource)){
            require(stream!=null,"DEV importer compiled bytes unavailable");
            byte[] bytes=stream.readNBytes(256*1024+1);
            require(bytes.length<=256*1024,"DEV importer compiled class exceeds 256KiB");
            try{
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
        }
    }
    private static BatchManifestStore.Header completedEcology()throws IOException {
        Path batches=WORLD.resolve("worldprep-v2/batches");
        require(Files.isDirectory(batches,LinkOption.NOFOLLOW_LINKS),"Missing ecological batches");
        List<BatchManifestStore.Header> matches=new ArrayList<>();
        try(var dirs=Files.list(batches)){
            for(Path dir:dirs.toList()){
                if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)
                    ||Files.isSymbolicLink(dir))throw new IOException("Unsafe ecological batch folder");
                Path manifest=dir.resolve("manifest.json");
                if(!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS))continue;
                var h=QualificationBinding.decode(
                    QualificationBinding.read(manifest,65536),BatchManifestStore.Header.class);
                if(!ContraV5SectorLineage.PARENT.equals(h.area()))continue;
                var area=new WorldPrepSavedData.Area(ContraV5SectorLineage.PARENT,
                    "minecraft:overworld",16,16,4079,4079);
                require(ContraV5CompositeRoute.exactParent(area,h,ContraV5SectorLineage.SEED)
                        &&dir.getFileName().toString().equals(h.batchId().toString()),
                        "Ecology parent header/seed/dimension/area not exact");
                var completed=QualificationBinding.decode(
                    QualificationBinding.read(dir.resolve("completed.json"),4096),
                    BatchManifestStore.Completion.class);
                require(completed.schemaVersion()==1&&completed.stages()==645160
                    &&completed.batchId().equals(h.batchId())
                    &&"STAGES_COMPLETED_DIRECTOR_NOT_READY".equals(completed.status())
                    &&sha(dir.resolve("00000000000000645159.json"))
                        .equals(completed.finalReceiptSha256()),
                    "Native final stage SHA receipt not actually present");
                matches.add(h);
            }
        }
        require(matches.size()==1,"Expected ONE complete Contra ecological parent, got "+matches.size());
        return matches.getFirst();
    }
    private static void preflight(String run,UUID batch,Path destination)throws IOException {
        require(run.equals("run47"),"Only new exact RUN46 postecology RUN47 candidate accepted");
        require(!Files.isSymbolicLink(WORLD)&&Files.isDirectory(WORLD,LinkOption.NOFOLLOW_LINKS)
            &&WORLD.toRealPath().equals(WORLD),"Wrong/missing exact isolated world");
        require(!Files.isSymbolicLink(PROJECT)&&Files.isDirectory(PROJECT,LinkOption.NOFOLLOW_LINKS),"Wrong project proof root");
        require(destination.getParent().equals(PROJECT)
            &&destination.getFileName().toString().equals("contra-v5-backups-"+run+"-"+batch),
            "Unexpected independent backup destination");
        var marker=json(WORLD.resolve("worldprep-candidate.json"),4096);
        require(marker.get("offlineBaseline").getAsBoolean()
            &&marker.get("candidateDirectory").getAsString().equals(WORLD.toString())
            &&"CONTRA_NVME_ECOLOGY_RUN6_SHA256_INDEPENDENT".equals(
                 marker.get("lineage").getAsString()),"Source immutable lineage not RUN6");
        Path finalProof=PROJECT.resolve("CONTRA_ECOLOGY_NVME_ORCHESTRATOR_FINAL_RUN46.json");
        Path nativeProof=PROJECT.resolve("CONTRA_ECOLOGY_NVME_NATIVE_RESULT_RUN46.json");
        var finalObj=json(finalProof,100000);
        var nativeObj=json(nativeProof,100000);
        require("RUN46_645160_ECOLOGY_NATIVE_COMPLETE_PENDING_EXTERNAL_COLD_AUDITS"
                .equals(finalObj.get("status").getAsString())
            &&"ECOLOGY_COMPLETE_AWAITING_NEW_BACKUP_V5_CERTIFICATION"
                .equals(nativeObj.get("status").getAsString())
            &&"873e51c5-b528-4fb7-bc34-7e0221d6c59d"
                .equals(nativeObj.get("batch").getAsString())
            &&finalObj.get("batch").getAsString().equals(nativeObj.get("batch").getAsString())
            &&finalObj.get("terminalSha256").getAsString().equals(sha(nativeProof)),
            "RUN46 exact batch true native terminal + checksum missing or changed");
        Path chain=PROJECT.resolve("CONTRA_RUN47_POSTECOLOGY_645160_RECEIPT_CHAIN_20261028.json");
        Path anvil=PROJECT.resolve("CONTRA_RUN47_POSTECOLOGY_ANVIL_NATIVE_INDEPENDENT_20261028.json");
        Path paged=PROJECT.resolve("CONTRA_RUN47_POSTECOLOGY_PAGED_SAVEDDATA_ALL_REFERENCED_SHA_20261028.json");
        var c=json(chain,1000000);var a=json(anvil,1000000);var z=json(paged,262144);
        require("RUN47_COLD_RUN46_PAGED_NATIVE_TWO_STORES_ALL_REFERENCED_PAGES_INDEPENDENT_SHA_OK_NOT_V5"
                 .equals(z.get("status").getAsString())
            &&z.get("world").getAsString().equals(WORLD.toString())
            &&z.getAsJsonArray("stores").size()==2,
            "Both new RUN46 paged cold SavedData stores must be independently SHA verified");
        Set<String> pagedStoreNames=new HashSet<>();
        for(var item:z.getAsJsonArray("stores")){
            var itemObj=item.getAsJsonObject();
            String store=itemObj.get("store").getAsString();
            require(Set.of("proutlost_worldprep","proutlost_worldprep_blocks").contains(store)
                    &&pagedStoreNames.add(store)
                    &&itemObj.get("referencedPages").getAsInt()>0
                    &&sha(WORLD.resolve("data").resolve(store+".dat"))
                        .equals(itemObj.get("rootSha256").getAsString()),
                    "RUN47 paged cold root SHA identity changed");
        }
        require(pagedStoreNames.size()==2,"Missing one of two paged root stores");
        require("CONTRA_645160_ECOLOGY_RECEIPT_SHA_CHAIN_AND_ALL_ARTIFACTS_VERIFIED_NOT_V5"
                    .equals(c.get("status").getAsString())
            &&c.get("stages").getAsInt()==645160
            &&c.get("ecologyBatch").getAsString().equals(nativeObj.get("batch").getAsString())
            &&c.get("world").getAsString().equals(WORLD.toString())
            &&c.get("noSkippingOrFabricatedStages").getAsBoolean()
            &&"FULL_65536_CHUNK_ANVIL_NBT_INDEPENDENT_VERIFIED_NOT_V5_CERTIFIED"
                    .equals(a.get("status").getAsString())
            &&a.get("world").getAsString().equals(WORLD.toString())
            &&a.get("fullLitModernChunks").getAsInt()==65536,
            "Full new RUN46 independent receipt/Anvil certificates not both positive");
        require(Files.isRegularFile(WORLD.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS),
            "No original level.dat");
    }
    private static <T> T withColdLock(CallableIO<T> work)throws Exception {
        try(var channel=FileChannel.open(WORLD.resolve("session.lock"),
            StandardOpenOption.READ,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()){
            require(lock!=null,"Native Minecraft currently owns the world; never write");
            return work.run();
        }
    }
    @FunctionalInterface private interface CallableIO<T>{T run()throws Exception;}

    private static String freeze(String run,UUID batch)throws Exception {
        Path destination=PROJECT.resolve("contra-v5-backups-"+run+"-"+batch);
        return withColdLock(()->{
            preflight(run,batch,destination);
            require(!Files.exists(destination),"Backups predate immutable frozen intention");
            require(!Files.exists(WORLD.resolve("worldprep-qualification-pin.json"))
                &&!Files.exists(WORLD.resolve("worldprep-qualification.json")),
                "World already has qualification or frozen pin");
            Path folder=journal(WORLD,batch);
            require(!Files.exists(folder),"New V5 UUID must not reuse an existing qualification WAL");
            var parent=completedEcology();
            String imported=runtimeImporterSHA();
            var request=plan(WORLD,destination,batch,parent,imported,Instant.now());
            var binder=new QualificationBinding(WORLD,folder,NativeV5QualificationIntent.class);
            var sem=QualificationBinding.JSON.toJsonTree(request).getAsJsonObject();
            sem.remove("backupSha256");
            String bytes=QualificationBinding.canonical(sem);
            String digest=binder.freeze(bytes);
            require(!binder.bound()&&!Files.exists(WORLD.resolve("worldprep-qualification.json")),
                "Unsealed pre-copy intent tried to publish V5 runtime");
            return "PRE_COPY_NATIVE_V5_CONTRA_INTENT_FROZEN_SHA256="+digest
                +" batch="+batch+" ecology="+parent.batchId()+" importerSHA="+imported
                +" NO_BACKUP_YET_NO_V5";
        });
    }
    private static void copyChecked(Path from,Path to)throws IOException {
        require(!Files.isSymbolicLink(from)&&Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)
            &&!Files.exists(to,LinkOption.NOFOLLOW_LINKS),"Page/header collision or unsafe source "+from);
        Files.copy(from,to);
        require(sha(to).equals(sha(from)),"Metadata SHA differs after copy "+from);
    }
    private static void pages(Path source,Path target,int expected,String terminal)throws IOException {
        require(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)
            &&!Files.isSymbolicLink(source)&&!Files.exists(target),"Unsafe/duplicate page metadata directory");
        require(expected>=1&&expected<=20000,"20k-page header cap preserved");
        try(var stream=Files.list(source)){
            require(stream.count()==expected,"Wrong number of V2 metadata pages");
        }
        Files.createDirectory(target);
        String previous=ZERO;
        for(int i=0;i<expected;i++){
            Path file=source.resolve("page-"+i+".json");
            require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                &&Files.size(file)<=128*1024,"Malformed V2 paged metadata");
            var p=json(file,128*1024);
            require(p.get("version").getAsInt()==2
                &&p.get("index").getAsLong()==i
                &&p.get("previousSha256").getAsString().equals(previous)
                &&p.get("entries").getAsJsonArray().size()>=1
                &&p.get("entries").getAsJsonArray().size()<=128,
                "Broken SHA-chained backup page index "+i);
            copyChecked(file,target.resolve(file.getFileName()));
            previous=sha(file);
        }
        require(terminal.equals(previous),"Terminal SHA-256 V2 metadata chain differs");
    }
    private static String seal(String run,UUID batch)throws Exception {
        Path destination=PROJECT.resolve("contra-v5-backups-"+run+"-"+batch);
        return withColdLock(()->{
            preflight(run,batch,destination);
            require(Files.isDirectory(destination,LinkOption.NOFOLLOW_LINKS)
                &&!Files.isSymbolicLink(destination),"Missing independent finished A/B backups");
            var result=json(PROJECT.resolve("CONTRA_RUN47_V5_TWO_BACKUPS_SCALED_RESULT_20261028.json"),100000);
            var prep=json(destination.resolve("PREPARATION_PROOF.json"),100000);
            require("CONTRA_V5_TWO_INDEPENDENT_SHA256_PAGED_COLD_BACKUPS_PREPARED"
                    .equals(result.get("status").getAsString())
                &&result.get("qualificationBatch").getAsString().equals(batch.toString())
                &&result.get("backupRoot").getAsString().equals(destination.toString())
                &&result.get("filesPerCopy").getAsInt()>=645160
                &&"TWO_INDEPENDENT_V2_OFFLINE_BACKUPS_PREPARED_NOT_BOUND"
                    .equals(prep.get("status").getAsString())
                &&prep.get("batch").getAsString().equals(batch.toString())
                &&prep.get("allFileHashesCompared").getAsBoolean(),
                "Two strict independent SHA A/B cold copies not complete");
            Path folder=journal(WORLD,batch);
            var binder=new QualificationBinding(WORLD,folder,NativeV5QualificationIntent.class);
            require(!binder.bound()&&!Files.exists(WORLD.resolve("worldprep-qualification.json")),
                "An existing V5 WAL or runtime request cannot be rewritten");
            var intent=binder.intent();
            var parsed=QualificationBinding.object(intent);
            require(parsed.get("batch").getAsString().equals(batch.toString())
                &&parsed.get("backupRootA").getAsString().equals(
                    destination.resolve("backup-a").toString())
                &&parsed.get("backupRootB").getAsString().equals(
                    destination.resolve("backup-b").toString()),
                "Frozen semantic intent does not match the two actual backed-up roots");
            for(String name:List.of("a","b")){
                Path source=destination.resolve("backup-"+name+".json");
                var header=QualificationBinding.decode(QualificationBinding.read(source,65536),
                    PagedBackupVerifier.Header.class);
                require(header.version()==2
                    &&header.sourceRoot().equals(WORLD.toString())
                    &&header.backupRoot().equals(destination.resolve("backup-"+name).toString())
                    &&header.files()>=645160
                    &&header.quiescentPinPath().equals("worldprep-qualification-pin.json")
                    &&header.quiescentPinSha256().equals(
                        sha(WORLD.resolve("worldprep-qualification-pin.json"))),
                    "Backup V2 header source/pin/identity changed "+name);
                copyChecked(source,folder.resolve("backup-"+name+".json"));
                pages(destination.resolve("backup-"+name+".json.pages"),
                    folder.resolve("backup-"+name+".json.pages"),
                    Math.toIntExact(header.pages()),header.terminalPageSha256());
            }
            var sealed=binder.seal();
            require(sealed.equals(binder.verify()),"Native V5 binding WAL/readback differs");
            return "CONTRA_V5_SINGLE_PARENT_A_B_REAL_SHA_PAGES_SEALED_NOT_PHYSICAL_V5"
                +" batch="+batch+" requestSha256="+sealed.requestSha256();
        });
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!Set.of("freeze","seal").contains(args[0])
            ||!Set.of("run47").contains(args[1]))
            throw new IllegalArgumentException(
                "Usage: freeze|seal run47 <new-canonical-uuid> (isolated RUN48 DEV ONLY)");
        UUID batch=UUID.fromString(args[2]);
        System.out.println(args[0].equals("freeze")
            ? freeze(args[1],batch):seal(args[1],batch));
    }
    private ContraV5PreCopyBootstrapRun9(){}
}
