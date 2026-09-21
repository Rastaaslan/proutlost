package fr.proutlost.worldprep.environment;

import fr.proutlost.worldprep.pipeline.PassId;
import java.util.Objects;
import java.util.UUID;

/** Exact, fully materialized block mutation. No planner is consulted while applying it. */
public record EnvironmentalMutation(PassId pass, int x, int y, int z, String beforeState,
        String afterState, String blockEntityType, String blockEntityNbt, UUID groupId) {
    public EnvironmentalMutation {
        Objects.requireNonNull(pass, "pass");
        requireId(beforeState, "beforeState");
        requireId(afterState, "afterState");
        if (beforeState.equals(afterState) && Objects.equals(blockEntityType, blockEntityNbt)) {
            throw new IllegalArgumentException("Mutation must change state or block entity data");
        }
        if ((blockEntityType == null) != (blockEntityNbt == null)) {
            throw new IllegalArgumentException("Block entity type and NBT must be captured together");
        }
    }
    public Position position() { return new Position(x, y, z); }
    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf(':') < 1) throw new IllegalArgumentException(name);
    }
    public record Position(int x, int y, int z) implements Comparable<Position> {
        @Override public int compareTo(Position other) {
            int c=Integer.compare(x,other.x); if(c==0)c=Integer.compare(z,other.z); return c==0?Integer.compare(y,other.y):c;
        }
    }
}
