package fr.proutlost.worldprep.physical;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
class DirectorStructureExportBridgeTest {
 @Test void overflowCannotSilentlyDropTheLastBatchesOrPlacementSites() {
  for(String scope:List.of("batch","placement")) {
   var paths=IntStream.rangeClosed(0,4096).mapToObj(i->Path.of("entry-"+i));
   var error=assertThrows(java.io.IOException.class,
    ()->DirectorStructureExportBridge.boundedEntries(paths,scope));
   assertTrue(error.getMessage().contains(scope));
   assertTrue(error.getMessage().contains("no partial export"));
  }
 }
 @Test void exactlyFullScanPreservesEveryIdentityAndOrdersDeterministically() throws Exception {
  var paths=IntStream.range(0,4096).mapToObj(i->Path.of("entry-"+i)).toList();
  var result=DirectorStructureExportBridge.boundedEntries(paths.reversed().stream(),"batch");
  assertEquals(paths.size(),result.size());
  assertEquals(new HashSet<>(paths),new HashSet<>(result));
  assertEquals(paths.stream().sorted().toList(),result);
 }
 @Test void emptyScanIsEmptyWithoutFabricatingEntries() throws Exception {
  assertTrue(DirectorStructureExportBridge.boundedEntries(Stream.empty(),"batch").isEmpty());
 }
 private static final UUID WORLD=UUID.fromString("ac56d927-8532-45e6-bdc7-5c0eb2aeac21");
 private static NativeVerifiedStructureCatalog.NativeLocationRecord record(String salt,int x) {
  UUID id=UUID.nameUUIDFromBytes(("site/"+salt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  UUID op=UUID.nameUUIDFromBytes(("op/"+salt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  UUID anchor=UUID.nameUUIDFromBytes(("anchor/"+salt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return new NativeVerifiedStructureCatalog.NativeLocationRecord(1,id,op,"minecraft:overworld",
   "betterdungeons:skeleton_dungeon","a".repeat(64),"b".repeat(64),x,-40,0,x+32,-20,32,
   Set.of("proutlost:structure","proutlost:dungeon"),
   List.of(new NativeVerifiedStructureCatalog.Anchor(anchor,x+16,-40,16,Set.of("proutlost:anchor_center"))),
   "PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
 }
 @Test void firstExportIsCompleteG0NotAnImportAck() throws Exception {
  var value=DirectorStructureExportBridge.next(WORLD,List.of(record("a",0)),null);
  assertEquals("1",value.get("export_revision").getAsString());
  assertEquals(WORLD.toString(),value.get("world_id").getAsString());
  assertEquals(1,value.getAsJsonArray("locations").size());
  assertEquals(1,value.getAsJsonArray("anchors").size());
  assertFalse(value.has("ack"));assertFalse(value.has("status"));
 }
 @Test void installedCodecCannotApproveAnEnvelopeTheNativeConsumerMustReject() throws Exception {
  var records=IntStream.range(0,33).mapToObj(i->record("overflow-"+i,i*128)).toList();
  var value=DirectorStructureExportBridge.next(WORLD,records,null);
  assertEquals(99,value.getAsJsonArray("regions").size()
   +value.getAsJsonArray("locations").size()+value.getAsJsonArray("anchors").size());
  assertThrows(Exception.class,()->DirectorStructureExportBridge.validateWithInstalledDirector(value),
   "A codec-valid export above the unchanged native envelope must be refused before replacing the prior snapshot");
 }
 @Test void installedNativeEnvelopeAcceptsAllEntriesAtTheBoundary() throws Exception {
  var records=IntStream.range(0,32).mapToObj(i->record("boundary-"+i,i*128)).toList();
  var value=DirectorStructureExportBridge.next(WORLD,records,null);
  assertEquals(96,value.getAsJsonArray("regions").size()
   +value.getAsJsonArray("locations").size()+value.getAsJsonArray("anchors").size());
  assertTrue(DirectorG0LocationExport.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);
  DirectorStructureExportBridge.validateWithInstalledDirector(value);
 }
 @Test void installedNativeByteCeilingAlsoAppliesBelowTheEntryCeiling() throws Exception {
  var records=IntStream.range(0,20).mapToObj(i->record("large-bytes-"+i,i*128)).toList();
  var value=DirectorStructureExportBridge.next(WORLD,records,null);
  for(var location:value.getAsJsonArray("locations"))
   location.getAsJsonObject().addProperty("external_id","retained-record/"+"x".repeat(2000)
    +location.getAsJsonObject().get("location_id").getAsString());
  value.remove("export_digest");
  value.addProperty("export_digest",DirectorG0LocationExport.sha256(DirectorG0LocationExport.canonical(value)));
  assertTrue(DirectorG0LocationExport.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768);
  var error=assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.validateWithInstalledDirector(value));
  assertTrue(error.getCause().getMessage().contains("native import canonical byte bound"));
  assertEquals(20,value.getAsJsonArray("locations").size());
 }
 @Test void refusedEnvelopeLeavesThePriorSnapshotAndEveryInputRecordUnchanged() throws Exception {
  var records=IntStream.range(0,33).mapToObj(i->record("retained-"+i,i*128)).toList();
  var previous=DirectorStructureExportBridge.next(WORLD,records.subList(0,1),null);
  var original=DirectorG0LocationExport.canonical(previous);
  var candidate=DirectorStructureExportBridge.next(WORLD,records,previous);
  assertThrows(Exception.class,()->DirectorStructureExportBridge.validateWithInstalledDirector(candidate));
  assertEquals(original,DirectorG0LocationExport.canonical(previous));
  assertEquals(33,candidate.getAsJsonArray("locations").size(),"No truncated fallback or source-page replacement");
  assertEquals(33,candidate.getAsJsonArray("anchors").size());
 }
 @Test void unchangedExportRetainsExactRevisionAndDigest() throws Exception {
  var records=List.of(record("a",0),record("b",128));
  var first=DirectorStructureExportBridge.next(WORLD,records,null);
  assertEquals(first,DirectorStructureExportBridge.next(WORLD,List.of(records.get(1),records.get(0)),first));
 }
 @Test void additionalPlacementIncrementsRevisionPreservingExistingIds() throws Exception {
  var a=record("a",0);var first=DirectorStructureExportBridge.next(WORLD,List.of(a),null);
  var second=DirectorStructureExportBridge.next(WORLD,List.of(a,record("b",128)),first);
  assertEquals("2",second.get("export_revision").getAsString());
  assertTrue(second.getAsJsonArray("locations").asList().stream().anyMatch(v->v.getAsJsonObject()
   .get("location_id").getAsString().equals(a.locationId().toString())));
 }
 @Test void everyLocationKeepsItsTagsWithoutLeakingIntoRegionsOrAnchors() throws Exception {
  var value=DirectorStructureExportBridge.next(WORLD,List.of(record("a",0),record("b",128)),null);
  assertEquals(2,value.getAsJsonArray("regions").size());
  Set<String> ids=new HashSet<>();
  for(var region:value.getAsJsonArray("regions")) {
   var obj=region.getAsJsonObject();ids.add(obj.get("region_id").getAsString());
   assertTrue(obj.getAsJsonArray("tags").isEmpty());
  }
  for(var location:value.getAsJsonArray("locations")) {
   var obj=location.getAsJsonObject();assertEquals(1,obj.getAsJsonArray("region_ids").size());
   assertTrue(ids.contains(obj.getAsJsonArray("region_ids").get(0).getAsString()));
   assertEquals(Set.of("proutlost:structure","proutlost:dungeon"),
    obj.getAsJsonArray("tags").asList().stream().map(t->t.getAsString())
     .collect(java.util.stream.Collectors.toSet()));
  }
  for(var anchor:value.getAsJsonArray("anchors")) {
   assertEquals(Set.of("proutlost:anchor_center"),anchor.getAsJsonObject()
    .getAsJsonArray("tags").asList().stream().map(t->t.getAsString())
     .collect(java.util.stream.Collectors.toSet()));
  }
 }
 @Test void modifiedDigestCannotBeOverwritten() throws Exception {
  var first=DirectorStructureExportBridge.next(WORLD,List.of(record("a",0)),null);
  first.addProperty("export_digest","f".repeat(64));
  assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.next(WORLD,List.of(record("a",0)),first));
 }
 @Test void wrongWorldIsNotSilentlyRebound() throws Exception {
  var first=DirectorStructureExportBridge.next(WORLD,List.of(record("a",0)),null);
  assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.next(UUID.randomUUID(),List.of(record("a",0)),first));
 }
 @Test void canonicalByteBoundCannotBeBypassedByUnicode() throws Exception {
  DirectorG0LocationExport.requireBoundedBytes("a".repeat(1_048_576));
  assertThrows(java.io.IOException.class,()->DirectorG0LocationExport.requireBoundedBytes("a".repeat(1_048_577)));
  assertThrows(java.io.IOException.class,()->DirectorG0LocationExport.requireBoundedBytes("\u00e9".repeat(524_289)));
 }
 @Test void oversizedCanonicalWritePublishesNoTargetOrPartial(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  var json=new com.google.gson.JsonObject();json.addProperty("data","\u00e9".repeat(524_289));
  assertThrows(java.io.IOException.class,()->DirectorG0LocationExport.writeOnce(directory,json));
  try(var entries=java.nio.file.Files.list(directory)){assertEquals(0,entries.count());}
 }
}

