package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** A button reference selects an already received header; it creates no receipt or authority. */
public final class DirectorAnchorInteractionCommands {
    private DirectorAnchorInteractionCommands(){}
    public record Context(EventInstanceId instance,AnchorId anchor,EffectId effect,Sequence sequence) {
        public Context { Objects.requireNonNull(instance);Objects.requireNonNull(anchor);Objects.requireNonNull(effect);Objects.requireNonNull(sequence); }
    }
    public static Context parse(String instance,String anchor,String effect,long sequence) {
        return new Context(new EventInstanceId(uuid(instance)),new AnchorId(uuid(anchor)),
            new EffectId(uuid(effect)),new Sequence(sequence));
    }
    private static UUID uuid(String text) {
        var result=UUID.fromString(text);
        if(!result.toString().equals(text))throw new IllegalArgumentException("canonical UUID required");
        return result;
    }
    public static ResourceId action(String commandAction) {
        return switch(commandAction) {
            case "listen" -> new ResourceId("proutlost:listen");
            case "leave_trace" -> new ResourceId("proutlost:leave_trace");
            default -> throw new IllegalArgumentException("unknown choice action");
        };
    }
    private static String actionName(ResourceId action) {
        return switch(action.value()) {
            case "proutlost:listen" -> "listen";
            case "proutlost:leave_trace" -> "leave_trace";
            default -> throw new IllegalArgumentException("unknown choice action");
        };
    }
    public static boolean matches(Context context,PerceptionHeader received,AnchorId currentAnchor) {
        return context!=null&&received!=null&&currentAnchor!=null
            &&context.anchor().equals(currentAnchor)&&context.instance().equals(received.instanceId())
            &&context.effect().equals(received.effectId())&&context.sequence().equals(received.sequence());
    }
    public static AnchorInteraction interaction(SessionIdentity current,PerceptionHeader received,
            Context context,ResourceId action) {
        Objects.requireNonNull(current);Objects.requireNonNull(received);Objects.requireNonNull(context);
        actionName(action);
        if(!current.worldId().equals(received.worldId())||!current.bootEpoch().equals(received.bootEpoch())
            ||!current.connectionEpoch().equals(received.connectionEpoch())||!current.dimension().equals(received.dimension())
            ||!matches(context,received,context.anchor())||received.remainingTtlTicks().value()==0)
            throw new IllegalArgumentException("current received choice reference required");
        // Keep the exact issued/received TTL and header. The server alone checks live expiry,
        // actual recipient, current offer, role, physical fingerprint and durable mutation lane.
        return new AnchorInteraction(received,context.anchor(),action);
    }
    public static String button(boolean catalogue,PerceptionHeader issued,AnchorId anchor,ResourceId action) {
        Objects.requireNonNull(issued);Objects.requireNonNull(anchor);
        return (catalogue?"/proutlost choice select ":"/proutlost director lab choice select ")
            +issued.instanceId().value()+" "+actionName(action)+" "+anchor.value()+" "
            +issued.effectId().value()+" "+issued.sequence().value();
    }
}
