package fr.proutlost.director.client.perception.g3;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import com.google.gson.JsonParser;

/** Synthetic writer verification only; does not produce native perception evidence. */
public final class G3EvidenceWriterTests {
 public static void main(String[] args)throws Exception {
  var root=Files.createTempDirectory(Path.of("build/J06"),"synthetic-writer-");var run=UUID.randomUUID();var capture=UUID.randomUUID();
  var writer=new G3EvidenceWriter(root,run,"A");
  var row=Map.<String,Object>of("kind","SYNTHETIC_UNIT_ONLY","admission_nonce",run.toString());
  writer.event(row).toCompletableFuture().get(10,TimeUnit.SECONDS);
  writer.capture(row,capture,2,1,new int[]{0xff0000ff,0xff00ff00}).toCompletableFuture().get(10,TimeUnit.SECONDS);
  writer.finish().toCompletableFuture().get(10,TimeUnit.SECONDS);
  var directory=root.resolve(run.toString()).resolve("A");var png=directory.resolve(capture+".png");var image=ImageIO.read(png.toFile());
  if(image.getRGB(0,0)!=0xffff0000||image.getRGB(1,0)!=0xff00ff00)throw new AssertionError("RGBA conversion");
  var lines=Files.readAllLines(directory.resolve("observations.jsonl"));if(lines.size()!=2)throw new AssertionError("rows");
  var evidence=JsonParser.parseString(lines.get(1)).getAsJsonObject();
  var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(png)));
  if(!evidence.get("png_sha256").getAsString().equals(digest))throw new AssertionError("hash");
  if(!evidence.get("admission_nonce").getAsString().equals(run.toString()))throw new AssertionError("correlation");
  if(writer.failure().isPresent())throw new AssertionError("IO failure");
  var invalid=new G3EvidenceWriter(root,UUID.randomUUID(),"B");
  try{invalid.capture(row,UUID.randomUUID(),3841,2160,new int[0]);throw new AssertionError("bounds");}catch(IllegalArgumentException expected){}
  invalid.finish().toCompletableFuture().get(10,TimeUnit.SECONDS);
  var duplicate=new G3EvidenceWriter(root,run,"A");
  try{duplicate.capture(row,capture,1,1,new int[]{0}).toCompletableFuture().get(10,TimeUnit.SECONDS);throw new AssertionError("overwrite");}catch(java.util.concurrent.ExecutionException expected){}
  if(duplicate.failure().isEmpty())throw new AssertionError("missing failure latch");
  try{duplicate.finish().toCompletableFuture().get(10,TimeUnit.SECONDS);throw new AssertionError("false success");}catch(java.util.concurrent.ExecutionException expected){}
  System.out.println("PASS G3 writer: 8 checks; synthetic pixels only; native NOT_RUN");
 }
}
