package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Conservative pure recovery table. A WAL COMMIT by itself is never physical proof. */
public final class RecoveryClassifier {
    public record Expected(OwnerToken owner,Epoch epoch,Epoch override,Fingerprint pre,Fingerprint post) {
        public Expected {Objects.requireNonNull(owner);Objects.requireNonNull(epoch);Objects.requireNonNull(override);Objects.requireNonNull(pre);Objects.requireNonNull(post);}
    }
    private RecoveryClassifier() {}
    public static Map<ResourceKey,ReconcileStatus> classify(Map<ResourceKey,Expected> expected,List<RecoveryEvidence> proofs) {
        Map<ResourceKey,List<RecoveryEvidence>> grouped=new HashMap<>();proofs.forEach(p->grouped.computeIfAbsent(p.resource(),k->new ArrayList<>()).add(p));
        Map<ResourceKey,ReconcileStatus> results=new HashMap<>();
        for(var entry:expected.entrySet()) {
            var e=entry.getValue();var candidates=grouped.getOrDefault(entry.getKey(),List.of());
            if(candidates.isEmpty()){results.put(entry.getKey(),ReconcileStatus.UNAVAILABLE);continue;}
            if(candidates.stream().distinct().count()!=1){results.put(entry.getKey(),ReconcileStatus.AMBIGUOUS);continue;}
            var p=candidates.getFirst();ReconcileStatus status;
            if(!p.playerOverrideEpoch().equals(e.override()) || p.ownerEpoch().compareTo(e.epoch())>0)status=ReconcileStatus.PLAYER_OVERRIDE;
            else if(p.status()!=EvidenceStatus.CONFIRMED || p.observedHash().isEmpty())status=ReconcileStatus.UNAVAILABLE;
            else if(!p.ownerEpoch().equals(e.epoch()) || !p.ownerToken().equals(Optional.of(e.owner())))status=ReconcileStatus.AMBIGUOUS;
            else if(p.observedHash().get().equals(e.pre()) && p.localAbsenceProven())status=ReconcileStatus.SAME_PRE_PROVEN;
            else if(!e.pre().equals(e.post()) && p.observedHash().get().equals(e.post()))status=ReconcileStatus.SAME_POST;
            else status=ReconcileStatus.AMBIGUOUS;
            results.put(entry.getKey(),status);
        }
        return Map.copyOf(results);
    }
}
