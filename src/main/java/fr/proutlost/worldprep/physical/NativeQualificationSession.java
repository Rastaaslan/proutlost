package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import fr.proutlost.worldprep.director.*;
import fr.proutlost.contract.director.*;
import static fr.proutlost.contract.director.Vocabulary.*;
import static fr.proutlost.worldprep.orchestration.QualificationPipeline.Phase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Real phase assembly. Ecology and backup are verified, never replayed or created implicitly.
 * Disk audits wait for a separately controlled normal save. Import requires an external real
 * isolated Director receipt bound to the exact exported bytes; no consumer runtime is bundled.
 */
public final class NativeQualificationSession implements NativeQualificationHandle {
    private static final Gson JSON=new Gson();
    public record Position(int x,int y,int z) {net.minecraft.core.BlockPos block(){return new net.minecraft.core.BlockPos(x,y,z);}}
    public record Request(int version,boolean enabled,UUID batch,String area,BatchManifestStore.Header ecology,
                          String backupManifest,String backupSha256,String backupRootA,String backupRootB,String template,Position origin,String rotation,String mirror,
                          NativeFaunaPass.Rule fauna,List<Position> population,String worldId,String buildId,String revision,
                          String validatedAt,String directorReceipt,String directorImporterSha256,NativeSpawnAudit.Spec spawn) {
        public Request {population=List.copyOf(population);if(spawn==null||(version!=3&&version!=4)||!enabled||batch==null||(version==3&&population.isEmpty())||population.size()>8||(version==4&&(origin!=null||!population.isEmpty())))throw new IllegalArgumentException("Protocol v3/v4 required; legacy v1/v2 journals retained, never reset");}
    }
    public record Receipt(int version,UUID batch,String phase,String evidenceSha256,String status) {}
    public record ImportReceipt(int version,String worldId,String buildId,String catalogueSha256,String importerSourceSha256,
                                String runtimeIdentity,String status) {}
    private final ServerLevel level;
    private final Request request;
    private final Path world,prep,directory;
    private final PhysicalStore artifacts,structures,fauna;
    private final QualificationOrchestrator driver;
    private final QualificationBinding binding;
    public NativeQualificationSession(ServerLevel level,Request request)throws IOException {
        this.level=level;this.request=request;NativePhysicalRuntime.requireThread(level);
        world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();prep=world.resolve("worldprep-v2");
        directory=NativePhysicalRuntime.root(level).resolve("qualification-"+request.batch());
        BackupAuditPair.requireNoLegacy(directory);
        binding=new QualificationBinding(world,directory,Request.class);
        var pinned=binding.verify();
        if(!QualificationBinding.canonical(QualificationBinding.JSON.toJsonTree(request)).equals(pinned.request()))throw new IOException("Loaded request differs from exact frozen binding");
        artifacts=new PhysicalStore(directory.resolve("artifacts"));structures=new PhysicalStore(directory.resolve("structure"));fauna=new PhysicalStore(directory.resolve("fauna"));
        artifacts.write("parent-area",area());
        if(request.version()==4&&groups().count()>64)throw new IOException("Qualification perimeter exceeds 64 bounded groups");
        var engines=new EnumMap<Phase,QualificationOrchestrator.Factory>(Phase.class);
        engines.put(Phase.ECOLOGY_RECEIPTS,p->ecology());
        engines.put(Phase.INTEGRITY_AND_BACKUP,p->backup());
        engines.put(Phase.STRUCTURES,p->structure());
        engines.put(Phase.STRUCTURE_DISK_AUDIT,p->{if(request.version()==4)return ()->proof(Phase.STRUCTURE_DISK_AUDIT,new PhysicalStore(directory.resolve("perimeter-structures/index")).readText("aggregate"));var audit=new NativeStructureAudit(structurePlan(),structures);return savedBoundary(withStructureWindow(audit.plan(),()->audit.step(level)?proof(Phase.STRUCTURE_DISK_AUDIT,structures.readText("disk-verified")):Optional.empty()));});
        engines.put(Phase.FAUNA,p->fauna());
        engines.put(Phase.POPULATION_DISK_AUDIT,p->{if(request.version()==4)return ()->proof(Phase.POPULATION_DISK_AUDIT,new PhysicalStore(directory.resolve("perimeter-fauna/index")).readText("aggregate"));var audit=new NativePopulationAudit(faunaPlan(),fauna);return savedBoundary(withFaunaWindow(audit.plan(),()->audit.step(level)?proof(Phase.POPULATION_DISK_AUDIT,fauna.readText("population-disk-verified")):Optional.empty()));});
        engines.put(Phase.CATALOG,p->catalogTask());
        engines.put(Phase.DIRECTOR_IMPORT,p->()->importReceipt());
        engines.put(Phase.FINAL_AUDIT,p->finalAuditTask());
        driver=new QualificationOrchestrator(directory.resolve("pipeline"),request.batch(),pinned.request(),engines,
            ()->{binding.verify();NativePhysicalRuntime.guard(level,area());if(NativePhysicalRuntime.hasActive(level))throw new IllegalStateException("Standalone engine owns dimension");},
            (phase,receipt)->{
                var r=JSON.fromJson(receipt,Receipt.class);
                if(r.version()!=1||!r.batch().equals(request.batch())||!r.phase().equals(phase.name())||!r.status().equals("VERIFIED")||!r.evidenceSha256().matches("[0-9a-f]{64}"))throw new IOException("Phase receipt identity mismatch");
                if(!r.evidenceSha256().equals(PhysicalStore.sha(artifacts.read("evidence-"+phase.name(),String.class))))throw new IOException("Phase evidence bytes changed");
                if(!artifacts.readText("phase-"+phase.name()).equals(receipt))throw new IOException("Phase evidence publication missing");
            });
    }
    private WorldPrepSavedData.Area area(){return WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),request.area());}
    private Optional<String> proof(Phase phase,String evidence)throws IOException {
        var receipt=new Receipt(1,request.batch(),phase.name(),PhysicalStore.sha(evidence),"VERIFIED");
        artifacts.write("evidence-"+phase.name(),evidence);
        artifacts.write("phase-"+phase.name(),receipt);return Optional.of(JSON.toJson(receipt));
    }
    private QualificationOrchestrator.Task ecology()throws IOException {
        var header=request.ecology();var job=WorldPrepRuntime.data(level).jobs().get(header.batchId());
        if(header.schemaVersion()!=1||header.stages()<1||header.minX()>area().minX()||header.minZ()>area().minZ()
            ||header.maxX()<area().maxX()||header.maxZ()<area().maxZ()
            ||job==null||job.operation()!=WorldPrepSavedData.Operation.BATCH_ECOLOGY||job.state()!=WorldPrepSavedData.JobState.COMPLETED
            ||job.cursor()!=header.stages()||!header.dimension().equals(area().dimension())||header.worldSeed()!=level.getSeed())
            throw new IOException("Ecology durable completion precondition missing");
        String text=bounded(BatchManifestStore.directory(prep,header.batchId()).resolve("completed.json"),65536);
        var completion=JSON.fromJson(text,BatchManifestStore.Completion.class);
        if(completion.schemaVersion()!=1||!completion.batchId().equals(header.batchId())||completion.stages()!=header.stages()
            ||!completion.status().equals("STAGES_COMPLETED_DIRECTOR_NOT_READY"))throw new IOException("Ecology completion receipt mismatch");
        return new QualificationOrchestrator.Task(){long cursor;public Optional<String> step()throws Exception {
            if(cursor<header.stages()){BatchManifestStore.read(prep,header,cursor++);return Optional.empty();}
            if(!BatchManifestStore.previousHash(prep,header.batchId(),header.stages()).equals(completion.finalReceiptSha256()))throw new IOException("Ecology terminal chain mismatch");
            return proof(Phase.ECOLOGY_RECEIPTS,text);
        }};
    }
    private QualificationOrchestrator.Task backup()throws IOException {
        binding.verify();
        var first=QualificationBinding.decode(QualificationBinding.read(binding.first(),65536),PagedBackupVerifier.Header.class);
        var second=QualificationBinding.decode(QualificationBinding.read(binding.second(),65536),PagedBackupVerifier.Header.class);
        if(first.version()!=2||second.version()!=2)throw new IOException("Legacy backup manifest refused; two V2 copies required");
        Path pagesA=Path.of(binding.first()+".pages"),pagesB=Path.of(binding.second()+".pages");
        var pair=BackupAuditPair.open(first,second,world,pagesA,pagesB,directory,binding.exclusions());
        var a=pair.first();var b=pair.second();
        return new QualificationOrchestrator.Task(){
            int audited;NativeBackupFaunaCensus census;
            public Optional<String> step()throws IOException {
                binding.verify();
                if(audited==0){if(a.stepBounded())audited=1;return Optional.empty();}
                if(audited==1){if(b.stepBounded())audited=2;return Optional.empty();}
                if(census==null)census=new NativeBackupFaunaCensus(level,first,pagesA,new PhysicalStore(directory.resolve("disk-fauna-census")));
                var result=census.stepBounded(16);if(result.isEmpty())return Optional.empty();
                return proof(Phase.INTEGRITY_AND_BACKUP,JSON.toJson(Map.of("backup",first,"secondBackup",second,"binding",binding.verify(),"census",result.get())));
            }
            public void close(){pair.close();}
        };
    }
    private QualificationOrchestrator.Task withStructureWindow(NativeStructurePass.Plan plan,QualificationOrchestrator.Task task) {
        var window=new NativeChunkWindow(level);
        return new QualificationOrchestrator.Task(){
            public Optional<String> step()throws Exception{return window.structure(plan)?task.step():Optional.empty();}
            public void close()throws IOException{try{task.close();}finally{window.close();}}
        };
    }
    private QualificationOrchestrator.Task withFaunaWindow(NativeFaunaPass.Plan plan,QualificationOrchestrator.Task task) {
        var window=new NativeChunkWindow(level);
        return new QualificationOrchestrator.Task(){
            public Optional<String> step()throws Exception{return window.fauna(plan)?task.step():Optional.empty();}
            public void close()throws IOException{try{task.close();}finally{window.close();}}
        };
    }
    private QualificationOrchestrator.Task savedBoundary(QualificationOrchestrator.Task audit) {
        return new QualificationOrchestrator.Task() {
            boolean saved;
            public void close()throws IOException{audit.close();}
            public Optional<String> step()throws Exception {
                if(!saved) {
                    if(!fr.proutlost.worldprep.runtime.BlockMutationRuntime.checkpointReady(level,WorldPrepRuntime.data(level)))return Optional.empty();
                    // Exactly one normal synchronous save on the server thread. Native save event
                    // drains the existing SavedData gate; no parallel/autosave worker is introduced.
                    level.save(null,true,false);saved=true;return Optional.empty();
                }
                return audit.step();
            }
        };
    }
    private NativeStructurePass.Plan structurePlan()throws IOException{return structures.read("plan",NativeStructurePass.Plan.class);}
    private NativeFaunaPass.Plan faunaPlan()throws IOException{return fauna.read("plan",NativeFaunaPass.Plan.class);}
    private QualificationOrchestrator.Task structure()throws IOException {
        if(request.version()==4) {
            var sweep=new NativePerimeterStructures(level,area(),request.batch(),JSON.toJson(request),request.template(),directory.resolve("perimeter-structures"));
            return new QualificationOrchestrator.Task() {
                public Optional<String> step()throws Exception {
                    var result=sweep.step();return result.isPresent()?proof(Phase.STRUCTURES,result.get()):Optional.empty();
                }
                public void close()throws IOException{sweep.close();}
            };
        }
        var survey=request.origin()==null&&!structures.exists("plan")?new NativeTemplateSitePlanner(area(),request.template(),JSON.toJson(request),level.getSeed()):null;
        return new QualificationOrchestrator.Task() {
            NativeStructurePass pass;NativeStructurePass.Plan selected;
            final NativeChunkWindow window=new NativeChunkWindow(level);
            public void close(){try{if(survey!=null)survey.close();}finally{window.close();}}
            public Optional<String> step()throws Exception {
                if(pass==null) {
                    NativeStructurePass.Plan plan;
                    if(selected!=null)plan=selected;
                    else if(structures.exists("plan"))plan=structurePlan();
                    else if(survey!=null) {
                        var candidate=survey.step(level);if(candidate.isEmpty())return Optional.empty();plan=candidate.get();
                    } else {
                        var origin=request.origin();
                        if(!window.blocks(origin.x()-16,origin.z()-16,origin.x()+16,origin.z()+16))return Optional.empty();
                        plan=NativeStructurePass.plan(level,area(),request.template(),request.origin().block(),
                        net.minecraft.world.level.block.Rotation.valueOf(request.rotation()),net.minecraft.world.level.block.Mirror.valueOf(request.mirror()),JSON.toJson(request));}
                    selected=plan;if(survey!=null)survey.close();
                    if(!window.structure(plan))return Optional.empty();
                    NativePhysicalRuntime.claim(level,"structure",area(),plan.operation());pass=new NativeStructurePass(plan,structures);
                    return Optional.empty(); // Intent durable before the first mutation tick.
                }
                pass.step(level,32);return pass.done()?proof(Phase.STRUCTURES,structures.readText("apply-"+pass.plan().cells().size())):Optional.empty();
            }
        };
    }
    private QualificationOrchestrator.Task fauna()throws IOException {
        // Publish the world budget only after the backup phase receipt is durable. Publishing
        // it inside the backup audit would invalidate source coverage after a boundary crash.
        var baseline=new PhysicalStore(directory.resolve("disk-fauna-census")).read("baseline",NativeBackupFaunaCensus.Baseline.class);
        var backupProof=com.google.gson.JsonParser.parseString(artifacts.read("evidence-"+Phase.INTEGRITY_AND_BACKUP.name(),String.class));
        if(!backupProof.isJsonObject()||!JSON.toJsonTree(baseline).equals(backupProof.getAsJsonObject().get("census")))
            throw new IOException("Disk census is not bound to completed backup phase evidence");
        new PhysicalStore(prep.resolve("physical-population-budget")).write("disk-baseline",baseline);
        if(request.version()==4) {
            var sweep=new NativePerimeterFauna(level,area(),request.batch(),JSON.toJson(request),request.fauna(),directory.resolve("perimeter-fauna"));
            return new QualificationOrchestrator.Task() {
                public Optional<String> step()throws Exception {
                    var result=sweep.step();return result.isPresent()?proof(Phase.FAUNA,result.get()):Optional.empty();
                }
                public void close()throws IOException{sweep.close();}
            };
        }
        return new QualificationOrchestrator.Task(){
            final NativeChunkWindow window=new NativeChunkWindow(level);NativeFaunaPass pass;
            public void close(){window.close();}
            public Optional<String> step()throws IOException {
                if(pass==null){
                    var a=area();if(!window.blocks(a.minX(),a.minZ(),a.maxX(),a.maxZ()))return Optional.empty();
                    boolean replayPlan=fauna.exists("plan");
                    var plan=replayPlan?faunaPlan():NativeFaunaPass.plan(level,a,request.fauna(),request.population().stream().map(Position::block).toList(),JSON.toJson(request));
                    if(replayPlan)NativePhysicalRuntime.requireRetainedPopulation(level,plan);
                    else NativePhysicalRuntime.reservePopulation(level,plan);
                    NativePhysicalRuntime.claim(level,"fauna",a,plan.operation());pass=new NativeFaunaPass(plan,fauna);return Optional.empty();
                }
                pass.step(level,1);return pass.done()?proof(Phase.FAUNA,fauna.readText("population-issued")):Optional.empty();
            }
        };
    }
    private PerimeterGroups groups() {
        var a=area();return new PerimeterGroups(request.batch(),PhysicalStore.sha(JSON.toJson(request)),a.dimension(),a.minX(),a.minZ(),a.maxX(),a.maxZ());
    }
    private record SiteEvidence(SemanticRegion region,PhysicalCatalogExporter.Site site) {}
    private SiteEvidence site(PhysicalStore structures)throws IOException {
        var plan=structures.read("plan",NativeStructurePass.Plan.class);var receipt=structures.read("disk-verified",NativeStructureAudit.Receipt.class);
        if(structures.exists("rollback-intent")||!receipt.operation().equals(plan.operation())||!receipt.status().equals("PLACED_VERIFIED")
            ||!receipt.planSha256().equals(PhysicalStore.sha(structures.readText("plan"))))throw new IOException("Structure proof invalid");
        for(var cell:plan.cells()) {
            var pos=new net.minecraft.core.BlockPos(cell.x(),cell.y(),cell.z());
            NativeStructurePass.requireSite(level,WorldPrepRuntime.data(level),area(),pos);
            NativeStructurePass.verifyLiveCell(level,cell);
        }
        var cells=plan.cells();int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        for(var c:cells){minX=Math.min(minX,c.x());minY=Math.min(minY,c.y());minZ=Math.min(minZ,c.z());maxX=Math.max(maxX,c.x());maxY=Math.max(maxY,c.y());maxZ=Math.max(maxZ,c.z());}
        var dimension=new Id(plan.dimension());var revision=new Revision(request.revision());
        var geometry=List.of(new Bounds(dimension,new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ)));
        var region=new SemanticRegion(new SemanticRegionId(new Id("proutlost:prepared_area/"+PhysicalStore.sha(plan.area().id()))),revision,geometry,Set.of(),0);
        var id=new Id("proutlost:template_instance/"+plan.operation());
        var physical=new EvidenceRef(new Id("proutlost:physical/"+plan.operation()),new Digest(PhysicalStore.sha(structures.readText("apply-"+cells.size()))),new Id("proutlost:exact_static_template_v1"));
        var disk=new EvidenceRef(new Id("proutlost:disk/"+plan.operation()),new Digest(PhysicalStore.sha(structures.readText("disk-verified"))),new Id("proutlost:anvil_readback_v1"));
        var site=new PhysicalCatalogExporter.Site(id,revision,SourceType.worldprep_prepared_site,null,new Id(plan.template()),geometry,Set.of(region.region_id()),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(physical),List.of(disk));
        return new SiteEvidence(region,site);
    }
    private QualificationOrchestrator.Task catalogTask()throws IOException {
        var discovery=new NativeLocationDiscovery(level,area(),new PhysicalStore(directory.resolve("location-discovery")));
        var walkingStore=new PhysicalStore(directory.resolve("catalog-walking"));
        var walking=new NativeSpawnAudit(level,area(),request.spawn(),walkingStore);
        return new QualificationOrchestrator.Task() {
            final NativeChunkWindow window=new NativeChunkWindow(level);
            public void close(){try{window.close();}finally{try{discovery.close();}finally{walking.close();}}}
            long cursor;long placedGroups,populatedGroups;boolean checkedCoverage;
            boolean discovered,walked,natural;final List<SiteEvidence> sites=new ArrayList<>();
            public Optional<String> step()throws Exception {
                long count=request.version()==4?groups().count():1;
                if(cursor<count) {
                    var store=request.version()==4?new PhysicalStore(directory.resolve("perimeter-structures/group-"+cursor)):structures;
                    if(store.exists("plan")) {
                        if(!window.structure(store.read("plan",NativeStructurePass.Plan.class)))return Optional.empty();
                        sites.add(site(store));placedGroups++;
                    } else {
                        var omitted=store.read("empty-survey",NativePerimeterStructures.Evidence.class);
                        if(!"NO_ELIGIBLE_EXACT_SITE".equals(omitted.disposition()))throw new IOException("Missing group physical disposition");
                    }
                    if(request.version()==4) {
                        var population=new PhysicalStore(directory.resolve("perimeter-fauna/group-"+cursor));
                        var evidence=population.read("evidence",NativePerimeterFauna.Evidence.class);
                        if(evidence.ordinal()!=cursor||!groups().group(cursor).operation().toString().equals(evidence.child()))
                            throw new IOException("Population group evidence identity mismatch");
                        if("POPULATION_SAVE_READBACK_VERIFIED".equals(evidence.disposition()))populatedGroups++;
                        else if(!Set.of("NO_ELIGIBLE_HABITAT","DURABLE_BUDGET_CONSUMED").contains(evidence.disposition()))
                            throw new IOException("Unexpected population disposition");
                    }
                    cursor++;return Optional.empty();
                }
                if(!checkedCoverage) {
                    if(request.version()==4)
                        PerimeterPhysicalCompletion.requireNonempty(count,placedGroups,populatedGroups);
                    checkedCoverage=true;
                }
                if(!discovered){window.release();discovered=discovery.step();return Optional.empty();}
                if(!walked){walked=walking.step();return Optional.empty();}
                if(!natural){
                    var spec=request.spawn();boolean grass=true,metadata=false,protectedTerrain=false;
                    if(WorldPrepRuntime.data(level).protections().size()>256)throw new IOException("Natural protection registry bound");
                    var samples=walkingStore.read("samples",NativeSpawnAudit.Sample[].class);
                    // Only level, all-grass support is admitted by this first natural adapter.
                    for(var sample:samples)if(sample.y()==spec.y()-1&&!sample.state().equals("minecraft:grass_block[snowy=false]"))grass=false;
                    for(int x=spec.x()-spec.radius();x<=spec.x()+spec.radius();x++)for(int z=spec.z()-spec.radius();z<=spec.z()+spec.radius();z++) {
                        var chunk=fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireChunk(level,x>>4,z>>4);
                        metadata|=!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty();
                        protectedTerrain|=WorldPrepRuntime.data(level).protectedBlock(area().dimension(),x,z);
                    }
                    // Disk metadata must also be empty in every walking chunk, not merely live maps.
                    var folder=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),world).resolve("region");
                    // This adapter is intentionally restricted to a single chunk to retain one disk read/tick.
                    if((spec.x()-spec.radius()>>4)!=(spec.x()+spec.radius()>>4)||(spec.z()-spec.radius()>>4)!=(spec.z()+spec.radius()>>4))grass=false;
                    if(grass&&!metadata&&!protectedTerrain) {
                        var disk=ReadOnlyChunkNbt.readBounded(folder,spec.x()>>4,spec.z()>>4).getCompound("structures");
                        metadata=!disk.contains("starts",net.minecraft.nbt.Tag.TAG_COMPOUND)||!disk.contains("References",net.minecraft.nbt.Tag.TAG_COMPOUND)||!disk.getCompound("starts").isEmpty()||!disk.getCompound("References").isEmpty();
                        if(!metadata) {
                            var habitat=fr.proutlost.worldprep.ecology.HabitatDiscovery.classify(List.of(new fr.proutlost.worldprep.ecology.HabitatDiscovery.Sample(spec.x(),spec.z(),spec.y()-1,0,false,false)),false).getFirst();
                            var dimension=new Id(area().dimension());var revision=new Revision(request.revision());
                            var bounds=new Bounds(dimension,new BlockPos(spec.x()-spec.radius(),spec.y()-1,spec.z()-spec.radius()),new BlockPos(spec.x()+spec.radius(),spec.y()+2,spec.z()+spec.radius()));
                            var region=new SemanticRegion(new SemanticRegionId(new Id("proutlost:observed_area/"+PhysicalStore.sha(area().dimension()+":"+spec.x()+":"+spec.y()+":"+spec.z()))),revision,List.of(bounds),Set.of(),0);
                            var evidence=new EvidenceRef(new Id("proutlost:walking/"+PhysicalStore.sha(walkingStore.readText("verified"))),new Digest(PhysicalStore.sha(walkingStore.readText("verified"))),new Id("proutlost:walking_disk_v1"));
                            var site=ObservedNaturalSite.create(dimension,spec.x(),spec.y(),spec.z(),spec.radius(),revision,region.region_id(),true,"OPEN_GRASSLAND".equals(habitat.habitat()),false,false,List.of(evidence),List.of(evidence));
                            sites.add(new SiteEvidence(region,site));walkingStore.write("natural-disposition","OBSERVED_GRASSLAND_WITH_VERIFIED_WALKING_ANCHOR");
                        }
                    }
                    if(sites.stream().noneMatch(v->v.site().source()==SourceType.worldprep_natural_site))walkingStore.write("natural-disposition","NOT_READY_NATURAL_ADAPTER_PRECONDITIONS");
                    natural=true;return Optional.empty();
                }
                var approved=sites.stream().map(SiteEvidence::site).toList();
                var result=PhysicalCatalogExporter.export(new Id(request.worldId()),new Id(request.buildId()),new Revision(request.revision()),
                    Instant.parse(request.validatedAt()),sites.stream().map(SiteEvidence::region).toList(),approved,s->{
                        if(!approved.contains(s))throw new IllegalStateException("Physical evidence substitution");
                    });
                if(result.json()==null)throw new IOException(result.reason());
                CatalogPublication.write(directory.resolve("catalog.json"),result.json());return proof(Phase.CATALOG,result.json());
            }
        };
    }
    private Optional<String> importReceipt()throws IOException {
        if("DEV_SERVICE_PROBE".equals(request.directorReceipt())) {
            if(net.neoforged.fml.loading.FMLLoader.isProduction())throw new IOException("DEV consumer bridge forbidden in production");
            var providers=ServiceLoader.load(CatalogImportProbe.class).stream().limit(2).toList();
            if(providers.size()!=1)throw new IOException("Exactly one DEV consumer provider required");
            var provider=providers.getFirst().get();var type=provider.getClass();
            String resource="/"+type.getName().replace('.','/')+".class";String implementation;
            try(var input=type.getResourceAsStream(resource)) {
                if(input==null)throw new IOException("DEV consumer implementation bytes unavailable");
                byte[] bytes=input.readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IOException("DEV consumer implementation bound");
                try{implementation=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
                catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
            }
            if(!implementation.equals(request.directorImporterSha256()))throw new IOException("DEV provider class digest differs from frozen configuration");
            String catalog=bounded(directory.resolve("catalog.json"),1024*1024);
            var admitted=provider.importAndReadAdmitted(catalog,new Id(request.worldId()),new Id(request.buildId()));
            CatalogValidation.requireSealed(admitted);
            if(!StrictCodec.encode(admitted).equals(catalog))throw new IOException("DEV consumer admitted different physical catalogue");
            // Receipt is derived only after real admission and readback, never supplied as JSON authority.
            return proof(Phase.DIRECTOR_IMPORT,JSON.toJson(Map.of("version",1,"provider",type.getName(),
                "providerClassSha256",implementation,"catalogueSha256",PhysicalStore.sha(catalog),"status","IMPORTED_REAL_DEV_RUNTIME")));
        }
        Path staged=world.resolve(request.directorReceipt()).normalize();
        if(Path.of(request.directorReceipt()).isAbsolute()||!staged.startsWith(world))throw new IOException("Import receipt must be staged in candidate");
        String text=bounded(staged,4096);
        var receipt=JSON.fromJson(text,ImportReceipt.class);
        if(receipt.version()!=1||!receipt.worldId().equals(request.worldId())||!receipt.buildId().equals(request.buildId())
            ||!receipt.catalogueSha256().equals(PhysicalStore.sha(bounded(directory.resolve("catalog.json"),1024*1024)))
            ||!receipt.importerSourceSha256().equals(request.directorImporterSha256())||receipt.runtimeIdentity()==null||receipt.runtimeIdentity().isBlank()
            ||!receipt.status().equals("IMPORTED_REAL_ISOLATED_RUNTIME"))throw new IOException("Director NOT_READY: real import receipt not proven");
        var providers=java.util.ServiceLoader.load(fr.proutlost.worldprep.director.CatalogImportProbe.class).stream().limit(2).toList();
        if(providers.size()!=1)throw new IOException("Director NOT_READY: exactly one real consumer import probe required; JSON receipt is insufficient");
        String catalog=bounded(directory.resolve("catalog.json"),1024*1024);
        var admitted=providers.getFirst().get().importAndReadAdmitted(catalog,new Id(request.worldId()),new Id(request.buildId()));
        CatalogValidation.requireSealed(admitted);
        if(!StrictCodec.encode(admitted).equals(catalog))throw new IOException("Consumer admitted a different snapshot");
        return proof(Phase.DIRECTOR_IMPORT,text);
    }
    private QualificationOrchestrator.Task finalAuditTask()throws IOException {
        var store=new PhysicalStore(directory.resolve("spawn-final"));
        var walking=new NativeSpawnAudit(level,area(),request.spawn(),store);
        var saved=savedBoundary(()->walking.step()?Optional.of(store.readText("verified")):Optional.empty());
        var physical=physicalFinalAuditTask();
        return new QualificationOrchestrator.Task() {
            boolean verified;
            public Optional<String> step()throws Exception {
                if(!verified) {
                    var result=saved.step();if(result.isEmpty())return Optional.empty();
                    artifacts.write("spawn-evidence",result.get());walking.close();verified=true;return Optional.empty();
                }
                return physical.step();
            }
            public void close()throws IOException{try{saved.close();physical.close();}finally{walking.close();}}
        };
    }
    private QualificationOrchestrator.Task physicalFinalAuditTask() {
        if(request.version()!=4) {
            return new QualificationOrchestrator.Task(){
                final NativeChunkWindow window=new NativeChunkWindow(level);boolean structureChecked;
                public void close(){window.close();}
                public Optional<String> step()throws IOException {
                    if(!structureChecked){var plan=structurePlan();if(!window.structure(plan))return Optional.empty();site(structures);structureChecked=true;window.release();return Optional.empty();}
                    if(!window.fauna(faunaPlan()))return Optional.empty();
                    for(var animal:faunaPlan().animals())if(level.getEntity(animal.uuid())==null)throw new IOException("Final population missing");
                    for(var p:Phase.values())if(p!=Phase.FINAL_AUDIT)artifacts.readText("phase-"+p.name());
                    return proof(Phase.FINAL_AUDIT,"QUALIFIED_NOT_PUBLISHED:"+request.batch()+":spawn:"+PhysicalStore.sha(artifacts.read("spawn-evidence",String.class)));
                }
            };
        }
        return new QualificationOrchestrator.Task() {
            long cursor;boolean structureChecked;final NativeChunkWindow window=new NativeChunkWindow(level);
            public void close(){window.close();}
            public Optional<String> step()throws IOException {
                if(cursor==groups().count()) {
                    for(var p:Phase.values())if(p!=Phase.FINAL_AUDIT)artifacts.readText("phase-"+p.name());
                    return proof(Phase.FINAL_AUDIT,"PERIMETER_PHYSICAL_QUALIFIED_NOT_PUBLISHED:"+request.batch()+":spawn:"+PhysicalStore.sha(artifacts.read("spawn-evidence",String.class)));
                }
                var structure=new PhysicalStore(directory.resolve("perimeter-structures/group-"+cursor));
                if(!structureChecked){
                    if(structure.exists("plan")){if(!window.structure(structure.read("plan",NativeStructurePass.Plan.class)))return Optional.empty();site(structure);}else structure.readText("empty-survey");
                    structureChecked=true;window.release();return Optional.empty();
                }
                var population=new PhysicalStore(directory.resolve("perimeter-fauna/group-"+cursor));
                if(population.exists("plan")) {
                    var plan=population.read("plan",NativeFaunaPass.Plan.class);
                    if(!window.fauna(plan))return Optional.empty();
                    for(var animal:plan.animals()) {
                        var live=level.getEntity(animal.uuid());
                        if(live==null||!live.isAlive()||!live.getPersistentData().getString("proutlost_initial_operation").equals(plan.operation().toString()))
                            throw new IOException("Perimeter population missing/ownership changed");
                    }
                }
                population.readText("evidence");window.release();structureChecked=false;cursor++;return Optional.empty();
            }
        };
    }
    private Optional<String> finalAudit()throws IOException {
        if(structures.exists("rollback-intent"))throw new IOException("Structure rolled back after audit");
        for(var p:Phase.values())if(p!=Phase.FINAL_AUDIT)artifacts.readText("phase-"+p.name());
        // Physical authorities must still match; receipt history alone never grants final admission.
        for(var c:structurePlan().cells()) {
            var pos=new net.minecraft.core.BlockPos(c.x(),c.y(),c.z());
            NativeStructurePass.requireSite(level,WorldPrepRuntime.data(level),area(),pos);
            NativeStructurePass.verifyLiveCell(level,c);
        }
        for(var animal:faunaPlan().animals())if(level.getEntity(animal.uuid())==null)throw new IOException("Final population missing");
        return proof(Phase.FINAL_AUDIT,"QUALIFIED_NOT_PUBLISHED:"+request.batch()+":spawn:"+PhysicalStore.sha(artifacts.read("spawn-evidence",String.class)));
    }
    private String boundInput(String relative,String expected,int limit)throws IOException {
        if(relative==null||expected==null||!expected.matches("[0-9a-f]{64}"))throw new IOException("Pinned external receipt required");
        Path path=world.resolve(relative).normalize();if(!path.startsWith(world)||Path.of(relative).isAbsolute())throw new IOException("External receipt must be staged in candidate");
        String text=bounded(path,limit);if(!PhysicalStore.sha(text).equals(expected))throw new IOException("Pinned input SHA256 mismatch");return text;
    }
    private static String bounded(Path file,int limit)throws IOException {
        for(Path p=file.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink evidence");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("Missing/bounded evidence required");return Files.readString(file);
    }
    public void tick(){driver.tick();}
    public void pause(){driver.pause();}
    public void resume(){driver.resume();}
    public String status(){return driver.status()+": "+driver.reason();}
    @Override public void close()throws IOException{driver.close();}
}
