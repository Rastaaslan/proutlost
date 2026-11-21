package fr.proutlost.p008qa.production;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** DECLARED_SYNTHETIC TEST_ONLY, one-producer actor-frame sink. No Minecraft, J04, admission or effect API.
 * Producer critical sections never perform IO. Only the worker owns the CSV.
 * Absence of run-end.json always means an interrupted/incomplete capture.
 */
public final class ActorFrameCapture {
    public static final long MAX_SECONDS=32_400, MAX_SAMPLES=650_000, MAX_BYTES=128L*1024*1024;
    private static final int METADATA_RESERVE=65_536, MAX_ROW_BYTES=1024, MAX_DETAILS=32;
    public static final String HEADER="frame_native_tick,wall_monotonic_ns,observed_post_tick,player_count_at_observation,frame_ordinal,global_budget_ns,critical_budget_ns,operation_limit,operations,mandatory_wall_ns,critical_wall_ns,ordinary_wall_ns,actor_wall_ns,actor_cpu_ns,critical_units_cumulative,ordinary_units_cumulative,unit_overruns_cumulative,critical_overruns_cumulative,emergency_critical_units_cumulative,window_samples,actor_wall_p95_ns,actor_cpu_p95_ns,p95_goal_ns,p95_goal_exceeded,phase_at_observation,health_at_observation,world_revision_at_observation\n";
    public record Limits(long seconds,long samples,long bytes,int queueCapacity) {
        public Limits {
            require(seconds>0&&seconds<=MAX_SECONDS,"duration bound");
            require(samples>0&&samples<=MAX_SAMPLES,"sample bound");
            require(bytes>=2L*METADATA_RESERVE&&bytes<=MAX_BYTES,"storage bound");
            require(queueCapacity>0&&queueCapacity<=4096,"queue bound");
        }
    }
    public enum Offer { QUEUED, QUEUE_FULL, DUPLICATE, INVALID, STARTUP_SKIPPED, STOPPED }
    public enum End { EXPLICIT_STOP, SERVER_STOP, DURATION_LIMIT, SAMPLE_LIMIT, STORAGE_LIMIT, SOURCE_FAILURE, IO_FAILURE }
    public record Sample(long completedTick,long wallMonotonicNs,long observedPostTick,int players,long frameOrdinal,long globalBudgetNs,long criticalBudgetNs,int operationLimit,int operations,long mandatoryWallNs,long criticalWallNs,long ordinaryWallNs,long actorWallNs,long actorCpuNs,long criticalUnits,long ordinaryUnits,long unitOverruns,long criticalOverruns,long emergencyCriticalUnits,int windowSamples,long actorWallP95Ns,long actorCpuP95Ns,long p95GoalNs,boolean p95GoalExceeded,String phaseAtObservation,String healthAtObservation,long worldRevisionAtObservation) {
        public Sample {
            require(phaseAtObservation!=null&&phaseAtObservation.matches("[A-Z_]{1,32}")&&healthAtObservation!=null&&healthAtObservation.matches("[A-Z_]{1,32}")&&worldRevisionAtObservation>=-1,"bounded observed state labels");
            require(completedTick>=0&&wallMonotonicNs>=0&&observedPostTick>=completedTick&&players>=0&&players<=2,"real bounded actor frame identity");
            require(frameOrdinal>0&&globalBudgetNs>0&&criticalBudgetNs>0&&criticalBudgetNs<=globalBudgetNs&&operationLimit>0&&operations>=0&&operations<=operationLimit,"actor budget units");
            require(mandatoryWallNs>=0&&criticalWallNs>=0&&ordinaryWallNs>=0&&actorWallNs>=0&&actorCpuNs>=-1&&criticalUnits>=0&&ordinaryUnits>=0&&unitOverruns>=0&&criticalOverruns>=0&&emergencyCriticalUnits>=0&&windowSamples>0&&actorWallP95Ns>=0&&actorCpuP95Ns>=-1&&p95GoalNs>0,"actor metric bounds");
        }
        String csv(){return completedTick+","+wallMonotonicNs+","+observedPostTick+","+players+","+frameOrdinal+","+globalBudgetNs+","+criticalBudgetNs+","+operationLimit+","+operations+","+mandatoryWallNs+","+criticalWallNs+","+ordinaryWallNs+","+actorWallNs+","+actorCpuNs+","+criticalUnits+","+ordinaryUnits+","+unitOverruns+","+criticalOverruns+","+emergencyCriticalUnits+","+windowSamples+","+actorWallP95Ns+","+actorCpuP95Ns+","+p95GoalNs+","+(p95GoalExceeded?1:0)+","+phaseAtObservation+","+healthAtObservation+","+worldRevisionAtObservation+"\n";}
    }
    public record Snapshot(boolean accepting,long sourceSamples,long queued,long written,long queueDropped,
                           long invalid,long duplicates,long sourceGapTicks,long startupSkipped,
                           int queueSize,End end,String failure) {}
    @FunctionalInterface interface BeforeWrite { void run() throws IOException; }
    private final Object gate=new Object();
    private final Thread producer=Thread.currentThread();
    private final Limits limits;
    private final Path root;
    private final Map<String,String> identity;
    private final ArrayBlockingQueue<Sample> queue;
    private final LongSupplier clock;
    private final BeforeWrite beforeWrite;
    private final long started;
    private final CompletableFuture<Path> completion=new CompletableFuture<>();
    private final AtomicLong written=new AtomicLong();
    private final AtomicLong writtenCpuAvailable=new AtomicLong(),writtenCpuUnavailable=new AtomicLong();
    private final List<String> gapDetails=new ArrayList<>();
    private volatile boolean accepting=true;
    private volatile End end;
    private volatile String failure="";
    private volatile long sourceSamples,queued,queueDropped,invalid,duplicates,sourceGapTicks,startupSkipped;
    private long lastSource=-1,lastWall=-1,firstQueued=-1,lastQueued=-1;
    private long csvBytes;
    private final Thread worker;

