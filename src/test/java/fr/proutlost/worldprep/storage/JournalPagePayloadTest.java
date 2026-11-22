package fr.proutlost.worldprep.storage;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JournalPagePayloadTest {
    @TempDir Path root;
    record Cell(int x, int y, int z, String before, String after) {}
    static final PagedJournalStore.ChunkCodec<Cell> CODEC = new PagedJournalStore.ChunkCodec<>() {
        public int chunkX(Cell c) { return Math.floorDiv(c.x(), 16); }
        public int chunkZ(Cell c) { return Math.floorDiv(c.z(), 16); }
        public void write(DataOutput out, Cell c) throws IOException {
            out.writeInt(c.x()); out.writeInt(c.y()); out.writeInt(c.z()); out.writeUTF(c.before()); out.writeUTF(c.after());
        }
        public Cell read(DataInput in) throws IOException {
            return new Cell(in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF());
        }
    };
    Cell cell(int x, int z) { return new Cell(x, 60, z, "minecraft:stone", "minecraft:deepslate"); }
    PagedJournalStore.Manifest empty() {
        return PagedJournalStore.empty(UUID.randomUUID(), UUID.randomUUID(), PlanFingerprint.of("legacy-compatible-plan").value(),
                PassId.GEOLOGY, "minecraft:overworld", "proutlost:test");
    }

    @Test void compressesRealChunkSectionsAndPreservesNoncontiguousOriginalOrder() throws Exception {
        var cells = new ArrayList<Cell>();
        for (int i = 0; i < 1024; i++) cells.add(new Cell(i % 16 - 16, i / 256, i / 16 % 16 - 16, "minecraft:stone", "minecraft:deepslate"));
        cells.add(cell(16, 0)); cells.add(cell(-1, -1));
        byte[] encoded = JournalPagePayload.encode(cells, CODEC);
        assertTrue(encoded.length < JournalPagePayload.legacy(cells, CODEC).length / 2);
        assertEquals(cells, JournalPagePayload.decode(encoded, cells.size(), CODEC));
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            assertEquals(JournalPagePayload.MAGIC, in.readInt()); assertEquals(1, in.readInt());
            assertEquals(cells.size(), in.readInt()); assertEquals(3, in.readInt());
            assertEquals(-1, in.readInt()); assertEquals(-1, in.readInt()); assertEquals(1024, in.readInt());
        }
        Path dir = root.resolve("chunked"); var manifest = PagedJournalStore.initialize(dir, empty());
        manifest = PagedJournalStore.append(dir, manifest, cells, CODEC);
        assertEquals(1, manifest.pages().size());
        var actual = new ArrayList<Cell>();
        var metrics = PagedJournalStore.visitPage(dir, PagedJournalStore.readManifest(dir), 0, CODEC, (sequence, values) -> actual.addAll(values));
        assertEquals(cells, actual); assertEquals(1, metrics.maximumResidentPages());
    }

    @Test void adoptsIdenticalLegacyOrphanWithoutRewritingAndColdReadsMixedPages() throws Exception {
        Path dir = root.resolve("legacy"); var expected = PagedJournalStore.initialize(dir, empty());
        var old = List.of(cell(-17, -1), cell(1, 16));
        var page = DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL, expected.journalId(), expected.pass(), expected.dimension(), 0, 0, 0,
                old.size(), JournalPagePayload.legacy(old, CODEC));
        Path original = PagedJournalStore.pagePath(dir, 0); DurablePageStore.publish(original, page);
        byte[] bytes = Files.readAllBytes(original);
        var first = PagedJournalStore.append(dir, expected, old, CODEC);
        assertEquals(page.checksum(), first.pages().getFirst().checksum()); assertArrayEquals(bytes, Files.readAllBytes(original));
        var next = List.of(cell(31, 31), cell(-16, -16));
        var second = PagedJournalStore.append(dir, first, next, CODEC);
        var cold = PagedJournalStore.readManifest(dir); assertEquals(second, cold);
        PagedJournalStore.validateAll(dir, cold);
        var observed = new ArrayList<Cell>(); PagedJournalStore.visitPages(dir, cold, CODEC, (sequence, values) -> observed.addAll(values));
        var all = new ArrayList<>(old); all.addAll(next); assertEquals(all, observed);
        assertArrayEquals(bytes, Files.readAllBytes(original));
        assertFalse(JournalPagePayload.isLegacy(DurablePageStore.read(PagedJournalStore.pagePath(dir, 1)).payload()));
    }

    @Test void unsupportedTruncatedTrailingWrongChunkAndInflationOverflowFailClosed() throws Exception {
        var values = List.of(cell(1, 2)); byte[] valid = JournalPagePayload.encode(values, CODEC);
        byte[] future = valid.clone(); ByteBuffer.wrap(future).putInt(4, 2);
        assertThrows(IOException.class, () -> JournalPagePayload.validate(future, 1));
        assertThrows(IOException.class, () -> JournalPagePayload.decode(future, 1, CODEC));
        assertThrows(IOException.class, () -> JournalPagePayload.decode(Arrays.copyOf(valid, valid.length - 1), 1, CODEC));
        assertThrows(IOException.class, () -> JournalPagePayload.decode(Arrays.copyOf(valid, valid.length + 1), 1, CODEC));
        byte[] wrongChunk = valid.clone(); ByteBuffer.wrap(wrongChunk).putInt(16, -999);
        assertThrows(IOException.class, () -> JournalPagePayload.decode(wrongChunk, 1, CODEC));
        var compressed = new ByteArrayOutputStream();
        try (var z = new DeflaterOutputStream(compressed)) { z.write(new byte[8192]); }
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(JournalPagePayload.MAGIC); out.writeInt(1); out.writeInt(1); out.writeInt(1);
            out.writeInt(0); out.writeInt(0); out.writeInt(1); out.writeInt(1);
            out.writeInt(compressed.size()); out.write(compressed.toByteArray());
        }
        assertThrows(IOException.class, () -> JournalPagePayload.decode(bytes.toByteArray(), 1, CODEC));
    }

    @Test void failedEncodingLeavesManifestAndOwnershipPrefixUntouched() throws Exception {
        Path dir = root.resolve("failed"); var manifest = PagedJournalStore.initialize(dir, empty());
        byte[] before = Files.readAllBytes(PagedJournalStore.manifestPath(dir));
        var broken = new PagedJournalStore.ChunkCodec<Cell>() {
            public int chunkX(Cell c) { return CODEC.chunkX(c); }
            public int chunkZ(Cell c) { return CODEC.chunkZ(c); }
            public void write(DataOutput out, Cell c) throws IOException {
                out.writeInt(c.x()); if (c.x() == 999) throw new IOException("injected writer interruption"); CODEC.write(out, c);
            }
            public Cell read(DataInput in) throws IOException { return CODEC.read(in); }
        };
        assertThrows(IOException.class, () -> PagedJournalStore.append(dir, manifest, List.of(cell(1, 2), cell(999, 2)), broken));
        assertArrayEquals(before, Files.readAllBytes(PagedJournalStore.manifestPath(dir)));
        assertFalse(Files.exists(PagedJournalStore.pagePath(dir, 0)));
    }

    @Test void legacyOrphanWithDifferentHeaderCannotGainOwnership() throws Exception {
        Path dir = root.resolve("foreign-header"); var expected = PagedJournalStore.initialize(dir, empty());
        var values = List.of(cell(1, 2));
        var page = DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL, expected.journalId(), expected.pass(), expected.dimension(),
                999, 0, 0, values.size(), JournalPagePayload.legacy(values, CODEC));
        DurablePageStore.publish(PagedJournalStore.pagePath(dir, 0), page);
        assertThrows(IOException.class, () -> PagedJournalStore.append(dir, expected, values, CODEC));
        assertEquals(expected, PagedJournalStore.readManifest(dir));
    }
}
