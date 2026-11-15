package fr.proutlost.director.api.model;

import java.util.*;

/** One immutable current J04 cut. This DTO is evidence, never an effect permission. */
public record PhysicalEventReadback(StoreSession session,Sequence cut,Optional<EventInstance> event,
        Map<MutationId,MutationRecord> mutations,List<OwnershipRecord> owners,
        List<EntityOwnershipRecord> entities,Set<OperationToken> unmatchedPrepares,boolean preStartAbortProven,Set<MutationId> cleanupProvenWithoutMutation) {
    public PhysicalEventReadback {
        Objects.requireNonNull(session);Objects.requireNonNull(cut);Objects.requireNonNull(event);
        mutations=Map.copyOf(mutations);owners=List.copyOf(owners);entities=List.copyOf(entities);
        unmatchedPrepares=Set.copyOf(unmatchedPrepares);cleanupProvenWithoutMutation=Set.copyOf(cleanupProvenWithoutMutation);
    }
    /** A terminal label alone cannot release a still-owned or uncertain site pin. */
    public boolean released() {
        if(!unmatchedPrepares.isEmpty())return false;
        if(event.isEmpty())return preStartAbortProven&&mutations.isEmpty()&&owners.isEmpty()&&entities.isEmpty();
        var e=event.orElseThrow();
        if(!Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(e.state())
                ||e.outcome().isEmpty()||(e.outcome().orElseThrow()==Outcome.DELIVERY_UNCERTAIN&&mutations.isEmpty()&&entities.isEmpty()&&owners.isEmpty())
                ||!mutations.keySet().containsAll(e.mutationRefs()))return false;
        for(var m:mutations.values()) {
            if(!m.instanceId().equals(e.instanceId())
                    ||(m.state()!=MutationState.ROLLED_BACK&&m.state()!=MutationState.YIELDED))return false;
            var linked=owners.stream().filter(o->o.creationMutationId().equals(m.mutationId())
                &&o.ownerTokens().contains(m.ownerToken())&&o.epoch().equals(m.ownerEpoch())).toList();
            if(linked.isEmpty())return false;
            for(var o:linked) {
                if(m.state()==MutationState.ROLLED_BACK&&o.disposition()!=OwnershipDisposition.RELEASED)return false;
                if(m.state()==MutationState.YIELDED&&o.disposition()!=OwnershipDisposition.YIELDED_TO_PLAYER)return false;
            }
        }
        for(var o:owners){
            if(o.disposition()!=OwnershipDisposition.RELEASED&&o.disposition()!=OwnershipDisposition.YIELDED_TO_PLAYER)return false;
            if(!mutations.containsKey(o.creationMutationId())&&!cleanupProvenWithoutMutation.contains(o.creationMutationId()))return false;
        }
        for(var entity:entities) {
            if(!entity.instanceId().equals(e.instanceId()))return false;
            if(entity.disposition()==OwnershipDisposition.RELEASED&&entity.phase()==EntityPhase.REMOVED)continue;
            if(entity.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER&&entity.adoptedBy().isPresent())continue;
            return false;
        }
        return true;
    }
}