    public static ActorFrameCapture open(Path parent,String runId,Limits limits,Map<String,String> identity) throws IOException {
        return new ActorFrameCapture(parent,runId,limits,identity,System::nanoTime,()->{});
    }
    ActorFrameCapture(Path parent,String runId,Limits limits,Map<String,String> identity,LongSupplier clock,BeforeWrite beforeWrite) throws IOException {
        require(parent.isAbsolute()&&!Files.isSymbolicLink(parent)&&Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS),"existing absolute unlinked output parent");
        require(runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"),"run ID");
        require(identity.size()<=32,"identity count");
        identity.forEach((k,v)->require(k.matches("[a-z_][a-z0-9_]{0,39}")&&v!=null&&v.length()<=1024,"bounded identity entry"));
        this.limits=limits;this.identity=Map.copyOf(identity);this.clock=clock;this.beforeWrite=beforeWrite;
        root=parent.toRealPath().resolve(runId);Files.createDirectory(root); // Never reuse or overwrite a run.
        queue=new ArrayBlockingQueue<>(limits.queueCapacity());started=clock.getAsLong();
        require(started>=0,"nonnegative monotone clock required by CSV contract");
        writeNew(root.resolve("run-start.json"),startJson().getBytes(StandardCharsets.UTF_8));
        worker=new Thread(this::writeLoop,"P008-QA-actor-frame-writer");
        worker.setDaemon(true);worker.start();
    }
    public Path root(){return root;}
    private void actor(){require(Thread.currentThread()==producer,"single producer actor");}
    public void startupSkipped(){actor();synchronized(gate){if(accepting)startupSkipped++;}}
    public Offer offer(Sample sample){
        long completed=sample.completedTick(),wall=sample.wallMonotonicNs();
        actor();synchronized(gate){
            if(!accepting)return Offer.STOPPED;
            if(wall<started){invalid++;failure="MONOTONE_CLOCK_BEFORE_START";stopLocked(End.SOURCE_FAILURE);return Offer.INVALID;}
            if(wall-started>=TimeUnit.SECONDS.toNanos(limits.seconds())){stopLocked(End.DURATION_LIMIT);return Offer.STOPPED;}
            if(sourceSamples>=limits.samples()){stopLocked(End.SAMPLE_LIMIT);return Offer.STOPPED;}
            if(completed==lastSource){duplicates++;return Offer.DUPLICATE;}
            if(completed<0||completed<lastSource||wall<=lastWall){invalid++;failure="SOURCE_TICK_OR_MONOTONE_CLOCK_REGRESSION";stopLocked(End.SOURCE_FAILURE);return Offer.INVALID;}
            if(lastSource>=0&&completed>lastSource+1){
                sourceGapTicks=Math.addExact(sourceGapTicks,completed-lastSource-1);
                if(gapDetails.size()<MAX_DETAILS)gapDetails.add(lastSource+":"+completed);
            }
            lastSource=completed;lastWall=wall;sourceSamples++;
            boolean accepted=queue.offer(sample);
            if(accepted){queued++;if(firstQueued<0)firstQueued=completed;lastQueued=completed;}else queueDropped++;
            if(sourceSamples==limits.samples())stopLocked(End.SAMPLE_LIMIT);
            return accepted?Offer.QUEUED:Offer.QUEUE_FULL;
        }
    }
    public CompletableFuture<Path> stop(End reason){require(reason!=null,"explicit stop reason required");synchronized(gate){stopLocked(reason);}return completion;}
    public void sourceFailure(String cause){
        actor();require(cause!=null&&cause.length()<=256,"failure length");
        synchronized(gate){if(accepting){invalid++;failure=cause;stopLocked(End.SOURCE_FAILURE);}}
    }
    private void stopLocked(End reason){
        // A soft stop can be followed by a truncated drain: preserve that real
        // storage failure instead of leaving only an apparently normal reason.
        if(accepting||(reason==End.STORAGE_LIMIT&&end!=End.SOURCE_FAILURE&&end!=End.IO_FAILURE)){accepting=false;end=reason;}
    }
    public CompletableFuture<Path> completion(){return completion;}
    public Snapshot snapshot(){return new Snapshot(accepting,sourceSamples,queued,written.get(),queueDropped,invalid,duplicates,sourceGapTicks,startupSkipped,queue.size(),end,failure);}

