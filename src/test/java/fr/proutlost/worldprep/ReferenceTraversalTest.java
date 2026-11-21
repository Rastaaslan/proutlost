package fr.proutlost.worldprep;

import fr.proutlost.worldprep.recovery.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceTraversalTest {
    static class CountedIndex extends TreeMap<Integer,UUID> {
        int reads;
        @Override public Map.Entry<Integer,UUID> firstEntry() { reads++; return super.firstEntry(); }
        @Override public Map.Entry<Integer,UUID> higherEntry(Integer key) { reads++; return super.higherEntry(key); }
        @Override public Set<Map.Entry<Integer,UUID>> entrySet() { throw new AssertionError("Full history traversal"); }
        @Override public Collection<UUID> values() { throw new AssertionError("Full history allocation"); }
    }
    ReferenceTraversal.Source source(CountedIndex index) {
        return ReferenceTraversal.source(index,Integer::valueOf,Object::toString,
            e->e.getValue()==null?null:new ReferenceInventory.Reference("plans",e.getValue()));
    }
    @Test void largeHistoryVisitsEachEntryOnceWithinPerPageBudget() {
        var index=new CountedIndex();
        for(int i=0;i<100_000;i++)index.put(i,new UUID(0,i));
        String cursor="start";
        int seen=0,pages=0;
        while(!cursor.equals("DONE")) {
            int before=index.reads;
            var page=ReferenceTraversal.page(List.of(source(index)),cursor);
            assertTrue(index.reads-before<=65);
            assertTrue(page.examined()<=64);
            for(var ref:page.references())assertEquals(new UUID(0,seen++),ref.id());
            cursor=page.next();pages++;
        }
        assertEquals(100_000,seen);
        assertEquals(1563,pages);
        assertEquals(100_001,index.reads);
    }
    @Test void nullPlanIdsConsumeBudgetRatherThanScanningEntireHistory() {
        var index=new CountedIndex();for(int i=0;i<1000;i++)index.put(i,null);
        var page=ReferenceTraversal.page(List.of(source(index)),"start");
        assertEquals(64,page.examined());assertEquals(64,index.reads);
        assertTrue(page.references().isEmpty());assertNotEquals("DONE",page.next());
    }
    @Test void cursorSurvivesDeletionWithoutOffsetSkipAndContinuesAcrossIndexes() {
        var a=new CountedIndex();var b=new CountedIndex();
        for(int i=0;i<70;i++)a.put(i,new UUID(0,i));
        b.put(0,new UUID(1,0));
        var sources=List.of(source(a),source(b));
        var first=ReferenceTraversal.page(sources,"0");
        a.remove(0);a.remove(63);
        var second=ReferenceTraversal.page(sources,first.next());
        assertEquals(7,second.examined());assertEquals(new UUID(0,64),second.references().getFirst().id());
        assertEquals(new UUID(1,0),second.references().getLast().id());assertEquals("DONE",second.next());
    }
    @Test void emptyIndexesFinishWithoutHistoryAllocation() {
        var index=new CountedIndex();
        var page=ReferenceTraversal.page(List.of(source(index),source(index),source(index),source(index)),"start");
        assertEquals("DONE",page.next());assertEquals(0,page.examined());assertEquals(4,index.reads);
    }
    @Test void malformedAndOversizedCursorRejected() {
        var sources=List.of(source(new CountedIndex()));
        for(String cursor:List.of("junk","-1.","4.","0.!","x".repeat(4097)))
            assertThrows(IllegalArgumentException.class,()->ReferenceTraversal.page(sources,cursor));
    }
}
