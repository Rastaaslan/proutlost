package fr.proutlost.worldprep;

import fr.proutlost.worldprep.gametest.DistinctTickCallback;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistinctTickCallbackTest {
    /** Pinned GameTestInfo order: iterate due entries, run callback, then remove entry. */
    private static final class Scheduler {
        final Object2LongOpenHashMap<Runnable> pending = new Object2LongOpenHashMap<>();
        long tick;
        void later(Runnable task) { pending.put(task, tick + 1); }
        void advance() {
            tick++;
            var iterator = pending.object2LongEntrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getLongValue() <= tick) {
                    entry.getKey().run();
                    iterator.remove();
                }
            }
        }
    }

    @Test void reusedRunnableLosesRescheduleAfterFirstLeaseStep() {
        var scheduler = new Scheduler();
        int[] steps = {0};
        Runnable[] task = {null};
        task[0] = () -> { steps[0]++; scheduler.later(task[0]); };
        scheduler.later(task[0]);
        for (int i=0;i<20;i++) scheduler.advance();
        assertEquals(1, steps[0]);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test void distinctCallbacksProgressAcrossTicksAndStopAtBound() {
        var scheduler = new Scheduler();
        int[] steps = {0};
        Runnable[] task = {null};
        task[0] = () -> {
            steps[0]++;
            if (steps[0] < 30) scheduler.later(DistinctTickCallback.wrap(task[0]));
        };
        assertNotSame(DistinctTickCallback.wrap(task[0]), DistinctTickCallback.wrap(task[0]));
        scheduler.later(DistinctTickCallback.wrap(task[0]));
        for (int i=1;i<=60;i++) {
            scheduler.advance();
            assertEquals(Math.min(i,30), steps[0], "exactly one step per tick until bound");
            assertEquals(i<30?1:0, scheduler.pending.size());
        }
    }
}
