package fr.proutlost.worldprep.physical;

import com.google.gson.*;
import fr.proutlost.contract.director.*;
import static fr.proutlost.contract.director.Vocabulary.*;
import fr.proutlost.worldprep.orchestration.*;
import static fr.proutlost.worldprep.orchestration.QualificationPipeline.Phase;
import fr.proutlost.worldprep.director.*;
import fr.proutlost.worldprep.director.CatalogImportAdmissionGate;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.*;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Native V5 *separate* multi-species qualification pipeline.
 *
 * No V3/V4 cursor migration or implicit journal fallback. All nine phases
 * are represented in the same fail-closed QualificationPipeline as legacy,
 * but this class owns separate V5 identity, stage/WAL and species receipts.
 * Even complete DEV qualification remains QUALIFIED_NOT_PUBLISHED; a real
 * independently approved Director G0/G5 runtime is never inferred.
 */
public final class NativeV5QualificationSession implements NativeQualificationHandle {
    private static final Gson JSON=new Gson();
    public record Receipt(int version,UUID batch,String phase,String evidenceSha256,String status) {}
    public record ImportReceipt(int version,String worldId,String buildId,String catalogueSha256,
                                String importerSourceSha256,String runtimeIdentity,String status) {}
    private record PhysicalSite(SemanticRegion region,PhysicalCatalogExporter.Site site) {}
    private final ServerLevel level;
    private final NativeV5QualificationIntent request;
    private final Path world,prep,directory;
    private final QualificationBinding binding;
    private final PhysicalStore artifacts;
    private final QualificationOrchestrator driver;
    private final String frozen;
    private final boolean contraComposite;
    private final ContraV5SegmentedManifest.Manifest contraManifest;

