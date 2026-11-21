package fr.proutlost.worldprep.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Dimension-local persisted block plans and compact chunk-scoped change journals. */
public final class BlockWorldPrepData extends AcknowledgedSavedData {
    public static final String FILE_ID = "proutlost_worldprep_blocks";
    public static final int FORMAT_VERSION = 4;
    public static final Factory<BlockWorldPrepData> FACTORY = new Factory<>(BlockWorldPrepData::new, BlockWorldPrepData::load);

    public record Change(int x, int y, int z, String before, String applied, String province) {}

    public static final class Plan extends TrackedBlockRecord {
        public final String area, pass, profile, input;
        private String fingerprint;
        private UUID planId;
        private long entryCount;
        private UUID upstreamPlanId;
        private String upstreamRoot = "";
        private long eligible, skipped;
        /** Test/authoring staging only. Production clears this after publication; it is never SavedData. */
        public final transient Map<String, Change> changes = new LinkedHashMap<>();

        public Plan(String area, String pass, String profile, String input, String fingerprint) {
            this.area = area;
            this.pass = pass;
            this.profile = profile;
            this.input = input;
            this.fingerprint = fingerprint;
        }

        public String fingerprint() { return fingerprint; }
        public void fingerprint(String value) {
            beforeMutation();
            if(!java.util.Objects.equals(fingerprint,value)) { fingerprint=value; metadataChanged(); }
        }
        public UUID planId() { return planId; }
        public void planId(UUID value) {
            beforeMutation();
            if(!java.util.Objects.equals(planId,value)) { planId=value; metadataChanged(); }
        }
        public long entryCount() { return entryCount; }
        public void entryCount(long value) {
            beforeMutation();
            if(!java.util.Objects.equals(entryCount,value)) { entryCount=value; metadataChanged(); }
        }
        public UUID upstreamPlanId() { return upstreamPlanId; }
        public void upstreamPlanId(UUID value) {
            beforeMutation();
            if(!java.util.Objects.equals(upstreamPlanId,value)) { upstreamPlanId=value; metadataChanged(); }
        }
        public String upstreamRoot() { return upstreamRoot; }
        public void upstreamRoot(String value) {
            beforeMutation();
            if(!java.util.Objects.equals(upstreamRoot,value)) { upstreamRoot=value; metadataChanged(); }
        }
        public long eligible() { return eligible; }
        public void eligible(long value) {
            beforeMutation();
            if(!java.util.Objects.equals(eligible,value)) { eligible=value; metadataChanged(); }
        }
        public long skipped() { return skipped; }
        public void skipped(long value) {
            beforeMutation();
            if(!java.util.Objects.equals(skipped,value)) { skipped=value; metadataChanged(); }
        }

        public void add(Change change) {
            String key = posKey(change.x(), change.y(), change.z());
            changes.put(key, change);
        }
    }

    public static final class Journal extends TrackedBlockRecord {
        public final UUID id, job, planId;
        public final String area, pass, fingerprint;
        private long pageCount,entryCount;private String journalRoot="";

        public long pageCount() { return pageCount; }
        public void pageCount(long value) {
            beforeMutation();
            if(!java.util.Objects.equals(pageCount,value)) { pageCount=value; metadataChanged(); }
        }
        public long entryCount() { return entryCount; }
        public void entryCount(long value) {
            beforeMutation();
            if(!java.util.Objects.equals(entryCount,value)) { entryCount=value; metadataChanged(); }
        }
        public String journalRoot() { return journalRoot; }
        public void journalRoot(String value) {
            beforeMutation();
            if(!java.util.Objects.equals(journalRoot,value)) { journalRoot=value; metadataChanged(); }
        }

        public UUID planId() { return planId; }
        public String fingerprint() { return fingerprint; }
        public Journal(UUID id, UUID job, UUID planId, String area, String pass, String fingerprint) {
            this.id = id;
            this.job = job;
            this.planId=planId;
            this.area = area;
            this.pass = pass;
            this.fingerprint = fingerprint;
        }
    }

