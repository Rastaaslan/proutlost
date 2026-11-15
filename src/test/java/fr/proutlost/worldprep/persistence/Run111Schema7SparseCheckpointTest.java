package fr.proutlost.worldprep.persistence;

import fr.proutlost.worldprep.area.WorldPrepArea;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.location.LocationDefinition;
import fr.proutlost.worldprep.location.LocationZone;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exact richer metadata preservation; disk cases require the real durable filesystem. */
class Run111Schema7SparseCheckpointTest {
    @TempDir Path temporary;
    private static final String STORE=WorldPrepSavedData.FILE_ID;
    private static final String DIMENSION="minecraft:overworld";
    private static final String SITE="proutlost:qa_site";
    private static final UUID ANALYSIS=new UUID(29,41);

    private static WorldPrepSavedData richModel() {
        var data=WorldPrepSavedData.load(Run111TrackedMetadataContractTest.baseline("PAUSED"),null);
        data.areas().put("tile-a",data.areas().get("tile-a")
            .withMask(WorldPrepSavedData.AreaMask.LAND)
            .withTreeMode(VegetationPlanner.TreeMode.SPARSE));
        data.locations().create(new LocationDefinition(SITE,LocationDefinition.Type.PROTECTED,
            new WorldPrepArea(SITE,DIMENSION,0,0,15,15),Map.of("observation",
                new LocationDefinition.Anchor(DIMENSION,2,64,2))));
        data.locations().zone(SITE,"manual",new WorldPrepArea(SITE+"/zone/manual",DIMENSION,0,0,3,3),
            LocationZone.Kind.MANUAL_ENVIRONMENT);
        var input=new WorldPrepSavedData.PlanInput(DIMENSION,0,0,15,15,1,"qa",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64));
        var analysis=new WorldPrepSavedData.Analysis(input,1,-64,320,1,0,"a".repeat(64));
        analysis.samples=1;analysis.surfaceSum=64;analysis.minSurface=64;analysis.maxSurface=64;
        analysis.altitudeBands.put("low",1L);analysis.slopeBands.put("flat",1L);
        analysis.biomes.put("minecraft:forest",1L);analysis.complete=true;
        data.analyses().put(ANALYSIS,analysis);
        return data;
    }
    private static void change(WorldPrepSavedData data) {
        data.areas().put("tile-a",data.areas().get("tile-a")
            .withMask(WorldPrepSavedData.AreaMask.COAST)
            .withTreeMode(VegetationPlanner.TreeMode.DENSE));
        data.locations().protect(SITE,false);
        data.analyses().get(ANALYSIS).terrainDigest="b".repeat(64);
        var id=Run111TrackedMetadataContractTest.FIRST;
        data.jobs().put(id,data.jobs().get(id).withCursor(42));
    }
    private static CompoundTag envelope(CompoundTag data) {
        var root=new CompoundTag();root.putInt("DataVersion",3955);root.put("data",data);return root;
    }
    private static void sameInline(CompoundTag expected,CompoundTag actual) {
        for(var key:new String[]{"schema","locationRegistrySchema","locations","analyses",
                "selections","protections","overrides"})
            assertEquals(expected.get(key),actual.get(key),"Exact inline metadata: "+key);
    }
    @Test void richMetadataAndAreaModesAreIncludedEvenWithoutDirtyJobKeys() {
        var full=richModel().save(new CompoundTag(),null);
        var data=WorldPrepSavedData.load(full,null);change(data);
        var expectedModel=WorldPrepSavedData.load(full,null);change(expectedModel);
        var expected=expectedModel.save(new CompoundTag(),null);
        var captured=data.saveRun111TrackedDelta(new CompoundTag());
        assertEquals(7,captured.getInt("schema"));sameInline(expected,captured);
        var area=Run111TrackedMetadataContractTest.patches(captured,"areas").getCompound(0);
        assertEquals("COAST",area.getString("mask"));assertEquals("DENSE",area.getString("treeMode"));
        data.locations().anchor(SITE,"second",new LocationDefinition.Anchor(DIMENSION,3,65,3));
        data.analyses().get(ANALYSIS).terrainDigest="c".repeat(64);
        var metadataOnly=data.saveRun111TrackedDelta(new CompoundTag());
        assertEquals(0,Run111TrackedMetadataContractTest.patches(metadataOnly,"jobs").size());
        assertEquals(0,Run111TrackedMetadataContractTest.patches(metadataOnly,"areas").size());
        var restored=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(restored.save(new CompoundTag(),null).get("locations"),metadataOnly.get("locations"));
        assertEquals(restored.save(new CompoundTag(),null).get("analyses"),metadataOnly.get("analyses"));
    }
    @Test void richerSparsePagesKeepOldRootUntilAtomicPublishAndColdReopenExactly() throws Exception {
        var canonical=envelope(richModel().save(new CompoundTag(),null));
        Path target=temporary.resolve("data/"+STORE+".dat");Files.createDirectories(target.getParent());
        var initial=ImmutableBucketedNbtCheckpoint.externalize(canonical,target,STORE);
        NbtIo.writeCompressed(initial.compactRoot(),target);
        try(var scope=new RuntimeScope(temporary)) {
        var data=PagedSavedDataRuntime.withStore(temporary,STORE,()->
            WorldPrepSavedData.load(initial.compactRoot().getCompound("data"),null));change(data);
        var expectedModel=WorldPrepSavedData.load(canonical.getCompound("data"),null);change(expectedModel);
        var expected=envelope(expectedModel.save(new CompoundTag(),null));
        var delta=data.save(new CompoundTag(),null);
        var extension=new CompoundTag();extension.putString("value","preserved future inline metadata");
        delta.put("future_inline",extension);expected.getCompound("data").put("future_inline",extension.copy());
        var candidate=PagedSavedDataRuntime.prepareWrite(envelope(delta),target);
        assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(
            NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE));
        assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(candidate,target,STORE));
        var staged=Files.createTempFile(target.getParent(),"schema7-cold-",".partial");
        NbtIo.writeCompressed(candidate,staged);
        try(var file=FileChannel.open(staged,StandardOpenOption.WRITE)){file.force(true);}
        Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var directory=FileChannel.open(target.getParent(),StandardOpenOption.READ)){directory.force(true);}
        var expanded=ImmutableBucketedNbtCheckpoint.expand(
            NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE);
        assertEquals(expected,expanded);
        var cold=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
        var reopened=PagedSavedDataRuntime.withStore(temporary,STORE,()->
            WorldPrepSavedData.load(cold.getCompound("data"),null));
        assertEquals(WorldPrepSavedData.AreaMask.COAST,reopened.areas().get("tile-a").mask());
        assertEquals(VegetationPlanner.TreeMode.DENSE,reopened.areas().get("tile-a").treeMode());
        assertEquals("b".repeat(64),reopened.analyses().get(ANALYSIS).terrainDigest);
        assertFalse(reopened.locations().require(SITE).blocksWorldPrep(reopened.locations().transformationAllowed(SITE)));
        assertTrue(reopened.protectedBlock(DIMENSION,1,1),"Manual authored subzone still protects cells");
        assertEquals(42,reopened.jobs().get(Run111TrackedMetadataContractTest.FIRST).cursor());
        }
    }
    @Test void eachKnownLegacyParentSchemaUpgradesWithoutDroppingItsRecordOrMetadata() throws Exception {
        for(int schema:new int[]{4,5,6}){
            var canonical=Run90ColdDeltaCheckpointTest.full(1);
            canonical.getCompound("data").putInt("schema",schema);
            Path target=temporary.resolve("schema-"+schema+"/data/"+STORE+".dat");
            Files.createDirectories(target.getParent());
            var initial=ImmutableBucketedNbtCheckpoint.externalize(canonical,target,STORE);
            NbtIo.writeCompressed(initial.compactRoot(),target);
            var data=WorldPrepSavedData.load(canonical.getCompound("data"),null);
            String id="proutlost:batch_0";
            data.areas().put(id,data.areas().get(id).withMask(WorldPrepSavedData.AreaMask.COAST)
                .withTreeMode(VegetationPlanner.TreeMode.DENSE));
            var delta=data.saveRun111TrackedDelta(new CompoundTag());
            assertEquals(7,delta.getInt("schema"));
            var expected=envelope(data.save(new CompoundTag(),null));
            var candidate=ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(envelope(delta),target,STORE);
            assertEquals(expected,ImmutableBucketedNbtCheckpoint.expand(candidate.compactRoot(),target,STORE),
                "Lossless upgrade from known schema"+schema);
            assertEquals(canonical,ImmutableBucketedNbtCheckpoint.expand(
                NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()),target,STORE));
        }
    }
    @Test void malformedMarkedRegistryAndSchemaDowngradeCannotPublishOrAppendPages() throws Exception {
        var canonical=envelope(richModel().save(new CompoundTag(),null));
        Path target=temporary.resolve("data/"+STORE+".dat");Files.createDirectories(target.getParent());
        var initial=ImmutableBucketedNbtCheckpoint.externalize(canonical,target,STORE);
        NbtIo.writeCompressed(initial.compactRoot(),target);
        var captured=WorldPrepSavedData.load(canonical.getCompound("data"),null)
            .saveRun111TrackedDelta(new CompoundTag());
        String before=ImmutableBucketedNbtCheckpoint.fileSha256(target);long files=pageFiles(target);
        var missing=captured.copy();missing.remove("locations");
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(envelope(missing),target,STORE));
        var legacy=captured.copy();legacy.putInt("schema",4);legacy.remove("locationRegistrySchema");
        legacy.remove("locations");legacy.remove("analyses");
        assertThrows(IOException.class,()->ImmutableBucketedNbtCheckpoint.externalizeRun90Delta(envelope(legacy),target,STORE));
        assertEquals(before,ImmutableBucketedNbtCheckpoint.fileSha256(target));assertEquals(files,pageFiles(target));
    }
    private static final class RuntimeScope implements AutoCloseable {
        private final java.util.Map<String,String> old=new java.util.LinkedHashMap<>();
        RuntimeScope(Path world)throws IOException {
            Files.writeString(world.resolve("level.dat"),"SCHEMA7_COLD_FIXTURE_NOT_A_GAME_WORLD");
            Files.writeString(world.resolve("worldprep-run21-paged-authorization.json"),
                new com.google.gson.Gson().toJson(Map.of("schemaVersion",1,"mode",PagedSavedDataRuntime.DEV_MODE,
                    "world",world.toRealPath().toString(),"protectedOriginalsUnchanged",true)));
            set(PagedSavedDataRuntime.MODE_KEY,PagedSavedDataRuntime.DEV_MODE);
            set(PagedSavedDataRuntime.WORLD_KEY,world.toRealPath().toString());
            set("proutlost.run90.strictIncrementalMetadata","true");set(TrackedRecordMap.FLAG,"true");
        }
        private void set(String key,String value){old.put(key,System.getProperty(key));System.setProperty(key,value);}
        @Override public void close(){old.forEach((key,value)->{
            if(value==null)System.clearProperty(key);else System.setProperty(key,value);
        });}
    }
    private static long pageFiles(Path target)throws IOException {
        try(var files=Files.walk(target.getParent().resolve("worldprep-paged-v1"))){
            return files.filter(Files::isRegularFile).count();
        }
    }
}
