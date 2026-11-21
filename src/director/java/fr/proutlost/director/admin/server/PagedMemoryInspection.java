package fr.proutlost.director.admin.server;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** A confirmed exact read target with incomplete data. This narrow marker
 * enables read-only navigation; it never reports successful full inspection. */
public final class PagedMemoryInspection {
    private PagedMemoryInspection(){}
    private static boolean target(AdminCommand command,ScopeRef ref){
        return command.operation()==AdminOperation.REGION&&command.payload() instanceof RegionInspectionPayload region
            &&ref.kind()==ScopeKind.REGION&&ref.id().equals(region.regionId().value())
            ||command.operation()==AdminOperation.PLAYER&&command.payload() instanceof PlayerInspectionPayload player
            &&ref.kind()==ScopeKind.PLAYER&&ref.id().equals(player.playerId().value());
    }
    public static boolean matches(AdminCommand command,AdminCommandResult result){
        return command!=null&&result!=null&&command.requestId().equals(result.commandId())&&result.status()==AdminStatus.DEFERRED
            &&result.reasonCodes().equals(List.of(ReasonCode.BUDGET_EXHAUSTED))&&result.resultRef().filter(ref->target(command,ref)).isPresent();
    }
    public static boolean matches(AdminCommand command,AdminResult result){
        return command!=null&&result!=null&&command.requestId().equals(result.requestId())&&result.status()==AdminStatus.DEFERRED
            &&result.error().filter(e->e.code()==ReasonCode.BUDGET_EXHAUSTED).isPresent()
            &&result.data().filter(row->target(command,row.scope())).isPresent();
    }
}
