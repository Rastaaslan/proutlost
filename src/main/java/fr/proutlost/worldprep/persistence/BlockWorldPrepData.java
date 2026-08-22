package fr.proutlost.worldprep.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Dimension-local persisted block plans and compact chunk-scoped change journals. */
public final class BlockWorldPrepData extends SavedData {
    public static final String FILE_ID = "proutlost_worldprep_blocks";
    public static final int FORMAT_VERSION = 2;
    /** Deliberate fail-closed hotfix bound; pages remain future work. */
    public static final int MAX_PLAN_CHANGES = 250_000;
    public static final Factory<BlockWorldPrepData> FACTORY = new Factory<>(BlockWorldPrepData::new, BlockWorldPrepData::load);

    public record Change(int x, int y, int z, String before, String applied, String province) {}

    public static final class Plan {
        public final String area, pass, profile, input;
        public String fingerprint;
        public long eligible, skipped;
        public final Map<String, Change> changes = new LinkedHashMap<>();

        public Plan(String area, String pass, String profile, String input, String fingerprint) {
            this.area = area;
            this.pass = pass;
            this.profile = profile;
            this.input = input;
            this.fingerprint = fingerprint;
        }

        public void add(Change change) {
            String key = posKey(change.x(), change.y(), change.z());
            if (!changes.containsKey(key)) requirePlanSize(changes.size() + 1);
            changes.put(key, change);
        }
    }

    public static final class Journal {
        public final UUID id, job;
        public final String area, pass, fingerprint;
        public final Map<String, Change> changes = new LinkedHashMap<>();

        public Journal(UUID id, UUID job, String area, String pass, String fingerprint) {
            this.id = id;
            this.job = job;
            this.area = area;
            this.pass = pass;
            this.fingerprint = fingerprint;
        }
    }

    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<UUID, Journal> journals = new LinkedHashMap<>();
    private boolean compatible = true;

    public Map<String, Plan> plans() { return plans; }
    public Map<UUID, Journal> journals() { return journals; }
    public boolean compatible() { return compatible; }
    public void changed() { setDirty(); }
    public static String planKey(String area, String pass) { return area + "|" + pass; }
    public static String posKey(int x, int y, int z) { return x + ":" + y + ":" + z; }
    public static void requirePlanSize(int size) {
        if (size < 0 || size > MAX_PLAN_CHANGES) throw new IllegalStateException("Block plan exceeds safe limit of " + MAX_PLAN_CHANGES + " changes");
    }

    public static BlockWorldPrepData load(CompoundTag root, HolderLookup.Provider ignored) {
        var data = new BlockWorldPrepData();
        data.compatible = root.getInt("format") == FORMAT_VERSION;
        if (!data.compatible) return data; // Never resume legacy block cursors/journals.
        for (Tag value : root.getList("plans", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) value;
            var plan = new Plan(tag.getString("area"), tag.getString("pass"), tag.getString("profile"), tag.getString("input"), tag.getString("fingerprint"));
            plan.eligible = tag.getLong("eligible");
            plan.skipped = tag.getLong("skipped");
            readChunks(tag.getList("chunks", Tag.TAG_COMPOUND), plan.changes);
            try { requirePlanSize(plan.changes.size()); } catch (IllegalStateException exception) { return incompatible(); }
            data.plans.put(planKey(plan.area, plan.pass), plan);
        }
        for (Tag value : root.getList("journals", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) value;
            var journal = new Journal(tag.getUUID("id"), tag.getUUID("job"), tag.getString("area"), tag.getString("pass"), tag.getString("fingerprint"));
            readChunks(tag.getList("chunks", Tag.TAG_COMPOUND), journal.changes);
            data.journals.put(journal.id, journal);
        }
        return data;
    }

    private static BlockWorldPrepData incompatible() {
        var data = new BlockWorldPrepData();
        data.compatible = false;
        return data;
    }

    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider ignored) {
        root.putInt("format", FORMAT_VERSION);
        var savedPlans = new ListTag();
        for (var plan : plans.values()) {
            var tag = new CompoundTag();
            tag.putString("area", plan.area); tag.putString("pass", plan.pass); tag.putString("profile", plan.profile);
            tag.putString("input", plan.input); tag.putString("fingerprint", plan.fingerprint);
            tag.putLong("eligible", plan.eligible); tag.putLong("skipped", plan.skipped);
            tag.put("chunks", writeChunks(plan.changes)); savedPlans.add(tag);
        }
        root.put("plans", savedPlans);
        var savedJournals = new ListTag();
        for (var journal : journals.values()) {
            var tag = new CompoundTag();
            tag.putUUID("id", journal.id); tag.putUUID("job", journal.job); tag.putString("area", journal.area);
            tag.putString("pass", journal.pass); tag.putString("fingerprint", journal.fingerprint);
            tag.put("chunks", writeChunks(journal.changes)); savedJournals.add(tag);
        }
        root.put("journals", savedJournals);
        return root;
    }

    private static ListTag writeChunks(Map<String, Change> changes) {
        var grouped = new LinkedHashMap<Long, ListTag>();
        for (var change : changes.values()) {
            long key = ((long) (change.x() >> 4) << 32) ^ ((change.z() >> 4) & 0xffffffffL);
            var entries = grouped.computeIfAbsent(key, ignored -> new ListTag());
            var tag = new CompoundTag();
            tag.putByte("x", (byte) (change.x() & 15)); tag.putInt("y", change.y()); tag.putByte("z", (byte) (change.z() & 15));
            tag.putString("before", change.before()); tag.putString("applied", change.applied()); tag.putString("province", change.province());
            entries.add(tag);
        }
        var result = new ListTag();
        for (var entry : grouped.entrySet()) {
            var tag = new CompoundTag(); tag.putInt("x", (int) (entry.getKey() >> 32)); tag.putInt("z", (int) (long) entry.getKey());
            tag.put("entries", entry.getValue()); result.add(tag);
        }
        return result;
    }

    private static void readChunks(ListTag chunks, Map<String, Change> output) {
        for (Tag value : chunks) {
            var chunk = (CompoundTag) value; int cx = chunk.getInt("x"), cz = chunk.getInt("z");
            for (Tag entry : chunk.getList("entries", Tag.TAG_COMPOUND)) {
                var tag = (CompoundTag) entry;
                var change = new Change((cx << 4) + (tag.getByte("x") & 15), tag.getInt("y"), (cz << 4) + (tag.getByte("z") & 15), tag.getString("before"), tag.getString("applied"), tag.getString("province"));
                output.put(posKey(change.x(), change.y(), change.z()), change);
            }
        }
    }
}
