package fr.proutlost.worldprep;
import fr.proutlost.worldprep.preview.PreviewScale;
import fr.proutlost.worldprep.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ToolingSafetyTest {
 @Test void previewsAreBounded(){var s=PreviewScale.fit(100000,100000,1_000_000);assertTrue((long)s.outputWidth()*s.outputHeight()<=1_000_000);assertTrue(s.blocksPerPixel()>1);}
 @Test void fatalFindingsGateProduction(){var r=new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Severity.FATAL,"STALE_PLAN","stale")));assertFalse(r.allowsProductionApply());}
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;
 @Test void sealedBiomePngIncludesLegendMetadataAndDoesNotRewritePlan() throws Exception {
  var id=java.util.UUID.randomUUID();String digest=fr.proutlost.worldprep.plan.PlanFingerprint.of("png-fixture").value();
  var cells=List.of(new fr.proutlost.worldprep.persistence.WorldPrepSavedData.BiomeCell(0,0,"minecraft:plains",1),
      new fr.proutlost.worldprep.persistence.WorldPrepSavedData.BiomeCell(1,1,"minecraft:forest",2));
  var publication=fr.proutlost.worldprep.storage.PagedPlanStore.publish(folder.resolve("plans"),id,fr.proutlost.worldprep.pipeline.PassId.BIOMES,
      "fixture:area","minecraft:overworld","fixture",digest,fr.proutlost.worldprep.runtime.RuntimePlanStore.ENGINE_VERSION,fr.proutlost.worldprep.runtime.RuntimePlanStore.PLANNER_VERSION,1,cells,fr.proutlost.worldprep.runtime.RuntimePlanStore.BIOME_CODEC);
  var request=pngRequest(publication,fr.proutlost.worldprep.pipeline.PassId.BIOMES,2,java.util.Set.of("minecraft:plains","minecraft:forest"));
  var source=fr.proutlost.worldprep.storage.PagedPlanStore.pagePath(publication.directory(),0);byte[] before=java.nio.file.Files.readAllBytes(source);
  var report=fr.proutlost.worldprep.preview.PreviewRenderer.render(request);assertEquals("biomes.png",report.image().getFileName().toString());
  var image=javax.imageio.ImageIO.read(report.image().toFile());assertNotNull(image);
  assertTrue((long)image.getWidth()*image.getHeight()<=fr.proutlost.worldprep.preview.PreviewRenderer.MAX_IMAGE_PIXELS);
  var legend=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.legend())).getAsJsonObject();
  assertTrue(legend.has("minecraft:plains"));assertTrue(legend.has("minecraft:forest"));
  var metadata=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.metadata())).getAsJsonObject();
  assertEquals(publication.manifest().rootFingerprint(),metadata.get("fingerprint").getAsString());assertEquals(2,metadata.get("entries").getAsLong());
  assertTrue(metadata.getAsJsonObject("registry_validation").get("valid").getAsBoolean());
  assertTrue(metadata.has("protected_zones")&&metadata.has("overrides")&&metadata.has("location_registry")&&metadata.has("fallback_usage"));
  assertArrayEquals(before,java.nio.file.Files.readAllBytes(source));assertEquals(publication.manifest(),fr.proutlost.worldprep.storage.PagedPlanStore.readManifest(publication.directory()));
  byte[] png=java.nio.file.Files.readAllBytes(report.image());assertTrue(fr.proutlost.worldprep.preview.PreviewRenderer.render(request).cached());assertArrayEquals(png,java.nio.file.Files.readAllBytes(report.image()));
  java.nio.file.Files.write(report.image(),new byte[]{1},java.nio.file.StandardOpenOption.APPEND);
  assertThrows(java.io.IOException.class,()->fr.proutlost.worldprep.preview.PreviewRenderer.render(request));
  assertArrayEquals(before,java.nio.file.Files.readAllBytes(source));
 }
 @Test void oreHeatmapIsCoarseAndUnsealedPlansAreNeverCertified() throws Exception {
  var id=java.util.UUID.randomUUID();String digest=fr.proutlost.worldprep.plan.PlanFingerprint.of("ore-png").value();
  var changes=List.of(new fr.proutlost.worldprep.persistence.BlockWorldPrepData.Change(1,20,1,"minecraft:stone","minecraft:iron_ore","IRON"),
      new fr.proutlost.worldprep.persistence.BlockWorldPrepData.Change(2,21,2,"minecraft:stone","minecraft:iron_ore","IRON"));
  var publication=fr.proutlost.worldprep.storage.PagedPlanStore.publish(folder.resolve("plans"),id,fr.proutlost.worldprep.pipeline.PassId.ORES,
      "fixture:area","minecraft:overworld","fixture",digest,fr.proutlost.worldprep.runtime.RuntimePlanStore.ENGINE_VERSION,fr.proutlost.worldprep.runtime.RuntimePlanStore.PLANNER_VERSION,1,changes,fr.proutlost.worldprep.runtime.RuntimePlanStore.BLOCK_CODEC);
  var request=pngRequest(publication,fr.proutlost.worldprep.pipeline.PassId.ORES,2,java.util.Set.of("minecraft:iron_ore"));
  assertTrue(fr.proutlost.worldprep.preview.PreviewRenderer.scale(request).blocksPerPixel()>=16);
  var report=fr.proutlost.worldprep.preview.PreviewRenderer.render(request);assertEquals("ore_heatmap_admin.png",report.image().getFileName().toString());
  var metadata=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.metadata())).getAsJsonObject();
  assertEquals(2,metadata.getAsJsonObject("distribution_statistics").get("minecraft:iron_ore").getAsLong());
  var writing=fr.proutlost.worldprep.storage.PagedPlanStore.begin(folder.resolve("plans"),java.util.UUID.randomUUID(),fr.proutlost.worldprep.pipeline.PassId.ORES,
      "fixture:area","minecraft:overworld","fixture",digest,fr.proutlost.worldprep.runtime.RuntimePlanStore.ENGINE_VERSION,fr.proutlost.worldprep.runtime.RuntimePlanStore.PLANNER_VERSION,1,fr.proutlost.worldprep.runtime.RuntimePlanStore.BLOCK_CODEC);
  writing.append(changes.getFirst());var manifest=fr.proutlost.worldprep.storage.PagedPlanStore.readManifest(writing.directory());
  var incomplete=new fr.proutlost.worldprep.storage.PagedPlanStore.Publication(writing.directory(),manifest);
  assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.preview.PreviewRenderer.render(pngRequest(incomplete,fr.proutlost.worldprep.pipeline.PassId.ORES,1,java.util.Set.of("minecraft:iron_ore"))));
 }
 @Test void sealedEnvironmentPngUsesTargetBlocksAndRendersNegativeYWithoutChangingPlans() throws Exception {
  record Sample(fr.proutlost.worldprep.pipeline.PassId pass,String filename,String low,String high){}
  var samples=List.of(
      new Sample(fr.proutlost.worldprep.pipeline.PassId.SOILS,"soils.png","minecraft:dirt","minecraft:moss_block"),
      new Sample(fr.proutlost.worldprep.pipeline.PassId.FLORA,"flora.png","minecraft:short_grass","minecraft:fern"),
      new Sample(fr.proutlost.worldprep.pipeline.PassId.TREES,"trees.png","minecraft:oak_log","minecraft:oak_leaves"),
      new Sample(fr.proutlost.worldprep.pipeline.PassId.AQUATIC,"aquatic.png","minecraft:sand","minecraft:seagrass"));
  for(var sample:samples){
   var id=java.util.UUID.randomUUID();String digest=fr.proutlost.worldprep.plan.PlanFingerprint.of("environment-png",sample.pass().name()).value();
   String province="fixture:private_column/x=1,z=1";
   var changes=List.of(new fr.proutlost.worldprep.persistence.BlockWorldPrepData.Change(1,-60,1,"minecraft:air",sample.low(),province),
       new fr.proutlost.worldprep.persistence.BlockWorldPrepData.Change(2,-55,2,"minecraft:air",sample.high(),province));
   var publication=fr.proutlost.worldprep.storage.PagedPlanStore.publish(folder.resolve("plans"),id,sample.pass(),
       "fixture:area","minecraft:overworld","fixture",digest,fr.proutlost.worldprep.runtime.RuntimePlanStore.ENGINE_VERSION,
       fr.proutlost.worldprep.runtime.RuntimePlanStore.PLANNER_VERSION,1,changes,fr.proutlost.worldprep.runtime.RuntimePlanStore.BLOCK_CODEC);
   var source=fr.proutlost.worldprep.storage.PagedPlanStore.pagePath(publication.directory(),0);
   byte[] before=java.nio.file.Files.readAllBytes(source);
   var request=pngRequest(publication,sample.pass(),2,java.util.Set.of(sample.low(),sample.high()));
   var report=fr.proutlost.worldprep.preview.PreviewRenderer.render(request);
   assertEquals(sample.filename(),report.image().getFileName().toString());
   var legend=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.legend())).getAsJsonObject();
   assertTrue(legend.has(sample.low()));assertTrue(legend.has(sample.high()));assertFalse(legend.toString().contains(province));
   var metadata=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(report.metadata())).getAsJsonObject();
   assertEquals(sample.pass().name(),metadata.get("pass").getAsString());
   assertEquals("NORMAL",metadata.get("tree_mode").getAsString());
   var statistics=metadata.getAsJsonObject("distribution_statistics");
   assertEquals(java.util.Set.of(sample.low(),sample.high()),statistics.keySet());
   assertEquals(1,statistics.get(sample.low()).getAsLong());assertEquals(1,statistics.get(sample.high()).getAsLong());
   var image=javax.imageio.ImageIO.read(report.image().toFile());assertNotNull(image);
   assertEquals(Integer.parseInt(legend.get(sample.high()).getAsString().substring(1),16),image.getRGB(16,156)&0xffffff);
   assertTrue((long)image.getWidth()*image.getHeight()<=fr.proutlost.worldprep.preview.PreviewRenderer.MAX_IMAGE_PIXELS);
   assertArrayEquals(before,java.nio.file.Files.readAllBytes(source));
   assertEquals(publication.manifest(),fr.proutlost.worldprep.storage.PagedPlanStore.readManifest(publication.directory()));
   assertTrue(fr.proutlost.worldprep.preview.PreviewRenderer.render(request).cached());
  }
 }
 private fr.proutlost.worldprep.preview.PreviewRenderer.Request pngRequest(fr.proutlost.worldprep.storage.PagedPlanStore.Publication source,fr.proutlost.worldprep.pipeline.PassId pass,long entries,java.util.Set<String> registry){
  var manifest=source.manifest();return new fr.proutlost.worldprep.preview.PreviewRenderer.Request(source.directory(),folder.resolve("previews"),manifest.planId(),pass,
      manifest.areaId(),manifest.dimension(),manifest.profile(),123,0,0,31,31,-64,320,manifest.rootFingerprint(),manifest.semanticInputDigest(),entries,
      java.util.Map.of("area_mask","FULL_AREA","tree_mode","NORMAL"),registry,List.of(),List.of(),"{}");
 }
}
