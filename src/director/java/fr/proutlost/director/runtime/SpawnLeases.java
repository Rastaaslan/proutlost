package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Actor-local, revocable contextual weights. A lease never changes caps or an external denial.
 * Installation/removal is performed by a journaled primitive; this registry has no world access.
 */
public final class SpawnLeases {
    public record Lease(OperationToken token,ResourceId rule,ScopeSet scopes,ResourceId species,
                        double multiplier,Tick expiry,Revision revision) {
        public Lease {Objects.requireNonNull(token);Objects.requireNonNull(rule);Objects.requireNonNull(scopes);Objects.requireNonNull(species);Objects.requireNonNull(expiry);Objects.requireNonNull(revision);if(!Double.isFinite(multiplier)||multiplier<0)throw new IllegalArgumentException("spawn multiplier");}
    }
    private final Thread actor=Thread.currentThread();private final int capacity;private final double ceiling;
    private final Map<OperationToken,Lease> leases=new HashMap<>();
    public SpawnLeases(int capacity,double ceiling){if(capacity<1||!Double.isFinite(ceiling)||ceiling<1)throw new IllegalArgumentException("lease bounds");this.capacity=capacity;this.ceiling=ceiling;}
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("server actor required");}
    public void install(Lease lease,Tick now){actor();if(lease.expiry().compareTo(now)<=0||lease.multiplier()>ceiling)throw new IllegalArgumentException("expired/oversized spawn lease");var previous=leases.get(lease.token());if(previous!=null&&!previous.equals(lease))throw new IllegalArgumentException("spawn operation substitution");if(previous==null&&leases.size()>=capacity)throw new IllegalStateException("spawn lease capacity");leases.put(lease.token(),lease);}
    public boolean revoke(OperationToken token,Revision revision){actor();Lease l=leases.get(token);if(l==null||!l.revision().equals(revision))return false;leases.remove(token);return true;}
    public double weight(ResourceId species,ScopeSet scope,double vanillaWeight,boolean otherRulesAllow,boolean capsAllow,Tick now){
        actor();if(!Double.isFinite(vanillaWeight)||vanillaWeight<0)throw new IllegalArgumentException("spawn weight");
        if(!otherRulesAllow||!capsAllow)return 0;
        double multiplier=1;
        for(Lease lease:leases.values().stream().sorted(Comparator.comparing(l->l.token().value())).toList())
            if(lease.expiry().compareTo(now)>0 && lease.species().equals(species) && lease.scopes().worldId().equals(scope.worldId())
                    && !Collections.disjoint(lease.scopes().scopes(),scope.scopes())) multiplier=Math.min(ceiling,multiplier*lease.multiplier());
        double result=vanillaWeight*multiplier;if(!Double.isFinite(result))throw new IllegalArgumentException("spawn weight overflow");return result;
    }
    public List<Lease> expire(Tick now){actor();var expired=leases.values().stream().filter(l->l.expiry().compareTo(now)<=0).sorted(Comparator.comparing(l->l.token().value())).toList();expired.forEach(l->leases.remove(l.token()));return expired;}
}
