package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Actor-local dispatch index only: it owns no journal, permission authority or
 * executor. The existing runtime keeps its opaque Owner. Replacing or removing
 * a live owner is refused. J05 still consumes its ordinary exact slice permit. */
public final class PhysicalSiteExecutionGuards {
    private PhysicalSiteExecutionGuards() {}
    private static final Map<WorldId,Owner> OWNERS=new HashMap<>();
    private record Bound(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver,
                         PhysicalSitePinJournalPort journal) {}
    private record Inverse(EffectIntent intent,BooleanSupplier ownership) {}
    public static final class Owner implements AutoCloseable {
        private final WorldId world;private final StoreSession session;private final Runnable actor;
        private final Thread thread=Thread.currentThread();private boolean closed,recovered;
        private final Map<EventInstanceId,Bound> events=new HashMap<>();
        private final Map<OperationToken,Inverse> inverses=new HashMap<>();
        private Owner(WorldId world,StoreSession session,Runnable actor){this.world=world;this.session=session;this.actor=actor;}
        private void actor(){if(Thread.currentThread()!=thread)throw new IllegalStateException("physical site guard actor");actor.run();}
        public void bind(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver,PhysicalSitePinJournalPort journal){
            actor();if(closed)throw new IllegalStateException("physical site owner fenced");pin.requirePlan(plan);
            if(!world.equals(pin.catalog().key().directorWorld())||!session.worldId().equals(world)
                    ||!session.storeGeneration().equals(pin.catalog().session().storeGeneration()))
                throw new IllegalArgumentException("physical site guard world/store generation changed");
            if(!journal.durable(pin))throw new IllegalArgumentException("physical site pin not actually J04 durable");
            resolver.requireCurrent(pin,plan,Optional.empty());
            var bound=new Bound(pin,plan,Objects.requireNonNull(resolver),Objects.requireNonNull(journal));
            var old=events.get(pin.event());
            if(old!=null&&!old.equals(bound))throw new IllegalArgumentException("physical site event pin changed");
            if(old==null&&events.size()>=128)throw new IllegalStateException("physical site guard capacity");events.put(pin.event(),bound);
        }
        /** Called only with exact J04-restored offer/plan after its source projection was recovered.
         * It restores cleanup identity; it does not make a historical need newly eligible. */
        public void bindRecoveredHistoric(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver,PhysicalSitePinJournalPort journal){
            actor();if(closed)throw new IllegalStateException("physical site owner fenced");pin.requirePlan(plan);
            if(!world.equals(pin.catalog().key().directorWorld())||!session.storeGeneration().equals(pin.catalog().session().storeGeneration())||!journal.durable(pin))
                throw new IllegalArgumentException("historical physical pin not actually J04 recovered");
            var bound=new Bound(pin,plan,Objects.requireNonNull(resolver),Objects.requireNonNull(journal));var old=events.get(pin.event());
            if(old!=null&&!old.equals(bound))throw new IllegalArgumentException("historical physical event pin changed");
            if(old==null&&events.size()>=128)throw new IllegalStateException("physical site guard capacity");events.put(pin.event(),bound);
        }
        /** The existing owner's complete verified pin-index scan, never an elapsed timer, opens dispatch. */
        public void recoveryComplete(){actor();if(closed)throw new IllegalStateException("physical site owner fenced");recovered=true;}
        /** Manual inverse runners using ordinary apply must register their exact
         * durable inverse and real ownership-readback callback. Unknown token is
         * never interpreted as cleanup. This method grants no J05 authority. */
        public void registerInverse(EffectIntent inverse,BooleanSupplier durableOwnership){
            actor();if(closed||!recovered)throw new IllegalStateException("physical site owner fenced/recovering");
            var bound=Objects.requireNonNull(events.get(inverse.instanceId()),"physical site event absent");
            requireWithin(bound.pin(),inverse);if(!Objects.requireNonNull(durableOwnership).getAsBoolean())throw new IllegalArgumentException("inverse durable ownership not observed");
            var old=inverses.get(inverse.operationToken());
            if(old!=null&&!old.intent().equals(inverse))throw new IllegalArgumentException("physical inverse token changed");
            if(old==null&&inverses.size()>=512)throw new IllegalStateException("physical inverse guard capacity");
            inverses.putIfAbsent(inverse.operationToken(),new Inverse(inverse,durableOwnership));
        }
        public void retire(EventInstanceId event){actor();events.remove(event);inverses.values().removeIf(i->i.intent().instanceId().equals(event));}
        @Override public void close(){actor();closed=true;} // Keep the world fenced until a new actual bootstrap replaces it.
        private void forward(EffectIntent intent){
            actor();if(closed||!recovered)throw new IllegalStateException("physical site owner fenced/recovering");
            var bound=events.get(intent.instanceId());if(bound==null)return;
            var inverse=inverses.get(intent.operationToken());
            if(inverse!=null){
                if(!inverse.intent().equals(intent)||!inverse.ownership().getAsBoolean())throw new IllegalStateException("physical inverse ownership changed");
                requireWithin(bound.pin(),intent);return;
            }
            if(!bound.plan().effects().contains(intent))throw new IllegalArgumentException("unregistered physical site forward operation");
            if(!bound.journal().durable(bound.pin()))throw new IllegalStateException("physical site pin durable readback lost");
            bound.resolver().requireCurrent(bound.pin(),bound.plan(),Optional.of(intent));
        }
        private void rearm(EventInstanceId event){
            actor();if(closed||!recovered)throw new IllegalStateException("physical site owner fenced/recovering");var bound=events.get(event);if(bound==null)return;
            if(!bound.journal().durable(bound.pin()))throw new IllegalStateException("actual D0 pin no longer durable");bound.resolver().requireCurrent(bound.pin(),bound.plan(),Optional.empty());
        }
        private void historical(EffectIntent intent){
            actor();if(closed||!recovered)throw new IllegalStateException("physical site owner fenced/recovering");
            var bound=events.get(intent.instanceId());if(bound!=null)requireWithin(bound.pin(),intent);
            // Called ONLY by ServerEffectPort cleanup/stabilization, whose real
            // inverse/stop permits and native ownership validation remain mandatory.
        }
    }
    public static synchronized Owner open(WorldId world,StoreSession current,Runnable actor){
        Objects.requireNonNull(world);Objects.requireNonNull(current);Objects.requireNonNull(actor);
        actor.run();if(!world.equals(current.worldId()))throw new IllegalArgumentException("physical site owner world");
        var old=OWNERS.get(world);if(old!=null&&!old.closed)throw new IllegalStateException("physical site world already owned");
        if(old==null&&OWNERS.size()>=64)throw new IllegalStateException("physical site world capacity");
        var owner=new Owner(world,current,actor);OWNERS.put(world,owner);return owner;
    }
    private static synchronized Owner owner(WorldId world){return OWNERS.get(world);}
    public static void requireForward(WorldId world,EffectIntent intent){var owner=owner(world);if(owner!=null)owner.forward(intent);}
    public static void requireRearm(WorldId world,EventInstanceId event){var owner=owner(world);if(owner!=null)owner.rearm(event);}
    public static void requireHistorical(WorldId world,EffectIntent intent){var owner=owner(world);if(owner!=null)owner.historical(intent);}
    private static void requireWithin(PhysicalSitePin pin,EffectIntent inverse){
        if(!pin.event().equals(inverse.instanceId())||pin.footprints().values().stream().noneMatch(f->within(f,inverse.expectedFootprint())))
            throw new IllegalArgumentException("physical inverse exceeds original pinned footprint");
    }
    private static boolean within(Footprint original,Footprint inverse){
        if(!original.dimension().equals(inverse.dimension())||!original.loadedChunks().containsAll(inverse.loadedChunks()))return false;
        return inverse.cells().stream().allMatch(p->original.cells().contains(p)||original.bounds().stream().anyMatch(b->p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()&&p.z()>=b.minZ()&&p.z()<b.maxZExclusive()))
            &&inverse.bounds().stream().allMatch(a->original.bounds().stream().anyMatch(b->a.minX()>=b.minX()&&a.minY()>=b.minY()&&a.minZ()>=b.minZ()
                &&a.maxXExclusive()<=b.maxXExclusive()&&a.maxYExclusive()<=b.maxYExclusive()&&a.maxZExclusive()<=b.maxZExclusive()));
    }
}
