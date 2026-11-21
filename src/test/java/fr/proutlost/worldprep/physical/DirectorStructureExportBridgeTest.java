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
 @Test void actualProducerSelectsImmutablePagedTransportWithoutChangingNativeLimits(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  var small=DirectorStructureExportBridge.publish(WORLD,directory,List.of(record("first",0)));
  assertEquals("worldprep-structures.json",Path.of(small.path()).getFileName().toString());
  assertEquals("DIRECTOR_CODEC_AND_DIGEST_PASS_NOT_IMPORTED",small.state());
  byte[] prior=java.nio.file.Files.readAllBytes(Path.of(small.path()));
  var records=IntStream.range(0,400).mapToObj(i->record("paged-"+i,i*128)).toList();
  var paged=DirectorStructureExportBridge.publish(WORLD,directory,records);
  assertEquals(400,paged.locations());assertEquals(400,paged.anchors());assertEquals(2,paged.revision());
  assertEquals("DIRECTOR_PAGED_TRANSPORT_READY_NOT_IMPORTED",paged.state());
  Path target=Path.of(paged.path());assertTrue(target.getFileName().toString().endsWith("_transfer"));
  assertTrue(target.getFileName().toString().length()<=64);
  assertArrayEquals(prior,java.nio.file.Files.readAllBytes(Path.of(small.path())),"Large generation never replaces the small native input file");
  var manifest=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationTransferManifest.class)
   .decode(java.nio.file.Files.readAllBytes(target.resolve("manifest.json")));
  assertEquals(1200,manifest.entryCount());assertEquals(paged.sha256(),manifest.sourceSha256().value());
  var joined=new java.io.ByteArrayOutputStream();
  for(int index=0;index<manifest.pageHashes().size();index++){
   byte[] wire=java.nio.file.Files.readAllBytes(target.resolve(String.format(java.util.Locale.ROOT,"page-%04d.json",index)));
   assertTrue(wire.length<=32768);
   var page=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationTransferPage.class).decode(wire);
   assertEquals(index,page.pageIndex());assertEquals(fr.proutlost.director.integration.locations.LocationTransfer.identity(manifest),page.manifestSha256());
   byte[] fragment=java.util.Base64.getDecoder().decode(page.bytesBase64());
   assertEquals(manifest.pageHashes().get(index),fr.proutlost.director.api.model.BlobContent.hash(fragment));joined.writeBytes(fragment);
  }
  var decoded=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationImport.class).decode(joined.toByteArray());
  assertEquals(400,decoded.locations().size());assertEquals(400,decoded.regions().size());assertEquals(400,decoded.anchors().size());
  assertThrows(IllegalArgumentException.class,()->fr.proutlost.director.integration.locations.StandaloneLocationImporter.requireNativeEnvelope(decoded));
  var replay=DirectorStructureExportBridge.publish(WORLD,directory,records.reversed());
  assertEquals(paged.path(),replay.path());assertEquals(paged.sha256(),replay.sha256());assertFalse(replay.rewritten());assertEquals(2,replay.revision());
  // The pointer is frozen producer recovery state; no native ACK is present.
  var pointer=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(directory.resolve("worldprep-structures-export-current.json"))).getAsJsonObject();
  assertEquals("FROZEN_EXPORT_ONLY_NOT_NATIVE_IMPORT",pointer.get("scope").getAsString());
  assertFalse(pointer.has("ack"));assertFalse(pointer.has("durable_sequence"));
 }
 @Test void corruptedFrozenSourceCannotSilentlyMintAnotherRevision(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  DirectorStructureExportBridge.publish(WORLD,directory,List.of(record("first",0)));
  Path pointer=directory.resolve("worldprep-structures-export-current.json");
  var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(pointer)).getAsJsonObject();
  Path archive=directory.resolve(state.get("source_archive").getAsString());
  byte[] priorPointer=java.nio.file.Files.readAllBytes(pointer);
  java.nio.file.Files.writeString(archive,java.nio.file.Files.readString(archive)+" ");
  assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.publish(WORLD,directory,List.of(record("first",0),record("second",128))));
  assertArrayEquals(priorPointer,java.nio.file.Files.readAllBytes(pointer));
  assertTrue(java.nio.file.Files.readString(archive).endsWith(" "),"corrupt historical bytes remain available as evidence");
 }
 @Test void exportWriterConflictRefusesBeforeChangingRecoveryState(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  Path lock=directory.resolve(".worldprep-structure-export.lock");
  try(var channel=java.nio.channels.FileChannel.open(lock,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
      var held=channel.lock()){
   assertTrue(held.isValid());
   assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.publish(WORLD,directory,List.of(record("first",0))));
   assertFalse(java.nio.file.Files.exists(directory.resolve("worldprep-structures-export-current.json")));
   assertFalse(java.nio.file.Files.exists(directory.resolve("worldprep-structures.json")));
  }
 }

 @Test void readonlyReceiptOpenNeverCreatesMissingSource(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  Path missing=directory.resolve("missing-source");
  assertThrows(java.io.IOException.class,()->PhysicalStore.openReadOnly(missing));
  assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.collectRecords(missing));
  assertFalse(java.nio.file.Files.exists(missing));
 }
 private static NativeVerifiedStructureCatalog.NativeLocationRecord syntheticReceipt(Path root,boolean wrongPlan) throws Exception {
  var sample=record("synthetic-readonly",0);
  var batch=new PhysicalStore(root.resolve("batch-synthetic"));
  var site=new PhysicalStore(root.resolve("batch-synthetic/placement-0"));
  String plan=site.write("native-plan",Map.of("scope","DECLARED_SYNTHETIC_RECEIPT_TEST_ONLY"));
  var disk=new NativeRegistryStructureAudit.Receipt(1,sample.operation(),sample.structureRegistryId(),plan,
   1,1,List.of("0,0"),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
  String diskHash=site.write("native-disk-verified",disk);
  var bound=new NativeVerifiedStructureCatalog.NativeLocationRecord(sample.schema(),sample.locationId(),sample.operation(),sample.dimension(),
   sample.structureRegistryId(),wrongPlan?"f".repeat(64):plan,diskHash,sample.minX(),sample.minY(),sample.minZ(),sample.maxXExclusive(),sample.maxYExclusive(),sample.maxZExclusive(),
   sample.tags(),sample.anchors(),sample.status());
  site.write("director-native-location-candidate",bound);
  batch.write("complete",Map.of("scope","DECLARED_SYNTHETIC_RECEIPT_TEST_ONLY"));
  return bound;
 }
 private static Map<String,String> receiptBytes(Path root)throws Exception {
  var result=new TreeMap<String,String>();
  try(var files=java.nio.file.Files.walk(root)){
   for(var file:files.filter(java.nio.file.Files::isRegularFile).toList())
    result.put(root.relativize(file).toString(),DirectorG0LocationExport.sha256(java.nio.file.Files.readString(file)));
  }
  return result;
 }
 @Test void workerReceiptCollectionPreservesEverySourceByte(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  var expected=syntheticReceipt(directory,false);var before=receiptBytes(directory);
  var actual=java.util.concurrent.CompletableFuture.supplyAsync(()->{
   try{return DirectorStructureExportBridge.collectRecords(directory);}
   catch(java.io.IOException error){throw new java.util.concurrent.CompletionException(error);}
  }).get(10,java.util.concurrent.TimeUnit.SECONDS);
  assertEquals(List.of(expected),actual);assertEquals(before,receiptBytes(directory));
  var readOnly=PhysicalStore.openReadOnly(directory.resolve("batch-synthetic/placement-0"));
  assertThrows(java.io.IOException.class,()->readOnly.write("unexpected-writer",Map.of("not","permitted")));
  assertEquals(before,receiptBytes(directory));
 }
 @Test void workerReceiptBindingRefusalPreservesEverySourceByte(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  syntheticReceipt(directory,true);var before=receiptBytes(directory);
  assertThrows(java.io.IOException.class,()->DirectorStructureExportBridge.collectRecords(directory));
  assertEquals(before,receiptBytes(directory));
 }

}

