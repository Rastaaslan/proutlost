package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.environment.EnvironmentalMutation;
import fr.proutlost.worldprep.environment.EnvironmentalMutationCodec;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Codecs and canonical location for the production immutable plan pages. */
public final class RuntimePlanStore {
    public static final int ENTRIES_PER_PAGE = 256;
    public static final String ENGINE_VERSION = "worldprep-v2";
    public static final String PLANNER_VERSION = "paged-plan-1";

    public static final PagedPlanStore.EntryCodec<WorldPrepSavedData.BiomeCell> BIOME_CODEC = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out, WorldPrepSavedData.BiomeCell value) throws IOException {
            out.writeInt(value.quartX()); out.writeInt(value.quartZ()); out.writeUTF(value.biome()); out.writeLong(value.terrainSignature());
        }
        public WorldPrepSavedData.BiomeCell read(DataInput in) throws IOException {
            return new WorldPrepSavedData.BiomeCell(in.readInt(), in.readInt(), in.readUTF(), in.readLong());
        }
    };
    public static final PagedPlanStore.EntryCodec<BlockWorldPrepData.Change> BLOCK_CODEC = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out, BlockWorldPrepData.Change value) throws IOException {
            out.writeInt(value.x()); out.writeInt(value.y()); out.writeInt(value.z());
            out.writeUTF(value.before()); out.writeUTF(value.applied()); out.writeUTF(value.province());
        }
        public BlockWorldPrepData.Change read(DataInput in) throws IOException {
            return new BlockWorldPrepData.Change(in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readUTF());
        }
    };
    public static final PagedPlanStore.EntryCodec<EnvironmentalMutation> ENVIRONMENTAL_CODEC = EnvironmentalMutationCodec.INSTANCE;

    public static Path root(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2").resolve("plans");
    }
    public static Path directory(ServerLevel level, UUID planId) { return root(level).resolve(planId.toString()); }
    public static PassId pass(String pass) { return PassId.valueOf(pass); }
    private RuntimePlanStore() {}
}
