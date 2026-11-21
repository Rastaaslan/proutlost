package fr.proutlost.p008qa.cohort;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Pure bounded actor DTO sink tests. No Minecraft, real CPU reading, J04 or clients. */
public final class ActorFrameCaptureTests {
 private ActorFrameCaptureTests(){}
 private static int checks;private static Path parent;private static final long START=1_000_000_000L;
 private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
 private static void refused(Runnable body,String message){boolean refused=false;try{body.run();}catch(IllegalArgumentException expected){refused=true;}check(refused,message);}
 private static ActorFrameCapture.Sample sample(long tick,long wall,long cpu){return new ActorFrameCapture.Sample(tick,wall,tick+1,2,tick+1,2_000_000,500_000,100,3,40,100,70,230,cpu,10,20,1,1,0,20,250,cpu<0?-1:100,3_000_000,false,"RUNNING","NORMAL",7);}
 private static ActorFrameCapture open(String id,AtomicLong clock,int queue,ActorFrameCapture.BeforeWrite write)throws IOException{return new ActorFrameCapture(parent,id,new ActorFrameCapture.Limits(30,3000,131072,queue),Map.of("scope","PURE_DECLARED_DTO_NOT_NATIVE_CPU"),clock::get,write);}
 private static String finish(ActorFrameCapture capture)throws Exception{capture.stop(ActorFrameCapture.End.EXPLICIT_STOP);return Files.readString(capture.completion().get(5,TimeUnit.SECONDS));}
 private static long number(String json,String key){var m=Pattern.compile("\\\""+key+"\\\":(-?[0-9]+)").matcher(json);if(!m.find())throw new AssertionError("missing "+key);return Long.parseLong(m.group(1));}
 private static String text(String json,String key){var m=Pattern.compile("\\\""+key+"\\\":\\\"([^\\\"]*)\\\"").matcher(json);if(!m.find())throw new AssertionError("missing "+key);return m.group(1);}
 public static void main(String[] args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("new evidence directory required");parent=Path.of(args[0]).toAbsolutePath();Files.createDirectory(parent);
  refused(()->sample(-1,START,1),"negative frame refused");refused(()->sample(1,START,-2),"invalid CPU sentinel refused");
  refused(()->new ActorFrameCapture.Sample(1,START,0,2,2,2000,500,100,3,0,0,0,0,-1,0,0,0,0,0,1,0,-1,3000,false,"RUNNING","NORMAL",1),"future frame cannot be attributed to an earlier observation");
  var clock=new AtomicLong(START);var valid=open("valid",clock,8,()->{});
  clock.incrementAndGet();check(valid.offer(sample(10,clock.get(),123))==ActorFrameCapture.Offer.QUEUED,"actual aligned frame queued");
  clock.incrementAndGet();check(valid.offer(sample(11,clock.get(),-1))==ActorFrameCapture.Offer.QUEUED,"unavailable CPU remains explicit");
  String end=finish(valid);check(end.contains("\"end_incomplete\":false"),"valid sink drained");var rows=Files.readAllLines(valid.root().resolve("actor-frames.csv"));
  check(rows.size()==3,"header and exact two frames");check(rows.getFirst().split(",",-1).length==27&&rows.get(1).split(",",-1).length==27,"actor CSV has fixed27columns");
  check(rows.get(1).split(",",-1)[0].equals("10")&&rows.get(1).split(",",-1)[2].equals("11"),"frame and observation ticks kept distinct");
  check(rows.get(1).split(",",-1)[13].equals("123")&&rows.get(2).split(",",-1)[13].equals("-1"),"CPU units and missing sentinel preserved");
  check(rows.get(1).split(",",-1)[10].equals("100")&&rows.getFirst().contains("critical_wall_ns"),"critical wall is never critical CPU");
  check(number(end,"written_samples")==2&&number(end,"accepted_but_unwritten_samples")==0,"exact durable frame counts");
  var bytes=Files.readAllBytes(valid.root().resolve("actor-frames.csv"));check(number(end,"csv_bytes")==bytes.length,"actor CSV bytes exact");
  check(text(end,"csv_sha256").equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),"actor CSV hash exact");
  check(end.contains("NO_WORKER_OR_WHOLE_SERVER_CPU"),"CPU scope explicit");
  check(number(end,"written_cpu_available_samples")==1&&number(end,"written_cpu_unavailable_samples")==1&&end.contains("\"actor_cpu_coverage_complete\":false"),"unknown CPU cannot qualify complete CPU coverage");
  var gap=open("gap",clock,8,()->{});clock.incrementAndGet();gap.offer(sample(1,clock.get(),1));clock.incrementAndGet();gap.offer(sample(3,clock.get(),1));end=finish(gap);check(number(end,"source_gap_ticks")==1&&end.contains("\"end_incomplete\":true"),"missing actor frame cannot be invented as zero");
  var duplicate=open("duplicate",clock,8,()->{});clock.incrementAndGet();duplicate.offer(sample(1,clock.get(),1));clock.incrementAndGet();check(duplicate.offer(sample(1,clock.get(),1))==ActorFrameCapture.Offer.DUPLICATE,"same closed frame cannot pad rows");check(finish(duplicate).contains("\"end_incomplete\":true"),"duplicate source explicit incomplete");
  var regression=open("regression",clock,8,()->{});clock.incrementAndGet();regression.offer(sample(2,clock.get(),1));clock.incrementAndGet();check(regression.offer(sample(1,clock.get(),1))==ActorFrameCapture.Offer.INVALID,"frame regression fails closed");check(finish(regression).contains("\"end_incomplete\":true"),"regressed frame cannot qualify");
  var io=open("io",clock,8,()->{throw new IOException("declared failure");});clock.incrementAndGet();io.offer(sample(1,clock.get(),1));end=finish(io);check(end.contains("\"end_reason\":\"IO_FAILURE\"")&&end.contains("\"end_incomplete\":true"),"IO failure retained");
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var full=open("queue",clock,1,()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}});
  try{clock.incrementAndGet();full.offer(sample(1,clock.get(),1));check(entered.await(5,TimeUnit.SECONDS),"writer occupied off actor");clock.incrementAndGet();full.offer(sample(2,clock.get(),1));clock.incrementAndGet();check(full.offer(sample(3,clock.get(),1))==ActorFrameCapture.Offer.QUEUE_FULL,"bounded actor offer never waits for writer");full.stop(ActorFrameCapture.End.EXPLICIT_STOP);}finally{release.countDown();}
  end=Files.readString(full.completion().get(5,TimeUnit.SECONDS));check(number(end,"queue_dropped_samples")==1&&end.contains("\"end_incomplete\":true"),"dropped frame not green");
  var durationClock=new AtomicLong(START);var duration=open("duration",durationClock,8,()->{});durationClock.addAndGet(TimeUnit.SECONDS.toNanos(31));check(duration.offer(sample(1,durationClock.get(),1))==ActorFrameCapture.Offer.STOPPED,"duration limit bounded");check(finish(duration).contains("\"end_incomplete\":true"),"empty duration window incomplete");
  var storageClock=new AtomicLong(START);var storage=open("storage",storageClock,4096,()->{});for(int n=0;n<2000;n++){storageClock.incrementAndGet();if(storage.offer(sample(n,storageClock.get(),1))==ActorFrameCapture.Offer.STOPPED)break;}end=finish(storage);check(end.contains("\"end_reason\":\"STORAGE_LIMIT\"")&&end.contains("\"end_incomplete\":true"),"storage truncation retained");
  check(number(end,"csv_bytes")<=131072-65536,"storage reserve respected");
  System.out.println("ActorFrameCaptureTests checks="+checks+" PASS scope=PURE_SINK_DECLARED_DTO_NO_NATIVE_CPU");
 }
}
