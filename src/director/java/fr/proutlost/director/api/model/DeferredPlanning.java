package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record DeferredPlanning(List<SampleRequest> observations,List<fr.proutlost.director.api.error.ReasonCode> reasons) implements PlanningResult {public DeferredPlanning {observations=list(observations);reasons=list(reasons);require(!observations.isEmpty()&&!reasons.isEmpty(),"targeted planning deferral");}}
