package fr.proutlost.p008qa.ticks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Pure temporary-folder tests. No Minecraft, J04, native tick capture or clients. */
public final class CompletedTickCaptureTests {
    private static int checks;
    private static Path parent;
    private static final long START=1_000_000_000L;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void refused(Runnable action,String message){boolean denied=false;try{action.run();}catch(IllegalArgumentException expected){denied=true;}check(denied,message);}
    private static CompletedTickCapture.Limits limits(long samples,int queue){return new CompletedTickCapture.Limits(30,samples,1_048_576,queue);}
    private static CompletedTickCapture open(String id,CompletedTickCapture.Limits limits,AtomicLong clock,CompletedTickCapture.BeforeWrite write)throws IOException{
        return new CompletedTickCapture(parent,id,limits,Map.of("test_scope","PURE_JDK_NOT_NATIVE"),clock::get,write);
    }
    private static String finish(CompletedTickCapture c)throws Exception{
        c.stop(CompletedTickCapture.End.EXPLICIT_STOP);return Files.readString(c.completion().get(5,TimeUnit.SECONDS));
    }
    private static long number(String json,String key){var m=Pattern.compile("\\\""+key+"\\\":(-?[0-9]+)").matcher(json);if(!m.find())throw new AssertionError("missing numeric field "+key);return Long.parseLong(m.group(1));}
    private static String text(String json,String key){var m=Pattern.compile("\\\""+key+"\\\":\\\"([^\\\"]*)\\\"").matcher(json);if(!m.find())throw new AssertionError("missing text field "+key);return m.group(1);}
    private static void complete(String end,boolean value){check(end.contains("\"end_incomplete\":"+!value),"end completeness explicit");}
    private static List<String> csv(CompletedTickCapture c)throws IOException{return Files.readAllLines(c.root().resolve("ticks.csv"));}
    private static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static void offered(CompletedTickCapture c,AtomicLong clock,long tick,long duration){clock.incrementAndGet();check(c.offer(tick,clock.get(),duration,2)==CompletedTickCapture.Offer.QUEUED,"sample queued");}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("new temp evidence parent required");
        parent=Path.of(args[0]).toAbsolutePath();Files.createDirectory(parent);
        refused(()->new CompletedTickCapture.Limits(0,1,131_072,1),"duration lower bound");
        refused(()->new CompletedTickCapture.Limits(32_401,1,131_072,1),"duration upper bound");
        refused(()->new CompletedTickCapture.Limits(1,650_001,131_072,1),"sample upper bound");
        refused(()->new CompletedTickCapture.Limits(1,1,131_071,1),"metadata storage reserve");
        refused(()->new CompletedTickCapture.Limits(1,1,131_072,4097),"queue upper bound");
        validAndActualPreviousSlot();gapsAndDuplicates();invalidSources();queueOverflow();storageOverflow();ioFailure();emptyAndEvidenceCollision();durationAndSampleLimits();producerAndStartupAndRing();
        System.out.println("CompletedTickCaptureTests checks="+checks+" PASS scope=PURE_JDK_SINK_READER_NO_MINECRAFT_NO_CLIENTS evidence="+parent);
    }
    private static void validAndActualPreviousSlot()throws Exception{
        var clock=new AtomicLong(START);var c=open("valid",limits(10,8),clock,()->{});
        long[] ring={888,111,222,999};clock.incrementAndGet();
        check(PreviousCompletedTickReader.observe(3,ring,clock.get(),2,c)==CompletedTickCapture.Offer.QUEUED,"read previous completed slot");
        ring[2]=333;clock.incrementAndGet();check(PreviousCompletedTickReader.observe(4,ring,clock.get(),1,c)==CompletedTickCapture.Offer.QUEUED,"next previous slot, player observation changes");
        String end=finish(c);complete(end,true);var rows=csv(c);
        check(rows.size()==3,"CSV header plus exactly two rows");check(rows.get(1).startsWith("2,"+ (START+1) +",222,2,"),"copied previous slot not current999 nor later333");
        check(rows.get(2).startsWith("3,"+(START+2)+",999,1,"),"current slot not inferred duration");
        check(rows.get(1).split(",",-1).length==8&&rows.get(1).endsWith(",,,,"),"CPU columns absent and CSV width fixed");
        check(number(end,"written_samples")==2&&number(end,"accepted_but_unwritten_samples")==0,"durable written count");
        byte[] bytes=Files.readAllBytes(c.root().resolve("ticks.csv"));check(number(end,"csv_bytes")==bytes.length,"CSV bytes exact");check(text(end,"csv_sha256").equals(digest(bytes)),"CSV digest exact");
        check(end.contains("NOT_ESTABLISHED_FROM_COMPLETED_OBSERVED_ROWS_ALONE"),"no false full wall-window claim");
        check(Files.readString(c.root().resolve("run-start.json")).contains("PLAYER_LIST_COUNT_AT_CURRENT_POST_NOT_PREVIOUS_TICK_MEMBERSHIP"),"player count scope explicit");
    }
    private static void gapsAndDuplicates()throws Exception{
        var clock=new AtomicLong(START);var gap=open("gap",limits(10,8),clock,()->{});offered(gap,clock,1,100);offered(gap,clock,3,300);String end=finish(gap);complete(end,false);check(number(end,"source_gap_ticks")==1,"missing completed tick detected");
        var duplicate=open("duplicate",limits(10,8),clock,()->{});offered(duplicate,clock,1,100);clock.incrementAndGet();check(duplicate.offer(1,clock.get(),100,2)==CompletedTickCapture.Offer.DUPLICATE,"duplicate refused");offered(duplicate,clock,2,200);end=finish(duplicate);complete(end,false);check(number(end,"duplicate_observations")==1&&number(end,"written_samples")==2,"duplicate cannot pad CSV or yield complete receipt");
    }
    private static void invalidSources()throws Exception{
        var clock=new AtomicLong(START);var regress=open("regression",limits(10,8),clock,()->{});offered(regress,clock,2,200);clock.incrementAndGet();check(regress.offer(1,clock.get(),100,2)==CompletedTickCapture.Offer.INVALID,"tick regression refused");complete(finish(regress),false);
        var reverse=open("clock_reverse",limits(10,8),clock,()->{});offered(reverse,clock,1,100);check(reverse.offer(2,clock.get()-1,200,2)==CompletedTickCapture.Offer.INVALID,"clock regression refused");complete(finish(reverse),false);
        var invalid=open("invalid_duration",limits(10,8),clock,()->{});clock.incrementAndGet();check(invalid.offer(1,clock.get(),0,2)==CompletedTickCapture.Offer.INVALID,"zero vanilla duration refused");offered(invalid,clock,2,200);String end=finish(invalid);complete(end,false);check(number(end,"invalid_samples")==1&&number(end,"written_samples")==1,"invalid source not written or silently complete");
        var badPlayers=open("invalid_players",limits(10,8),clock,()->{});clock.incrementAndGet();check(badPlayers.offer(1,clock.get(),100,-1)==CompletedTickCapture.Offer.INVALID,"negative player observation refused");complete(finish(badPlayers),false);
    }
    private static void queueOverflow()throws Exception{
        var clock=new AtomicLong(START);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=open("queue_overflow",limits(10,1),clock,()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}});
        try{offered(c,clock,1,100);check(entered.await(5,TimeUnit.SECONDS),"writer blocked off producer");offered(c,clock,2,200);clock.incrementAndGet();check(c.offer(3,clock.get(),300,2)==CompletedTickCapture.Offer.QUEUE_FULL,"full queue returns immediately without IO or waiting");c.stop(CompletedTickCapture.End.EXPLICIT_STOP);}finally{release.countDown();}
        String end=Files.readString(c.completion().get(5,TimeUnit.SECONDS));complete(end,false);check(number(end,"queue_dropped_samples")==1&&number(end,"written_samples")==2,"queue drop explicit, accepted rows drained");
    }
    private static void storageOverflow()throws Exception{
        var clock=new AtomicLong(START);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=open("storage_overflow",new CompletedTickCapture.Limits(30,650_000,131_072,4096),clock,()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}});
        try{offered(c,clock,1,Long.MAX_VALUE);check(entered.await(5,TimeUnit.SECONDS),"storage writer held");for(int i=2;i<=3000;i++){clock.incrementAndGet();if(c.offer(i,clock.get(),Long.MAX_VALUE,2)!=CompletedTickCapture.Offer.QUEUED)throw new AssertionError("premature test queue overflow");}c.stop(CompletedTickCapture.End.EXPLICIT_STOP);}finally{release.countDown();}
        String end=Files.readString(c.completion().get(5,TimeUnit.SECONDS));complete(end,false);check(text(end,"end_reason").equals("STORAGE_LIMIT"),"storage limit supersedes clean stop when drain cannot fit");check(number(end,"accepted_but_unwritten_samples")>0,"storage unfinished rows explicit");check(Files.size(c.root().resolve("ticks.csv"))<131_072,"bounded CSV storage");
    }
    private static void ioFailure()throws Exception{
        var clock=new AtomicLong(START);var c=open("io_failure",limits(2,2),clock,()->{throw new IOException("declared test IO failure");});offered(c,clock,1,100);String end=Files.readString(c.completion().get(5,TimeUnit.SECONDS));complete(end,false);check(text(end,"end_reason").equals("IO_FAILURE")&&number(end,"written_samples")==0,"IO failure not green and no false written row");
    }
    private static void emptyAndEvidenceCollision()throws Exception{
        var clock=new AtomicLong(START);var empty=open("empty",limits(2,2),clock,()->{});complete(finish(empty),false);
        boolean collision=false;try{open("empty",limits(2,2),clock,()->{});}catch(FileAlreadyExistsException expected){collision=true;}check(collision,"run ID never reused");
        var c=open("end_collision",limits(2,2),clock,()->{});Files.writeString(c.root().resolve("run-end.json"),"preserved external proof",StandardOpenOption.CREATE_NEW);c.stop(CompletedTickCapture.End.EXPLICIT_STOP);
        boolean failed=false;try{c.completion().get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){failed=true;}check(failed,"existing terminal proof not replaced");check(Files.readString(c.root().resolve("run-end.json")).equals("preserved external proof"),"terminal collision preserved");
    }
    private static void durationAndSampleLimits()throws Exception{
        var clock=new AtomicLong(START);var c=open("sample_limit",limits(2,4),clock,()->{});offered(c,clock,1,100);offered(c,clock,2,200);check(c.offer(3,clock.incrementAndGet(),300,2)==CompletedTickCapture.Offer.STOPPED,"sample limit closes producer");String end=Files.readString(c.completion().get(5,TimeUnit.SECONDS));complete(end,true);check(text(end,"end_reason").equals("SAMPLE_LIMIT"),"sample end reason");
        clock=new AtomicLong(START);c=open("duration_limit",new CompletedTickCapture.Limits(1,100,1_048_576,4),clock,()->{});offered(c,clock,1,100);clock.set(START+1_000_000_000L);check(c.offer(2,clock.get(),200,2)==CompletedTickCapture.Offer.STOPPED,"duration deadline closes producer");end=Files.readString(c.completion().get(5,TimeUnit.SECONDS));complete(end,true);check(text(end,"end_reason").equals("DURATION_LIMIT"),"duration end reason");check(number(end,"capture_wall_elapsed_ns")>=1_000_000_000L,"elapsed monotone capture recorded independently of rows");
    }
    private static void producerAndStartupAndRing()throws Exception{
        var clock=new AtomicLong(START);var c=open("reader",limits(10,8),clock,()->{});
        check(PreviousCompletedTickReader.observe(0,new long[]{7},clock.get(),0,c)==CompletedTickCapture.Offer.STARTUP_SKIPPED,"startup tick0 skipped");check(PreviousCompletedTickReader.observe(1,new long[]{7},clock.get(),0,c)==CompletedTickCapture.Offer.STARTUP_SKIPPED,"startup uninitialized previous slot skipped");
        long tick=Integer.toUnsignedLong(Integer.MIN_VALUE)-1;long[] ring={11,12,13,14};clock.incrementAndGet();check(PreviousCompletedTickReader.observe(Integer.MIN_VALUE,ring,clock.get(),0,c)==CompletedTickCapture.Offer.QUEUED,"signed int wrap read unsigned tick");
        var denied=new AtomicBoolean();var t=new Thread(()->{try{c.offer(tick+1,clock.get()+1,100,0);}catch(IllegalArgumentException expected){denied.set(true);}});t.start();t.join(5000);check(!t.isAlive()&&denied.get(),"foreign producer refused");refused(()->c.stop(null),"null end reason refused");String end=finish(c);complete(end,true);check(number(end,"startup_skipped_posts")==2&&csv(c).get(1).startsWith(tick+","),"startup count and unsigned identity retained");
        var bad=open("ring_unavailable",limits(10,8),clock,()->{});check(PreviousCompletedTickReader.observe(2,new long[0],clock.incrementAndGet(),0,bad)==CompletedTickCapture.Offer.INVALID,"missing vanilla ring fails closed");complete(finish(bad),false);
    }
}
