package fr.proutlost.compat.mixin;
import java.lang.invoke.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UnionLibCosmeticsMixinTest {
    private static final MethodHandle READY;
    static {
        try {
            READY=MethodHandles.privateLookupIn(UnionLibCosmeticsMixin.class,MethodHandles.lookup())
                .findStatic(UnionLibCosmeticsMixin.class,"proutlost$completedCosmeticsPoll",
                    MethodType.methodType(boolean.class,CompletableFuture.class));
        } catch(ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static boolean ready(CompletableFuture<?> pending)throws Throwable {
        return (boolean)READY.invokeExact(pending);
    }
    @Test void completionBetweenInstalledFieldReadsDoesNotCrash()throws Throwable {
        var future=new CompletableFuture<Void>();
        var slot=new AtomicReference<CompletableFuture<?>>(future);
        future.whenComplete((value,failure)->slot.set(null));
        assertNotNull(slot.get());
        future.complete(null); // Deterministic completion between the installed two field reads.
        var second=slot.get();assertNull(second);
        assertThrows(NullPointerException.class,()->second.isDone());
        assertTrue(ready(second));
    }
    @Test void unfinishedTaskStillPreventsAnotherPollWithoutMutatingIt()throws Throwable {
        var pending=new CompletableFuture<Void>();
        assertFalse(ready(pending));assertFalse(pending.isDone());assertFalse(pending.isCancelled());
    }
    @Test void completedFailedAndCancelledFuturesKeepOriginalSemantics()throws Throwable {
        var done=CompletableFuture.completedFuture("ok");
        var failed=new CompletableFuture<Void>();failed.completeExceptionally(new IllegalStateException("fixture"));
        var cancelled=new CompletableFuture<Void>();cancelled.cancel(false);
        for(var future:List.of(done,failed,cancelled))assertEquals(future.isDone(),ready(future));
        assertEquals("ok",done.join());assertTrue(failed.isCompletedExceptionally());assertTrue(cancelled.isCancelled());
    }
}