    public NativeV5QualificationSession(ServerLevel level,NativeV5QualificationIntent request,
                                         String exactRequest)throws IOException {
        this.level=Objects.requireNonNull(level);
        this.request=Objects.requireNonNull(request);
        NativePhysicalRuntime.requireThread(level);
        if(FMLLoader.isProduction() && "DEV_SERVICE_PROBE".equals(request.directorReceipt()))
            throw new IOException("Production Director cannot use DEV consumer");
        world=level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        prep=world.resolve("worldprep-v2");
        directory=NativePhysicalRuntime.root(level).resolve("qualification-"+request.batch());
        BackupAuditPair.requireNoLegacy(directory);
        frozen=QualificationBinding.canonical(QualificationBinding.object(exactRequest));
        binding=new QualificationBinding(world,directory,NativeV5QualificationIntent.class);
        String bound=QualificationBinding.exactContraSegmented(request)
            ?binding.verifySegmented().request():binding.verify().request();
        if(!bound.equals(exactRequest)||!bound.equals(frozen))
            throw new IOException("V5 requires identical canonical bound bytes");
        NativeV5ContractGate.requireActualSealedBinding(
            world,directory,exactRequest,request.multiSpecies(),area(),FMLLoader.isProduction());
        if(!ContraV5SectorLineage.isExactOrPinnedSector(request.ecology(),area())
            ||request.ecology().worldSeed()!=level.getSeed()
            ||request.ecology().stages()<1
            ||request.multiSpecies().stages().size()<2)
            throw new IOException("V5 exact ecology/species identity conflict");
        contraComposite=ContraV5CompositeRoute.exactParent(
            area(),request.ecology(),level.getSeed());
        if(ContraV5SectorLineage.PARENT.equals(area().id())&&!contraComposite)
            throw new IOException("Contra global V5 requires the EXACT 645160-stage parent and 1024-group geometry");
        if(contraComposite&&!"CONTRA_S2".equals(request.spawn().profile()))
            throw new IOException("Contra must audit its unchanged genuine 2048,127,2048 MAP spawn, never a fixture");
        contraManifest=contraComposite?ContraV5CompositeRoute.manifest(
            request,frozen,area(),level.getSeed()):null;
        artifacts=new PhysicalStore(directory.resolve("artifacts"));
        artifacts.write("parent-area",area());

        var engines=new EnumMap<Phase,QualificationOrchestrator.Factory>(Phase.class);
        engines.put(Phase.ECOLOGY_RECEIPTS,p->ecology());
        engines.put(Phase.INTEGRITY_AND_BACKUP,p->backup());
        engines.put(Phase.STRUCTURES,p->structures());
        engines.put(Phase.STRUCTURE_DISK_AUDIT,p->structureReceipts());
        engines.put(Phase.FAUNA,p->species());
        engines.put(Phase.POPULATION_DISK_AUDIT,p->settlements());
        engines.put(Phase.CATALOG,p->catalog());
        engines.put(Phase.DIRECTOR_IMPORT,p->directorImport());
        engines.put(Phase.FINAL_AUDIT,p->finalAudit());

        driver=new QualificationOrchestrator(directory.resolve("pipeline"),request.batch(),frozen,
            engines,()->{
                NativePhysicalRuntime.requireThread(level);
                if(QualificationBinding.exactContraSegmented(request))
                    binding.verifySegmented();
                else binding.verify();
                NativePhysicalRuntime.guard(level,area());
                if(NativePhysicalRuntime.hasActive(level))
                    throw new IOException("Another native engine owns this dimension");
            },(phase,receipt)->{
                var record=JSON.fromJson(receipt,Receipt.class);
                if(record==null||record.version()!=1||!record.batch().equals(request.batch())
                    ||!record.phase().equals(phase.name())||!"VERIFIED".equals(record.status())
                    ||!record.evidenceSha256().matches("[0-9a-f]{64}")
                    ||!record.evidenceSha256().equals(
                        PhysicalStore.sha(artifacts.read("evidence-"+phase.name(),String.class)))
                    ||!artifacts.readText("phase-"+phase.name()).equals(receipt))
                    throw new IOException("Changed native v5 phase evidence "+phase);
            });
    }
    private WorldPrepSavedData.Area area() {
        return WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level),request.area());
    }
    private Optional<String> proof(Phase phase,String text)throws IOException {
        var record=new Receipt(1,request.batch(),phase.name(),PhysicalStore.sha(text),"VERIFIED");
        artifacts.write("evidence-"+phase.name(),text);
        artifacts.write("phase-"+phase.name(),record);
        return Optional.of(JSON.toJson(record));
    }
    private QualificationOrchestrator.Task ecology()throws IOException {
        var header=request.ecology();
        var area=area();
        var parent=WorldPrepRuntime.data(level).jobs().get(header.batchId());
        if(header.schemaVersion()!=1||parent==null
            ||parent.operation()!=WorldPrepSavedData.Operation.BATCH_ECOLOGY
            ||parent.state()!=WorldPrepSavedData.JobState.COMPLETED
            ||parent.cursor()!=header.stages()
            ||!ContraV5SectorLineage.isExactOrPinnedSector(header,area)
            ||!header.dimension().equals(area.dimension())
            ||header.worldSeed()!=level.getSeed()
            ||header.minX()>area.minX()||header.minZ()>area.minZ()
            ||header.maxX()<area.maxX()||header.maxZ()<area.maxZ())
            throw new IOException("Actual completed ecology batch prerequisite missing");
        var finish=bounded(BatchManifestStore.directory(prep,header.batchId()).resolve("completed.json"),65536);
        var completion=JSON.fromJson(finish,BatchManifestStore.Completion.class);
        if(completion==null||completion.schemaVersion()!=1||!completion.batchId().equals(header.batchId())
            ||completion.stages()!=header.stages()
            ||!"STAGES_COMPLETED_DIRECTOR_NOT_READY".equals(completion.status()))
            throw new IOException("Wrong ecology terminal receipt");
        return new QualificationOrchestrator.Task() {
            long cursor;
            public Optional<String> step()throws Exception {
                // Finite incremental SHA/replay; no receipt or terrain is skipped.
                for(int i=0;i<16&&cursor<header.stages();i++)
                    BatchManifestStore.read(prep,header,cursor++);
                if(cursor<header.stages())return Optional.empty();
                if(!BatchManifestStore.previousHash(prep,header.batchId(),header.stages())
                    .equals(completion.finalReceiptSha256()))
                    throw new IOException("Terminal ecology receipt hash mismatch");
                return proof(Phase.ECOLOGY_RECEIPTS,finish);
            }
        };
    }
    /** RUN69: actual Contra V5 uses the complete frozen multi-segment
     * independent A/B cold SHA receipt, followed by every segment's native
     * bounded fauna census. Never fall back to a synthetic 100k V2 inventory.
     * No V5 is admitted without an actual prior cold seal.
     */
    private QualificationOrchestrator.Task segmentedBackup()throws IOException {
        var sealed=binding.verifySegmented();
        var audited=binding.segmentedColdAudit();
        if(sealed.verifiedFiles()<1||sealed.verifiedPages()<1
            ||!sealed.manifestSha256().equals(request.backupSha256()))
            throw new IOException("RUN69 cold V5 segmented evidence mismatch");
        return new QualificationOrchestrator.Task() {
            NativeBackupFaunaCensus census;
            public Optional<String> step()throws IOException {
                var exact=binding.verifySegmented();
                if(!exact.equals(sealed))
                    throw new IOException("RUN69 live native V5 WAL changed");
                if(census==null)
                    census=new NativeBackupFaunaCensus(level,
                        Path.of(request.backupManifest()),world,
                        Path.of(request.backupRootA()),Path.of(request.backupRootB()),
                        request.batch(),new PhysicalStore(directory.resolve("disk-fauna-census")));
                var result=census.stepBounded(16);
                if(result.isEmpty())return Optional.empty();
                return proof(Phase.INTEGRITY_AND_BACKUP,JSON.toJson(Map.of(
                    "segmentedBinding",sealed,
                    "independentFullColdABSHAReceipt",audited,
                    "completeMultiSegmentCensus",result.orElseThrow(),
                    "manifestSha256",sealed.manifestSha256(),
                    "status","VERIFIED_COLD_A_B_AND_COMPLETE_CENSUS_NOT_DIRECTOR_PUBLICATION")));
            }
        };
    }
    private QualificationOrchestrator.Task backup()throws IOException {
        if(contraComposite)return segmentedBackup();
        binding.verify();
        var ha=QualificationBinding.decode(
            QualificationBinding.read(binding.first(),65536),PagedBackupVerifier.Header.class);
        var hb=QualificationBinding.decode(
            QualificationBinding.read(binding.second(),65536),PagedBackupVerifier.Header.class);
        if(ha.version()!=2||hb.version()!=2)
            throw new IOException("V5 requires two V2 immutable backup headers");
        // V5-only, DEV-only cold-snapshot continuity protocol. Minecraft writes
        // its own derived lighting/tick metadata after launch; the immutable
        // pre-copy backup bytes MUST remain validated by independent A/B checks,
        // and the live world MUST first pass the full strict semantic audit.
        // Legacy V3/V4 uses the unchanged live-source PagedBackupVerifier.
        String continuity=System.getProperty("proutlost.v5.snapshot.continuity","");
        if(!continuity.isBlank()) {
            if(FMLLoader.isProduction()
                ||!"DEV_SERVICE_PROBE".equals(request.directorReceipt()))
                throw new IOException("External postboot source proof forbidden outside isolated DEV");
            var frozen=new FrozenBackupPairVerifier(world,ha,hb,
                Path.of(binding.first()+".pages"),Path.of(binding.second()+".pages"),
                Path.of(continuity),request.batch());
            return new QualificationOrchestrator.Task(){
                boolean audited;NativeBackupFaunaCensus census;
                public Optional<String> step()throws IOException {
                    binding.verify();
                    if(!audited){audited=frozen.stepBounded();return Optional.empty();}
                    if(census==null)census=new NativeBackupFaunaCensus(level,ha,
                        Path.of(binding.first()+".pages"),
                        new PhysicalStore(directory.resolve("disk-fauna-census")));
                    var result=census.stepBounded(16);
                    if(result.isEmpty())return Optional.empty();
                    return proof(Phase.INTEGRITY_AND_BACKUP,JSON.toJson(Map.of(
                        "backup",ha,"secondBackup",hb,
                        "binding",binding.verify(),
                        "strictFrozenContinuitySha256",frozen.semanticProofSha256(),
                        "coldFilesSHA256VerifiedPerCopy",frozen.verifiedFiles(),
                        "census",result.orElseThrow())));
                }
                public void close()throws IOException {frozen.close();}
            };
        }
        var pair=BackupAuditPair.open(ha,hb,world,
            Path.of(binding.first()+".pages"),Path.of(binding.second()+".pages"),
            directory,binding.exclusions());
        var a=pair.first();var b=pair.second();
        return new QualificationOrchestrator.Task() {
            int stage;
            NativeBackupFaunaCensus census;
            public Optional<String> step()throws IOException {
                binding.verify();
                if(stage==0){if(a.stepBounded())stage=1;return Optional.empty();}
                if(stage==1){if(b.stepBounded())stage=2;return Optional.empty();}
                if(census==null)census=new NativeBackupFaunaCensus(level,ha,
                    Path.of(binding.first()+".pages"),
                    new PhysicalStore(directory.resolve("disk-fauna-census")));
                var result=census.stepBounded(16);
                if(result.isEmpty())return Optional.empty();
                return proof(Phase.INTEGRITY_AND_BACKUP,JSON.toJson(Map.of(
                    "backup",ha,"secondBackup",hb,"binding",binding.verify(),
                    "census",result.orElseThrow())));
            }
            public void close(){pair.close();}
        };
    }
    private QualificationOrchestrator.Task structures()throws IOException {
        if(contraComposite){
            var sweep=new ContraV5SegmentedStructures(level,request,frozen,directory);
            return new QualificationOrchestrator.Task(){
                public Optional<String> step()throws Exception {
                    var result=sweep.step();
                    return result.isPresent()?proof(Phase.STRUCTURES,result.orElseThrow())
                        :Optional.empty();
                }
                public void close()throws IOException{sweep.close();}
            };
        }
        var sweep=new NativePerimeterStructures(level,area(),request.batch(),frozen,
            request.template(),directory.resolve("perimeter-structures"));
        return new QualificationOrchestrator.Task() {
            public Optional<String> step()throws Exception {
                var receipt=sweep.step();
                return receipt.isPresent()
                    ?proof(Phase.STRUCTURES,receipt.orElseThrow()):Optional.empty();
            }
            public void close()throws IOException{sweep.close();}
        };
    }
    /** All 36 structure records must be present: both PLACED and audited SKIP
     * are explicit decisions, but at least one genuinely placed site is required.
     */
    private QualificationOrchestrator.Task structureReceipts()throws IOException {
        if(contraComposite)return new QualificationOrchestrator.Task(){
            public Optional<String> step()throws IOException {
                // Original 17 SHA-chained sectors, all 1024 real group WALs and
                // exact native plan/physical Anvil receipts. SKIP is not a placement.
                var summary=ContraV5SegmentedStructureProofs.audit(
                    contraManifest,directory.resolve("contra-v5-segmented-structures"),frozen);
                if(summary.placed()<17||summary.children().size()!=17||summary.groups()!=1024)
                    throw new IOException("At least one real placement per exact Contra sector required");
                return proof(Phase.STRUCTURE_DISK_AUDIT,JSON.toJson(summary));
            }
        };
        var groups=groups();
        return new QualificationOrchestrator.Task() {
            long ordinal,placed;
            public Optional<String> step()throws IOException {
                if(ordinal<groups.count()) {
                    var store=new PhysicalStore(directory.resolve(
                        "perimeter-structures/group-"+ordinal));
                    var g=groups.group(ordinal);
                    if(store.exists("plan")) {
                        var plan=store.read("plan",NativeStructurePass.Plan.class);
                        var receipt=store.read("disk-verified",NativeStructureAudit.Receipt.class);
                        if(store.exists("rollback-intent")
                           ||!receipt.operation().equals(plan.operation())
                           ||!receipt.planSha256().equals(PhysicalStore.sha(store.readText("plan")))
                           ||!"PLACED_VERIFIED".equals(receipt.status()))
                            throw new IOException("Unsealed native structure group "+ordinal);
                        placed++;
                    }else {
                        var skipped=store.read("empty-survey",NativePerimeterStructures.Evidence.class);
                        if(skipped.ordinal()!=ordinal
                            ||!g.operation().toString().equals(skipped.child())
                            ||!"NO_ELIGIBLE_EXACT_SITE".equals(skipped.disposition()))
                            throw new IOException("Missing exact per-group structure refusal "+ordinal);
                    }
                    ordinal++;return Optional.empty();
                }
                if(placed<1)throw new IOException("No actual native structures physically placed");
                var index=new PhysicalStore(directory.resolve("perimeter-structures/index"));
                var aggregate=index.readText("aggregate");
                return proof(Phase.STRUCTURE_DISK_AUDIT,JSON.toJson(Map.of(
                    "groups",ordinal,"placed",placed,"aggregate",aggregate)));
            }
        };
    }
    private long structureGroupCount(){
        return contraComposite?contraManifest.totalGroups():groups().count();
    }
    private PhysicalStore structureGroupStore(long ordinal)throws IOException {
        Path folder=contraComposite
            ?ContraV5CompositeRoute.structureGroup(directory,contraManifest,ordinal)
            :directory.resolve("perimeter-structures/group-"+ordinal);
        if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing native physical structure group WAL: "+ordinal);
        return new PhysicalStore(folder);
    }
    private PerimeterGroups groups() {
        var a=area();
        return new PerimeterGroups(request.batch(),PhysicalStore.sha(frozen),
            a.dimension(),a.minX(),a.minZ(),a.maxX(),a.maxZ());
    }
    private QualificationOrchestrator.Task species()throws IOException {
        if(contraComposite){
            var sweep=new ContraV5SegmentedFauna(level,request,frozen,directory);
            return new QualificationOrchestrator.Task(){
                public Optional<String> step()throws Exception {
                    var result=sweep.step();
                    return result.isPresent()?proof(Phase.FAUNA,result.orElseThrow())
                        :Optional.empty();
                }
                public void close()throws IOException{sweep.close();}
            };
        }
        // Exactly the same backup-derived initial census and shared disk budget
        // gate as the existing v4 pipeline, but V5 has distinct species children.
        var baseline=new PhysicalStore(directory.resolve("disk-fauna-census"))
            .read("baseline",NativeBackupFaunaCensus.Baseline.class);
        var completed=JsonParser.parseString(
            artifacts.read("evidence-"+Phase.INTEGRITY_AND_BACKUP.name(),String.class));
        if(!completed.isJsonObject()||!JSON.toJsonTree(baseline).equals(
            completed.getAsJsonObject().get("census")))
            throw new IOException("Backup census not bound to immutable phase");
        new PhysicalStore(prep.resolve("physical-population-budget"))
            .write("disk-baseline",baseline);
        var sweep=new NativeMultiSpeciesPerimeter(level,area(),
            request.multiSpecies(),frozen,directory,5);
        return new QualificationOrchestrator.Task() {
            public Optional<String> step()throws Exception {
                var result=sweep.step();
                return result.isPresent()?proof(Phase.FAUNA,result.orElseThrow())
                    :Optional.empty();
            }
            public void close()throws IOException{sweep.close();}
        };
    }
    /** Proof of actual Anvil UUIDs for every species, not just 36 SKIP receipts. */
    private QualificationOrchestrator.Task settlements()throws IOException {
        if(contraComposite){
            var reader=new ContraV5SegmentedFaunaColdAudit.Reader(
                world,directory,request,frozen,contraManifest);
            return new QualificationOrchestrator.Task(){
                public Optional<String> step()throws IOException {
                    var verified=reader.step(); // at most one 64-group sector/species
                    return verified.isPresent()
                        ?proof(Phase.POPULATION_DISK_AUDIT,JSON.toJson(verified.orElseThrow()))
                        :Optional.empty();
                }
            };
        }
        var stages=request.multiSpecies().stages();
        return new QualificationOrchestrator.Task() {
            int cursor;
            final List<NativeV5SpeciesSettlement.Summary> results=new ArrayList<>();
            public Optional<String> step()throws IOException {
                if(cursor<stages.size()) {
                    var stage=stages.get(cursor);
                    var summary=NativeV5SpeciesSettlement.verify(world,
                        directory.resolve(stage.directory()),area(),stage,frozen);
                    NativeV5SpeciesSettlement.requireObservedFauna(summary);
                    results.add(summary);cursor++;return Optional.empty();
                }
                new PhysicalStore(directory.resolve("settlement"))
                    .write("species",results);
                return proof(Phase.POPULATION_DISK_AUDIT,JSON.toJson(results));
            }
        };
    }
    private PhysicalSite prepared(PhysicalStore retained)throws IOException {
        var plan=retained.read("plan",NativeStructurePass.Plan.class);
        var receipt=retained.read("disk-verified",NativeStructureAudit.Receipt.class);
        if(retained.exists("rollback-intent")
            ||!plan.operation().equals(receipt.operation())
            ||!"PLACED_VERIFIED".equals(receipt.status())
            ||!receipt.planSha256().equals(PhysicalStore.sha(retained.readText("plan"))))
            throw new IOException("Unverified V5 physical site");
        for(var cell:plan.cells()) {
            NativeStructurePass.requireSite(level,WorldPrepRuntime.data(level),area(),
                new net.minecraft.core.BlockPos(cell.x(),cell.y(),cell.z()));
            NativeStructurePass.verifyLiveCell(level,cell);
        }
        var cells=plan.cells();
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(var c:cells) {
            x0=Math.min(x0,c.x());y0=Math.min(y0,c.y());z0=Math.min(z0,c.z());
            x1=Math.max(x1,c.x());y1=Math.max(y1,c.y());z1=Math.max(z1,c.z());
        }
        var dim=new Id(plan.dimension());var revision=new Revision(request.revision());
        var bounds=List.of(new Bounds(dim,new BlockPos(x0,y0,z0),new BlockPos(x1,y1,z1)));
        var region=new SemanticRegion(new SemanticRegionId(
            ContraV5PreparedSiteRegionIdentity.forOperation(plan.area().id(),plan.operation()).value()),
            revision,bounds,Set.of(),0);
        var id=new Id("proutlost:template_instance/"+plan.operation());
        var physical=new EvidenceRef(new Id("proutlost:physical/"+plan.operation()),
            new Digest(PhysicalStore.sha(retained.readText("apply-"+cells.size()))),
            new Id("proutlost:exact_static_template_v1"));
        var disk=new EvidenceRef(new Id("proutlost:disk/"+plan.operation()),
            new Digest(PhysicalStore.sha(retained.readText("disk-verified"))),
            new Id("proutlost:anvil_readback_v1"));
        var witnesses=new ArrayList<EvidenceRef>();witnesses.add(physical);
        fr.proutlost.integration.physical.NativeLoadedCellWitness.stateFingerprint(level.getServer(),bounds).ifPresent(digest->witnesses.add(new EvidenceRef(
            new Id("proutlost:loaded_state/"+plan.operation()),digest,fr.proutlost.integration.physical.NativeLoadedCellWitness.VALIDATOR)));
        return new PhysicalSite(region,new PhysicalCatalogExporter.Site(id,revision,
            SourceType.worldprep_prepared_site,null,new Id(plan.template()),bounds,
            Set.of(region.region_id()),List.of(),List.of(),List.of(),List.of(),
            List.of(),List.copyOf(witnesses),List.of(disk)));
    }
    private QualificationOrchestrator.Task catalog()throws IOException {
        var discovery=new NativeLocationDiscovery(level,area(),
            new PhysicalStore(directory.resolve("location-discovery")));
        var walkingStore=new PhysicalStore(directory.resolve("catalog-walking"));
        var walking=new NativeSpawnAudit(level,area(),request.spawn(),walkingStore);
        long totalStructureGroups=structureGroupCount();
        return new QualificationOrchestrator.Task() {
            long cursor,placed;
            final NativeChunkWindow window=new NativeChunkWindow(level);
            final List<PhysicalSite> sites=new ArrayList<>();
            boolean discovered,walked,faunaChecked;
            public Optional<String> step()throws Exception {
                if(cursor<totalStructureGroups) {
                    var retained=structureGroupStore(cursor);
                    if(retained.exists("plan")) {
                        var plan=retained.read("plan",NativeStructurePass.Plan.class);
                        if(!window.structure(plan))return Optional.empty();
                        sites.add(prepared(retained));placed++;window.release();
                    }else retained.readText("empty-survey");
                    cursor++;return Optional.empty();
                }
                if(placed==0)throw new IOException("No physical site for V5 D0 export");
                // Immutable population seal is checked once, not re-read
                // 64,516 times during the bounded location discovery.
                if(!faunaChecked){
                if(contraComposite){
                    var complete=new PhysicalStore(directory.resolve("artifacts"))
                        .read("evidence-POPULATION_DISK_AUDIT",String.class);
                    var nativePhase=new PhysicalStore(directory.resolve("contra-v5-segmented-fauna"))
                        .readText("global-fauna-phase");
                    if(!JsonParser.parseString(complete).equals(JsonParser.parseString(nativePhase)))
                        throw new IOException("Native global population Anvil evidence changed");
                }else for(var stage:request.multiSpecies().stages()) {
                    var summary=NativeV5SpeciesSettlement.verify(world,
                        directory.resolve(stage.directory()),area(),stage,frozen);
                    NativeV5SpeciesSettlement.requireObservedFauna(summary);
                }
                faunaChecked=true;
                }
                if(!discovered) {
                    window.release();
                    discovered=discovery.step();return Optional.empty();
                }
                if(!walked) {
                    walked=walking.step();return Optional.empty();
                }
                var eligible=sites.stream().map(PhysicalSite::site).toList();
                var exported=PhysicalCatalogExporter.export(
                    new Id(request.worldId()),new Id(request.buildId()),
                    new Revision(request.revision()),Instant.parse(request.validatedAt()),
                    sites.stream().map(PhysicalSite::region).toList(),eligible,
                    site->{if(!eligible.contains(site))
                        throw new IllegalStateException("Substituted native V5 site evidence");});
                if(exported.json()==null)
                    throw new IOException("Cannot create verified physical D0 catalogue: "+
                        exported.reason());
                CatalogPublication.write(directory.resolve("catalog.json"),exported.json());
                return proof(Phase.CATALOG,exported.json());
            }
            public void close()throws IOException {
                try{window.close();}
                finally{try{discovery.close();}finally{walking.close();}}
            }
        };
    }
    private QualificationOrchestrator.Task directorImport() {
        return new QualificationOrchestrator.Task() {
            CatalogImportProbe consumer;
            String actualSha,consumerName,devCatalog;
            CatalogImportAdmissionGate gate;

            private void resolveConsumer(boolean dev)throws IOException {
                if(consumer!=null)return;
                var providers=ServiceLoader.load(CatalogImportProbe.class).stream().limit(2).toList();
                if(providers.size()!=1)throw new IOException("Exactly one pinned real Director consumer required");
                var candidate=providers.getFirst().get();
                String name=candidate.getClass().getName();
                if(!dev&&!name.equals("fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe"))
                    throw new IOException("Unexpected or DEV Director production provider");
                String resource="/"+name.replace('.','/')+".class";
                try(var in=candidate.getClass().getResourceAsStream(resource)) {
                    if(in==null)throw new IOException("Director provider bytecode absent");
                    var bytes=in.readNBytes(256*1024+1);
                    if(bytes.length>256*1024)throw new IOException("Oversized Director provider");
                    try{actualSha=HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
                    catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
                }
                if(!actualSha.equals(request.directorImporterSha256()))
                    throw new IOException("Real Director provider differs from frozen bytecode SHA");
                consumer=candidate;consumerName=name;
                gate=new CatalogImportAdmissionGate(new Id(request.worldId()),new Id(request.buildId()),actualSha);
            }

            public Optional<String> step()throws IOException {
                var catalog=bounded(directory.resolve("catalog.json"),1024*1024);
                CatalogImportAdmissionGate.requireQualifiedSource(catalog,
                    artifacts.read("evidence-"+Phase.CATALOG.name(),String.class),
                    new Id(request.worldId()),new Id(request.buildId()));
                boolean dev="DEV_SERVICE_PROBE".equals(request.directorReceipt());
                if(dev&&FMLLoader.isProduction())
                    throw new IOException("Cannot DEV-import physical V5 in production");
                if(dev) {
                    CatalogImportAdmissionGate.requireExactSource(catalog,
                        new Id(request.worldId()),new Id(request.buildId()));
                    if(devCatalog==null)devCatalog=catalog;
                    else if(!devCatalog.equals(catalog))
                        throw new IOException("DEV Director input changed while pending");
                }
                resolveConsumer(dev);
                if(dev) {
                    var admitted=Objects.requireNonNull(consumer.pollImportAndReadAdmitted(catalog,
                        new Id(request.worldId()),new Id(request.buildId())));
                    if(admitted.isEmpty())return Optional.empty();
                    CatalogValidation.requireSealed(admitted.orElseThrow());
                    if(!StrictCodec.encode(admitted.orElseThrow()).equals(catalog))
                        throw new IOException("DEV imported D0 catalogue was not read back exactly");
                    return proof(Phase.DIRECTOR_IMPORT,JSON.toJson(Map.of(
                        "status","IMPORTED_REAL_DEV_RUNTIME_G0_G5_NOT_PUBLISHED",
                        "importer",consumerName,"importerSha256",actualSha,
                        "catalogSha256",PhysicalStore.sha(catalog))));
                }
                Path record=world.resolve(request.directorReceipt()).normalize();
                if(Path.of(request.directorReceipt()).isAbsolute()||!record.startsWith(world))
                    throw new IOException("Foreign Director import receipt path");
                final String[] receiptBytes=new String[1];
                var complete=gate.step(catalog,consumer,()->{
                    if(!Files.exists(record,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
                    if(Files.isSymbolicLink(record)||!Files.isRegularFile(record,LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Director receipt must be a regular owned file");
                    String raw=bounded(record,4096);
                    var value=JSON.fromJson(raw,ImportReceipt.class);
                    if(value==null)throw new IOException("Empty Director import receipt");
                    receiptBytes[0]=raw;
                    return Optional.of(new CatalogImportAdmissionGate.Receipt(value.version(),
                        value.worldId(),value.buildId(),value.catalogueSha256(),
                        value.importerSourceSha256(),value.runtimeIdentity(),value.status()));
                });
                if(complete.isEmpty())return Optional.empty();
                // Keep exact consumer receipt bytes as the existing phase evidence.
                return proof(Phase.DIRECTOR_IMPORT,Objects.requireNonNull(receiptBytes[0]));
            }
        };
    }

    private QualificationOrchestrator.Task finalAudit()throws IOException {
        var walkingStore=new PhysicalStore(directory.resolve("spawn-final"));
        var walking=new NativeSpawnAudit(level,area(),request.spawn(),walkingStore);
        var stages=request.multiSpecies().stages();
        long totalStructureGroups=structureGroupCount();
        return new QualificationOrchestrator.Task() {
            boolean saved,walked,finalFaunaChecked;
            long groupCursor;
            ContraV5SegmentedFaunaColdAudit.Reader finalFaunaReader;
            final NativeChunkWindow window=new NativeChunkWindow(level);
            public Optional<String> step()throws Exception {
                if(!walked) {
                    if(!saved) {
                        if(!BlockMutationRuntime.checkpointReady(level,WorldPrepRuntime.data(level)))
                            return Optional.empty();
                        level.save(null,true,false);saved=true;return Optional.empty();
                    }
                    walked=walking.step();return Optional.empty();
                }
                if(groupCursor<totalStructureGroups){
                    var store=structureGroupStore(groupCursor);
                    if(store.exists("plan")){
                        var plan=store.read("plan",NativeStructurePass.Plan.class);
                        if(!window.structure(plan))return Optional.empty();
                        prepared(store);window.release();
                    }else store.readText("empty-survey");
                    groupCursor++;return Optional.empty();
                }
                if(contraComposite&&!finalFaunaChecked){
                    if(finalFaunaReader==null)finalFaunaReader=
                        new ContraV5SegmentedFaunaColdAudit.Reader(
                            world,directory,request,frozen,contraManifest);
                    var validated=finalFaunaReader.step();
                    if(validated.isEmpty())return Optional.empty();
                    finalFaunaChecked=true;
                    var prior=artifacts.read("evidence-POPULATION_DISK_AUDIT",String.class);
                    if(!JsonParser.parseString(prior).equals(
                        JSON.toJsonTree(validated.orElseThrow())))
                        throw new IOException("V5 final independent cold Anvil fauna differs");
                }
                if(!contraComposite)for(var stage:stages){
                    var settlement=NativeV5SpeciesSettlement.verify(world,
                        directory.resolve(stage.directory()),area(),stage,frozen);
                    NativeV5SpeciesSettlement.requireObservedFauna(settlement);
                }
                for(var phase:Phase.values())
                    if(phase!=Phase.FINAL_AUDIT)
                        artifacts.readText("phase-"+phase.name());
                var snap=bounded(directory.resolve("catalog.json"),1024*1024);
                if(!snap.equals(artifacts.read("evidence-"+Phase.CATALOG.name(),String.class)))
                    throw new IOException("Frozen V5 physical catalogue changed after import");
                return proof(Phase.FINAL_AUDIT,
                    "V5_NINE_PHASES_QUALIFIED_NOT_PUBLISHED:"+request.batch()
                    +":spawn:"+PhysicalStore.sha(walkingStore.readText("verified"))
                    +":catalog:"+PhysicalStore.sha(snap));
            }
            public void close()throws IOException {
                try{window.close();}finally{walking.close();}
            }
        };
    }
    private static String bounded(Path file,int cap)throws IOException {
        for(Path p=file.toAbsolutePath();p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Symlinked V5 input");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
            ||Files.size(file)>cap)throw new IOException("Missing/bounded V5 evidence "+file);
        return Files.readString(file);
    }
    @Override public void tick(){driver.tick();}
    @Override public void pause(){driver.pause();}
    @Override public void resume(){driver.resume();}
    @Override public String status(){return driver.status()+": "+driver.reason();}
    @Override public void close()throws IOException{driver.close();}
}
