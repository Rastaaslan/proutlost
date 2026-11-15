package fr.proutlost.quest.runtime;

import com.google.gson.Gson;
import fr.proutlost.quest.interaction.Interactions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Explicit bindings to existing prepared sites. Never generates, modifies, or loads world chunks. */
public final class PreparedSites {
    public enum Kind { WRECK, SAFE_SHORE, CAMP, SIGNAL, OLD_CAMP, ARCHIVE, OLD_SHELTER, PERCEPTION, PASSAGE, DETOUR, SIGNAL_REGION, TRANSMITTER, RETREAT, MEMORY }
    public record Site(Kind kind,String dimension,int x,int y,int z) {
        public BlockPos pos(){return new BlockPos(x,y,z);}
        public boolean near(ServerPlayer p,int radius){return dimension.equals(p.level().dimension().location().toString())&&p.blockPosition().distSqr(pos())<=radius*radius;}
    }
    private final Map<String,String> baselines=new HashMap<>();
    public void captureBaseline(String id,String block){baselines.putIfAbsent(id,block);}
    public boolean changedPoint(String id,String block){return baselines.containsKey(id)&&!baselines.get(id).equals(block);}
    private final Map<String,Site> sites=new LinkedHashMap<>();
    private final Map<String,Long> activity=new HashMap<>();
    private final Map<String,Integer> visits=new HashMap<>();
    private final Map<UUID,String> inside=new HashMap<>();
    private final Map<UUID,Interactions.Comparison> comparisons=new HashMap<>();
    public void bind(String id,Site site) {
        if(!id.matches("[a-z0-9_]{1,48}"))throw new IllegalArgumentException("Invalid site id");
        if(sites.entrySet().stream().anyMatch(e->!e.getKey().equals(id)&&e.getValue().equals(site)))throw new IllegalArgumentException("Duplicate site anchor");
        var old=sites.putIfAbsent(id,site);if(old!=null&&!old.equals(site))throw new IllegalArgumentException("Site binding immutable; choose a new id");
    }
    public Map<String,Site> sites(){return Map.copyOf(sites);}
    public Optional<Map.Entry<String,Site>> near(ServerPlayer p,Kind kind,int radius){return sites.entrySet().stream().filter(e->e.getValue().kind()==kind&&e.getValue().near(p,radius)).min(Comparator.<Map.Entry<String,Site>>comparingDouble(e->p.blockPosition().distSqr(e.getValue().pos())).thenComparing(Map.Entry::getKey));}
    public void activity(ServerPlayer p,boolean moved){
        var camp=near(p,Kind.CAMP,12);
        if(camp.isEmpty()){inside.remove(p.getUUID());return;}
        String id=camp.get().getKey();String old=inside.put(p.getUUID(),id);
        if(!id.equals(old))visits.merge(id,1,Integer::sum);
        if(moved)activity.merge(id,20L,Long::sum);
    }
    public long activeTicks(String id){return activity.getOrDefault(id,0L);}
    public int visits(String id){return visits.getOrDefault(id,0);}
    public void prepare(UUID player,Interactions.Comparison comparison){comparisons.put(player,comparison);}
    public Optional<Interactions.Comparison> comparison(UUID player){return Optional.ofNullable(comparisons.get(player));}
    public String save(){return new Gson().toJson(this);}
    public static PreparedSites restore(String json){return new Gson().fromJson(json,PreparedSites.class);}
}
