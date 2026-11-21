package fr.proutlost.compat.mixin;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The asynchronous completion clears UnionLib's future between two field reads.
 * Preserve its scheduler: a task cleared after completion is ready for polling. */
@Pseudo
@Mixin(targets="com.stereowalker.unionlib.supporter.Cosmetics",remap=false)
public abstract class UnionLibCosmeticsMixin {
    @Redirect(method="pollCosmetics()V",
        at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;isDone()Z",remap=false),
        remap=false,require=1,expect=1,allow=1)
    private static boolean proutlost$completedCosmeticsPoll(CompletableFuture<?> pending) {
        return pending==null || pending.isDone();
    }
}
