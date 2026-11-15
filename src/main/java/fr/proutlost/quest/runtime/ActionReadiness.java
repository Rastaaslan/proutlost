package fr.proutlost.quest.runtime;

import fr.proutlost.quest.core.QuestCore;
import java.util.*;

/** Availability is derived from prepared usable adapters, not from a promise in a journal string. */
public final class ActionReadiness {
    private ActionReadiness(){}
    public static void refresh(QuestCore core,PreparedSites sites,boolean ambientEnabled){
        var counts=new EnumMap<PreparedSites.Kind,Long>(PreparedSites.Kind.class);
        sites.sites().values().forEach(s->counts.merge(s.kind(),1L,Long::sum));
        core.actionReadiness(id->switch(id){
            case "P0"->has(counts,"SAFE_SHORE");case "P1","B2","D1"->has(counts,"CAMP");
            case "P2"->has(counts,"SIGNAL","OLD_CAMP");case "P3"->has(counts,"WRECK","OLD_CAMP");
            case "A1"->has(counts,"OLD_CAMP");case "A2"->has(counts,"ARCHIVE");case "A3"->has(counts,"ARCHIVE","OLD_SHELTER");
            case "B1"->ambientEnabled&&has(counts,"CAMP");case "B3"->has(counts,"OLD_SHELTER");
            case "C1","C2","C3"->has(counts,"PERCEPTION");case "D2"->has(counts,"ARCHIVE","OLD_SHELTER","PASSAGE");
            case "D3"->counts.getOrDefault(PreparedSites.Kind.PASSAGE,0L)>=2&&counts.getOrDefault(PreparedSites.Kind.DETOUR,0L)>=2;
            case "E1"->sites.sites().values().stream().filter(s->s.kind()==PreparedSites.Kind.SIGNAL_REGION).map(s->s.dimension()+":"+Math.floorDiv(s.x(),128)+":"+Math.floorDiv(s.z(),128)).distinct().count()>=2;
            case "E2","F2"->has(counts,"TRANSMITTER");case "E3","R1"->has(counts,"TRANSMITTER","RETREAT");
            case "F4"->has(counts,"TRANSMITTER")&&(has(counts,"PASSAGE")||has(counts,"DETOUR"));
            default->true;
        });
    }
    private static boolean has(Map<PreparedSites.Kind,Long> counts,String...kinds){return Arrays.stream(kinds).allMatch(k->counts.getOrDefault(PreparedSites.Kind.valueOf(k),0L)>0);}
}
