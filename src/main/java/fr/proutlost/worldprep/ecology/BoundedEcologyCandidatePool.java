package fr.proutlost.worldprep.ecology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure worker pool. All Column inputs are immutable snapshots captured by the
 * server thread. NO Minecraft Level/Chunk/Registry/SavedData is visible here.
 * Workers only filter deterministic candidate coordinates; block writes,
 * revalidation, journaling and checkpoints remain on the server thread.
 */
public final class BoundedEcologyCandidatePool {
    public record Column(int x, int z, int surfaceY, int floorY, boolean protectedColumn) {}
    public record Candidate(int x, int y, int z) {}
    public record Ticket(String pass, int workers, int columns,
                         List<Future<List<Candidate>>> tasks) {}
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_WORKERS = 8;
    private static final Map<Integer, ThreadPoolExecutor> POOLS = new HashMap<>();
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();

    public static boolean supports(String pass) {
        return "SOILS".equals(pass) || "FLORA".equals(pass) || "AQUATIC".equals(pass);
    }
    private static void validate(String pass, List<Column> columns, int workers) {
        if (!supports(pass) || workers < 1 || workers > MAX_WORKERS
                || columns == null || columns.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Unbounded/unsupported immutable ecology snapshot");
        }
        Set<Long> unique = new HashSet<>();
        Column previous = null;
        for (Column column : columns) {
            if (column == null) throw new IllegalArgumentException("Null column");
            long key = ((long) column.z() << 32) ^ (column.x() & 0xffffffffL);
            if (!unique.add(key) || (previous != null
                    && (column.z() < previous.z()
                    || (column.z() == previous.z() && column.x() <= previous.x())))) {
                throw new IllegalArgumentException("Duplicate or non-canonical column order");
            }
            previous = column;
        }
    }
    private static ThreadPoolExecutor pool(int workers) {
        synchronized (POOLS) {
            return POOLS.computeIfAbsent(workers, n -> {
                var executor = new ThreadPoolExecutor(n, n, 10L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(32), runnable -> {
                    var thread = new Thread(runnable,
                            "worldprep-run39-ecology-pure-" + WORKER_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                executor.allowCoreThreadTimeOut(true);
                return executor;
            });
        }
    }
    public static Ticket submit(long seed, String pass, List<Column> columns, int workers) {
        if (columns == null) throw new IllegalArgumentException("Null ecology snapshot");
        var snapshot = List.copyOf(columns);
        validate(pass, snapshot, workers);
        int stride = Math.max(1, (snapshot.size() + workers * 2 - 1) / (workers * 2));
        var pending = new ArrayList<Future<List<Candidate>>>();
        try {
            for (int from = 0; from < snapshot.size(); from += stride) {
                var slice = snapshot.subList(from, Math.min(snapshot.size(), from + stride));
                pending.add(pool(workers).submit(() -> calculate(seed, pass, slice)));
            }
        } catch (RuntimeException error) {
            pending.forEach(f -> f.cancel(true));
            throw new IllegalStateException("Bounded ecology worker queue rejected snapshot", error);
        }
        return new Ticket(pass, workers, snapshot.size(), List.copyOf(pending));
    }
    public static boolean ready(Ticket ticket) {
        return ticket.tasks().stream().allMatch(Future::isDone);
    }
    public static List<Candidate> collect(Ticket ticket) {
        if (!ready(ticket)) throw new IllegalStateException("Worker results not complete");
        var result = new ArrayList<Candidate>();
        try {
            // Futures are merged in original capture order, NEVER completion order.
            for (var task : ticket.tasks()) result.addAll(task.get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel(ticket);
            throw new IllegalStateException("Ecology worker interrupted", error);
        } catch (ExecutionException error) {
            cancel(ticket);
            throw new IllegalStateException("Pure ecology worker failed", error.getCause());
        } catch (CancellationException error) {
            throw new IllegalStateException("Cancelled ecology preview must never publish",error);
        }
        if (result.size() > ticket.columns()) throw new IllegalStateException("Worker output exceeds snapshot");
        return List.copyOf(result);
    }
    public static void cancel(Ticket ticket) {
        ticket.tasks().forEach(f -> f.cancel(true));
    }
    public static List<Candidate> sequential(long seed, String pass, List<Column> columns) {
        var copy = List.copyOf(columns);
        validate(pass, copy, 1);
        return List.copyOf(calculate(seed, pass, copy));
    }
    private static List<Candidate> calculate(long seed, String pass, List<Column> columns) {
        var found = new ArrayList<Candidate>();
        for (var c : columns) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Worker cancelled");
            if (c.protectedColumn()) continue;
            int y;
            switch (pass) {
                case "SOILS" -> {
                    if (c.surfaceY() != c.floorY()) continue;
                    y = c.floorY();
                }
                case "FLORA" -> {
                    if (c.surfaceY() != c.floorY()
                            || !VegetationPlanner.flora(seed, c.x(), c.z(), .28, 1)) continue;
                    y = c.surfaceY() + 1;
                }
                case "AQUATIC" -> {
                    int depth = c.surfaceY() - c.floorY();
                    if (depth < 2 || depth > 16 ||
                            VegetationPlanner.unit(seed, c.x(), c.z(), 123) >= .30) continue;
                    y = c.floorY() + 1;
                }
                default -> throw new IllegalStateException("Unsupported pass");
            }
            found.add(new Candidate(c.x(), y, c.z()));
        }
        return found;
    }
    private BoundedEcologyCandidatePool() {}
}
