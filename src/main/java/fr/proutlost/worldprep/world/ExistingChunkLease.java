package fr.proutlost.worldprep.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, synchronous coordinator. The adapter must pin each EMPTY read until close.
 * No FULL request is allowed until all nine persisted chunks have been verified.
 * This class never owns a Level and can be tested without a Minecraft server.
 */
public final class ExistingChunkLease implements AutoCloseable {
    public record Position(int x, int z) {}
    public interface Port {
        boolean loadAndPinExistingFull(Position position);
        void requestFull(Position center);
        boolean isFullReady(Position center);
        void release(Position center, List<Position> pinned);
    }
    private final Position center;
    private final Port port;
    private final List<Position> pinned = new ArrayList<>(9);
    private int next;
    private int waits;
    private boolean promoted;
    private boolean closed;

    public ExistingChunkLease(int x, int z, Port port) {
        if(x<=Integer.MIN_VALUE || x>=Integer.MAX_VALUE || z<=Integer.MIN_VALUE || z>=Integer.MAX_VALUE)
            throw new IllegalArgumentException("Chunk coordinate overflow");
        this.center=new Position(x,z);this.port=java.util.Objects.requireNonNull(port);
    }

    /** At most one disk load per call. Promotion and polling happen on subsequent calls. */
    public boolean step() {
        if(closed)throw new IllegalStateException("Lease closed");
        try {
            if(next<9) {
                var pos=new Position(center.x()+next%3-1,center.z()+next/3-1);
                pinned.add(pos); // track even a partially failed adapter call, for cleanup
                if(!port.loadAndPinExistingFull(pos))throw new IllegalStateException("Existing FULL chunk required: "+pos);
                next++;
                return false;
            }
            if(!promoted) { port.requestFull(center);promoted=true;return false; }
            if(port.isFullReady(center))return true;
            if(++waits>200)throw new IllegalStateException("FULL promotion timed out");
            return false;
        } catch(RuntimeException failure) {
            close();
            throw failure;
        }
    }
    @Override public void close() {
        if(!closed) { closed=true;port.release(center,List.copyOf(pinned)); }
    }
}