    public static final String BLOCK_DELTA_FLAG="proutlost.persistence.strictBlockDelta";
    static final String BLOCK_DELTA_MARKER="__worldprep_block_delta_v1";
    private final boolean sparseAtConstruction=Boolean.getBoolean(BLOCK_DELTA_FLAG);
    private final Thread metadataOwner=Thread.currentThread();
    private boolean loadedFromPagedColdRoot;
    private final DirtyBlockMap<String,Plan> plans=new DirtyBlockMap<>(this,p->p.planId()!=null,p->planKey(p.area,p.pass));
    private final DirtyBlockMap<UUID,Journal> journals=new DirtyBlockMap<>(this,j->true,j->j.id);
    boolean strictMetadataTracking() { return sparseAtConstruction; }
    void requireMetadataOwner() {
        if(sparseAtConstruction && metadataOwner!=Thread.currentThread())
            throw new IllegalStateException("Block metadata mutation requires its owner thread");
    }
    private boolean compatible = true;
    /**
     * RUN116 opt-in, owner-thread only. A Plan or Journal is mutable, so every
     * persisted field is snapshotted and equality-checked on every save.
     * Cached NBT is never returned to caller without a deep copy; a previous
     * checkpoint cannot be mutated by a later snapshot or test.
     * The output schema, ordering and SHA-paged protocol are unchanged.
     */
    private final boolean safeTagCache = Boolean.getBoolean("proutlost.run116.cachedBlockNbt");
    private record PlanSnapshot(String area,String pass,String profile,String input,
                                String fingerprint,UUID planId,long entryCount,
                                UUID upstreamPlanId,String upstreamRoot,long eligible,long skipped) {}
    private record JournalSnapshot(UUID id,UUID job,UUID planId,String area,
                                   String pass,String fingerprint,long pageCount,
                                   long entryCount,String journalRoot) {}
    private record PlanCache(PlanSnapshot snapshot,CompoundTag frozen) {}
    private record JournalCache(JournalSnapshot snapshot,CompoundTag frozen) {}
    private final Map<Plan,PlanCache> savedPlanTags=new WeakHashMap<>();
    private final Map<Journal,JournalCache> savedJournalTags=new WeakHashMap<>();

    public java.util.NavigableMap<String,Plan> plans() { return plans; }
    public java.util.NavigableMap<UUID,Journal> journals() { return journals; }
    public boolean compatible() { return compatible; }
    public void changed() { setDirty(); }
    public static String planKey(String area, String pass) { return area + "|" + pass; }
    public static String posKey(int x, int y, int z) { return x + ":" + y + ":" + z; }
    public static BlockWorldPrepData load(CompoundTag root, HolderLookup.Provider ignored) {
        boolean paged=root.contains(PagedSavedDataRuntime.INDEX_MARKER,Tag.TAG_COMPOUND);
        root=PagedSavedDataRuntime.expandLoad(root,FILE_ID);
        var data = new BlockWorldPrepData();
        data.loadedFromPagedColdRoot=paged;
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
        data.plans.admitLoadedBaseline();
        data.journals.admitLoadedBaseline();
        data.setDirty(false);
        return data;
    }

    private static BlockWorldPrepData incompatible() {
        var data = new BlockWorldPrepData();
        data.compatible = false;
        return data;
    }

