package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure slot transitions. RESERVED is never a spawn permission; a durable spawn PREPARE and
 * live admission are still mandatory. UNLOADED/uncertain slots retain their budget forever
 * until positive terminal evidence or explicit transfer, never a timeout respawn.
 */
public final class EntityCohorts {
    private EntityCohorts() {}
    public static UUID stableEntityId(WorldId world,EventInstanceId event,CohortId cohort,String slot) {
        if(slot.isBlank())throw new IllegalArgumentException("slot");
        return UUID.nameUUIDFromBytes(("director-entity-v1\0"+world.value()+"\0"+event.value()+"\0"+cohort.value()+"\0"+slot).getBytes(StandardCharsets.UTF_8));
    }
    public static long persistentSlots(Collection<EntityOwnershipRecord> slots) {
        return slots.stream().filter(s->s.phase()!=EntityPhase.REMOVED).count();
    }
    public static boolean cleanupEligible(EntityOwnershipRecord slot,OwnerToken observedOwner,Epoch observedGeneration) {
        return slot.adoptedBy().isEmpty() && slot.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE
                && slot.ownerToken().equals(observedOwner) && slot.generation().equals(observedGeneration)
                && Set.of(EntityPhase.PRESENT,EntityPhase.MOVING,EntityPhase.CLEANING).contains(slot.phase());
    }
    public static EntityOwnershipRecord unloaded(EntityOwnershipRecord slot,Tick tick) {
        if(slot.phase()==EntityPhase.REMOVED || slot.phase()==EntityPhase.RESERVED || slot.phase()==EntityPhase.SPAWN_INTENT_DURABLE)
            throw new IllegalArgumentException("unload without presence evidence");
        return copy(slot,EntityPhase.UNLOADED,slot.disposition(),slot.adoptedBy(),slot.lastKnownChunk(),tick,slot.generation());
    }
    public static EntityOwnershipRecord loaded(EntityOwnershipRecord slot,UUID observedUuid,OwnerToken observedOwner,
                                               Epoch observedGeneration,ChunkRef chunk,Tick tick) {
        if(!slot.entityUuid().equals(observedUuid) || !slot.ownerToken().equals(observedOwner)
                || !slot.generation().equals(observedGeneration) || slot.phase()==EntityPhase.REMOVED
                || slot.phase()==EntityPhase.RESERVED)throw new IllegalArgumentException("ambiguous or duplicate entity provenance");
        return copy(slot,slot.adoptedBy().isPresent()?EntityPhase.ADOPTED
                :slot.disposition()==OwnershipDisposition.DIRECTOR_DURABLE?EntityPhase.SETTLED:EntityPhase.PRESENT,
                slot.disposition(),slot.adoptedBy(),chunk,tick,slot.generation());
    }
    public static EntityOwnershipRecord adopted(EntityOwnershipRecord slot,PlayerId player,Tick tick) {
        if(slot.phase()==EntityPhase.REMOVED || slot.phase()==EntityPhase.RESERVED)throw new IllegalArgumentException("adoption without entity");
        return copy(slot,EntityPhase.ADOPTED,OwnershipDisposition.YIELDED_TO_PLAYER,Optional.of(player),slot.lastKnownChunk(),tick,slot.generation().plus(1));
    }
    public static EntityOwnershipRecord settled(EntityOwnershipRecord slot,Tick tick) {
        if(slot.adoptedBy().isPresent() || slot.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE
                || !Set.of(EntityPhase.PRESENT,EntityPhase.MOVING).contains(slot.phase()))throw new IllegalArgumentException("settle ownership");
        return copy(slot,EntityPhase.SETTLED,OwnershipDisposition.DIRECTOR_DURABLE,Optional.empty(),slot.lastKnownChunk(),tick,slot.generation().plus(1));
    }
    /** Called only on positively observed death/removal, never absence or elapsed time. */
    public static EntityOwnershipRecord removed(EntityOwnershipRecord slot,UUID observedUuid,Tick tick) {
        if(!slot.entityUuid().equals(observedUuid))throw new IllegalArgumentException("removal identity");
        return copy(slot,EntityPhase.REMOVED,OwnershipDisposition.RELEASED,slot.adoptedBy(),slot.lastKnownChunk(),tick,slot.generation().plus(1));
    }
    private static EntityOwnershipRecord copy(EntityOwnershipRecord s,EntityPhase phase,OwnershipDisposition disposition,
                                               Optional<PlayerId> adopted,ChunkRef chunk,Tick tick,Epoch generation) {
        if(tick.compareTo(s.lastSeenTick())<0)throw new IllegalArgumentException("clock regression");
        return new EntityOwnershipRecord(new AggregateHeader(1,s.header().id(),s.header().revision().plus(1),s.header().lastSequence(),tick),
                s.ownerToken(),s.entityUuid(),s.instanceId(),s.cohortId(),s.slotId(),phase,disposition,chunk,tick,s.persistMode(),s.cleanupPolicy(),adopted,generation);
    }
}
