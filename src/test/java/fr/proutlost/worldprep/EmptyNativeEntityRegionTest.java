package fr.proutlost.worldprep;
import com.google.gson.Gson;
import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.PhysicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EmptyNativeEntityRegionTest {
 @TempDir Path tmp;
 final Gson json=new Gson();
 record Fixture(Path source,Path backup,Path pages,PagedBackupVerifier.Header header,PagedBackupVerifier.Entry entry,PhysicalStore store){}
 Fixture fixture(int size)throws Exception {
  Path s=Files.createDirectory(tmp.resolve("s")),b=Files.createDirectory(tmp.resolve("b")),p=Files.createDirectory(tmp.resolve("pages"));
  Files.createDirectory(s.resolve("entities"));Files.createDirectory(b.resolve("entities"));
  byte[] bytes=new byte[size];Files.write(s.resolve("entities/r.0.0.mca"),bytes);Files.write(b.resolve("entities/r.0.0.mca"),bytes);
  Files.writeString(s.resolve("pin"),"frozen");Files.writeString(b.resolve("pin"),"frozen");
  var entry=entry(s,b,"entities/r.0.0.mca",size);
  var page=new PagedBackupVerifier.Page(2,0,PagedBackupVerifier.ZERO,List.of(entry,entry(s,b,"pin",6)));
  String text=json.toJson(page);Files.writeString(p.resolve("page-0.json"),text);
  var h=new PagedBackupVerifier.Header(2,s.toString(),b.toString(),"pin",PhysicalStore.sha("frozen"),1,2,PhysicalStore.sha(text));
  return new Fixture(s,b,p,h,entry,new PhysicalStore(tmp.resolve("journal")));
 }
 PagedBackupVerifier.Entry entry(Path s,Path b,String path,long size)throws Exception {
  String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(b.resolve(path))));
  return new PagedBackupVerifier.Entry(path,size,hash,Files.getLastModifiedTime(s.resolve(path)).toMillis(),Files.getLastModifiedTime(b.resolve(path)).toMillis());
 }
 @Test void actualAuditedZeroRegionHasOneReceiptAndReplaysUnchanged()throws Exception {
  var f=fixture(0);try(var v=new PagedBackupVerifier(f.header,f.source,f.pages,tmp.resolve("cursor"),Set.of())){int ticks=0;while(!v.step())assertTrue(++ticks<100);}
  assertTrue(EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  String key="empty-region-"+PhysicalStore.sha(f.entry.path());String first=f.store.readText(key);
  assertTrue(EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,new PhysicalStore(tmp.resolve("journal"))));assertEquals(first,f.store.readText(key));
  var receipt=f.store.read(key,EmptyNativeEntityRegion.Receipt.class);assertEquals(1024,receipt.slots());assertEquals(0,receipt.counts().total());assertEquals("EMPTY_NATIVE_ENTITY_REGION",receipt.disposition());
  try(var paths=Files.list(tmp.resolve("journal"))){assertEquals(1,paths.count());}
 }
 @Test void boundariesOne8191Refuse8192IsNeverAnEmptyDisposition()throws Exception {
  var f=fixture(8192);assertFalse(EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  for(int n:new int[]{1,8191}){Files.write(f.source.resolve(f.entry.path()),new byte[n]);Files.write(f.backup.resolve(f.entry.path()),new byte[n]);var e=entry(f.source,f.backup,f.entry.path(),n);assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,e,f.store));}
 }
 @Test void changedBytesMtimeAndWrongShaRefuseEvenAfterReceipt()throws Exception {
  var f=fixture(0);assertTrue(EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));Path b=f.backup.resolve(f.entry.path());
  Files.setLastModifiedTime(b,FileTime.fromMillis(f.entry.backupModifiedMillis()+2000));assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  Files.setLastModifiedTime(b,FileTime.fromMillis(f.entry.backupModifiedMillis()));Files.write(b,new byte[]{1});assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  var e=new PagedBackupVerifier.Entry(f.entry.path(),0,PagedBackupVerifier.ZERO,f.entry.sourceModifiedMillis(),f.entry.backupModifiedMillis());assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,e,f.store));
 }
 @Test void symlinksAndHardlinksRefuse()throws Exception {
  var f=fixture(0);Path b=f.backup.resolve(f.entry.path());Files.delete(b);Files.createSymbolicLink(b,f.source.resolve(f.entry.path()));assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  Files.delete(b);Files.createLink(b,f.source.resolve(f.entry.path()));assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
 }
 @Test void inventorySubstitutionAndPartialReceiptRefuse()throws Exception {
  var f=fixture(0);assertTrue(EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
  var h=new PagedBackupVerifier.Header(2,f.source.toString(),f.backup.toString(),"pin",f.header.quiescentPinSha256(),1,2,PagedBackupVerifier.ZERO);
  assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(h,f.entry,f.store));
  Files.writeString(tmp.resolve("journal/empty-region-"+PhysicalStore.sha(f.entry.path())+".json"),"{");
  assertThrows(java.io.IOException.class,()->EmptyNativeEntityRegion.recordIfEmpty(f.header,f.entry,f.store));
 }
}
