package fr.proutlost.director.persistence;

import java.nio.file.*;
import static fr.proutlost.director.persistence.MetadataAttemptResumeFixture.*;

/** Pre-correction reproduction. A seed JVM halts after genuine durable source ACKs. */
public final class MetadataAttemptClockCounterexample {
    private MetadataAttemptClockCounterexample(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("seed-crash|resume-refusal PRIVATE_ROOT PROFILE");
        var root=Path.of(args[1]).toAbsolutePath();var profile=Path.of(args[2]).toAbsolutePath();
        if(args[0].equals("seed-crash")){Files.createDirectory(root);seedSourceCrash(root.resolve("store"));return;}
        if(!args[0].equals("resume-refusal"))throw new IllegalArgumentException("phase");
        var store=open(root.resolve("store"));try{
            oldActive(store);visit(store,profile,0,100561);visit(store,profile,99,110663);staleClockRefused(store);oldActive(store);
            System.out.println("COUNTEREXAMPLE_CONFIRMED immutableClock=88593 actualRegionAndMemoryTicks=100561,110663 sameSourceRevision=2 actualJ04=true realMemoryReducer=true assertions="+assertions);
            throw new AssertionError("SAME_EXPORT_HAS_NO_LEGACY_RECOVERY_PATH_AFTER_REAL_J01_VISITS");
        }finally{close(store);}
    }
}
