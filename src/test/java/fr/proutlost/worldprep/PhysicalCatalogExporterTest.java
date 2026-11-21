package fr.proutlost.worldprep;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.catalog.ReadyCatalog;
import fr.proutlost.worldprep.director.PhysicalCatalogExporter;
import static fr.proutlost.contract.director.Vocabulary.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic evidence exercises the REAL pinned consumer, not a physical qualification claim. */
class PhysicalCatalogExporterTest {
    final Id world=new Id("proutlost:test_world"),build=new Id("proutlost:test_build"),dimension=new Id("minecraft:overworld");
    final Revision revision=new Revision("test-r1");
    final Bounds bounds=new Bounds(dimension,new BlockPos(0,64,0),new BlockPos(4,68,4));
    final SemanticRegion region=new SemanticRegion(new SemanticRegionId(new Id("proutlost:test_region")),revision,List.of(bounds),Set.of(),0);
    final EvidenceRef physical=new EvidenceRef(new Id("proutlost:synthetic/physical"),Digests.sha256("synthetic physical"),new Id("proutlost:test_validator"));
    final EvidenceRef disk=new EvidenceRef(new Id("proutlost:synthetic/disk"),Digests.sha256("synthetic disk"),new Id("proutlost:test_validator"));
    PhysicalCatalogExporter.Site site(List<EvidenceRef> disks) {
        return new PhysicalCatalogExporter.Site(new Id("proutlost:template_instance/test"),revision,SourceType.worldprep_static_structure,
            new Id("proutlost:template_instance/test"),new Id("proutlost:qualification/platform"),List.of(bounds),Set.of(region.region_id()),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(physical),disks);
    }
    PhysicalCatalogExporter.Result export(List<EvidenceRef> disks) {
        return PhysicalCatalogExporter.export(world,build,revision,Instant.parse("2026-10-23T00:00:00Z"),List.of(region),List.of(site(disks)),s->{
            assertEquals(List.of(physical),s.physicalEvidence());assertEquals(List.of(disk),s.diskEvidence());
        });
    }
    @Test void actualD2ImporterAcceptsSealedProducerWire100() {
        var result=export(List.of(disk));assertNotNull(result.json());
        assertEquals("PHYSICAL_EXPORT_SEALED_DIRECTOR_NOT_READY",result.status());
        var admitted=new fr.proutlost.director.catalog.QualificationImportProbe().importAndReadAdmitted(result.json(),world,build);
        assertEquals(result.json(),StrictCodec.encode(admitted));
        var importer=new ReadyCatalog(world,build);importer.importJson(result.json());
        assertTrue(importer.admitting());assertEquals(SchemaVersion.BASE,importer.snapshot().schema_version());
        assertEquals(new Id("proutlost:template_instance/test"),importer.snapshot().entries().getFirst().structure_instance_id());
    }
    @Test void exactVanillaTemplateIsPreparedSiteWithoutRegistryStructureIdentity() {
        var original=site(List.of(disk));
        var prepared=new PhysicalCatalogExporter.Site(original.location(),revision,SourceType.worldprep_prepared_site,null,
            new Id("minecraft:igloo/top"),List.of(bounds),Set.of(region.region_id()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(physical),List.of(disk));
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(prepared),s->{});
        assertNotNull(result.json(),result.reason());var importer=new ReadyCatalog(world,build);importer.importJson(result.json());
        var entry=importer.snapshot().entries().getFirst();assertNull(entry.structure_instance_id());
        assertEquals(SourceType.worldprep_prepared_site,entry.source_type());
        assertEquals(new Id("minecraft:igloo/top"),entry.provenance().source_ref());
    }
    @Test void actualD2ImporterRejectsModifiedDigestAndExecutionSchema() {
        var json=export(List.of(disk)).json();var importer=new ReadyCatalog(world,build);importer.importJson(json);
        assertThrows(IllegalArgumentException.class,()->importer.importJson(json.replace("test-r1","test-r2")));
        assertFalse(importer.admitting());
        assertThrows(IllegalArgumentException.class,()->new ReadyCatalog(world,build).importJson(json.replace("1.0.0","1.1.0")));
    }
    @Test void missingDiskProofNeverProducesReadyJson() {
        var result=export(List.of());assertEquals("NOT_READY",result.status());assertNull(result.json());
    }
    @Test void evidenceAuthorityCanRefuseExport() {
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(site(List.of(disk))),s->{throw new IllegalStateException("No actual disk receipt");});
        assertEquals("NOT_READY",result.status());assertNull(result.json());
    }
    @Test void duplicateDiscoveriesImportAsOnePhysicalInstance() {
        var original=site(List.of(disk));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(original,original),s->calls.incrementAndGet());
        assertNotNull(result.json(),result.reason());var importer=new ReadyCatalog(world,build);importer.importJson(result.json());
        assertEquals(1,importer.snapshot().entries().size());assertEquals(1,calls.get());
    }
    @Test void conflictingEvidenceForSameIdentityIsNotSilentlyDiscarded() {
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(site(List.of(disk)),site(List.of(physical))),s->{});
        assertEquals("NOT_READY",result.status());assertNull(result.json());
        assertEquals("Conflicting physical location identity",result.reason());
    }
    @Test void differentLocationIdsCannotAliasOneStructureInstance() {
        var original=site(List.of(disk));
        var alias=new PhysicalCatalogExporter.Site(new Id("proutlost:alias"),revision,original.source(),original.structureInstance(),original.sourceRef(),
            original.geometry(),original.regions(),original.anchors(),original.surfaces(),original.capabilities(),original.protection(),original.recovery(),original.physicalEvidence(),original.diskEvidence());
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(original,alias),s->{});
        assertEquals("NOT_READY",result.status());assertEquals("Aliased structure instance identity",result.reason());
    }
    @Test void observedGrasslandImportsWithVerifiedAnchorAndNoInventedCapability() {
        var natural=fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,revision,region.region_id(),true,true,false,false,List.of(physical),List.of(disk));
        var result=PhysicalCatalogExporter.export(world,build,revision,Instant.EPOCH,List.of(region),List.of(natural),v->{});
        assertNotNull(result.json(),result.reason());var importer=new ReadyCatalog(world,build);importer.importJson(result.json());
        var entry=importer.snapshot().entries().getFirst();assertEquals(SourceType.worldprep_natural_site,entry.source_type());
        assertNull(entry.structure_instance_id());assertEquals(1,entry.anchors().size());assertTrue(entry.capabilities().isEmpty());
        var again=fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,new Revision("changed"),region.region_id(),true,true,false,false,List.of(physical),List.of(disk));
        assertEquals(natural.location(),again.location());
    }
    @Test void naturalAccessCannotBeClaimedFromHabitatAlone() {
        assertThrows(IllegalArgumentException.class,()->fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,revision,region.region_id(),false,true,false,false,List.of(physical),List.of(disk)));
        assertThrows(IllegalArgumentException.class,()->fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,revision,region.region_id(),true,true,false,false,List.of(physical),List.of()));
    }
    @Test void protectedOrStructureMetadataCannotBecomeNaturalSite() {
        assertThrows(IllegalArgumentException.class,()->fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,revision,region.region_id(),true,true,true,false,List.of(physical),List.of(disk)));
        assertThrows(IllegalArgumentException.class,()->fr.proutlost.worldprep.director.ObservedNaturalSite.create(dimension,8,65,8,2,revision,region.region_id(),true,true,false,true,List.of(physical),List.of(disk)));
    }
    @Test void wrongWorldIsRejectedByActualImporter() {
        assertThrows(IllegalArgumentException.class,()->new ReadyCatalog(new Id("proutlost:other_world"),build).importJson(export(List.of(disk)).json()));
    }
}
