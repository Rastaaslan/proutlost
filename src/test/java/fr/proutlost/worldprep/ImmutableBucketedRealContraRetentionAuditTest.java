package fr.proutlost.worldprep;

import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtCheckpoint;
import fr.proutlost.worldprep.persistence.ImmutableBucketedNbtRetentionPlan;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableBucketedRealContraRetentionAuditTest {
    private static final Path ROOT=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027")
            .resolve("contra-run20-real-immutable-nbt-bench-run4-20261028");

    private static void verify(String store)throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("contra.run20.realBench"));
        Path data=ROOT.resolve(store).resolve("data");
        Path current=data.resolve(store+".dat");
        Path prior=data.resolve("previous-verified-index.dat");
        assertTrue(Files.isRegularFile(current) && Files.isRegularFile(prior));
        var newRoot=NbtIo.readCompressed(current,NbtAccounter.unlimitedHeap());
        var oldRoot=NbtIo.readCompressed(prior,NbtAccounter.unlimitedHeap());
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(newRoot));
        assertTrue(ImmutableBucketedNbtCheckpoint.isPaged(oldRoot));
        var plan=ImmutableBucketedNbtRetentionPlan.inspect(newRoot,oldRoot,current,store);
        assertEquals(plan.allPages(),plan.reachableFromEitherRoot(),
                "The two complete verified roots must pin every actual page");
        assertEquals(0,plan.detachedPages(),
                "A page from the old or current state must NEVER be collected");
        assertEquals(0L,plan.detachedCompressedBytes());
        assertTrue(plan.explicitlyDryRun());
        System.out.println("RUN20_REAL_CONTRA_TWO_GENERATION_RETENTION_AUDITED"+
                " store="+store+" pages="+plan.allPages()+
                " pinned="+plan.reachableFromEitherRoot()+
                " eligibleForPurge=0 oldAndNewRootBothLossless=true"+
                " noPageDeletionPerformed=true");
    }

    @Test void exactFrozenWorldPrepGenerationsRemainFullyReachable()throws Exception{
        verify("proutlost_worldprep");
    }
    @Test void exactFrozenBlockGenerationsRemainFullyReachable()throws Exception{
        verify("proutlost_worldprep_blocks");
    }
}
