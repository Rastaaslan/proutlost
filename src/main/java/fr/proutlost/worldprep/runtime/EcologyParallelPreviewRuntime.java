package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool;
import fr.proutlost.worldprep.ecology.BoundedEcologyCandidatePool.Column;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * RUN39 DEV only, opt-in. Snapshot capture, replay validation and publication
 * remain on the owning Minecraft thread. The workers receive pure records.
 * Pending previews have NO durable mutation until the ordered plan is sealed.
 */
public final class EcologyParallelPreviewRuntime {
    private record Pending(UUID job, String areaId, String pass, long seed,
                           int workers, List<Column> snapshot,
                           BoundedEcologyCandidatePool.Ticket ticket) {}
    private static final Map<WorldPrepSavedData, Pending> PENDING = new WeakHashMap<>();

    private static void thread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("RUN39 Minecraft world access must stay on server thread");
        }
    }
    static List<Column> capture(ServerLevel level, WorldPrepSavedData data,
                                        WorldPrepSavedData.Area area) {
        EcologyMutationPlanner.requireBounded(area);
        var snapshot = new ArrayList<Column>();
        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                var chunk = ServerExistingChunkAccess.requireBlock(
                        level, area, x, level.getMinBuildHeight(), z);
                boolean protectedColumn = data.protectedBlock(area.dimension(), x, z)
                        || !chunk.getAllStarts().isEmpty() || !chunk.getAllReferences().isEmpty();
                snapshot.add(new Column(x, z,
                        chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                        chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), protectedColumn));
            }
        }
        return List.copyOf(snapshot);
    }
    static boolean verifyStillCurrent(ServerLevel level, WorldPrepSavedData data,
                                            WorldPrepSavedData.Area area, List<Column> snapshot) {
        // We must not discard new candidates if the world changes between
        // snapshot and commit. No silently stale results, even on empty tiles.
        for (var column : snapshot) {
            int x=column.x(), z=column.z();
            var chunk=ServerExistingChunkAccess.requireBlock(
                    level, area, x, level.getMinBuildHeight(), z);
            boolean protectedColumn=data.protectedBlock(area.dimension(),x,z)
                    || !chunk.getAllStarts().isEmpty() || !chunk.getAllReferences().isEmpty();
            if (column.protectedColumn()!=protectedColumn
                    || column.floorY()!=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)
                    || column.surfaceY()!=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)) {
                return false;
            }
        }
        return true;
    }
    public static boolean populate(ServerLevel level, WorldPrepSavedData data,
                                   UUID job, WorldPrepSavedData.Area area, String pass,
                                   BlockWorldPrepData.Plan plan, int workers) {
        thread(level);
        if (!BoundedEcologyCandidatePool.supports(pass)) {
            throw new IllegalArgumentException("RUN39 unsupported preview pass");
        }
        if (workers<1 || workers>8 || !plan.area.equals(area.id()) || !plan.pass.equals(pass)
                || plan.planId()!=null || !plan.changes.isEmpty()) {
            throw new IllegalStateException("RUN39 preview admission/ownership refusal");
        }
        var prior = PENDING.get(data);
        if (prior == null) {
            var snapshot=capture(level,data,area);
            var ticket=BoundedEcologyCandidatePool.submit(level.getSeed(),pass,snapshot,workers);
            PENDING.put(data,new Pending(job,area.id(),pass,level.getSeed(),workers,snapshot,ticket));
            return false;
        }
        if (!prior.job().equals(job) || !prior.areaId().equals(area.id())
                || !prior.pass().equals(pass) || prior.seed()!=level.getSeed()
                || prior.workers()!=workers) {
            cancel(data);
            throw new IllegalStateException("RUN39 pending snapshot belongs to another job/pass");
        }
        if (!BoundedEcologyCandidatePool.ready(prior.ticket())) return false;
        try {
            var candidates = BoundedEcologyCandidatePool.collect(prior.ticket());
            if(!verifyStillCurrent(level,data,area,prior.snapshot()))
                throw new IllegalStateException("RUN39 stale snapshot rejected without changing terrain");
            for (var candidate : candidates) {
                if (candidate.y() < level.getMinBuildHeight()
                        || candidate.y() >= level.getMaxBuildHeight()) continue;
                // Same exact current-world validation as legacy serial preview.
                var change=EcologyMutationPlanner.change(level,data,area,pass,
                        candidate.x(),candidate.y(),candidate.z());
                if (change!=null) plan.add(change);
            }
            return true;
        } finally {
            cancel(data);
        }
    }
    public static void cancel(WorldPrepSavedData data) {
        var prior=PENDING.remove(data);
        if (prior!=null) BoundedEcologyCandidatePool.cancel(prior.ticket());
    }
    private EcologyParallelPreviewRuntime() {}
}
