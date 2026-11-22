package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Session-private score-only pages. Full calculation/audit stay in the original
 * job. One opaque continuation per job bounds retained cursor state. */
final class AdminLaboratoryResultPages {
    private final SessionId session;
    private final AdminRow original;
    private final AdminSnapshotStamp stamp;
    private final LaboratoryAdminData lab;
    private final Fingerprint proposalHash,auditHash;
    private record Cursor(String token,int offset,int limit,Revision expected) {}
    private Cursor cursor;

    AdminLaboratoryResultPages(SessionId session,AdminRow row,AdminSnapshotStamp stamp) {
        this.session=session;this.original=row;this.stamp=stamp;
        this.lab=(LaboratoryAdminData)row.data();
        proposalHash=BlobContent.hash(new ModelCodec<>(DecisionProposal.class).encode(lab.proposal()));
        auditHash=BlobContent.hash(new ModelCodec<>(AuditDecision.class).encode(lab.proposal().audit()));
    }
    static boolean fits(AdminReadResult result) {
        try {
            if(new ModelCodec<>(AdminReadResult.class).encode(result).length>AdminFacade.MAX_READ_BYTES)return false;
            // Include every advertised capability to bound the actual wire envelope too.
            var stamp=result.snapshot().orElseThrow();
            var wire=new AdminSnapshot(new RequestId(new UUID(0,0)),stamp.snapshotRevision(),
                stamp.capturedTick(),stamp.lastDurableSequence(),result.data(),result.nextCursor(),
                EnumSet.allOf(AdminOperation.class));
            return new ModelCodec<>(AdminSnapshot.class).encode(wire).length<=AdminFacade.MAX_READ_BYTES;
        }catch(IllegalArgumentException invalid){return false;}
    }
    AdminReadResult query(AdminQuery query) {
        if(!session.equals(query.actorSession())||query.panel()!=AdminPanel.LABORATORY
                ||!(query.filter() instanceof LaboratoryFilter f)
                ||!f.fixtureId().filter(lab.fixtureId()::equals).isPresent())
            throw new AdminProjectionPages.Rejected(ReasonCode.AUTH_DENIED);
        if(query.expectedSnapshotRevision().filter(r->!r.equals(stamp.snapshotRevision())).isPresent())
            throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
        int offset=0;
        if(query.cursor().isPresent()) {
            if(cursor==null||!cursor.token().equals(query.cursor().get())||cursor.limit()!=query.limit()
                    ||!cursor.expected().equals(stamp.snapshotRevision()))
                throw new AdminProjectionPages.Rejected(ReasonCode.STALE_CONTEXT);
            offset=cursor.offset();
        }
        var candidates=lab.proposal().candidates();
        String token=UUID.randomUUID().toString();
        int end=offset;
        AdminReadResult result=null;
        do {
            int candidateEnd=Math.min(candidates.size(),end+1);
            var data=new LaboratoryResultPageData(lab.fixtureId(),lab.proposal().proposalId(),
                proposalHash,auditHash,offset,candidates.size(),candidates.subList(offset,candidateEnd),
                lab.proposal().audit().chosen(),lab.proposal().audit().validations(),lab.admissionReasons());
            var row=new AdminRow(original.scope(),original.revision(),original.capturedTick(),data);
            var candidate=new AdminReadResult(AdminStatus.SUCCEEDED,List.of(row),Optional.empty(),
                candidateEnd<candidates.size()?Optional.of(token):Optional.empty(),Optional.of(stamp));
            if(!fits(candidate)) {
                if(result==null)throw new AdminProjectionPages.Rejected(ReasonCode.BUDGET_EXHAUSTED);
                break;
            }
            result=candidate;end=candidateEnd;
        }while(end<candidates.size());
        // Advance only after a complete bounded page exists. Rejected queries
        // leave their continuation usable; consumed tokens cannot be replayed.
        cursor=result.nextCursor().isPresent()?new Cursor(token,end,query.limit(),stamp.snapshotRevision()):null;
        return result;
    }
}
