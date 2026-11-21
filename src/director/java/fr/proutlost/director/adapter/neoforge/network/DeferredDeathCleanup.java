package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Actor-owned deferred final-outcome check. At most one entry per current connection.
 * The supplier is evaluated after event dispatch, on server tick Post, never asynchronously. */
final class DeferredDeathCleanup {
    private record Pending(SessionIdentity session, BooleanSupplier finallyDead) {}
    private final PerceptionTransport transport;
    private final Map<PlayerId,Pending> pending=new HashMap<>();
    DeferredDeathCleanup(PerceptionTransport transport){this.transport=transport;}
    void observe(PlayerId player,BooleanSupplier finallyDead){
        transport.identity(player).ifPresent(session->pending.put(player,new Pending(session,finallyDead)));
    }
    void forget(PlayerId player){pending.remove(player);}
    void drain(){
        for(var entry:pending.entrySet()){
            var value=entry.getValue();
            // A delayed death must not close a respawn/reconnect session.
            if(transport.identity(entry.getKey()).filter(value.session()::equals).isPresent()&&value.finallyDead().getAsBoolean())
                transport.close(entry.getKey());
        }
        pending.clear();
    }
    void clear(){pending.clear();}
}
