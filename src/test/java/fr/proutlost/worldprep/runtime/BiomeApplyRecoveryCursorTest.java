package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BiomeApplyRecoveryCursorTest {
    @TempDir Path temporary;

    @Test void fullNativeSizedPrefixChecksEveryCellBeforeWritesAndSpendsAtMost256Cells() {
        var cursor = new BiomeApplyRecoveryCursor(256);
        int[] checked = {0}, replayed = {0}, calls = {0};
        while (cursor.progress().phase() != BiomeApplyRecoveryCursor.Phase.READY) {
            int used = cursor.advance(256, page -> 96, (page, offset, count, phase) -> {
                if (phase == BiomeApplyRecoveryCursor.Phase.VALIDATE) checked[0] += count;
                else {
                    assertEquals(24576, checked[0], "Entire prefix before the first recovery effect");
                    replayed[0] += count;
                }
            });
            assertTrue(used > 0 && used <= 256);
            calls[0]++;
        }
        assertEquals(24576, checked[0]); assertEquals(24576, replayed[0]);
        assertEquals(192, calls[0]);
        assertEquals(49152, cursor.progress().visitedCells());
        assertEquals(256, cursor.progress().maximumSliceCells());
        assertEquals(0, cursor.advance(256, p -> { throw new AssertionError(); },
            (p,o,n,phase) -> { throw new AssertionError("Completed prefix replayed"); }));
    }

    @Test void subPageBudgetAndZeroBudgetDoNotGrantUncountedWork() {
        var cursor = new BiomeApplyRecoveryCursor(3);
        assertEquals(0, cursor.advance(0, p -> { throw new AssertionError(); },
            (p,o,n,phase) -> { throw new AssertionError(); }));
        int[] visits = new int[2];
        while (cursor.progress().phase() != BiomeApplyRecoveryCursor.Phase.READY) {
            int used = cursor.advance(7, p -> 96,
                (p,o,n,phase) -> visits[phase.ordinal()] += n);
            assertTrue(used <= 7);
        }
        assertArrayEquals(new int[]{288,288}, visits);
        assertTrue(cursor.progress().maximumSliceCells() <= 7);
        assertEquals(BiomeApplyRecoveryCursor.Phase.READY,
            new BiomeApplyRecoveryCursor(0).progress().phase());
    }

    @Test void conflictInTheLastPrefixCellRejectsBeforeAnyRecoveryWrite() {
        var cursor = new BiomeApplyRecoveryCursor(256);
        var writes = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> {
            while (cursor.progress().phase() != BiomeApplyRecoveryCursor.Phase.READY)
                cursor.advance(256, p -> 96, (p,o,n,phase) -> {
                    if (phase == BiomeApplyRecoveryCursor.Phase.REPLAY) writes.addAndGet(n);
                    else if (p == 255 && o + n == 96)
                        throw new IllegalStateException("Foreign biome in last cell");
                });
        });
        assertEquals(0, writes.get());
        assertEquals(BiomeApplyRecoveryCursor.Phase.VALIDATE, cursor.progress().phase());
    }

    @Test void newProcessRechecksAllCellsAndOnlyRestoresStillOwnedUnappliedCells() {
        int cells = 12; int[] actual = new int[cells];
        var effectiveWrites = new AtomicInteger();
        var first = new BiomeApplyRecoveryCursor(3);
        BiomeApplyRecoveryCursor.PageSlice visit = (page, offset, count, phase) -> {
            for (int n=offset; n<offset+count; n++) {
                int index = Math.toIntExact(page)*4+n;
                assertTrue(actual[index] == 0 || actual[index] == 1, "Owned before/post only");
                if (phase == BiomeApplyRecoveryCursor.Phase.REPLAY && actual[index] == 0) {
                    actual[index] = 1; effectiveWrites.incrementAndGet();
                }
            }
        };
        // Lose the process after a durable prefix was only partly applied.
        while (effectiveWrites.get() == 0) first.advance(3, p -> 4, visit);
        assertTrue(effectiveWrites.get() < cells);
        var second = new BiomeApplyRecoveryCursor(3);
        int writesBefore = effectiveWrites.get();
        for (int n=0; n<4; n++) {
            second.advance(3, p -> 4, visit);
            assertEquals(writesBefore, effectiveWrites.get(), "Fresh full preflight has zero effects");
        }
        while (second.progress().phase() != BiomeApplyRecoveryCursor.Phase.READY)
            second.advance(3, p -> 4, visit);
        assertEquals(cells, effectiveWrites.get());
        assertTrue(Arrays.stream(actual).allMatch(v -> v == 1));
    }

    private static final PagedPlanStore.EntryCodec<Integer> CODEC = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out, Integer value) throws IOException { out.writeInt(value); }
        public Integer read(DataInput in) throws IOException { return in.readInt(); }
    };
    private PagedJournalStore.Manifest journal() throws IOException {
        var m = PagedJournalStore.initialize(temporary, PagedJournalStore.empty(
            UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),PassId.BIOMES,
            "minecraft:overworld","bounded-recovery"));
        for (int n=0;n<3;n++) m=PagedJournalStore.append(temporary,m,List.of(n*2,n*2+1),CODEC);
        return m;
    }

    @Test void corruptLastAuthenticatedPageFailsBeforeAnyReplay() throws IOException {
        var manifest = journal();
        Path last = PagedJournalStore.pagePath(temporary,2);
        byte[] raw = Files.readAllBytes(last); raw[raw.length-1] ^= 1; Files.write(last,raw);
        var cursor = new BiomeApplyRecoveryCursor(3);
        var writes = new AtomicInteger();
        assertThrows(UncheckedIOException.class, () -> {
            while (cursor.progress().phase() != BiomeApplyRecoveryCursor.Phase.READY) {
                cursor.advance(2,p -> manifest.pages().get(Math.toIntExact(p)).entryCount(),
                    (p,o,n,phase) -> {
                        try {
                            PagedJournalStore.visitPage(temporary,manifest,p,CODEC,(seq,values) -> {
                                if (phase == BiomeApplyRecoveryCursor.Phase.REPLAY) writes.addAndGet(n);
                            });
                        } catch(IOException e) { throw new UncheckedIOException(e); }
                    });
            }
        });
        assertEquals(0,writes.get());
    }

    @Test void changedManifestCannotUseAnEarlierPageAuthority() throws IOException {
        var manifest = journal();
        PagedJournalStore.append(temporary,manifest,List.of(6,7),CODEC);
        var callbacks = new AtomicInteger();
        assertThrows(IOException.class, () ->
            PagedJournalStore.visitPage(temporary,manifest,0,CODEC,(p,values) -> callbacks.incrementAndGet()));
        assertEquals(0,callbacks.get());
    }
}
