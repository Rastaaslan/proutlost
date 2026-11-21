package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import java.util.*;

/** Actor-owned immutable projection and opaque, session-bound pagination. A publication
 * is atomic even when empty. Queries never perform disk IO or consult live Minecraft. */
public final class AdminProjectionPages {
    private record Cursor(SessionId session,Revision revision,AdminPanel panel,AdminFilter filter,int limit,int offset) {}
    private final WorldId world;
    private final int maxRows,maxCursors,maxSnapshotBytes;
    private Revision revision=new Revision(0);
    private Tick captured=new Tick(0);
    private Sequence durable=new Sequence(0);
    private List<AdminRow> rows=List.of();
    private Set<AdminOperation> capabilities=Set.of();
    private final Map<String,Cursor> cursors=new HashMap<>();
    private boolean published;
    private MemoryView memoryCoverage;
    public AdminProjectionPages(WorldId world,int maxRows,int maxCursors,int maxSnapshotBytes) {
        this.world=Objects.requireNonNull(world);
        if(maxRows<1||maxCursors<1||maxSnapshotBytes<1) throw new IllegalArgumentException("limits");
        this.maxRows=maxRows;this.maxCursors=maxCursors;this.maxSnapshotBytes=maxSnapshotBytes;
    }
    public void publish(WorldId world,Revision revision,Tick captured,Sequence durable,List<AdminRow> rows,Set<AdminOperation> capabilities) {
        if(!this.world.equals(world)||rows.size()>maxRows||published&&revision.compareTo(this.revision)<=0
                ||captured.compareTo(this.captured)<0||durable.compareTo(this.durable)<0) throw new IllegalArgumentException("publication fence");
        this.rows=List.copyOf(rows);this.capabilities=Set.copyOf(capabilities);this.revision=revision;this.captured=captured;this.durable=durable;
        cursors.clear();published=true;memoryCoverage=null;
    }
    /** Scoped native publications carry their actual immutable memory coverage. */
    public void publish(WorldId world,Revision revision,Tick captured,Sequence durable,List<AdminRow> rows,
            Set<AdminOperation> capabilities,MemoryView coverage) {
        Objects.requireNonNull(coverage);
        if(!world.equals(coverage.scopes().worldId()))throw new IllegalArgumentException("foreign memory coverage");
        publish(world,revision,captured,durable,rows,capabilities);memoryCoverage=coverage;
    }
    public AdminSnapshot snapshot(AdminQuery query,ActorIdentity actor) {
        if(!actor.serverAuthenticated()||actor.permissionLevel()<4||!actor.session().equals(query.actorSession())) throw new Rejected(ReasonCode.AUTH_DENIED);
        if(!published||query.expectedSnapshotRevision().filter(r->!r.equals(revision)).isPresent()) throw new Rejected(ReasonCode.STALE_CONTEXT);
        if(query.panel()==AdminPanel.MEMORY&&memoryCoverage!=null&&!AdminMemoryCoverage.available(query.filter(),memoryCoverage))
            throw new Rejected(ReasonCode.STALE_CONTEXT);
        int offset=0;
        if(query.cursor().isPresent()) {
            var c=cursors.get(query.cursor().get());
            if(c==null||!c.session.equals(actor.session())||!c.revision.equals(revision)||c.panel!=query.panel()
                    ||!c.filter.equals(query.filter())||c.limit!=query.limit()) throw new Rejected(ReasonCode.STALE_CONTEXT);
            offset=c.offset;
        }
        var matching=rows.stream().filter(r->matches(query,r.data())).toList();
        int end=offset;
        String token=UUID.randomUUID().toString();
        var codec=new fr.proutlost.director.api.codec.ModelCodec<>(AdminSnapshot.class);
        AdminSnapshot result=new AdminSnapshot(query.requestId(),revision,captured,durable,List.of(),Optional.empty(),capabilities);
        if(matching.isEmpty() && !fits(codec,result)) throw new Rejected(ReasonCode.BUDGET_EXHAUSTED);
        while(end<matching.size() && end-offset<query.limit()) {
            int candidateEnd=end+1;
            var candidate=new AdminSnapshot(query.requestId(),revision,captured,durable,matching.subList(offset,candidateEnd),
                    candidateEnd<matching.size()?Optional.of(token):Optional.empty(),capabilities);
            if(!fits(codec,candidate)) {
                if(end==offset) throw new Rejected(ReasonCode.BUDGET_EXHAUSTED);
                break;
            }
            result=candidate;
            end=candidateEnd;
        }
        // A continuation is a one-use capability. Without retiring the cursor
        // just consumed, a normal long pagination fills maxCursors and can
        // never reach its last page. Do not consume anything on a rejected
        // response: the caller must be able to retry after a budget failure.
        if(result.nextCursor().isPresent()
                && cursors.size() - (query.cursor().isPresent() ? 1 : 0) >= maxCursors)
            throw new Rejected(ReasonCode.RATE_LIMITED);
        query.cursor().ifPresent(cursors::remove);
        if(result.nextCursor().isPresent())
            cursors.put(token,new Cursor(actor.session(),revision,query.panel(),query.filter(),query.limit(),end));
        return result;
    }

