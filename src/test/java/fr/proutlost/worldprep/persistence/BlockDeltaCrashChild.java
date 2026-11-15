package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import java.nio.file.Path;

/** Test-only JVM: abrupt process death at actual native publisher boundaries. */
public final class BlockDeltaCrashChild {
    private static final class CrashingCheckpoint extends AcknowledgedSavedData {
        private final CompoundTag capture;
        private final String phase;
        CrashingCheckpoint(CompoundTag capture,String phase){this.capture=capture;this.phase=phase;setDirty();}
        @Override public CompoundTag save(CompoundTag ignored,net.minecraft.core.HolderLookup.Provider registries) {
            return capture.copy();
        }
        @Override protected void persistenceBoundary(String current,Path target) {
            if(current.equals(phase))Runtime.getRuntime().halt(73);
        }
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        Path file=Path.of(args[0]);
        try(var scope=new BlockDeltaDurabilityTest.Scope(file.getParent().getParent())) {
            var data=BlockDeltaDurabilityTest.load(file);
            data.plans().firstEntry().getValue().entryCount(777);
            var checkpoint=new CrashingCheckpoint(data.save(new CompoundTag(),null),args[1]);
            checkpoint.save(file.toFile(),null);
            checkpoint.flushNativeSave();
            throw new AssertionError("Crash boundary was not reached");
        }
    }
}
