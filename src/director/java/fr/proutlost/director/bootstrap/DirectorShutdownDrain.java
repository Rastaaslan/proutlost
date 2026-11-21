package fr.proutlost.director.bootstrap;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shutdown-only bounded acknowledgement drain. Never used by normal ticks.
 * Completion includes exceptional completion; callers retain the original
 * future and must not turn this boolean into a successful durability claim. */
public final class DirectorShutdownDrain {
    private DirectorShutdownDrain() {}

    public static boolean await(CompletionStage<?> close,long timeoutNanos,
            Consumer<BooleanSupplier> managedBlock) {
        return await(close,timeoutNanos,managedBlock,System::nanoTime);
    }

    static boolean await(CompletionStage<?> close,long timeoutNanos,
            Consumer<BooleanSupplier> managedBlock,LongSupplier clock) {
        Objects.requireNonNull(close);Objects.requireNonNull(managedBlock);Objects.requireNonNull(clock);
        if(timeoutNanos<=0 || timeoutNanos>TimeUnit.MINUTES.toNanos(1))
            throw new IllegalArgumentException("bounded shutdown timeout required");
        var completion=close.toCompletableFuture();
        long started=clock.getAsLong();
        managedBlock.accept(()->completion.isDone() || clock.getAsLong()-started>=timeoutNanos);
        return completion.isDone();
    }
}