    private void writeLoop(){
        MessageDigest digest=sha();
        try(var csv=FileChannel.open(root.resolve("actor-frames.csv"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            writeCsv(csv,HEADER.getBytes(StandardCharsets.UTF_8),digest);
            while(true){
                if(clock.getAsLong()-started>=TimeUnit.SECONDS.toNanos(limits.seconds()))stop(End.DURATION_LIMIT);
                Sample sample=queue.poll(250,TimeUnit.MILLISECONDS);
                if(sample!=null){
                    byte[] row=sample.csv().getBytes(StandardCharsets.UTF_8);
                    require(row.length<=MAX_ROW_BYTES,"row byte bound");
                    if(row.length>limits.bytes()-METADATA_RESERVE-csvBytes){stop(End.STORAGE_LIMIT);break;}
                    beforeWrite.run();writeCsv(csv,row,digest);written.incrementAndGet();if(sample.actorCpuNs()>=0)writtenCpuAvailable.incrementAndGet();else writtenCpuUnavailable.incrementAndGet();
                }
                synchronized(gate){if(!accepting&&queue.isEmpty())break;}
            }
            csv.force(true);
        }catch(IOException|InterruptedException|RuntimeException failed){
            if(failed instanceof InterruptedException)Thread.currentThread().interrupt();
            synchronized(gate){accepting=false;end=End.IO_FAILURE;failure=bounded(failed.getClass().getSimpleName()+":"+failed.getMessage());}
        }
        synchronized(gate){if(accepting)stopLocked(End.SOURCE_FAILURE);queue.clear();}
        String csvSha=failure.isEmpty()?HexFormat.of().formatHex(digest.digest()):"";
        try{
            byte[] body=endJson(csvSha).getBytes(StandardCharsets.UTF_8);
            require(body.length<METADATA_RESERVE/2,"end metadata bound");
            writeNew(root.resolve("run-end.json"),body);completion.complete(root.resolve("run-end.json"));
        }catch(IOException|RuntimeException failed){
            failure=bounded("FINAL_METADATA_NOT_DURABLE:"+failed.getClass().getSimpleName());
            completion.completeExceptionally(failed);
            System.err.println("P008_PRODUCTION_ACTOR_FRAME_CAPTURE_END_INCOMPLETE reason="+failure);
        }
    }
    private void writeCsv(FileChannel channel,byte[] bytes,MessageDigest digest)throws IOException{
        var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())csvBytes+=channel.write(b);digest.update(bytes);
    }
    private String startJson(){
        return "{\"schema_version\":1,\"provider_mode\":\"TEST_ONLY\",\"capture_state\":\"RECORDING_OR_INTERRUPTED\",\"end_observed\":false,\"end_incomplete\":true,\"source\":\"COMPLETED_PRODUCTION_RUNTIME_ACTOR_BUDGET_FRAME\",\"cpu_scope\":\"DIRECTOR_ACTOR_FRAME_CPU_ONLY_MINUS_ONE_UNAVAILABLE_NO_WORKER_OR_WHOLE_SERVER_CPU\",\"players_scope\":\"PLAYER_LIST_COUNT_AT_CURRENT_POST_NOT_PREVIOUS_TICK_MEMBERSHIP\",\"no_admission\":true,\"no_world_writes\":true,\"started_monotonic_ns\":"+started+",\"duration_limit_seconds\":"+limits.seconds()+",\"source_sample_limit\":"+limits.samples()+",\"storage_limit_bytes\":"+limits.bytes()+",\"queue_capacity\":"+limits.queueCapacity()+",\"identity\":"+mapJson(identity)+"}\n";
    }
    private String endJson(String csvSha){
        long rows=written.get(),unwritten=queued-rows,ended=clock.getAsLong();
        long elapsed=Math.max(0,ended-started),lastAge=lastWall<0?-1:Math.max(0,ended-lastWall);
        boolean complete=rows>0&&queueDropped==0&&invalid==0&&duplicates==0&&sourceGapTicks==0&&unwritten==0&&failure.isEmpty()&&end!=End.STORAGE_LIMIT&&end!=End.SOURCE_FAILURE&&end!=End.IO_FAILURE;
        return "{\"schema_version\":1,\"provider_mode\":\"TEST_ONLY\",\"capture_state\":\""+(complete?"MEASURED_OBSERVED_SCOPE_ONLY_NOT_ACCEPTANCE":"INCOMPLETE")+"\",\"end_observed\":true,\"end_incomplete\":"+!complete+",\"observed_range_contiguous_and_drained\":"+complete+",\"end_reason\":"+quote(String.valueOf(end))+",\"failure\":"+quote(failure)+",\"source_samples\":"+sourceSamples+",\"queued_samples\":"+queued+",\"written_samples\":"+rows+",\"written_cpu_available_samples\":"+writtenCpuAvailable.get()+",\"written_cpu_unavailable_samples\":"+writtenCpuUnavailable.get()+",\"actor_cpu_coverage_complete\":"+(rows>0&&writtenCpuAvailable.get()==rows)+",\"queue_dropped_samples\":"+queueDropped+",\"accepted_but_unwritten_samples\":"+unwritten+",\"invalid_samples\":"+invalid+",\"duplicate_observations\":"+duplicates+",\"source_gap_ticks\":"+sourceGapTicks+",\"gap_details_first_32\":"+listJson(gapDetails)+",\"startup_skipped_posts\":"+startupSkipped+",\"first_queued_completed_tick\":"+firstQueued+",\"last_queued_completed_tick\":"+lastQueued+",\"csv_bytes\":"+csvBytes+",\"csv_sha256\":"+quote(csvSha)+",\"ended_monotonic_ns\":"+ended+",\"capture_wall_elapsed_ns\":"+elapsed+",\"last_observation_age_at_end_ns\":"+lastAge+",\"full_wall_window_coverage\":\"NOT_ESTABLISHED_FROM_COMPLETED_OBSERVED_ROWS_ALONE\",\"cpu_scope\":\"DIRECTOR_ACTOR_FRAME_CPU_ONLY_MINUS_ONE_UNAVAILABLE_NO_WORKER_OR_WHOLE_SERVER_CPU\",\"endpoint_boundary\":\"FRAME_TICK_IS_NATIVE_UNSIGNED_SERVER_COUNTER_JOIN_VANILLA_BY_EXACT_TICK_ONLY\",\"identity\":"+mapJson(identity)+"}\n";
    }
    private static void writeNew(Path target,byte[] bytes)throws IOException{
        require(bytes.length<METADATA_RESERVE/2,"metadata bound");require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"preserve existing evidence target");Path temp=target.resolveSibling(target.getFileName()+".pending");
        try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"evidence target appeared while finalizing");
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
    }
    private static String bounded(String s){return s==null?"UNKNOWN":s.substring(0,Math.min(256,s.length()));}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String mapJson(Map<String,String> map){var out=new StringBuilder("{");boolean comma=false;for(var e:new TreeMap<>(map).entrySet()){if(comma)out.append(',');comma=true;out.append(quote(e.getKey())).append(':').append(quote(e.getValue()));}return out.append('}').toString();}
    private static String listJson(List<String> list){return "["+String.join(",",list.stream().map(ActorFrameCapture::quote).toList())+"]";}
    private static String quote(String value){var out=new StringBuilder("\"");for(char c:value.toCharArray()){switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.append('"').toString();}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
}
