package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * TEST_ONLY monotone Director tick anchored to recovered J04 memory.
 * Minecraft GameTestServer can recreate its Level with gameTime=0 even
 * while keeping the J04 WAL identity and historical memory. Initializing
 * from getGameTime alone then replays older ticks over durable player state.
 *
 * The authoritative production clock belongs to DirectorWorldState/J04;
 * this proof-lane clock deliberately does not satisfy that requirement.
 */
public final class DirectorLabRecoveredClock {
    private final MinecraftServer server;
    private final long gameAtBoot;
    private final long anchoredDirectorTick;
    private long last;
    public DirectorLabRecoveredClock(MinecraftServer server,MemoryView verified) {this(server,verified,0);}
    public DirectorLabRecoveredClock(MinecraftServer server,MemoryView verified,long metadataClockFloor) {
        if(metadataClockFloor<0)throw new IllegalArgumentException("metadata clock floor");
        this.server=Objects.requireNonNull(server);
        Objects.requireNonNull(verified);
        actor();
        gameAtBoot=server.overworld().getGameTime();
        long highest=-1;
        for(var player:verified.operationalPlayers())
            highest=Math.max(highest,player.header().updatedTick().value());
        for(var region:verified.regions())
            highest=Math.max(highest,region.header().updatedTick().value());
        for(var region:verified.regionRecords().values())
            highest=Math.max(highest,region.header().updatedTick().value());
        for(var sig:verified.significantPlayers())
            highest=Math.max(highest,sig.header().updatedTick().value());
        highest=Math.max(highest,metadataClockFloor>0?metadataClockFloor:-1);
        anchoredDirectorTick=Math.max(gameAtBoot,highest<0?gameAtBoot:
            Math.incrementExact(highest));
        last=anchoredDirectorTick;
        System.out.println("DIRECTOR_NATIVE_J04_RECOVERED_CLOCK_BOUND old_highest="+
            highest+" monotone_start="+anchoredDirectorTick+" game_at_boot="+gameAtBoot);
    }
    private void actor(){
        if(!server.isSameThread())
            throw new IllegalStateException("J04 clock Minecraft actor required");
    }
    public long now(){
        actor();
        long delta=Math.subtractExact(server.overworld().getGameTime(),gameAtBoot);
        if(delta<0)throw new IllegalStateException("Minecraft gameTime rollback");
        long tick=Math.addExact(anchoredDirectorTick,delta);
        if(tick<last)throw new IllegalStateException("Director time regression");
        last=tick;return tick;
    }
}