    private boolean fits(fr.proutlost.director.api.codec.ModelCodec<AdminSnapshot> codec,AdminSnapshot snapshot) {
        try { return codec.encode(snapshot).length<=maxSnapshotBytes; }
        catch(IllegalArgumentException codecLimit) { return false; }
    }

    /** Adapter for bootstrap publications; the boot epoch is supplied by the real lifecycle. */
    public AdminReadResult query(AdminQuery query,ActorIdentity actor,Epoch boot) {
        try {
            var snapshot=snapshot(query,actor);
            return new AdminReadResult(AdminStatus.SUCCEEDED,snapshot.rows(),Optional.empty(),snapshot.nextCursor(),
                    Optional.of(new AdminSnapshotStamp(world,boot,snapshot.snapshotRevision(),snapshot.capturedTick(),snapshot.lastDurableSequence())));
        } catch(Rejected rejected) {
            var error=new SystemError(rejected.reason(),Severity.WARNING,false,Optional.empty(),Set.of(),new CorrelationId(UUID.randomUUID()),SafeState.UNCHANGED);
            return new AdminReadResult(rejected.reason()==ReasonCode.STALE_CONTEXT?AdminStatus.DEFERRED:AdminStatus.REJECTED,List.of(),Optional.of(error),Optional.empty(),Optional.empty());
        }
    }
    public void disconnect(SessionId session) { cursors.values().removeIf(c->c.session.equals(session)); }
    private static boolean matches(AdminQuery query,AdminData data) {
        return switch(query.filter()) {
            case WorldFilter ignored -> data instanceof WorldAdminData;
            case EventFilter f -> data instanceof EventAdminData e && f.instanceId().map(id->id.equals(e.event().instanceId())).orElse(true)
                    && (f.states().isEmpty()||f.states().contains(e.event().state()))
                    // DisasterStage and EventState are distinct domains. A disaster
                    // cannot satisfy an explicit EventState filter.
                    || data instanceof DisasterAdminData d && f.states().isEmpty()
                    && f.instanceId().map(id->id.equals(d.disaster().eventInstanceId())).orElse(true);
            case MutationFilter f -> data instanceof MutationAdminData m && f.mutationId().equals(m.mutation().mutationId());
            case ChainFilter f -> data instanceof ChainAdminData c && f.chainId().map(id->id.equals(c.chain().chainId())).orElse(true);
            case RegionFilter f -> data instanceof RegionAdminData r && f.regionId().map(id->id.equals(r.region().regionId())).orElse(true)&&r.tags().containsAll(f.tags())
                    || data instanceof OwnershipAdminData && f.regionId().isEmpty()&&f.tags().isEmpty();
            case PlayerFilter f -> data instanceof PlayerAdminData p && f.playerId().equals(p.operational().playerId());
            case DecisionFilter f -> data instanceof DecisionAdminData d && f.decisionId().map(id->id.equals(d.decisionId())).orElse(true);
            case LaboratoryFilter f -> data instanceof LaboratoryAdminData l && f.fixtureId().map(id->id.equals(l.fixtureId())).orElse(true);
        };
    }
    public static final class Rejected extends RuntimeException {
        private final ReasonCode reason;
        public Rejected(ReasonCode reason) {super(reason.name());this.reason=reason;}
        public ReasonCode reason() {return reason;}
    }
}
