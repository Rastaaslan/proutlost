package fr.proutlost.worldprep.persistence;

import fr.proutlost.worldprep.ecology.HabitatMap;
import fr.proutlost.worldprep.ecology.VegetationPlanner;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Bounded coarse observation prefixes; immutable disk plans certify completed results. */
public final class HabitatWorldPrepData extends SavedData {
    public static final int SCHEMA = 1, CELL_SIZE = 16, MAX_CELLS = 256, MAX_RUNS = 8;
    public static final String FILE_ID = "proutlost_worldprep_habitats";
    public static final Factory<HabitatWorldPrepData> FACTORY = new Factory<>(HabitatWorldPrepData::new, HabitatWorldPrepData::load);
    public record Observation(int floorY, int waterDepth, boolean forest, boolean ocean, boolean river,
                              boolean protectedColumn, HabitatMap.Cell cell) {
        public Observation {
            if (waterDepth < 0 || cell == null || cell.habitat() == null || cell.scores() == null)
                throw new IllegalArgumentException("Invalid habitat observation");
            var s = cell.scores();
            for (float v : new float[]{s.forest(), s.openness(), s.wetness(), s.rockiness(), s.marine()})
                if (!Float.isFinite(v) || v < 0 || v > 1) throw new IllegalArgumentException("Invalid habitat score");
        }
    }
    public static final class Run {
        public final UUID job;
        public final WorldPrepSavedData.Area area;
        public final String input;
        public final int minChunkX, minChunkZ, width, depth;
        private final Map<Integer, Observation> cells = new LinkedHashMap<>();
        public String sealedRoot = "";
        public Run(UUID job, WorldPrepSavedData.Area area, String input) {
            this.job = java.util.Objects.requireNonNull(job);this.area = java.util.Objects.requireNonNull(area);
            this.input = new PlanFingerprint(input).value();
            if (area.id().isBlank() || area.dimension().isBlank() || area.minX() > area.maxX() || area.minZ() > area.maxZ())
                throw new IllegalArgumentException("Invalid habitat bounds/identity");
            minChunkX = Math.floorDiv(area.minX(), CELL_SIZE);minChunkZ = Math.floorDiv(area.minZ(), CELL_SIZE);
            width = Math.addExact(Math.subtractExact(Math.floorDiv(area.maxX(), CELL_SIZE), minChunkX), 1);
            depth = Math.addExact(Math.subtractExact(Math.floorDiv(area.maxZ(), CELL_SIZE), minChunkZ), 1);
            if (width < 1 || depth < 1 || Math.multiplyExact((long) width, depth) > MAX_CELLS)
                throw new IllegalArgumentException("Habitat area exceeds 256 coarse cells; author smaller areas");
        }
        public int total() { return width * depth; }
        public int cursor() { return cells.size(); }
        public Map<Integer, Observation> cells() { return java.util.Collections.unmodifiableMap(cells); }
        public void append(int index, Observation observation) {
            if (!sealedRoot.isEmpty() || index != cursor() || index >= total()) throw new IllegalStateException("Habitat prefix mismatch");
            cells.put(index, java.util.Objects.requireNonNull(observation));
        }
        public HabitatMap.Cell atBlock(int x, int z) {
            if (x < area.minX() || x > area.maxX() || z < area.minZ() || z > area.maxZ() || sealedRoot.isEmpty()) return null;
            var observation = cells.get((Math.floorDiv(z, CELL_SIZE) - minChunkZ) * width + Math.floorDiv(x, CELL_SIZE) - minChunkX);
            return observation == null ? null : observation.cell();
        }
    }
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    public Run get(UUID job) { return runs.get(job); }
    public void requireCapacity(UUID job) {
        if (!runs.containsKey(job) && runs.size() >= MAX_RUNS) throw new IllegalStateException("Habitat retained-run capacity reached; no automatic eviction");
    }
    public void put(Run run) { requireCapacity(run.job);runs.put(run.job, run);setDirty(); }
    private static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) throw new IllegalStateException("Missing/invalid habitat field " + field);
    }
    private static ListTag list(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_LIST);
        var value = (ListTag)tag.get(field);
        if (!value.isEmpty() && value.getElementType() != Tag.TAG_COMPOUND) throw new IllegalStateException("Invalid habitat list " + field);
        return value;
    }
    private static boolean bool(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_BYTE);byte value = tag.getByte(field);
        if (value != 0 && value != 1) throw new IllegalStateException("Invalid habitat boolean " + field);
        return value == 1;
    }
    public static HabitatWorldPrepData load(CompoundTag root, HolderLookup.Provider ignored) {
        require(root, "schema", Tag.TAG_INT);
        if (root.getInt("schema") != SCHEMA) throw new IllegalStateException("Unsupported habitat schema");
        var data = new HabitatWorldPrepData();var values = list(root, "runs");
        if (values.size() > MAX_RUNS) throw new IllegalStateException("Habitat run bound");
        for (var value : values) {
            var tag = (CompoundTag)value;
            require(tag, "job", Tag.TAG_INT_ARRAY);
            if (!tag.hasUUID("job")) throw new IllegalStateException("Invalid habitat job UUID");
            for (var field : new String[]{"area", "dimension", "mask", "treeMode", "input", "sealedRoot"}) require(tag, field, Tag.TAG_STRING);
            for (var field : new String[]{"minX", "minZ", "maxX", "maxZ"}) require(tag, field, Tag.TAG_INT);
            var area = new WorldPrepSavedData.Area(tag.getString("area"), tag.getString("dimension"), tag.getInt("minX"), tag.getInt("minZ"),
                    tag.getInt("maxX"), tag.getInt("maxZ"), WorldPrepSavedData.AreaMask.valueOf(tag.getString("mask")),
                    VegetationPlanner.TreeMode.valueOf(tag.getString("treeMode")));
            var run = new Run(tag.getUUID("job"), area, tag.getString("input"));var cells = list(tag, "cells");
            if (cells.size() > run.total()) throw new IllegalStateException("Habitat cell bound");
            for (var row : cells) {
                var t = (CompoundTag)row;
                for (var field : new String[]{"index", "floorY", "waterDepth"}) require(t, field, Tag.TAG_INT);
                require(t, "habitat", Tag.TAG_STRING);
                for (var field : new String[]{"forestScore", "openness", "wetness", "rockiness", "marine"}) require(t, field, Tag.TAG_FLOAT);
                var scores = new HabitatMap.Scores(t.getFloat("forestScore"), t.getFloat("openness"), t.getFloat("wetness"), t.getFloat("rockiness"), t.getFloat("marine"));
                run.append(t.getInt("index"), new Observation(t.getInt("floorY"), t.getInt("waterDepth"), bool(t,"forest"), bool(t,"ocean"),
                        bool(t,"river"), bool(t,"protected"), new HabitatMap.Cell(HabitatMap.Habitat.valueOf(t.getString("habitat")), scores)));
            }
            run.sealedRoot = tag.getString("sealedRoot");
            if (!run.sealedRoot.isEmpty()) {
                new PlanFingerprint(run.sealedRoot);
                if (run.cursor() != run.total()) throw new IllegalStateException("Incomplete sealed habitat cache");
            }
            if (data.runs.putIfAbsent(run.job, run) != null) throw new IllegalStateException("Duplicate habitat run");
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider ignored) {
        root.putInt("schema", SCHEMA);var values = new ListTag();
        for (var run : runs.values()) {
            var t = new CompoundTag();var area = run.area;
            t.putUUID("job", run.job);t.putString("area", area.id());t.putString("dimension", area.dimension());t.putString("mask", area.mask().name());
            t.putString("treeMode", area.treeMode().name());t.putInt("minX", area.minX());t.putInt("minZ", area.minZ());t.putInt("maxX", area.maxX());t.putInt("maxZ", area.maxZ());
            t.putString("input", run.input);t.putString("sealedRoot", run.sealedRoot);var cells = new ListTag();
            run.cells.forEach((index, o) -> {
                var c = new CompoundTag();var s = o.cell().scores();
                c.putInt("index", index);c.putInt("floorY", o.floorY());c.putInt("waterDepth", o.waterDepth());
                c.putBoolean("forest", o.forest());c.putBoolean("ocean", o.ocean());c.putBoolean("river", o.river());c.putBoolean("protected", o.protectedColumn());
                c.putString("habitat", o.cell().habitat().name());c.putFloat("forestScore", s.forest());c.putFloat("openness", s.openness());
                c.putFloat("wetness", s.wetness());c.putFloat("rockiness", s.rockiness());c.putFloat("marine", s.marine());cells.add(c);
            });
            t.put("cells", cells);values.add(t);
        }
        root.put("runs", values);return root;
    }
}
