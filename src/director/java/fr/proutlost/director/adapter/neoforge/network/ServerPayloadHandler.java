package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;
import java.util.function.*;

/** Main-thread ingress. The bootstrap binds callbacks to the owning domains using frozen DTOs only.
 * Admin owns query snapshots, command idempotency and permission revalidation before deferred writes.
 * Journal owns durable deduplication. The transport never invents snapshot revisions or durable ACKs. */
public final class ServerPayloadHandler implements BiConsumer<Object,IPayloadContext> {
    private final PerceptionTransport transport;
    private final BiConsumer<ActorIdentity,Object> accepted;
    private final BiPredicate<PlayerId,FactId> receivedFacts;
    private final BiPredicate<PlayerId,AnchorInteraction> liveAnchor;
    private final BiConsumer<PlayerId,String> rejection;
    public ServerPayloadHandler(PerceptionTransport transport,BiConsumer<ActorIdentity,Object> accepted,
        BiPredicate<PlayerId,FactId> receivedFacts,BiPredicate<PlayerId,AnchorInteraction> liveAnchor,BiConsumer<PlayerId,String> rejection){
        this.transport=Objects.requireNonNull(transport);this.accepted=Objects.requireNonNull(accepted);
        this.receivedFacts=Objects.requireNonNull(receivedFacts);this.liveAnchor=Objects.requireNonNull(liveAnchor);this.rejection=Objects.requireNonNull(rejection);
    }
    @Override public void accept(Object dto,IPayloadContext context){
        Checks.require(context.player() instanceof ServerPlayer,"server sender");var player=(ServerPlayer)context.player();
        Checks.require(player.server.isSameThread(),"server actor thread");var id=new PlayerId(player.getUUID());
        if(transport.identity(id).isEmpty()){rejection.accept(id,"STALE_CONTEXT");return;}
        int permission=0;for(int level=4;level>0;level--)if(player.hasPermissions(level)){permission=level;break;}
        var actor=transport.actor(id,permission);boolean valid;
        if(dto instanceof Capabilities c)valid=transport.capabilities(id,c);
        else if(dto instanceof PerceptionAck ack){valid=transport.acknowledge(id,ack);if(valid)accepted.accept(actor,ack);}
        else if(dto instanceof RecordObservation observation)valid=transport.record(id,observation,f->receivedFacts.test(id,f),r->accepted.accept(actor,r));
        else if(dto instanceof AnchorInteraction action)valid=transport.interaction(id,action,a->liveAnchor.test(id,a),a->accepted.accept(actor,a));
        else if(dto instanceof AdminQuery query){valid=actor.permissionLevel()>=4&&transport.adminQuery(id,query.actorSession());if(valid)accepted.accept(actor,query);}
        else if(dto instanceof AdminCommand command){valid=AdminContracts.authorized(command,actor)&&transport.admin(id,command.actorSession());
            // RecordObservation must use the dedicated provenance-checked packet, never this generic command.
            if(command.operation()==AdminOperation.RECORD_OBSERVATION)valid=false;
            if(valid)accepted.accept(actor,command);
        } else valid=false;
        if(!valid)rejection.accept(id,"AUTH_DENIED_OR_STALE_CONTEXT");
    }
}
