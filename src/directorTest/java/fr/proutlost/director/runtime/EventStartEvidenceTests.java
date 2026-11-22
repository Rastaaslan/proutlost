package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.Optional;

public final class EventStartEvidenceTests {
    public static void main(String[] args) {
        var observed=new Tick(805935);
        for(var state:EventState.values())if(state!=EventState.RUNNING)
            check(EventStartEvidence.observe(Optional.empty(),state,observed,true).isEmpty(),"no start from non-RUNNING metadata");
        check(EventStartEvidence.observe(Optional.empty(),EventState.RUNNING,observed,false).isEmpty(),"RUNNING label without real execution proof cannot start");
        check(EventStartEvidence.observe(Optional.empty(),EventState.RUNNING,observed,true).equals(Optional.of(observed)),"real execution observed at RUNNING sets start");
        var first=Optional.of(new Tick(42));
        for(var state:EventState.values())check(EventStartEvidence.observe(first,state,observed,true).equals(first),"recovery and later states retain original start");
        check(EventStartEvidence.observe(Optional.empty(),EventState.SUCCEEDED,observed,true).isEmpty(),"old terminal null start is preserved without invented historical timestamp");
        System.out.println("{\"scenarios\":5,\"pass\":5,\"native_or_human_qualification\":false}");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
