package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure health fences shared by native measurement, durable WORLD publication and selection. */
public final class ServerHealthTransitions {
    private ServerHealthTransitions(){}
    public static HealthMode worst(HealthMode first,HealthMode second){
        Objects.requireNonNull(first);Objects.requireNonNull(second);return first.ordinal()>=second.ordinal()?first:second;
    }
    /** A recovery is not public before its original durable ACK has been verified. */
    public static HealthMode effective(HealthMode measured,HealthMode durable,Optional<HealthMode> pendingFrom){
        Objects.requireNonNull(pendingFrom);var result=worst(measured,durable);
        return pendingFrom.map(value->worst(result,value)).orElse(result);
    }
    public static boolean available(HealthMode mode){return mode==HealthMode.NORMAL||mode==HealthMode.DEGRADED;}
    public static boolean allowsNewScene(HealthMode mode,EventDefinition definition){
        Objects.requireNonNull(mode);Objects.requireNonNull(definition);
        if(mode==HealthMode.NORMAL)return true;if(mode!=HealthMode.DEGRADED)return false;
        var cost=definition.budget();
        return definition.families().contains(Family.SAFE_MICRO_EVENT)&&definition.safety().risk()==Risk.SAFE
            &&!definition.families().contains(Family.DANGER)&&!definition.families().contains(Family.DISASTER)
            &&cost.liveEntities()==0&&cost.persistentEntities()==0&&cost.mutationsInFlight()==0&&cost.navigationNodes()==0;
    }
    public static DirectorWorldState next(DirectorWorldState before,StoreSession session,Tick now,HealthMode measured){
        Objects.requireNonNull(before);Objects.requireNonNull(session);Objects.requireNonNull(now);Objects.requireNonNull(measured);
        Checks.require(before.worldId().equals(session.worldId())&&before.storeGeneration().equals(session.storeGeneration())
            &&before.bootEpoch().equals(session.bootEpoch()),"health WORLD session identity");
        Checks.require(now.compareTo(before.simTick())>=0&&now.compareTo(before.header().updatedTick())>=0,"health WORLD clock regression");
        return new DirectorWorldState(new AggregateHeader(before.header().schemaVersion(),before.header().id(),before.header().revision().plus(1),before.header().lastSequence(),now),
            before.worldId(),session.storeGeneration(),now,before.runControl(),measured,before.pacingState(),before.tension(),before.intensityProfileId(),
            before.catalogHash(),before.configHash(),session.bootEpoch(),before.lastCheckpointSeq(),before.engineControl());
    }
}
