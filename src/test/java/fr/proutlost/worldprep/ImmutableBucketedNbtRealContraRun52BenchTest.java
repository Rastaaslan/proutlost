package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** RUN52: actual immutable RUN46-admitted 88,378-receipt HDD cold corpus.
 * Only READ the cold NBT/page source, and write new temp DEV outputs on HDD.
 * All original/historic bytes stay untouched.
 */
class ImmutableBucketedNbtRealContraRun52BenchTest {
 @TempDir Path work;
 private static final String FLAG="proutlost.run52.parallelNbtBuckets";
 private static final String STORE="proutlost_worldprep";
 private static final Path COLD=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/Contra-Run35-cold-A-RUN46");
 static String sha(Path p)throws Exception {
   var h=MessageDigest.getInstance("SHA-256");
   try(var in=Files.newInputStream(p)){
     byte[] buf=new byte[65536];for(int n;(n=in.read(buf))!=-1;)h.update(buf,0,n);
   }
   return java.util.HexFormat.of().formatHex(h.digest());
 }
 static void flag(boolean b){if(b)System.setProperty(FLAG,"true");else System.clearProperty(FLAG);}
 static long median(ArrayList<Long> values) {
   var a=new ArrayList<Long>(values);a.sort(Long::compare);return a.get(a.size()/2);
 }
 @Test void exactCurrentContraColdNBTIsParallelFasterOrSlowerMeasuredWithoutWorldModification()throws Exception{
  Assumptions.assumeTrue(Boolean.getBoolean("proutlost.run52.realBenchmark"));
  var original=System.getProperty(FLAG);
  try{
   Path input=COLD.resolve("data/"+STORE+".dat");
   assertTrue(Files.isRegularFile(input)&&!Files.isSymbolicLink(input));
   var frozen=sha(input);
   var old=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap());
   assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(old));
   var full=ImmutableBucketedNbtCheckpoint.expand(old,input,STORE);
   var records=full.getCompound("data").getList("jobs",Tag.TAG_COMPOUND);
   assertTrue(records.size()>60_000,"Actual frozen 88k receipt corpus must be sufficiently large");
   var ix=-1;
   for(int i=0;i<records.size();i++)
     if("BATCH_ECOLOGY".equals(records.getCompound(i).getString("operation"))){ix=i;break;}
   assertTrue(ix>=0);
   var beforeSerial=work.resolve("serial/data/"+STORE+".dat");
   var beforeParallel=work.resolve("parallel/data/"+STORE+".dat");
   flag(false);long st=System.nanoTime();
   var a=ImmutableBucketedNbtCheckpoint.externalize(full,beforeSerial,STORE);
   long firstSerial=(System.nanoTime()-st)/1_000_000;
   flag(true);st=System.nanoTime();
   var b=ImmutableBucketedNbtCheckpoint.externalize(full,beforeParallel,STORE);
   long firstParallel=(System.nanoTime()-st)/1_000_000;
   assertEquals(a.compactRoot(),b.compactRoot());
   // The historical compressed index is not required to be byte-identical to a
   // newly reconstructed index. The complete decoded NBT and all root/page
   // identities must remain losslessly equivalent, including insertion order.
   assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(a.compactRoot(),beforeSerial,STORE));
   assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(b.compactRoot(),beforeParallel,STORE));
   var timesSerial=new ArrayList<Long>();var timesParallel=new ArrayList<Long>();
   for(int iteration=1;iteration<=4;iteration++){
      var changed=records.getCompound(ix).copy();
      changed.putLong("cursor",88378L+iteration);
      records.set(ix,changed);
      ImmutableBucketedNbtCheckpoint.Capture nextA,nextB;
      if(iteration%2==0){
        flag(true);st=System.nanoTime();
        nextB=ImmutableBucketedNbtCheckpoint.externalize(full,beforeParallel,STORE);
        timesParallel.add((System.nanoTime()-st)/1_000_000);
        flag(false);st=System.nanoTime();
        nextA=ImmutableBucketedNbtCheckpoint.externalize(full,beforeSerial,STORE);
        timesSerial.add((System.nanoTime()-st)/1_000_000);
      }else{
        flag(false);st=System.nanoTime();
        nextA=ImmutableBucketedNbtCheckpoint.externalize(full,beforeSerial,STORE);
        timesSerial.add((System.nanoTime()-st)/1_000_000);
        flag(true);st=System.nanoTime();
        nextB=ImmutableBucketedNbtCheckpoint.externalize(full,beforeParallel,STORE);
        timesParallel.add((System.nanoTime()-st)/1_000_000);
      }
      assertEquals(nextA.compactRoot(),nextB.compactRoot());
      assertEquals(nextA.metrics().newPages(),nextB.metrics().newPages());
      assertEquals(full,ImmutableBucketedNbtCheckpoint.expand(
                        nextB.compactRoot(),beforeParallel,STORE));
   }
   assertEquals(frozen,sha(input),"COLD backed-up original root was changed!");
   System.out.println("RUN52_REAL_CONTRA_88378_HDD_COLD_NBT_BENCH"+
       " originalJobs="+records.size()+" initialSerialMs="+firstSerial+
       " initialParallelMs="+firstParallel+" incrementalSerialMedianMs="+median(timesSerial)+
       " incrementalParallelMedianMs="+median(timesParallel)+
       " incrementalSerialTotalMs="+timesSerial.stream().mapToLong(Long::longValue).sum()+
       " incrementalParallelTotalMs="+timesParallel.stream().mapToLong(Long::longValue).sum()+
       " exactEveryGenerationNBTParity=true originalColdRootSha256="+frozen+
       " immutableBackupUnmodified=true noLiveWorldWrites=true");
  }finally{
   if(original==null)System.clearProperty(FLAG);else System.setProperty(FLAG,original);
  }
 }
}
