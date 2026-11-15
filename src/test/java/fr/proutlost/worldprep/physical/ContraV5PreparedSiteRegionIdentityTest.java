package fr.proutlost.worldprep.physical;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.catalog.ReadyCatalog;
import fr.proutlost.worldprep.director.PhysicalCatalogExporter;
import static fr.proutlost.contract.director.Vocabulary.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** RUN79: offline catalogue compatibility only, never a Contra physical certificate. */
class ContraV5PreparedSiteRegionIdentityTest {
    private static final Id WORLD = new Id("proutlost:fixture_world");
    private static final Id BUILD = new Id("proutlost:fixture_build");
    private static final Id DIMENSION = new Id("minecraft:overworld");
    private static final Revision REVISION = new Revision("run79-fixture-r1");

    private static UUID uuid(String suffix) {
        return UUID.fromString("10271027-2026-4079-8079-" + suffix);
    }

    private SemanticRegion region(String area, UUID operation, int x) {
        var bounds = new Bounds(DIMENSION,
            new BlockPos(x,65,x),new BlockPos(x+2,68,x+2));
        return new SemanticRegion(
            ContraV5PreparedSiteRegionIdentity.forOperation(area,operation),
            REVISION,List.of(bounds),Set.of(),0);
    }

    private PhysicalCatalogExporter.Site site(UUID operation, SemanticRegion region) {
        var evidence = new EvidenceRef(
            new Id("proutlost:fixture/proof_" + operation.toString().replace('-','_')),
            Digests.sha256("run79-fixture:" + operation),new Id("proutlost:synthetic_validator"));
        return new PhysicalCatalogExporter.Site(
            new Id("proutlost:template_instance/" + operation),REVISION,
            SourceType.worldprep_prepared_site,null,new Id("minecraft:igloo/top"),
            region.bounds(),Set.of(region.region_id()),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(evidence),List.of(evidence));
    }

    @Test void sameAreaAndOperationAreStable() {
        var id=ContraV5PreparedSiteRegionIdentity.forOperation(
            "proutlost:contra_parent",uuid("000000000001"));
        assertEquals(id,ContraV5PreparedSiteRegionIdentity.forOperation(
            "proutlost:contra_parent",uuid("000000000001")));
    }

    @Test void distinctOperationsInSameAreaDoNotAlias() {
        assertNotEquals(
            ContraV5PreparedSiteRegionIdentity.forOperation("same_area",uuid("000000000001")),
            ContraV5PreparedSiteRegionIdentity.forOperation("same_area",uuid("000000000002")));
    }

    @Test void sameOperationInDifferentAreasDoesNotAlias() {
        assertNotEquals(
            ContraV5PreparedSiteRegionIdentity.forOperation("area_a",uuid("000000000001")),
            ContraV5PreparedSiteRegionIdentity.forOperation("area_b",uuid("000000000001")));
    }

    @Test void invalidOperationOrAreaFailsClosed() {
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5PreparedSiteRegionIdentity.forOperation("",uuid("000000000001")));
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5PreparedSiteRegionIdentity.forOperation("area_a",null));
    }

    @Test void twoRealD0SitesFromOneAreaAreBothSealedAndAdmitted() {
        var first=region("one_area",uuid("000000000001"),0);
        var second=region("one_area",uuid("000000000002"),8);
        var result=PhysicalCatalogExporter.export(
            WORLD,BUILD,REVISION,Instant.EPOCH,List.of(first,second),
            List.of(site(uuid("000000000001"),first),site(uuid("000000000002"),second)),
            evidence->{assertFalse(evidence.physicalEvidence().isEmpty());});
        assertEquals("PHYSICAL_EXPORT_SEALED_DIRECTOR_NOT_READY",result.status(),result.reason());
        assertNotNull(result.json());
        var consumer=new ReadyCatalog(WORLD,BUILD);
        consumer.importJson(result.json());
        assertTrue(consumer.admitting());
        assertEquals(2,consumer.snapshot().regions().size());
        assertEquals(2,consumer.snapshot().entries().size());
        assertEquals(result.json(),StrictCodec.encode(consumer.snapshot()));
    }

    @Test void historicalAreaOnlyRegionAliasFailsClosedInsteadOfDroppingSite() {
        var first=region("one_area",uuid("000000000001"),0);
        var second=region("one_area",uuid("000000000002"),8);
        var alias=new SemanticRegion(first.region_id(),REVISION,second.bounds(),Set.of(),0);
        var result=PhysicalCatalogExporter.export(
            WORLD,BUILD,REVISION,Instant.EPOCH,List.of(first,alias),
            List.of(site(uuid("000000000001"),first),site(uuid("000000000002"),alias)),
            evidence->{});
        assertEquals("NOT_READY",result.status());
        assertNull(result.json());
        assertTrue(result.reason().contains("Duplicate catalog identity"),result.reason());
    }
}
