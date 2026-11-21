package fr.proutlost.worldprep.ecology;

import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.Candidate;
import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.Column;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RUN41: one independent tile per worker, NOT four workers on a single 256-column tile.
 * Input is bounded and immutable; every task computes all three positional
 * ecology passes for that tile. No Minecraft objects enter a worker.
 * Caller owns ordered completion, verification, terrain writes, journal and ACK.
 */
public final class BoundedTilePipeline implements AutoCloseable {
    public static final int MAX_TILES = 8;
    public static final List<String> PASSES = List.of("SOILS","FLORA","AQUATIC");

    public record TileInput(long index, long seed, List<Column> columns) {
        public TileInput {
            if(index<0 || columns==null || columns.isEmpty() || columns.size()>256) {
                throw new IllegalArgumentException("Invalid bounded tile input");
            }
            columns=List.copyOf(columns);
            // Check the same invariants as the trusted 256-column classifier.
            BoundedEcologyCandidatePool.sequential(seed,"SOILS",columns);
        }
    }
    public record TileResult(long index, Map<String,List<Candidate>> passes) {
        public TileResult {
            if(index<0 || passes==null || !passes.keySet().equals(java.util.Set.copyOf(PASSES))) {
                throw new IllegalArgumentException("Incomplete tile result");
            }
            var canonical=new HashMap<String,List<Candidate>>();
            for(String pass:PASSES)canonical.put(pass,List.copyOf(passes.get(pass)));
            passes=Map.copyOf(canonical);
        }
        public List<Candidate> forPass(String pass) {
            var result=passes.get(pass);
            if(result==null)throw new IllegalArgumentException("Unknown tile pass");
            return result;
        }
    }
    @FunctionalInterface public interface PureCalculator {
        TileResult calculate(TileInput input) throws Exception;
    }
    private static final AtomicInteger THREAD_IDS=new AtomicInteger();
    private final int workers;
    private final ThreadPoolExecutor executor;
    private final PureCalculator calculator;
    private final Map<Long,Future<TileResult>> tasks=new TreeMap<>();
    private final AtomicInteger active=new AtomicInteger();
    private final AtomicInteger peak=new AtomicInteger();
    private boolean closed;

    public BoundedTilePipeline(int workers) {
        this(workers,BoundedTilePipeline::compute);
    }
    /** Package-private test-only pure calculator, never passed a Minecraft API. */
    BoundedTilePipeline(int workers,PureCalculator calculator) {
        if(workers<1||workers>MAX_TILES)throw new IllegalArgumentException("Invalid bounded tile worker count");
        this.workers=workers;
        this.calculator=Objects.requireNonNull(calculator);
        this.executor=new ThreadPoolExecutor(workers,workers,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(workers), runnable -> {
                var thread=new Thread(runnable,"worldprep-tile-cpu-"+THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            },new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }
    public int capacity(){return workers;}
    public int outstanding(){return tasks.size();}
    public int peakConcurrentWorkers(){return peak.get();}
    public boolean has(long tile){return tasks.containsKey(tile);}
    public void offer(TileInput input) {
        if(closed)throw new IllegalStateException("Tile pipeline was closed");
        if(tasks.containsKey(input.index()))throw new IllegalStateException("Duplicate tile work rejected");
        if(tasks.size()>=workers)throw new IllegalStateException("Tile prefetch window full");
        var future=executor.submit(()->{
            int count=active.incrementAndGet();
            peak.accumulateAndGet(count,Math::max);
            try {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                TileResult result=calculator.calculate(input);
                if(result==null||result.index()!=input.index()) {
                    throw new IllegalStateException("Tile result identity mismatch");
                }
                return result;
            } finally {
                active.decrementAndGet();
            }
        });
        tasks.put(input.index(),future);
    }
    /** Null means not ready; never waits/blocks the owning Minecraft server tick. */
    public TileResult poll(long index) {
        if(closed)throw new IllegalStateException("Tile pipeline closed");
        var task=tasks.get(index);
        if(task==null||!task.isDone())return null;
        try {
            TileResult result=task.get();
            if(result.index()!=index)throw new IllegalStateException("Tile result index drift");
            return result;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tile worker interrupted",e);
        }catch(ExecutionException|CancellationException e){
            throw new IllegalStateException("Tile worker failed closed",e);
        }
    }
    public void retireBefore(long next) {
        tasks.entrySet().removeIf(e->{
            if(e.getKey()>=next)return false;
            e.getValue().cancel(true);
            return true;
        });
    }
    public void invalidate(long index) {
        var task=tasks.remove(index);
        if(task!=null)task.cancel(true);
    }
    public static TileResult compute(TileInput input) {
        var results=new HashMap<String,List<Candidate>>();
        for(String pass:PASSES) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Tile worker cancelled");
            results.put(pass,BoundedEcologyCandidatePool.sequential(input.seed(),pass,input.columns()));
        }
        return new TileResult(input.index(),results);
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        tasks.values().forEach(f->f.cancel(true));
        tasks.clear();
        executor.shutdownNow();
    }
}
