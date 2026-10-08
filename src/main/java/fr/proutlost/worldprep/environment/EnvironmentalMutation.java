package fr.proutlost.worldprep.environment;

import fr.proutlost.worldprep.pipeline.PassId;
import java.util.Objects;
import java.util.UUID;

/** Exact, fully materialized block mutation. No planner is consulted while applying it. */
public record EnvironmentalMutation(PassId pass, int x, int y, int z, String beforeState,
        String afterState, String beforeBlockEntityType, String beforeBlockEntityNbt,
        String afterBlockEntityType, String afterBlockEntityNbt, UUID groupId) {
    /**
     * Compatibility constructor for materializers which only create the AFTER block entity.
     * The absence of BEFORE data is explicit, rather than being inferred while applying.
     */
    public EnvironmentalMutation(PassId pass, int x, int y, int z, String beforeState,
            String afterState, String afterBlockEntityType, String afterBlockEntityNbt, UUID groupId) {
        this(pass, x, y, z, beforeState, afterState, null, null,
                afterBlockEntityType, afterBlockEntityNbt, groupId);
    }
    public EnvironmentalMutation {
        Objects.requireNonNull(pass, "pass");
        requireId(beforeState, "beforeState");
        requireId(afterState, "afterState");
        if (beforeState.equals(afterState)
                && Objects.equals(beforeBlockEntityType, afterBlockEntityType)
                && Objects.equals(beforeBlockEntityNbt, afterBlockEntityNbt)) {
            throw new IllegalArgumentException("Mutation must change state or block entity data");
        }
        requireBlockEntityPair(beforeBlockEntityType, beforeBlockEntityNbt, "before");
        requireBlockEntityPair(afterBlockEntityType, afterBlockEntityNbt, "after");
    }
    public Position position() { return new Position(x, y, z); }
    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf(':') < 1) throw new IllegalArgumentException(name);
    }
    private static void requireBlockEntityPair(String type, String nbt, String side) {
        if ((type == null) != (nbt == null)) throw new IllegalArgumentException(side + " BlockEntity type and NBT must be captured together");
        if (type != null) requireId(type, side + "BlockEntityType");
    }
    public record Position(int x, int y, int z) implements Comparable<Position> {
        @Override public int compareTo(Position other) {
            int c=Integer.compare(x,other.x); if(c==0)c=Integer.compare(z,other.z); return c==0?Integer.compare(y,other.y):c;
        }
    }
}
