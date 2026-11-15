package fr.proutlost.quest.runtime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Transient targeted delivery queue. Persistent fragments remain recoverable after disconnection. */
public final class PerceptionDelivery {
    private record Note(UUID player,int due,float pitch){}
    private static final List<Note> QUEUE=new ArrayList<>();
    private PerceptionDelivery(){}
    public static void schedule(ServerPlayer player,List<String> sounds){
        int tick=player.server.getTickCount();for(int i=0;i<sounds.size();i++)QUEUE.add(new Note(player.getUUID(),tick+i*8,switch(sounds.get(i)){case "grave"->.5f;case "moyen"->1f;case "aigu"->1.5f;default->throw new IllegalArgumentException();}));
    }
    public static void tick(ServerTickEvent.Post event){
        var it=QUEUE.iterator();while(it.hasNext()){var note=it.next();if(note.due()>event.getServer().getTickCount())continue;it.remove();var p=event.getServer().getPlayerList().getPlayer(note.player());if(p!=null)p.playNotifySound(SoundEvents.NOTE_BLOCK_HARP.value(),SoundSource.AMBIENT,.7f,note.pitch());}
    }
    public static void stopped(ServerStoppedEvent event){QUEUE.clear();}
}
