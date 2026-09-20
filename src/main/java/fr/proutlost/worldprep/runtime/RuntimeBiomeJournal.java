package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.DurablePageStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Durable, checksummed biome ownership pages. Publication precedes every palette mutation. */
public final class RuntimeBiomeJournal {
    public static List<WorldPrepSavedData.SnapshotCell> publishOrRead(ServerLevel level,
            WorldPrepSavedData.Snapshot snapshot, long sequence, int quartX, int quartZ,
            List<WorldPrepSavedData.SnapshotCell> proposed) {
        Path target = pagePath(level, snapshot.id, sequence);
        try {
            if (Files.exists(target)) return validateAndDecode(level, snapshot, sequence, quartX, quartZ);
            byte[] payload = encode(proposed);
            var page = DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL, snapshot.id,
                    PassId.BIOMES, snapshot.dimension, QuartPosHelper.chunk(quartX), QuartPosHelper.chunk(quartZ),
                    sequence, proposed.size(), payload);
            DurablePageStore.publish(target, page);
            var durable = DurablePageStore.read(target);
            snapshot.pages.put(sequence, durable.checksum());
            return decode(durable);
        } catch (IOException exception) {
            throw new IllegalStateException("Durable biome journal publication failed", exception);
        }
    }

    public static void requireAll(ServerLevel level, WorldPrepSavedData.Snapshot snapshot) {
        if (!snapshot.cells.isEmpty() && snapshot.pages.isEmpty()) {
            throw new IllegalStateException("Legacy biome snapshot has no durable journal pages");
        }
        for (var reference : snapshot.pages.entrySet()) {
            try {
                var page = DurablePageStore.read(pagePath(level, snapshot.id, reference.getKey()));
                if (page.storageKind() != DurablePageStore.StorageKind.JOURNAL
                        || !page.ownerId().equals(snapshot.id) || page.passId() != PassId.BIOMES
                        || !page.dimension().equals(snapshot.dimension) || page.sequence() != reference.getKey()
                        || !page.checksum().equals(reference.getValue())) throw new IOException("Biome page identity mismatch");
                decode(page);
            } catch (IOException exception) {
                throw new IllegalStateException("Biome journal page validation failed", exception);
            }
        }
    }

    private static List<WorldPrepSavedData.SnapshotCell> validateAndDecode(ServerLevel level,
            WorldPrepSavedData.Snapshot snapshot, long sequence, int quartX, int quartZ) throws IOException {
        var page = DurablePageStore.read(pagePath(level, snapshot.id, sequence));
        String expected = snapshot.pages.get(sequence);
        if (expected == null || page.storageKind() != DurablePageStore.StorageKind.JOURNAL
                || !page.ownerId().equals(snapshot.id) || page.passId() != PassId.BIOMES
                || !page.dimension().equals(snapshot.dimension) || page.sequence() != sequence
                || page.chunkX() != QuartPosHelper.chunk(quartX) || page.chunkZ() != QuartPosHelper.chunk(quartZ)
                || !page.checksum().equals(expected)) throw new IOException("Biome page identity mismatch");
        return decode(page);
    }

    private static byte[] encode(List<WorldPrepSavedData.SnapshotCell> cells) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(cells.size());
            for (var cell : cells) { out.writeInt(cell.quartX()); out.writeInt(cell.quartY()); out.writeInt(cell.quartZ()); out.writeUTF(cell.beforeBiome()); out.writeUTF(cell.appliedBiome()); }
        }
        return bytes.toByteArray();
    }

    private static List<WorldPrepSavedData.SnapshotCell> decode(DurablePageStore.Page page) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(page.payload()))) {
            int count = in.readInt();
            if (count != page.entryCount()) throw new IOException("Biome page entry count mismatch");
            var result = new ArrayList<WorldPrepSavedData.SnapshotCell>(count);
            for (int i=0;i<count;i++) result.add(new WorldPrepSavedData.SnapshotCell(in.readInt(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF()));
            if (in.read()!=-1) throw new IOException("Trailing biome page payload");
            return result;
        }
    }

    public static Path pagePath(ServerLevel level, java.util.UUID snapshotId, long sequence) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2").resolve("biome-journals")
                .resolve(snapshotId.toString()).resolve(String.format("%020d.wpp",sequence));
    }

    private static final class QuartPosHelper { static int chunk(int quart) { return (quart << 2) >> 4; } }
    private RuntimeBiomeJournal() {}
}
