package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;

/** References to personal facts actually emitted by the existing ACK/journal route.
 * Transport owns the shared128-authority ceiling. This cache stores no new fact,
 * grants no independent authority, and never represents a renderer receipt. */
public final class DirectorPersonalFactReceipts {
    private record Emitted(SessionIdentity identity,PerceptionHeader envelope,DirectorFact fact) {}
    private final Thread owner=Thread.currentThread();
    private final int sessions,references;
    private final Map<PlayerId,Map<FactId,Emitted>> emitted=new HashMap<>();
    public DirectorPersonalFactReceipts(int sessions,int references){
        Checks.require(sessions>0&&sessions<=64&&references>0&&references<=128,"personal fact reference bounds");
        this.sessions=sessions;this.references=references;
    }
    private void actor(){Checks.require(Thread.currentThread()==owner,"personal fact actor");}
    public void clear(PlayerId player){actor();emitted.remove(player);}
    public void clear(){actor();emitted.clear();}
    public boolean emit(PerceptionTransport transport,SessionIdentity identity,DirectorFact fact,
                        LongSupplier sequence,Consumer<PerceptionHeader> sender){
        actor();Objects.requireNonNull(sender);var player=identity.playerId();
        if(transport.identity(player).filter(identity::equals).isEmpty())return false;
        var source=DirectorRecordedPerception.source(fact,player);
        if(source.isEmpty()||!source.orElseThrow().header().worldId().equals(identity.worldId()))return false;
        var rows=emitted.get(player);
        if(rows!=null&&!rows.isEmpty()&&!rows.values().iterator().next().identity().equals(identity)){clear(player);rows=null;}
        var prior=rows==null?null:rows.get(fact.factId());
        if(prior!=null&&!prior.fact().equals(fact))return false;
        if((rows==null&&emitted.size()>=sessions)||(rows!=null&&prior==null&&rows.size()>=references))return false;
        PerceptionHeader envelope;
        if(prior!=null)envelope=prior.envelope();
        else if(transport.received(player,source.orElseThrow().header()))envelope=source.orElseThrow().header();
        else{
            var original=source.orElseThrow().header();
            // Current envelope authorizes RECORD only. Original source bytes/epochs remain immutable.
            envelope=new PerceptionHeader(1,identity.worldId(),identity.bootEpoch(),identity.connectionEpoch(),
                identity.dimension(),original.instanceId(),original.effectId(),new Sequence(sequence.getAsLong()),new DurationTicks(0));
        }
        var exact=envelope;
        if(!transport.emitRecordFact(player,fact.factId(),exact,()->sender.accept(exact)))return false;
        emitted.computeIfAbsent(player,p->new HashMap<>()).put(fact.factId(),new Emitted(identity,exact,fact));
        return true;
    }
    public Optional<DirectorFact> source(PerceptionTransport transport,SessionIdentity identity,RecordObservation request,
                                         Function<FactId,Optional<DirectorFact>> retained){
        actor();var player=identity.playerId();
        if(transport.identity(player).filter(identity::equals).isEmpty())return Optional.empty();
        var id=request.observation().receivedFactId().orElse(null);
        var rows=emitted.get(player);var prior=rows==null?null:rows.get(id);
        if(prior==null||!prior.identity().equals(identity)||!prior.envelope().equals(request.header()))return Optional.empty();
        var current=retained.apply(id);
        if(current.isEmpty()||!prior.fact().equals(current.orElseThrow())
            ||DirectorRecordedPerception.source(current.orElseThrow(),player).isEmpty())return Optional.empty();
        return current;
    }
    public static boolean current(SessionIdentity identity,WorldId world,Epoch boot,Epoch connection){
        return identity!=null&&identity.worldId().equals(world)&&identity.bootEpoch().equals(boot)
            &&identity.connectionEpoch().equals(connection);
    }
    public static String command(FactId fact,PerceptionHeader envelope){
        return "/proutlost perception recordfact "+fact.value()+" "+envelope.instanceId().value()+" "+
            envelope.effectId().value()+" "+envelope.sequence().value()+" "+envelope.remainingTtlTicks().value()+" "+
            envelope.bootEpoch().value()+" "+envelope.connectionEpoch().value()+" "+envelope.worldId().value();
    }
}
