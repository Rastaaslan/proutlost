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
    public static final int FORMAT_VERSION = 4;
    public static final Factory<BlockWorldPrepData> FACTORY = new Factory<>(BlockWorldPrepData::new, BlockWorldPrepData::load);

    public record Change(int x, int y, int z, String before, String applied, String province) {}

    public static final class Plan {
        public final String area, pass, profile, input;
        public String fingerprint;
        public UUID planId;
        public long entryCount;
        public UUID upstreamPlanId;
        public String upstreamRoot = "";
        public long eligible, skipped;
        /** Test/authoring staging only. Production clears this after publication; it is never SavedData. */
        public final transient Map<String, Change> changes = new LinkedHashMap<>();

        public Plan(String area, String pass, String profile, String input, String fingerprint) {
            this.area = area;
            this.pass = pass;
            this.profile = profile;
            this.input = input;
            this.fingerprint = fingerprint;
        }

        public void add(Change change) {
            String key = posKey(change.x(), change.y(), change.z());
            changes.put(key, change);
        }
    }

    public static final class Journal {
        public final UUID id, job, planId;
        public final String area, pass, fingerprint;
        public long pageCount,entryCount;public String journalRoot="";

        public Journal(UUID id, UUID job, UUID planId, String area, String pass, String fingerprint) {
            this.id = id;
            this.job = job;
            this.planId=planId;
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
    public static BlockWorldPrepData load(CompoundTag root, HolderLookup.Provider ignored) {
        var data = new BlockWorldPrepData();
        data.compatible = root.getInt("format") == FORMAT_VERSION;
        if (!data.compatible) return data; // Never resume legacy block cursors/journals.
        for (Tag value : root.getList("plans", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) value;
            var plan = new Plan(tag.getString("area"), tag.getString("pass"), tag.getString("profile"), tag.getString("input"), tag.getString("fingerprint"));
            plan.eligible = tag.getLong("eligible");
            plan.skipped = tag.getLong("skipped");
            if (!tag.hasUUID("planId")) return incompatible();
            plan.planId=tag.getUUID("planId");plan.entryCount=tag.getLong("entryCount");
            if(tag.hasUUID("upstreamPlanId"))plan.upstreamPlanId=tag.getUUID("upstreamPlanId");plan.upstreamRoot=tag.getString("upstreamRoot");
            data.plans.put(planKey(plan.area, plan.pass), plan);
        }
        for (Tag value : root.getList("journals", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) value;
            if(!tag.hasUUID("planId"))return incompatible();
            var journal = new Journal(tag.getUUID("id"), tag.getUUID("job"),tag.getUUID("planId"), tag.getString("area"), tag.getString("pass"), tag.getString("fingerprint"));
            journal.pageCount=tag.getLong("pageCount");journal.entryCount=tag.getLong("entryCount");journal.journalRoot=tag.getString("journalRoot");
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
            if (plan.planId == null) continue; // transient GameTest/authoring staging, never production authority
            var tag = new CompoundTag();
            tag.putString("area", plan.area); tag.putString("pass", plan.pass); tag.putString("profile", plan.profile);
            tag.putString("input", plan.input); tag.putString("fingerprint", plan.fingerprint);
            tag.putUUID("planId",plan.planId);tag.putLong("entryCount",plan.entryCount);
            if(plan.upstreamPlanId!=null)tag.putUUID("upstreamPlanId",plan.upstreamPlanId);tag.putString("upstreamRoot",plan.upstreamRoot);
            tag.putLong("eligible", plan.eligible); tag.putLong("skipped", plan.skipped);
            savedPlans.add(tag);
        }
        root.put("plans", savedPlans);
        var savedJournals = new ListTag();
        for (var journal : journals.values()) {
            var tag = new CompoundTag();
            tag.putUUID("id", journal.id); tag.putUUID("job", journal.job);tag.putUUID("planId",journal.planId); tag.putString("area", journal.area);
            tag.putString("pass", journal.pass); tag.putString("fingerprint", journal.fingerprint);
            tag.putLong("pageCount",journal.pageCount);tag.putLong("entryCount",journal.entryCount);tag.putString("journalRoot",journal.journalRoot);savedJournals.add(tag);
        }
        root.put("journals", savedJournals);
        return root;
    }


}
