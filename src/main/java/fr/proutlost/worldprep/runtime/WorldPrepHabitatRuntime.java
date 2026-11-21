package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.ecology.HabitatMap;
import fr.proutlost.worldprep.persistence.HabitatWorldPrepData;
import fr.proutlost.worldprep.persistence.HabitatWorldPrepData.Observation;
import fr.proutlost.worldprep.persistence.HabitatWorldPrepData.Run;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.plan.PlanIdentity;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

/** Coarse observed snapshots, not an accepted Director habitat catalog or fauna population ACK. */
public final class WorldPrepHabitatRuntime {
    private static final String PLANNER = "habitat-chunk-center-1";
    private static final WeakHashMap<ServerLevel, Set<UUID>> VERIFIED = new WeakHashMap<>();
    private record Row(int index, Observation observation) {}
    private static final PagedPlanStore.EntryCodec<Row> CODEC = new PagedPlanStore.EntryCodec<>() {
        public void write(DataOutput out, Row row) throws IOException {
            var o = row.observation();var s = o.cell().scores();
            out.writeInt(row.index());out.writeInt(o.floorY());out.writeInt(o.waterDepth());
            out.writeBoolean(o.forest());out.writeBoolean(o.ocean());out.writeBoolean(o.river());out.writeBoolean(o.protectedColumn());
            out.writeUTF(o.cell().habitat().name());out.writeFloat(s.forest());out.writeFloat(s.openness());
            out.writeFloat(s.wetness());out.writeFloat(s.rockiness());out.writeFloat(s.marine());
        }
        public Row read(DataInput in) throws IOException {
            int index = in.readInt(), floor = in.readInt(), water = in.readInt();
            boolean forest = in.readBoolean(), ocean = in.readBoolean(), river = in.readBoolean(), protect = in.readBoolean();
            var habitat = HabitatMap.Habitat.valueOf(in.readUTF());
            var scores = new HabitatMap.Scores(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
            return new Row(index, new Observation(floor, water, forest, ocean, river, protect, new HabitatMap.Cell(habitat, scores)));
        }
    };
    private WorldPrepHabitatRuntime() {}
    private static void actor(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Habitat observations require server actor");
    }
    private static HabitatWorldPrepData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(HabitatWorldPrepData.FACTORY, HabitatWorldPrepData.FILE_ID);
    }
    private static String input(ServerLevel level, WorldPrepSavedData state, WorldPrepSavedData.Area area, UUID ownJob) {
        if (state.jobs().size() > 4096) throw new IllegalStateException("Habitat mutation-history inventory exceeds 4096 jobs");
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);var tags = new StringBuilder();
        for (var tag : java.util.List.of(BiomeTags.IS_FOREST, BiomeTags.IS_OCEAN, BiomeTags.IS_RIVER)) {
            tags.append(tag.location()).append('=');
            registry.getTag(tag).ifPresent(values -> values.stream().map(h -> h.unwrapKey().orElseThrow().location().toString())
                    .sorted().forEach(id -> tags.append(id).append(';')));
        }
        var changes = new StringBuilder();
        state.jobs().values().stream().filter(job -> !job.id().equals(ownJob)
                && (job.operation().name().startsWith("APPLY_") || job.operation().name().startsWith("ROLLBACK_")))
                .filter(job -> {
                    var other = state.areas().get(job.area());
                    return other == null || other.dimension().equals(area.dimension()) && other.minX() <= area.maxX()
                            && other.maxX() >= area.minX() && other.minZ() <= area.maxZ() && other.maxZ() >= area.minZ();
                }).sorted(java.util.Comparator.comparing(job -> job.id().toString())).forEach(job -> changes.append(job.id()).append('|')
                        .append(job.operation()).append('|').append(job.area()).append('|').append(job.cursor()).append('|').append(job.snapshotId()).append('\n'));
        return PlanFingerprint.of(PLANNER, PlanIdentity.fingerprint(PlanIdentity.capture(level, state, area)),
                area.toString(), tags.toString(), changes.toString()).value();
    }
    /** Existing PREVIEW_HABITATS dispatcher; missing loaded chunks pause without spending a new cohort slot. */
    public static void process(ServerLevel level, WorldPrepSavedData state, UUID id, WorldPrepSavedData.Job job, int budget) throws IOException {
        actor(level);
        try { processSlice(level,state,id,job,budget); }
        catch (ServerExistingChunkAccess.Refused refusal) {
            if (refusal.refusal() != ServerExistingChunkAccess.Refusal.MISSING_OR_INCOMPLETE_CHUNK) throw refusal;
            var prefix=data(level).get(id);var latest=java.util.Objects.requireNonNull(state.jobs().get(id));
            state.jobs().put(id,new WorldPrepSavedData.Job(latest.id(),latest.operation(),latest.area(),WorldPrepSavedData.JobState.PAUSED,
                    prefix==null?0:prefix.cursor(),"MISSING_LOADED_CHUNK_RELOAD_AND_RESUME",latest.snapshotId()));state.changed();
            System.out.println("WORLDPREP_HABITAT_PAUSED_MISSING_LOADED_CHUNK job="+id);
        }
    }
    private static void processSlice(ServerLevel level, WorldPrepSavedData state, UUID id, WorldPrepSavedData.Job job, int budget) throws IOException {
        if (!WorldPrepConfig.ENABLED.get()) throw new IllegalStateException("WorldPrep disabled");
        if (!job.id().equals(id) || !job.operation().name().equals("PREVIEW_HABITATS")) throw new IllegalArgumentException("Exact habitat job required");
        if (budget <= 0) return;
        var area = WorldPrepRuntime.requireArea(state, job.area());
        if (!area.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("Habitat dimension changed");
        var identity = input(level, state, area, id);var saved = data(level);var run = saved.get(id);
        if (run == null) { saved.requireCapacity(id);run = new Run(id, area, identity); }
        if (!run.input.equals(identity)) throw new IllegalStateException("Habitat prefix stale: inputs or intersecting mutation jobs changed");
        var directory = RuntimePlanStore.directory(level, id);
        if (Files.isRegularFile(PagedPlanStore.manifestPath(directory))) {
            restoreSealed(level, saved, run);complete(state, job, run);return;
        }
        if (!run.sealedRoot.isEmpty()) throw new IllegalStateException("Sealed habitat manifest is missing; recovery required");
        int remaining = Math.min(8, Math.max(1, budget / 344));
        while (remaining-- > 0 && run.cursor() < run.total()) {
            int index = run.cursor(), cx = run.minChunkX + index % run.width, cz = run.minChunkZ + index / run.width;
            int x = Math.max(area.minX(), Math.min(area.maxX(), cx * 16 + 8));
            int z = Math.max(area.minZ(), Math.min(area.maxZ(), cz * 16 + 8));
            var chunk = ServerExistingChunkAccess.requireBlock(level, area, x, level.getMinBuildHeight(), z);
            var terrain = MinecraftTerrainSampler.sample(level, x, z);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (floor < level.getMinBuildHeight() || floor >= level.getMaxBuildHeight()) throw new IllegalStateException("Habitat terrain floor unavailable");
            var pos = new BlockPos(x, floor, z);
            int waterTop=surface;
            // WORLD_SURFACE may include one lily pad above the real water surface.
            if(waterTop>floor&&!chunk.getFluidState(new BlockPos(x,waterTop,z)).is(FluidTags.WATER))waterTop--;
            int water=floor+1<level.getMaxBuildHeight()&&waterTop>floor
                    &&chunk.getFluidState(pos.above()).is(FluidTags.WATER)
                    &&chunk.getFluidState(new BlockPos(x,waterTop,z)).is(FluidTags.WATER)?waterTop-floor:0;
            var biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(floor), QuartPos.fromBlock(z));
            boolean forest = biome.is(BiomeTags.IS_FOREST), ocean = biome.is(BiomeTags.IS_OCEAN), river = biome.is(BiomeTags.IS_RIVER);
            HabitatMap.Habitat habitat;
            if (water > 0) habitat = ocean ? (water > 20 ? HabitatMap.Habitat.DEEP_MARINE : water > 6 ? HabitatMap.Habitat.MARINE : HabitatMap.Habitat.SHALLOW_MARINE)
                    : river ? HabitatMap.Habitat.RIVER : HabitatMap.Habitat.WETLAND;
            else if (terrain.surfaceY() >= 133) habitat = HabitatMap.Habitat.MOUNTAIN;
            else if (terrain.slope() >= 8) habitat = HabitatMap.Habitat.ROCKY_HIGHLAND;
            else habitat = forest ? HabitatMap.Habitat.FOREST : HabitatMap.Habitat.OPEN_GRASSLAND;
            var scores = new HabitatMap.Scores(water == 0 && forest ? 1 : 0, water == 0 && !forest ? 1 : 0,
                    water > 0 ? 1 : 0, Math.min(1, terrain.slope() / 8f), water > 0 && ocean ? 1 : 0);
            boolean protect = state.protectedBlock(area.dimension(), x, z) || !chunk.getAllStarts().isEmpty() || !chunk.getAllReferences().isEmpty();
            run.append(index, new Observation(floor, water, forest, ocean, river, protect, new HabitatMap.Cell(habitat, scores)));
            if(saved.get(id)==null)saved.put(run);else saved.setDirty();
        }
        state.jobs().put(id, job.withCursor(run.cursor()));state.changed();
        if (run.cursor() < run.total()) return;
        if (Files.exists(directory)) throw new IOException("Incomplete habitat publication retained; recovery required");
        var rows = run.cells().entrySet().stream().map(e -> new Row(e.getKey(), e.getValue())).toList();
        var published = PagedPlanStore.publish(RuntimePlanStore.root(level), id, PassId.HABITATS, area.id(), area.dimension(), PlanIdentity.PROFILE,
                identity, RuntimePlanStore.ENGINE_VERSION, PLANNER, HabitatWorldPrepData.MAX_CELLS, rows, CODEC);
        run.sealedRoot = published.manifest().rootFingerprint();saved.setDirty();
        VERIFIED.computeIfAbsent(level, unused -> new HashSet<>()).add(id);complete(state, job, run);
    }
    private static void complete(WorldPrepSavedData state, WorldPrepSavedData.Job job, Run run) {
        state.jobs().put(job.id(), job.withCursor(run.total()).withState(WorldPrepSavedData.JobState.COMPLETED));state.changed();
    }
    private static void restoreSealed(ServerLevel level, HabitatWorldPrepData saved, Run original) throws IOException {
        var manifest = PagedPlanStore.readManifest(RuntimePlanStore.directory(level, original.job));manifest.requireApplicable();
        if (!manifest.planId().equals(original.job) || manifest.passId() != PassId.HABITATS || !manifest.areaId().equals(original.area.id())
                || !manifest.dimension().equals(original.area.dimension()) || !manifest.profile().equals(PlanIdentity.PROFILE)
                || !manifest.semanticInputDigest().equals(original.input) || !manifest.engineVersion().equals(RuntimePlanStore.ENGINE_VERSION)
                || !manifest.plannerVersion().equals(PLANNER) || manifest.pages().size() != 1
                || manifest.pages().getFirst().entryCount() != original.total()
                || !original.sealedRoot.isEmpty() && !original.sealedRoot.equals(manifest.rootFingerprint())) throw new IOException("Habitat sealed identity mismatch");
        var restored = new Run(original.job, original.area, original.input);
        PagedPlanStore.read(RuntimePlanStore.directory(level, original.job), manifest, CODEC, row -> restored.append(row.index(), row.observation()));
        for (var prefix : original.cells().entrySet())
            if (!prefix.getValue().equals(restored.cells().get(prefix.getKey()))) throw new IOException("Habitat saved prefix differs from sealed output");
        restored.sealedRoot = manifest.rootFingerprint();saved.put(restored);
        VERIFIED.computeIfAbsent(level, unused -> new HashSet<>()).add(original.job);
    }
    /** Historical observed job snapshot; never a guarantee that current terrain is unchanged. */
    public static Optional<HabitatMap.Cell> query(ServerLevel level, UUID id, int x, int z) throws IOException {
        actor(level);var saved = data(level);var run = saved.get(id);
        if (run == null) {
            var state = WorldPrepRuntime.data(level);var job = state.jobs().get(id);
            if (job == null || !job.operation().name().equals("PREVIEW_HABITATS")) return Optional.empty();
            var area = WorldPrepRuntime.requireArea(state, job.area());run = new Run(id, area, input(level, state, area, id));
        }
        if (!run.area.dimension().equals(level.dimension().location().toString())) throw new IllegalStateException("Habitat query dimension mismatch");
        if (!VERIFIED.getOrDefault(level, Set.of()).contains(id)) {
            if (!Files.isRegularFile(PagedPlanStore.manifestPath(RuntimePlanStore.directory(level, id)))) return Optional.empty();
            restoreSealed(level, saved, run);run = saved.get(id);
        }
        return Optional.ofNullable(run.atBlock(x, z));
    }
}
