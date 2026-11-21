package fr.proutlost.worldprep;

import fr.proutlost.contract.director.*;
import fr.proutlost.worldprep.director.FrozenDirectorCrosswalk;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FrozenDirectorCrosswalkTest {
    private static final Id WORLD=new Id("proutlost:s2_world");
    private static final Id REGION_A=new Id("proutlost:semantic_region/a");
    private static final Id REGION_B=new Id("proutlost:semantic_region/b");
    private static final Id LOCATION_A=new Id("proutlost:template_instance/a");
    private static final Id LOCATION_B=new Id("proutlost:template_instance/b");
    private static final UUID WORLD_UUID=UUID.fromString("85f5b7f3-8419-5544-aacd-d2f930db57a8");
    private static final UUID REGION_A_UUID=UUID.fromString("021213b6-1cc5-524f-92ba-a3e4d377858b");
    private static final UUID REGION_B_UUID=UUID.fromString("cd280b5b-31a6-5db9-822c-c716e9501681");
    private static final UUID LOCATION_A_UUID=UUID.fromString("3d4408a8-cc0d-55aa-97b9-d2d5b3d5a9a1");
    private static final UUID LOCATION_B_UUID=UUID.fromString("508bc669-f120-5964-bd96-bf1e5b06145f");
    private static final Digest SNAPSHOT_SHA=new Digest("1".repeat(64));

    private static Map<Id,Set<Id>> membership(){
        return Map.of(LOCATION_A,Set.of(REGION_A),LOCATION_B,Set.of(REGION_B));
    }
    private static Map<Id,UUID> mappedRegions(){
        return Map.of(REGION_A,REGION_A_UUID,REGION_B,REGION_B_UUID);
    }
    private static Map<Id,UUID> mappedLocations(){
        return Map.of(LOCATION_A,LOCATION_A_UUID,LOCATION_B,LOCATION_B_UUID);
    }
    private FrozenDirectorCrosswalk.Manifest build(Map<Id,Set<Id>> membership,
            Map<Id,UUID> regionMap,Map<Id,UUID> locationMap)throws IOException {
        return FrozenDirectorCrosswalk.preflight(WORLD,WORLD_UUID,SNAPSHOT_SHA,
            Set.of(REGION_A,REGION_B),Set.of(LOCATION_A,LOCATION_B),
            membership,regionMap,locationMap);
    }
    private static CatalogSnapshot sealedD0Fixture() {
        var exporter=new PhysicalCatalogExporterTest();
        return StrictCodec.decode(exporter.export(List.of(exporter.disk)).json(),CatalogSnapshot.class);
    }
    private static FrozenDirectorCrosswalk.Manifest crosswalk(CatalogSnapshot d0)throws IOException {
        return FrozenDirectorCrosswalk.requireExactD0(d0,WORLD_UUID,
            Map.of(d0.regions().getFirst().region_id().value(),REGION_A_UUID),
            Map.of(d0.entries().getFirst().location_id(),LOCATION_A_UUID));
    }
    @Test void authenticD0CatalogCrosswalksOnlyAfterDigestVerification()throws Exception {
        var fixture=sealedD0Fixture();
        var crosswalk=crosswalk(fixture);
        assertEquals(fixture.digest(),crosswalk.catalogDigest());
        assertEquals(1,crosswalk.regions().size());
        assertEquals(1,crosswalk.locations().size());
    }
    @Test void falseReadyStatusCannotBypassTamperedCatalogDigest() {
        var fixture=sealedD0Fixture();
        var wrong=new CatalogSnapshot(fixture.world_id(),fixture.schema_version(),fixture.build_id(),
            fixture.catalog_revision(),new Digest("f".repeat(64)),fixture.state(),
            fixture.validated_at(),fixture.validation_evidence(),fixture.regions(),fixture.entries());
        assertThrows(IOException.class,()->crosswalk(wrong));
    }
    @Test void validTopLevelDigestDoesNotMaskTamperedEntryDigest() {
        var fixture=sealedD0Fixture();
        var e=fixture.entries().getFirst();
        var corrupt=new StaticLocationCatalogEntry(e.world_id(),e.schema_version(),e.build_id(),
            e.location_id(),e.revision(),e.source_type(),e.structure_instance_id(),
            e.semantic_region_ids(),e.geometry(),e.tags(),e.anchors(),e.surfaces(),
            e.capabilities(),e.protection(),e.presentation_channels(),e.recovery_anchors(),
            e.provenance(),e.validation_evidence(),new Digest("f".repeat(64)));
        var raw=new CatalogSnapshot(fixture.world_id(),fixture.schema_version(),fixture.build_id(),
            fixture.catalog_revision(),new Digest("0".repeat(64)),fixture.state(),
            fixture.validated_at(),fixture.validation_evidence(),fixture.regions(),List.of(corrupt));
        var forged=new CatalogSnapshot(raw.world_id(),raw.schema_version(),raw.build_id(),
            raw.catalog_revision(),StrictCodec.contentDigest(raw),raw.state(),raw.validated_at(),
            raw.validation_evidence(),raw.regions(),raw.entries());
        assertThrows(IOException.class,()->crosswalk(forged));
    }
    @Test void nonzeroD0RegionPriorityCannotBeSilentlyLostOnG0Import() {
        var fixture=sealedD0Fixture();
        var region=fixture.regions().getFirst();
        var prioritized=new SemanticRegion(region.region_id(),region.revision(),region.bounds(),
            region.tags(),3);
        var raw=new CatalogSnapshot(fixture.world_id(),fixture.schema_version(),fixture.build_id(),
            fixture.catalog_revision(),new Digest("0".repeat(64)),fixture.state(),
            fixture.validated_at(),fixture.validation_evidence(),List.of(prioritized),fixture.entries());
        var valid=new CatalogSnapshot(raw.world_id(),raw.schema_version(),raw.build_id(),
            raw.catalog_revision(),StrictCodec.contentDigest(raw),raw.state(),raw.validated_at(),
            raw.validation_evidence(),raw.regions(),raw.entries());
        assertThrows(IOException.class,()->crosswalk(valid));
    }
    @Test void explicitStableD0ResourceIdsConvertOnlyViaPinnedG0Uuids()throws Exception {
        var manifest=build(membership(),mappedRegions(),mappedLocations());
        assertEquals(1,manifest.version());
        assertEquals(WORLD,manifest.worldPrepWorldId());
        assertEquals(WORLD_UUID,manifest.directorWorldId());
        assertEquals(SNAPSHOT_SHA,manifest.catalogDigest());
        assertEquals(2,manifest.regions().size());
        assertEquals(2,manifest.locations().size());
        assertEquals(REGION_A,manifest.regions().getFirst().worldPrepId());
        assertEquals(REGION_A_UUID,manifest.regions().getFirst().directorId());
        assertEquals("IDENTITY_CROSSWALK_VALIDATED_IMPORT_NOT_ADMITTED",manifest.status());
        assertThrows(UnsupportedOperationException.class,()->manifest.locations().clear());
    }
    @Test void missingRegionMappingFailsClosed(){
        assertThrows(IOException.class,()->build(membership(),
            Map.of(REGION_A,REGION_A_UUID),mappedLocations()));
    }
    @Test void extraDirectorRegionNotInFrozenD0CatalogFailsClosed(){
        var more=new HashMap<>(mappedRegions());
        more.put(new Id("proutlost:made_up_region"),UUID.randomUUID());
        assertThrows(IOException.class,()->build(membership(),more,mappedLocations()));
    }
    @Test void uuidReuseAcrossDifferentEntityKindsIsForbidden(){
        var wrong=new HashMap<>(mappedLocations());
        wrong.put(LOCATION_A,REGION_A_UUID);
        assertThrows(IOException.class,()->build(membership(),mappedRegions(),wrong));
    }
    @Test void unknownOrEmptyRegionMembershipFailsClosed(){
        var m=new HashMap<>(membership());
        m.put(LOCATION_A,Set.of(new Id("proutlost:unregistered")));
        assertThrows(IOException.class,()->build(m,mappedRegions(),mappedLocations()));
        m.put(LOCATION_A,Set.of());
        assertThrows(IOException.class,()->build(m,mappedRegions(),mappedLocations()));
    }
    @Test void missingLocationOrChangedKeysFailsClosed() {
        assertThrows(IOException.class,()->build(membership(),mappedRegions(),
            Map.of(LOCATION_A,LOCATION_A_UUID)));
        var wrong=new HashMap<>(membership());
        wrong.remove(LOCATION_B);
        assertThrows(IOException.class,()->build(wrong,mappedRegions(),mappedLocations()));
    }
    @Test void D0InclusiveMinecraftSingleBlockProjectsToG0HalfOpenSingleCell()
          throws Exception {
        var input=new fr.proutlost.contract.director.Bounds(
            new Id("minecraft:overworld"),
            new fr.proutlost.contract.director.BlockPos(4871,75,-1370),
            new fr.proutlost.contract.director.BlockPos(4871,75,-1370));
        var g0=FrozenDirectorCrosswalk.halfOpen(input);
        assertEquals(4871,g0.minX());
        assertEquals(4872,g0.maxXExclusive());
        assertEquals(75,g0.minY());
        assertEquals(76,g0.maxYExclusive());
        assertEquals(-1370,g0.minZ());
        assertEquals(-1369,g0.maxZExclusive());
        assertEquals("minecraft:overworld",g0.dimension());
    }
    @Test void D0InclusiveBoxProjectsToExactHalfOpenG0Volume()throws Exception {
        var input=new fr.proutlost.contract.director.Bounds(
            new Id("minecraft:overworld"),
            new fr.proutlost.contract.director.BlockPos(4500,40,-1660),
            new fr.proutlost.contract.director.BlockPos(4502,42,-1658));
        var g0=FrozenDirectorCrosswalk.halfOpen(input);
        assertEquals(3,(long)g0.maxXExclusive()-g0.minX());
        assertEquals(3,(long)g0.maxYExclusive()-g0.minY());
        assertEquals(3,(long)g0.maxZExclusive()-g0.minZ());
    }
    @Test void G0HalfOpenIntegerOverflowFailsInsteadOfTruncatingOrWrapping(){
        var input=new fr.proutlost.contract.director.Bounds(
            new Id("minecraft:overworld"),
            new fr.proutlost.contract.director.BlockPos(1,1,1),
            new fr.proutlost.contract.director.BlockPos(Integer.MAX_VALUE,1,1));
        assertThrows(IOException.class,()->FrozenDirectorCrosswalk.halfOpen(input));
    }
    @Test void worldOrDigestMustBeExplicitlyProvided() {
        assertThrows(IOException.class,()->FrozenDirectorCrosswalk.preflight(
            null,WORLD_UUID,SNAPSHOT_SHA,Set.of(REGION_A),Set.of(LOCATION_A),
            Map.of(LOCATION_A,Set.of(REGION_A)),
            Map.of(REGION_A,REGION_A_UUID),Map.of(LOCATION_A,LOCATION_A_UUID)));
        assertThrows(IOException.class,()->FrozenDirectorCrosswalk.preflight(
            WORLD,WORLD_UUID,null,Set.of(REGION_A),Set.of(LOCATION_A),
            Map.of(LOCATION_A,Set.of(REGION_A)),
            Map.of(REGION_A,REGION_A_UUID),Map.of(LOCATION_A,LOCATION_A_UUID)));
    }
}