    private static PlanSnapshot view(Plan p) {
        return new PlanSnapshot(p.area,p.pass,p.profile,p.input,p.fingerprint,
                p.planId,p.entryCount,p.upstreamPlanId,p.upstreamRoot,p.eligible,p.skipped);
    }
    private static JournalSnapshot view(Journal j) {
        return new JournalSnapshot(j.id,j.job,j.planId,j.area,j.pass,j.fingerprint,
                j.pageCount,j.entryCount,j.journalRoot);
    }
    private static CompoundTag encode(Plan plan) {
        var tag=new CompoundTag();
        tag.putString("area",plan.area);tag.putString("pass",plan.pass);
        tag.putString("profile",plan.profile);tag.putString("input",plan.input);
        tag.putString("fingerprint",plan.fingerprint);
        tag.putUUID("planId",plan.planId);tag.putLong("entryCount",plan.entryCount);
        if(plan.upstreamPlanId!=null)tag.putUUID("upstreamPlanId",plan.upstreamPlanId);
        tag.putString("upstreamRoot",plan.upstreamRoot);
        tag.putLong("eligible",plan.eligible);tag.putLong("skipped",plan.skipped);
        return tag;
    }
    private static CompoundTag encode(Journal journal) {
        var tag=new CompoundTag();
        tag.putUUID("id",journal.id);tag.putUUID("job",journal.job);
        tag.putUUID("planId",journal.planId);tag.putString("area",journal.area);
        tag.putString("pass",journal.pass);tag.putString("fingerprint",journal.fingerprint);
        tag.putLong("pageCount",journal.pageCount);tag.putLong("entryCount",journal.entryCount);
        tag.putString("journalRoot",journal.journalRoot);
        return tag;
    }
    private CompoundTag saved(Plan plan) {
        if(!safeTagCache)return encode(plan);
        var view=view(plan);
        var cached=savedPlanTags.get(plan);
        if(cached!=null&&cached.snapshot().equals(view))return cached.frozen().copy();
        var tag=encode(plan);
        savedPlanTags.put(plan,new PlanCache(view,tag.copy()));
        return tag;
    }
    private CompoundTag saved(Journal journal) {
        if(!safeTagCache)return encode(journal);
        var view=view(journal);
        var cached=savedJournalTags.get(journal);
        if(cached!=null&&cached.snapshot().equals(view))return cached.frozen().copy();
        var tag=encode(journal);
        savedJournalTags.put(journal,new JournalCache(view,tag.copy()));
        return tag;
    }
    /** Capture independent immutable tags for changed keys only. ACK/failure remains
     * owned by AcknowledgedSavedData; capture is never a durable acknowledgement. */
    private CompoundTag sparseCapture(CompoundTag root) {
        requireMetadataOwner();
        var marker=new CompoundTag();
        marker.putInt("version",1);marker.putString("store",FILE_ID);
        marker.putInt("plansCount",plans.persistedSize());
        marker.putInt("journalsCount",journals.persistedSize());
        var planChanges=new ListTag();var planDeletes=new ListTag();
        for(var key:plans.changedKeys()) {
            Plan plan=plans.get(key);
            if(plan!=null && plan.planId()!=null)planChanges.add(encode(plan));
            else if(plans.previouslyCaptured(key))planDeletes.add(net.minecraft.nbt.StringTag.valueOf(key));
        }
        var journalChanges=new ListTag();var journalDeletes=new ListTag();
        for(var key:journals.changedKeys()) {
            Journal journal=journals.get(key);
            if(journal!=null)journalChanges.add(encode(journal));
            else if(journals.previouslyCaptured(key))journalDeletes.add(net.minecraft.nbt.StringTag.valueOf(key.toString()));
        }
        marker.put("plans",planChanges);marker.put("journals",journalChanges);
        marker.put("plansDeleted",planDeletes);marker.put("journalsDeleted",journalDeletes);
        root.putInt("format",FORMAT_VERSION);root.put(BLOCK_DELTA_MARKER,marker);
        plans.capturedChanges();journals.capturedChanges();
        return root;
    }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider ignored) {
        if(Boolean.getBoolean(BLOCK_DELTA_FLAG) && !sparseAtConstruction)
            throw new IllegalStateException("Block delta must be enabled before metadata construction");
        if(sparseAtConstruction && PagedSavedDataRuntime.configured() && loadedFromPagedColdRoot)
            return sparseCapture(root);
        root.putInt("format",FORMAT_VERSION);
        var savedPlans=new ListTag();
        for(var plan:plans.values()) {
            if(plan.planId==null)continue;
            savedPlans.add(saved(plan));
        }
        root.put("plans",savedPlans);
        var savedJournals=new ListTag();
        for(var journal:journals.values())savedJournals.add(saved(journal));
        root.put("journals",savedJournals);
        return root;
    }
}
