package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** RUN116: cache correctness, mutable records and captured-root isolation. */
class Run116CachedBlockTagParityTest {
    private static final String FLAG="proutlost.run116.cachedBlockNbt";

    @Test void savedBlockTagsRemainExactlyCanonicalUnderMutation() {
        String previous=System.getProperty(FLAG);
        try {
            System.setProperty(FLAG,"true");
            var optimized=new BlockWorldPrepData();
            System.setProperty(FLAG,"false");
            var original=new BlockWorldPrepData();
            var id=UUID.fromString("ebdaa993-8a2c-4b8b-86be-cf15980f8f45");
            var plan=new BlockWorldPrepData.Plan("area","TREES","profile","input","fingerprint");
            plan.planId(id);plan.entryCount(23);plan.eligible(17);plan.skipped(5);
            var journal=new BlockWorldPrepData.Journal(
                id,UUID.randomUUID(),id,"area","TREES","fingerprint");
            journal.pageCount(1);journal.entryCount(17);journal.journalRoot("old-hash");
            original.plans().put("area|TREES",plan);
            optimized.plans().put("area|TREES",plan);
            original.journals().put(id,journal);
            optimized.journals().put(id,journal);
            for(int repeat=0;repeat<3;repeat++) {
                var prior=original.save(new CompoundTag(),null);
                var cached=optimized.save(new CompoundTag(),null);
                assertEquals(prior,cached,"Cache must preserve exact NBT order and bytes");
            }
            var isolated=optimized.save(new CompoundTag(),null);
            isolated.getList("plans",Tag.TAG_COMPOUND).getCompound(0)
                .putString("fingerprint","POISONED_RETURNED_NBT");
            assertEquals(original.save(new CompoundTag(),null),
                optimized.save(new CompoundTag(),null),
                "Caller must not mutate immutable checkpoint cache");
            plan.fingerprint("edited-fingerprint");plan.eligible(999);
            plan.upstreamRoot("changed-upstream");plan.upstreamPlanId(UUID.randomUUID());
            plan.entryCount(903);plan.skipped(0);
            journal.pageCount(17);journal.entryCount(903);journal.journalRoot("changed-root");
            assertEquals(original.save(new CompoundTag(),null),
                optimized.save(new CompoundTag(),null),
                "All in-place mutable Plan/Journal fields must invalidate cache");
            var reread=BlockWorldPrepData.load(optimized.save(new CompoundTag(),null),null);
            assertTrue(reread.compatible());
            assertEquals(optimized.save(new CompoundTag(),null),
                reread.save(new CompoundTag(),null),"Cold reopened checkpoint parity");
            optimized.plans().remove("area|TREES");
            original.plans().remove("area|TREES");
            optimized.journals().remove(id);
            original.journals().remove(id);
            assertEquals(original.save(new CompoundTag(),null),
                optimized.save(new CompoundTag(),null),
                "Removal must not leak cached historical record");
        } finally {
            if(previous==null)System.clearProperty(FLAG);
            else System.setProperty(FLAG,previous);
        }
    }
    @Test void planFirstBecomesPersistableAfterIdAssignment() {
        String previous=System.getProperty(FLAG);
        try {
            System.setProperty(FLAG,"true");
            var blocks=new BlockWorldPrepData();
            var p=new BlockWorldPrepData.Plan("area","AQUATIC","profile","input","fp");
            blocks.plans().put("area|AQUATIC",p);
            assertTrue(blocks.save(new CompoundTag(),null).getList("plans",Tag.TAG_COMPOUND).isEmpty());
            p.planId(UUID.randomUUID());
            assertEquals(1,blocks.save(new CompoundTag(),null).getList("plans",Tag.TAG_COMPOUND).size());
        } finally {
            if(previous==null)System.clearProperty(FLAG);
            else System.setProperty(FLAG,previous);
        }
    }
}
