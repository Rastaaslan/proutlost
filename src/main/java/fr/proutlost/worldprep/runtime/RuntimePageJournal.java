package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.DurablePageStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Durable disk page publication used by the live block executor before mutation. */
public final class RuntimePageJournal {
    public static DurablePageStore.Page publish(ServerLevel level, BlockWorldPrepData.Journal journal,
            long sequence, List<BlockWorldPrepData.Change> changes) {
        if (changes.isEmpty()) throw new IllegalArgumentException("Empty journal page");
        try {
            byte[] payload = encode(changes);
            var first = changes.getFirst();
            var page = DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL, journal.id,
                    PassId.valueOf(journal.pass), level.dimension().location().toString(),
                    first.x() >> 4, first.z() >> 4, sequence, changes.size(), payload);
            Path target = pagePath(level, journal.id, sequence);
            if (Files.exists(target)) {
                var existing = DurablePageStore.read(target);
                if (!existing.checksum().equals(page.checksum())) {
                    throw new IOException("Published journal page conflicts with planned batch");
                }
                return existing;
            }
            DurablePageStore.publish(target, page);
            return DurablePageStore.read(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Durable journal publication failed", exception);
        }
    }

    public static void requirePage(ServerLevel level, BlockWorldPrepData.Journal journal, long sequence) {
        String expected = journal.pages.get(sequence);
        if (expected == null) throw new IllegalStateException("Missing journal page reference " + sequence);
        try {
            var page = DurablePageStore.read(pagePath(level, journal.id, sequence));
            if (!page.ownerId().equals(journal.id) || page.sequence() != sequence || !page.checksum().equals(expected)) {
                throw new IOException("Journal page identity mismatch");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Journal page validation failed", exception);
        }
    }

    public static Path pagePath(ServerLevel level, java.util.UUID journalId, long sequence) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2").resolve("journals")
                .resolve(journalId.toString()).resolve(String.format("%020d.wpp", sequence));
    }

    private static byte[] encode(List<BlockWorldPrepData.Change> changes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(changes.size());
            for (var change : changes) {
                out.writeInt(change.x()); out.writeInt(change.y()); out.writeInt(change.z());
                out.writeUTF(change.before()); out.writeUTF(change.applied()); out.writeUTF(change.province());
            }
        }
        return bytes.toByteArray();
    }

    private RuntimePageJournal() {}
}
