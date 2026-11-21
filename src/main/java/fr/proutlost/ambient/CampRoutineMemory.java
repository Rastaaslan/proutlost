package fr.proutlost.ambient;

import com.google.gson.Gson;
import java.util.*;

/** General world capability. Stores counts/relative sectors, never trajectories or quest state. */
public final class CampRoutineMemory {
    public record Replay(String location,Set<UUID> witnesses,Map<Integer,Integer> sectors,long timestamp){}
    private static final class Memory {
        long first,last,replayed=-1; int visits; Map<Integer,Integer> sectors=new TreeMap<>();
    }
    private final Map<String,Memory> camps=new HashMap<>();
    public Optional<Replay> sample(String location,Map<UUID,Integer> participants,long now,int minVisits,long minElapsed,long cooldown){
        if(minVisits<2||minElapsed<0||cooldown<1)throw new IllegalArgumentException();
        if(participants.isEmpty())return Optional.empty();
        var m=camps.computeIfAbsent(location,k->{var v=new Memory();v.first=now;v.last=-1;return v;});
        if(now<=m.last)return Optional.empty();m.last=now;m.visits++;
        participants.values().forEach(sector->m.sectors.merge(sector,1,Integer::sum));
        if(m.visits<minVisits||now-m.first<minElapsed||(m.replayed>=0&&now-m.replayed<cooldown))return Optional.empty();
        m.replayed=now;return Optional.of(new Replay(location,Set.copyOf(participants.keySet()),Map.copyOf(m.sectors),now));
    }
    public String save(){return new Gson().toJson(this);}
    public static CampRoutineMemory restore(String json){return new Gson().fromJson(json,CampRoutineMemory.class);}
}
