package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.memory.MemoryProjection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authentic retained Contra G0 metadata in isolated filesystem tests.
 * These tests do not attest D0, physical geometry in Minecraft, or human acceptance. */
final class NativeAuthoredLocationVocabularyTest {
    @TempDir Path directory;
    private static final ModelCodec<LocationImport> CODEC=new ModelCodec<>(LocationImport.class);
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static LocationImport original(int revision) throws Exception {
        try(var in=NativeAuthoredLocationVocabularyTest.class.getResourceAsStream(
                "/authentic-contra-g0/revision"+revision+".json")){
            assertNotNull(in);
            return CODEC.decode(in.readAllBytes());
        }
    }
    private StandaloneLocationImporter open(WorldId world) throws Exception {
        return StandaloneLocationImporter.open(directory,world,
            Map.of(DIM,new ImportBounds(-29999984,-64,-29999984,29999984,320,29999984)),
            DirectorNativeLocationImports::acceptsAuthoredTag,Runnable::run,
            StandaloneLocationImporter.NATIVE_MAX_BYTES,StandaloneLocationImporter.NATIVE_MAX_ENTRIES)
            .toCompletableFuture().get(5,TimeUnit.SECONDS);
    }
    private static void close(StandaloneLocationImporter registry) throws Exception {
        registry.closeAsync().toCompletableFuture().get(5,TimeUnit.SECONDS);
    }
    private static LocationImport seal(LocationImport value) {
        return new LocationImport(value.schemaVersion(),value.worldId(),value.sourceNamespace(),
            value.exportRevision(),StandaloneLocationImporter.digest(value),
            value.regions(),value.locations(),value.anchors());
    }
    private static DirectorNativeLocationImports.Context empty(WorldId world) {
        return new DirectorNativeLocationImports.Context(new Sequence(0),new Tick(1),
            MemoryProjection.of(new ScopeSet(world,Set.of(new ScopeRef(ScopeKind.WORLD,world.value()))),List.of()),
            Map.of(),Set.of());
    }
    @Test void completeAuthenticProducerMetadataIsAcceptedAndSurvivesColdReopen() throws Exception {
        var input=original(3);
        assertEquals(10,input.regions().size());assertEquals(11,input.locations().size());
        assertEquals(33,input.anchors().size());
        assertEquals(new Fingerprint("21f1950f10556ca610f670e2c15e77950c8408922b02e9413427028fd10d155d"),
            input.exportDigest());
        assertEquals(input.exportDigest(),StandaloneLocationImporter.digest(input));
        StandaloneLocationImporter.requireNativeEnvelope(input);
        var registry=open(input.worldId());
        try {
            assertEquals(AckStatus.DURABLE,registry.submit(input).toCompletableFuture().get().status(),
                "Every authenticated producer tag must be accepted without filtering or page replacement");
            assertEquals(input,registry.snapshot().current().get(input.sourceNamespace()));
        } finally { close(registry); }
        var cold=open(input.worldId());
        try { assertEquals(input,cold.snapshot().current().get(input.sourceNamespace())); }
        finally { close(cold); }
    }
    @Test void allThreeRealRevisionsRemainArchivedAndTheNewestRemainsComplete() throws Exception {
        var latest=original(3);var registry=open(latest.worldId());
        try {
            for(int revision=1;revision<=3;revision++){
                var input=original(revision);
                assertEquals(AckStatus.DURABLE,registry.submit(input).toCompletableFuture().get().status());
                assertArrayEquals(CODEC.encode(input),Files.readAllBytes(
                    directory.resolve(BlobContent.hash(CODEC.encode(input)).value()+".json")));
            }
            byte[] before=Files.readAllBytes(directory.resolve("CURRENT.json"));
            assertEquals(AckStatus.DURABLE,registry.submit(latest).toCompletableFuture().get().status());
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("CURRENT.json")),
                "An exact retry must not create a new revision");
            assertEquals(AckStatus.REJECTED,registry.submit(original(2)).toCompletableFuture().get().status());
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("CURRENT.json")),
                "An older revision must not overwrite the source");
        } finally { close(registry); }
        var cold=open(latest.worldId());
        try {
            assertEquals(latest,cold.snapshot().current().get(latest.sourceNamespace()));
            var manifest=(JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(directory.resolve("CURRENT.json")));
            assertEquals(3,((JsonValue.ObjectValue)manifest.values().get("imports")).values().size());
        } finally { close(cold); }
    }
    @Test void unknownTagsAreRefusedWithoutReplacingTheAcceptedSource() throws Exception {
        var input=original(3);var registry=open(input.worldId());
        try {
            assertEquals(AckStatus.DURABLE,registry.submit(input).toCompletableFuture().get().status());
            byte[] before=Files.readAllBytes(directory.resolve("CURRENT.json"));
            var first=input.locations().getFirst();var tags=new HashSet<>(first.tags());
            tags.add(new TagId("proutlost:unreviewed_production_capability"));
            var locations=new ArrayList<>(input.locations());
            locations.set(0,new ImportedLocation(first.locationId(),first.externalId(),first.regionIds(),
                first.dimension(),first.bounds(),tags));
            var changed=seal(new LocationImport(1,input.worldId(),input.sourceNamespace(),new Revision(4),
                input.exportDigest(),input.regions(),locations,input.anchors()));
            assertEquals(AckStatus.REJECTED,registry.submit(changed).toCompletableFuture().get().status());
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("CURRENT.json")));
            assertEquals(input,registry.snapshot().current().get(input.sourceNamespace()));
        } finally { close(registry); }
    }
    @Test void newRoleTagsCannotBePromotedToRegionOrLocationFacts() throws Exception {
        var input=original(3);
        var first=input.regions().getFirst();var tags=new HashSet<>(first.tags());
        tags.add(new TagId("proutlost:anchor_observation"));
        var regions=new ArrayList<>(input.regions());
        regions.set(0,new ImportedRegion(first.regionId(),first.dimension(),first.bounds(),tags));
        var changed=seal(new LocationImport(1,input.worldId(),input.sourceNamespace(),input.exportRevision(),
            input.exportDigest(),regions,input.locations(),input.anchors()));
        assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            input.worldId(),changed,null,empty(input.worldId()),Map.of(),Set.of()),
            "An anchor role must not acquire region semantics");
        var location=input.locations().getFirst();var locations=new ArrayList<>(input.locations());
        var locationTags=new HashSet<>(location.tags());locationTags.add(new TagId("proutlost:anchor_observation"));
        locations.set(0,new ImportedLocation(location.locationId(),location.externalId(),location.regionIds(),
            location.dimension(),location.bounds(),locationTags));
        var changedLocation=seal(new LocationImport(1,input.worldId(),input.sourceNamespace(),input.exportRevision(),
            input.exportDigest(),input.regions(),locations,input.anchors()));
        assertThrows(IllegalArgumentException.class,()->DirectorNativeLocationImports.preflight(
            input.worldId(),changedLocation,null,empty(input.worldId()),Map.of(),Set.of()));
    }
    @Test void fullAuthenticMetadataHasAnExecutableNativeAdmissionPlan() throws Exception {
        var input=original(3);
        var plan=DirectorNativeLocationImports.preflight(input.worldId(),input,null,
            empty(input.worldId()),Map.of(),Set.of());
        assertEquals(64,plan.states().size(),
            "Ten regions and their memories, eleven locations, thirty-three anchors");
        assertEquals(64,plan.states().keySet().size());
    }
}
