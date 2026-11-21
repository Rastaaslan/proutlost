package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * Main-thread inbox for native J06 session resets.
 * NeoForge may deliver the reset before ClientLevel or LocalPlayer exists.
 * Never consume it into a player-less F8/perception session; never rewind a
 * connection epoch on an out-of-order packet from the same world.
 */
public final class PendingClientReset {
    private SessionReset pending;
    private SessionReset applied;

    private static int order(SessionReset a,SessionReset b) {
        int boot=a.bootEpoch().compareTo(b.bootEpoch());
        return boot!=0?boot:a.connectionEpoch().compareTo(b.connectionEpoch());
    }
    public boolean offer(SessionReset next) {
        Objects.requireNonNull(next);
        if(applied!=null && applied.worldId().equals(next.worldId()) &&
           order(next,applied)<=0)return false;
        if(pending!=null && pending.worldId().equals(next.worldId()) &&
           order(next,pending)<=0)return false;
        pending=next;
        return true;
    }
    public boolean hasPending(){return pending!=null;}
    public Optional<SessionReset> takeIfReady(boolean clientLevel,
                                             boolean localPlayer) {
        if(!clientLevel||!localPlayer||pending==null)
            return Optional.empty();
        var current=pending;
        pending=null;
        applied=current;
        return Optional.of(current);
    }
    public void clear(){pending=null;applied=null;}
}
