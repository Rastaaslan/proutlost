package fr.proutlost.worldprep.world;

import java.util.*;
import java.util.function.Function;

/** Explicit owner, independent of SavedData job cleanup. At most nine centers / 81 halo pins.
 * Each advance calls at most ONE lease step; changing footprint releases the previous set first.
 */
public final class VerifiedChunkWindow implements AutoCloseable {
    private final Function<ExistingChunkLease.Position,ExistingChunkLease> factory;
    private List<ExistingChunkLease.Position> target=List.of();
    private final List<ExistingChunkLease> leases=new ArrayList<>();
    private int cursor;private boolean closed;
    public VerifiedChunkWindow(Function<ExistingChunkLease.Position,ExistingChunkLease> factory){this.factory=Objects.requireNonNull(factory);}
    public boolean advance(List<ExistingChunkLease.Position> requested) {
        if(closed)throw new IllegalStateException("Window closed");
        try {
            if(requested.isEmpty()||requested.size()>9||new HashSet<>(requested).size()!=requested.size())throw new IllegalArgumentException("REFUSED: window requires 1..9 unique centers");
            var sorted=requested.stream().sorted(Comparator.comparingInt(ExistingChunkLease.Position::x).thenComparingInt(ExistingChunkLease.Position::z)).toList();
            int minX=sorted.stream().mapToInt(ExistingChunkLease.Position::x).min().orElseThrow(),maxX=sorted.stream().mapToInt(ExistingChunkLease.Position::x).max().orElseThrow();
            int minZ=sorted.stream().mapToInt(ExistingChunkLease.Position::z).min().orElseThrow(),maxZ=sorted.stream().mapToInt(ExistingChunkLease.Position::z).max().orElseThrow();
            if((long)maxX-minX>2||(long)maxZ-minZ>2)throw new IllegalArgumentException("REFUSED: footprint exceeds 3x3 centers");
            if(!target.equals(sorted)){release();target=sorted;}
            if(cursor==target.size())return true;
            if(leases.size()==cursor)leases.add(factory.apply(target.get(cursor)));
            if(leases.get(cursor).step())cursor++;
            return false; // work starts on a later tick, even after the last promotion
        } catch(RuntimeException e){try{close();}catch(RuntimeException cleanup){e.addSuppressed(cleanup);}throw e;}
    }
    public int ownedLeases(){return leases.size();}
    public void release(){
        RuntimeException failure=null;
        for(var lease:leases)try{lease.close();}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        leases.clear();target=List.of();cursor=0;
        if(failure!=null)throw failure;
    }
    public void close(){closed=true;release();}
}
