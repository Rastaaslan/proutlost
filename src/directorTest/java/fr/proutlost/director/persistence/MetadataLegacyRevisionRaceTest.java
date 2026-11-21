package fr.proutlost.director.persistence;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Synthetic zero-head source race: legacy source revision remains an activation precondition. */
public final class MetadataLegacyRevisionRaceTest {
 private MetadataLegacyRevisionRaceTest(){}
 private static final WorldId WORLD=new WorldId(new UUID(8808,1));
 private static final ResourceId SOURCE=new ResourceId("fixture:legacy_revision_race");
 private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(30,TimeUnit.SECONDS);}
 private static LocationImport source(long rev){var raw=new LocationImport(1,WORLD,SOURCE,new Revision(rev),new Fingerprint("0".repeat(64)),List.of(),List.of(),List.of());return new LocationImport(1,WORLD,SOURCE,raw.exportRevision(),StandaloneLocationImporter.digest(raw),List.of(),List.of(),List.of());}
 private static CommitAck append(JournalStore s,DurableRecord record,List<BlobContent> blobs)throws Exception{return await(s.append(new DurableBatch(new TransactionId(UUID.randomUUID()),s.session().storeGeneration(),Map.of(),List.of(record),blobs,Optional.empty(),BlobContent.hash(new ModelCodec<>(DurableRecord.class).encode(record)))));}
 private static DirectorFact fact(LocationImport source){String value="director-location-import-v1/"+WORLD.value()+"/"+SOURCE.value()+"/"+source.exportRevision().value()+"/"+source.exportDigest().value();return new DirectorFact(new FactId(UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))),new FactType("proutlost_director:location_import_applied"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("source_namespace",new JsonValue.Text(SOURCE.value()),"export_revision",new JsonValue.Text(Long.toString(source.exportRevision().value())),"export_digest",new JsonValue.Text(source.exportDigest().value()),"location_import_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(new ModelCodec<>(LocationImport.class).encode(source)))),true,Optional.empty());}
 public static void main(String[]args)throws Exception{Path root=args.length>0?Path.of(args[0]):Files.createTempDirectory("P008-legacy-revision-race-");var s=await(JournalStore.open(root,WORLD,new StoreOptions(1048620,4194304,16777216,33554432,64,0,128)));
  try{var old=LocationTransfer.prepare(source(1));for(var page:old.pages()){var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(page.bytesBase64()));if(append(s,new DurableMetadataSourcePage(old.manifest(),page.pageIndex(),blob.reference()),List.of(blob)).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("initial raw stage refused");}
   if(append(s,new DurableFact(fact(source(2))),List.of()).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("concurrent newer legacy fact refused");
   var result=append(s,new DurableMetadataActivation(old.manifest(),Optional.empty(),0),List.of());
   System.out.println("ACTUAL_OLD_REVISION_ACTIVATION="+result.ackStatus());
   if(result.ackStatus()!=AckStatus.REJECTED)throw new AssertionError("source revision regressed from newer legacy rev2 to staged rev1 with no keys");
   var snapshot=s.metadataSnapshot().orElseThrow();if(!await(s.readMetadataSource(snapshot,SOURCE)).orElseThrow().equals(source(2)))throw new AssertionError("newer exact legacy source not retained");
   if(append(s,new DurableMetadataCancellation(old.manifest()),List.of()).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("failed proposal cancellation refused");
   var next=LocationTransfer.prepare(source(3));for(var page:next.pages()){var blob=BlobContent.capture(new ResourceId("proutlost_director:location_transfer_fragment"),1,Base64.getDecoder().decode(page.bytesBase64()));if(append(s,new DurableMetadataSourcePage(next.manifest(),page.pageIndex(),blob.reference()),List.of(blob)).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("newer corrected source stage refused");}
   if(append(s,new DurableMetadataActivation(next.manifest(),Optional.empty(),0),List.of()).ackStatus()!=AckStatus.DURABLE)throw new AssertionError("legitimate corrected revision3 activation refused");
   if(!await(s.readMetadataSource(s.metadataSnapshot().orElseThrow(),SOURCE)).orElseThrow().equals(source(3)))throw new AssertionError("legitimate corrected revision3 not current");
   System.out.println("PASS MetadataLegacyRevisionRaceTest unchanged-head legacy revision fence and explicit corrected revision3");
  }finally{await(s.closeAsync());}
 }
}
