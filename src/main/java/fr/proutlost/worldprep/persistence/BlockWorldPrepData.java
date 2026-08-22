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
    public static final String FILE_ID="proutlost_worldprep_blocks";
    public static final Factory<BlockWorldPrepData> FACTORY=new Factory<>(BlockWorldPrepData::new,BlockWorldPrepData::load);
    public record Change(int x,int y,int z,String before,String applied,String province){}
    public static final class Plan { public final String area,pass,profile,input; public String fingerprint; public long eligible,skipped; public final Map<String,Change> changes=new LinkedHashMap<>(); public Plan(String a,String p,String profile,String input,String fp){area=a;pass=p;this.profile=profile;this.input=input;fingerprint=fp;} }
    public static final class Journal { public final UUID id,job; public final String area,pass,fingerprint; public final Map<String,Change> changes=new LinkedHashMap<>(); public Journal(UUID id,UUID job,String area,String pass,String fp){this.id=id;this.job=job;this.area=area;this.pass=pass;fingerprint=fp;} }
    private final Map<String,Plan> plans=new LinkedHashMap<>(); private final Map<UUID,Journal> journals=new LinkedHashMap<>();
    public Map<String,Plan> plans(){return plans;} public Map<UUID,Journal> journals(){return journals;} public void changed(){setDirty();}
    public static String planKey(String area,String pass){return area+"|"+pass;} public static String posKey(int x,int y,int z){return x+":"+y+":"+z;}
    public static BlockWorldPrepData load(CompoundTag root,HolderLookup.Provider ignored){var d=new BlockWorldPrepData();for(Tag v:root.getList("plans",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var p=new Plan(t.getString("area"),t.getString("pass"),t.getString("profile"),t.getString("input"),t.getString("fingerprint"));p.eligible=t.getLong("eligible");p.skipped=t.getLong("skipped");readChunks(t.getList("chunks",Tag.TAG_COMPOUND),p.changes);d.plans.put(planKey(p.area,p.pass),p);}for(Tag v:root.getList("journals",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var j=new Journal(t.getUUID("id"),t.getUUID("job"),t.getString("area"),t.getString("pass"),t.getString("fingerprint"));readChunks(t.getList("chunks",Tag.TAG_COMPOUND),j.changes);d.journals.put(j.id,j);}return d;}
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider ignored){var ps=new ListTag();for(var p:plans.values()){var t=new CompoundTag();t.putString("area",p.area);t.putString("pass",p.pass);t.putString("profile",p.profile);t.putString("input",p.input);t.putString("fingerprint",p.fingerprint);t.putLong("eligible",p.eligible);t.putLong("skipped",p.skipped);t.put("chunks",writeChunks(p.changes));ps.add(t);}root.put("plans",ps);var js=new ListTag();for(var j:journals.values()){var t=new CompoundTag();t.putUUID("id",j.id);t.putUUID("job",j.job);t.putString("area",j.area);t.putString("pass",j.pass);t.putString("fingerprint",j.fingerprint);t.put("chunks",writeChunks(j.changes));js.add(t);}root.put("journals",js);return root;}
    private static ListTag writeChunks(Map<String,Change> changes){var grouped=new LinkedHashMap<Long,ListTag>();for(var c:changes.values()){long k=((long)(c.x()>>4)<<32)^((c.z()>>4)&0xffffffffL);var l=grouped.computeIfAbsent(k,x->new ListTag());var t=new CompoundTag();t.putByte("x",(byte)(c.x()&15));t.putInt("y",c.y());t.putByte("z",(byte)(c.z()&15));t.putString("before",c.before());t.putString("applied",c.applied());t.putString("province",c.province());l.add(t);}var out=new ListTag();for(var e:grouped.entrySet()){var t=new CompoundTag();t.putInt("x",(int)(e.getKey()>>32));t.putInt("z",(int)(long)e.getKey());t.put("entries",e.getValue());out.add(t);}return out;}
    private static void readChunks(ListTag chunks,Map<String,Change> out){for(Tag v:chunks){var ch=(CompoundTag)v;int cx=ch.getInt("x"),cz=ch.getInt("z");for(Tag ev:ch.getList("entries",Tag.TAG_COMPOUND)){var t=(CompoundTag)ev;var c=new Change((cx<<4)+(t.getByte("x")&15),t.getInt("y"),(cz<<4)+(t.getByte("z")&15),t.getString("before"),t.getString("applied"),t.getString("province"));out.put(posKey(c.x(),c.y(),c.z()),c);}}}
}
