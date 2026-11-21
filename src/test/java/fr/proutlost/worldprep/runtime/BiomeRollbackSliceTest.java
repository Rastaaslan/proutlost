package fr.proutlost.worldprep.runtime;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeRollbackSliceTest {
    private static final class Cell {
        final String before, applied;
        String current;
        int writes;
        Cell(String before,String applied) { this.before=before;this.applied=applied;current=applied; }
        void restore() { current=before;writes++; }
    }

    @Test void mixed1536CellJournalConvergesAcross256WriteSlicesWithoutReplaying1152Noops() {
        var cells=new ArrayList<Cell>();
        for(int n=0;n<1152;n++)cells.add(new Cell("plains","plains"));
        for(int n=0;n<384;n++)cells.add(new Cell("plains","forest"));
        long legacyInflatedCursor=682496;
        int slices=0;
        BiomeRollbackSlice last;
        do {
            last=new BiomeRollbackSlice(256);
            for(var cell:cells)last.reconcileCell(cell.before,cell.applied,cell.current,cell::restore);
            assertTrue(last.writes()<=256);
            // Actual authenticated cell state replaces an untrustworthy historical work counter.
            legacyInflatedCursor=last.reconciledCells();
            assertTrue(legacyInflatedCursor<=1536);
            assertTrue(++slices<=2,"No-op prefix must not starve later actual changes");
        } while(last.pending());
        assertEquals(2,slices);assertEquals(1536,legacyInflatedCursor);
        assertTrue(cells.stream().allMatch(c->c.current.equals(c.before)));
        assertEquals(0,cells.subList(0,1152).stream().mapToInt(c->c.writes).sum());
        assertEquals(384,cells.stream().mapToInt(c->c.writes).sum());
        var repeated=new BiomeRollbackSlice(256);
        for(var c:cells)repeated.reconcileCell(c.before,c.applied,c.current,c::restore);
        assertFalse(repeated.pending());assertEquals(0,repeated.writes());assertEquals(1536,repeated.reconciledCells());
    }

    @Test void alreadyRestoredCellsDoNotConsumeSmallBatchBudget() {
        var slice=new BiomeRollbackSlice(1);
        var a=new Cell("old","new");a.current="old";
        var b=new Cell("old","new");var c=new Cell("old","new");
        for(var x:new Cell[]{a,b,c})slice.reconcileCell(x.before,x.applied,x.current,x::restore);
        assertEquals(0,a.writes);assertEquals(1,b.writes);assertEquals(0,c.writes);
        assertEquals(1,slice.writes());assertEquals(2,slice.reconciledCells());assertTrue(slice.pending());
    }

    @Test void foreignBiomeIsRejectedBeforeAnyWriteOrProgressCredit() {
        var slice=new BiomeRollbackSlice(256);var writes=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->slice.reconcileCell("old","applied","player-edit",writes::incrementAndGet));
        assertEquals(0,writes.get());assertEquals(0,slice.writes());assertEquals(0,slice.reconciledCells());
    }

    @Test void restartAfterWriteBeforeCheckpointDoesNotRepeatTheWorldWrite() {
        var actual=new AtomicReference<>("applied");var writes=new AtomicInteger();var first=new BiomeRollbackSlice(256);
        assertThrows(IllegalStateException.class,()->first.reconcileCell("old","applied",actual.get(),()->{
            actual.set("old");writes.incrementAndGet();throw new IllegalStateException("checkpoint interrupted");
        }));
        var recovered=new BiomeRollbackSlice(256);
        recovered.reconcileCell("old","applied",actual.get(),()->{writes.incrementAndGet();actual.set("old");});
        assertEquals("old",actual.get());assertEquals(1,writes.get());
        assertEquals(0,recovered.writes());assertEquals(1,recovered.reconciledCells());assertFalse(recovered.pending());
    }
}
