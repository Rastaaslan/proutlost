package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Actor-captured identities and complete incidental audience. Never filter the source snapshot
 * down to role targets. Missing/incomplete coverage causes a targeted planning deferral.
 */
public record PlanningBindings(CandidateId candidateId,DefinitionRef definition,EventInstanceId instanceId,OperationToken operationToken,String stepId,RoleAssignments roles,ScopeSet scopes,Footprint footprint,Set<PlayerId> incidentalAudience,boolean audienceComplete) {
 public PlanningBindings {Objects.requireNonNull(candidateId);Objects.requireNonNull(definition);Objects.requireNonNull(instanceId);Objects.requireNonNull(operationToken);text(stepId);require(stepId.matches("[a-z][a-z0-9_]*"),"step id");Objects.requireNonNull(roles);Objects.requireNonNull(scopes);Objects.requireNonNull(footprint);incidentalAudience=set(incidentalAudience);}
}
