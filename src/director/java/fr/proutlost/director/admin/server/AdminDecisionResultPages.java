package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Candidate views of an immutable completed replay. One private, one-use
 * continuation per job; existing DecisionAdminData and wire bounds are retained.
 * A page never changes the full calculation, selection or validation metadata. */
final class AdminDecisionResultPages {
    private final SessionId session;
    private final AdminRow original;
    private final AdminSnapshotStamp stamp;
    private final DecisionAdminData decision;
    private record Cursor(String token, int offset, int limit, Revision expected) {}
    private Cursor cursor;

    AdminDecisionResultPages(SessionId session, AdminRow row, AdminSnapshotStamp stamp) {
        this.session=Objects.requireNonNull(session);
        this.original=Objects.requireNonNull(row);
        this.stamp=Objects.requireNonNull(stamp);
        this.decision=(DecisionAdminData)row.data();
    }
    static boolean fits(AdminReadResult result) {
        return AdminLaboratoryResultPages.fits(result);
    }
    AdminReadResult query(AdminQuery query) {
        if(!session.equals(query.actorSession()) || query.panel()!=AdminPanel.DECISIONS
                || !(query.filter() instanceof DecisionFilter filter)
                || !filter.decisionId().filter(decision.decisionId()::equals).isPresent())
            throw new AdminProjectionPages.Rejected(ReasonCode.AUTH_DENIED);
        if(query.expectedSnapshotRevision().filter(r->!r.equals(stamp.snapshotRevision())).isPresent())
            throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
        int offset=0;
        if(query.cursor().isPresent()) {
            if(cursor==null || !cursor.token().equals(query.cursor().orElseThrow())
                    || cursor.limit()!=query.limit() || !cursor.expected().equals(stamp.snapshotRevision()))
                throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
            offset=cursor.offset();
        }
        var candidates=decision.candidates();
        String token=UUID.randomUUID().toString();
        int end=offset;
        AdminReadResult result=null;
        do {
            int candidateEnd=Math.min(candidates.size(),end+1);
            var data=new DecisionAdminData(decision.decisionId(),decision.capturedTick(),
                    candidates.subList(offset,candidateEnd),decision.selected(),decision.advisor(),
                    decision.validations(),decision.outcome());
            var row=new AdminRow(original.scope(),original.revision(),original.capturedTick(),data);
            var candidate=new AdminReadResult(AdminStatus.SUCCEEDED,List.of(row),Optional.empty(),
                    candidateEnd<candidates.size()?Optional.of(token):Optional.empty(),Optional.of(stamp));
            if(!fits(candidate)) {
                if(result==null)throw new AdminProjectionPages.Rejected(ReasonCode.BUDGET_EXHAUSTED);
                break;
            }
            result=candidate;end=candidateEnd;
        } while(end<candidates.size());
        // Invalid/oversize requests cannot consume the last valid continuation.
        cursor=result.nextCursor().isPresent()
                ? new Cursor(token,end,query.limit(),stamp.snapshotRevision()) : null;
        return result;
    }
}
