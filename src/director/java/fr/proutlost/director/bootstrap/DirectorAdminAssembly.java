package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.admin.server.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Wires existing admin services to the runtime's own actor, writer and operations. */
public final class DirectorAdminAssembly {
    private DirectorAdminAssembly() {}
    public record Components(RuntimeAdminMailbox controls,AdminFacade facade,
            AdminAccessGuard access,AdminReadOnlyCommands readOnly) {
        /** WORLD writers remain serialized by the runtime. Delivery/revocation always progresses. */
        public void tick(boolean allowWorldWrite) {
            controls.rejectRevokedQueued();
            if(allowWorldWrite)controls.pump();
            facade.tick();access.drainCompletions();
        }
        public void disconnect(SessionId session){access.disconnect(session);facade.disconnect(session);}
        public Optional<String> exportPath(SessionId session,RequestId request) {
            return readOnly.exportPath(new RuntimeAdminKey(session,request)).map(Object::toString);
        }
    }
    /** route receives the real logical mailbox. It must route the other operations
     * to the real orchestrator, and return typed refusals for inadmissible scopes.
     * Workers receive immutable calculation/export inputs; actorMailbox is bounded. */
    public static Components create(DurableStore store,ActorAuthorityPort authority,AdminProjectionPort projections,
            Supplier<DirectorWorldState> world,Map<ResourceId,ConfigView> profiles,LongSupplier now,
            Supplier<AdminSnapshotStamp> stamp,Consumer<RuntimeAdminMailbox.Change> publish,ConfigView limits,
            AdminReadOnlyCalculations calculations,AdminAuditExporter exporter,Function<DecisionId,AuditDecision> audits,
            Executor worker,Executor actorMailbox,Function<RuntimeAdminMailbox,RuntimeAdminPort> route,
            int capacity,ProviderMode mode) {
        var handler=new AdminWorldControlHandler(world,profiles,now,mode);
        var handlers=new EnumMap<AdminOperation,RuntimeAdminMailbox.Handler>(AdminOperation.class);
        AdminWorldControlHandler.OPERATIONS.forEach(operation->handlers.put(operation,handler));
        handlers.put(AdminOperation.REPORT,new DirectorAdminReportHandler(world,authority,now,mode));
        var controls=new RuntimeAdminMailbox(store,authority,stamp,publish,handlers,capacity,mode);
        var readOnly=new AdminReadOnlyCommands(calculations,exporter,audits,authority,worker,capacity);
        var facade=new AdminFacade(projections,Objects.requireNonNull(route.apply(controls)),authority,
                store.session().worldId(),store.session().bootEpoch(),capacity,readOnly,mode);
        var access=new AdminAccessGuard(facade,authority,now,actorMailbox,256,capacity,
                Math.toIntExact(value(limits,"admin.command_per_second")),value(limits,"admin.refresh_ticks"),
                value(limits,"admin.report_gap_ticks"),mode);
        return new Components(controls,facade,access,readOnly);
    }
    private static long value(ConfigView config,String key) {
        var value=config.values().get(key);if(value==null)throw new IllegalArgumentException("missing admin capacity: "+key);
        return value.value().longValueExact();
    }
}
