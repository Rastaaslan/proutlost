package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Actor projection of resident ownership. Tombstones are never TTL-evicted.
 * Local adapters must dirty their matching attachment before returning from the edit hook.
 * pendingChanges remains until an exact durable version is acknowledged; newer edits win.
 */
public final class OwnershipService {
    private final Thread actor=Thread.currentThread();
    private final Map<ResourceKey,OwnershipRecord> owned=new HashMap<>();
    private final Map<ResourceKey,OwnershipRecord> dirty=new HashMap<>();
    private final int capacity;
    public OwnershipService(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;}
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public void restore(OwnershipRecord record) {
        actor();OwnershipRecord old=owned.get(record.resourceKey());
        if(old!=null && (!old.equals(record)))throw new IllegalStateException("resident evidence must be reconciled, not overwritten");
        if(old==null && owned.size()>=capacity)throw new IllegalStateException("ownership resident capacity");
        owned.put(record.resourceKey(),record);
    }
    public Optional<OwnershipRecord> externalChange(ResourceKey resource,OwnershipRules.ExternalChange cause,Tick tick) {
        actor();OwnershipRecord before=owned.get(resource);if(before==null)return Optional.empty();
        OwnershipRecord next=OwnershipRules.cede(before,cause,tick);owned.put(resource,next);dirty.put(resource,next);return Optional.of(next);
    }
    public Optional<OwnershipRecord> view(ResourceKey resource){actor();return Optional.ofNullable(owned.get(resource));}
    public List<OwnershipRecord> pendingChanges(int limit) {
        actor();if(limit<1)throw new IllegalArgumentException("batch limit");
        return dirty.values().stream().sorted(Comparator.comparing((OwnershipRecord r)->r.resourceKey().dimension().value())
                .thenComparing(r->r.resourceKey().domain().name()).thenComparing(r->r.resourceKey().key())).limit(limit).toList();
    }
    public void acknowledged(OwnershipRecord exactVersion) {actor();dirty.remove(exactVersion.resourceKey(),exactVersion);}
    public OwnershipRules.Rollback rollback(ResourceKey key,OwnerToken owner,Epoch epoch,Epoch override,
                                            Fingerprint post,Optional<Fingerprint> observed,boolean safeInverse) {
        actor();OwnershipRecord record=owned.get(key);
        return record==null?OwnershipRules.Rollback.CONFLICT_ISOLATED
                :OwnershipRules.rollback(record,owner,epoch,override,post,observed,safeInverse);
    }
}
