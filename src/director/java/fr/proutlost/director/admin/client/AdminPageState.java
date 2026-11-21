package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.function.LongSupplier;

/** One bounded dashboard page. Called only on the client main thread.
 * Transport must authenticate the session envelope before delivering a DTO.
 * This class grants no server permission and performs no safety calculation.
 */
public final class AdminPageState {
    private SessionIdentity session;
    private AdminQuery pending;
    private AdminSnapshot snapshot;
    private boolean needsQuery = true;
    private fr.proutlost.director.api.error.ReasonCode failureReason;
    private long retryAfterTick;
    private final MemoryDocumentAssembler documents=new MemoryDocumentAssembler();
    private AdminFilter documentFilter;
    private AdminQuery snapshotQuery, automaticQuery, refusedAutomaticQuery;
    private AdminSnapshot automaticBasis;
    private int continuationRateRetries;
    private boolean continuationRetryScheduled, continuationDeadlineSet;
    private boolean directoryAutomaticQuery;
    private long continuationRetryAfterNanos, continuationDeadlineNanos, continuationBackoffNanos;
    private final LongSupplier nanoTime;
    private static final int MAX_CONTINUATION_RATE_RETRIES=3;
    private static final long CONTINUATION_RETRY_WINDOW_NANOS=30_000_000_000L;

    public AdminPageState(){this(System::nanoTime);}
    public AdminPageState(LongSupplier nanoTime){this.nanoTime=Objects.requireNonNull(nanoTime);}
    private void clearContinuationRetry(){
        snapshotQuery=null;automaticQuery=null;refusedAutomaticQuery=null;automaticBasis=null;continuationRateRetries=0;
        continuationRetryScheduled=false;continuationDeadlineSet=false;directoryAutomaticQuery=false;
    }
    /** A client monotonic bound is not evidence of server cursor freshness;
     * the original server TTL, revision and session fences remain authoritative. */
    public boolean expireAutomaticContinuation(){
        if(!continuationDeadlineSet||nanoTime.getAsLong()-continuationDeadlineNanos<0)return false;
        terminalFailure(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT);return true;
    }
    private void terminalFailure(fr.proutlost.director.api.error.ReasonCode reason){
        pending=null;snapshot=null;needsQuery=false;failureReason=reason;
        documents.clear();documentFilter=null;clearContinuationRetry();
    }
    public boolean continuationRetrying(){expireAutomaticContinuation();return continuationRetryScheduled;}
    public boolean matchesDocumentContext(AdminPanel panel,AdminFilter filter){
        return panel==AdminPanel.MEMORY&&Objects.equals(documentFilter,filter);
    }
    public void cancelDocumentContinuation(){terminalFailure(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT);}
    public Optional<AdminQuery> continuationRetryQuery(RequestId newId,long tick){
        Objects.requireNonNull(newId);expireAutomaticContinuation();
        if(!continuationRetryScheduled||!canRetry(tick))return Optional.empty();
        if(newId.equals(automaticQuery.requestId()))throw new IllegalArgumentException("continuation retry requires new request ID");
        return Optional.of(new AdminQuery(automaticQuery.actorSession(),newId,automaticQuery.panel(),automaticQuery.filter(),
            automaticQuery.cursor(),automaticQuery.expectedSnapshotRevision(),automaticQuery.limit()));
    }
    /** Evidence-only view; no permission, ACK or response acceptance is inferred.
     * Monotonic values are from this client JVM and cannot be server-tick claims. */
    public record ContinuationRetryView(SessionIdentity session,AdminQuery refusedQuery,
            Optional<RequestId> retryRequestId,int retryOrdinal,int maximumRetries,
            long observedNanos,long retryNotBeforeNanos,long deadlineNanos,long backoffNanos,
            Fingerprint documentHash,int documentIndex,int documentCount,int documentBytes,int receivedBytes,
            Revision snapshotRevision,Tick capturedTick,Sequence lastDurableSequence){}
    public Optional<ContinuationRetryView> continuationRetryView(){
        if(!continuationDeadlineSet||refusedAutomaticQuery==null||automaticBasis==null||session==null)return Optional.empty();
        for(var row:automaticBasis.rows())if(row.data() instanceof MemoryDocumentPageData part){
            int received=part.byteOffset()+java.util.Base64.getDecoder().decode(part.bytesBase64()).length;
            return Optional.of(new ContinuationRetryView(session,refusedAutomaticQuery,
                pending==null?Optional.empty():Optional.of(pending.requestId()),continuationRateRetries,MAX_CONTINUATION_RATE_RETRIES,
                nanoTime.getAsLong(),continuationRetryAfterNanos,continuationDeadlineNanos,continuationBackoffNanos,
                part.documentHash(),part.documentIndex(),part.documentCount(),part.documentBytes(),received,
                automaticBasis.snapshotRevision(),automaticBasis.capturedTick(),automaticBasis.lastDurableSequence()));
        }
        return Optional.empty();
    }
    /** Evidence-only directory retry state; no invented document/hash is used. */
    public record DirectoryContinuationRetryView(SessionIdentity session,AdminQuery refusedQuery,
            Optional<RequestId> retryRequestId,int retryOrdinal,int maximumRetries,
            long observedNanos,long retryNotBeforeNanos,long deadlineNanos,long backoffNanos,
            Revision snapshotRevision,Tick capturedTick,Sequence lastDurableSequence){}
    public Optional<DirectoryContinuationRetryView> directoryContinuationRetryView(){
        if(!directoryAutomaticQuery||!continuationDeadlineSet||refusedAutomaticQuery==null||automaticBasis==null||session==null)return Optional.empty();
        return Optional.of(new DirectoryContinuationRetryView(session,refusedAutomaticQuery,
            pending==null?Optional.empty():Optional.of(pending.requestId()),continuationRateRetries,MAX_CONTINUATION_RATE_RETRIES,
            nanoTime.getAsLong(),continuationRetryAfterNanos,continuationDeadlineNanos,continuationBackoffNanos,
            automaticBasis.snapshotRevision(),automaticBasis.capturedTick(),automaticBasis.lastDurableSequence()));
    }
    public boolean directoryContinuationRetrying(){return directoryAutomaticQuery&&continuationRetryScheduled;}
    public boolean directoryContinuationActive(){return directoryAutomaticQuery&&(pending!=null||continuationRetryScheduled);}
    /** Only an empty, already accepted directory page may auto-load its exact
     * cursor. Semantic NEXT/fresh reads retain their explicit retry behavior. */
    public boolean beginDirectoryContinuation(AdminQuery query){
        Objects.requireNonNull(query);if(expireAutomaticContinuation())return false;
        if(session==null||pending!=null||documents.pending()||query.panel()!=AdminPanel.MEMORY
            ||!(query.filter() instanceof RegionFilter region)||region.regionId().isPresent()
            ||!query.filter().equals(documentFilter)||query.cursor().isEmpty()||query.expectedSnapshotRevision().isEmpty())
            throw new IllegalArgumentException("no exact automatic directory continuation");
        if(continuationRetryScheduled){
            if(!directoryAutomaticQuery||!canRetry(retryAfterTick))return false;
            var previous=automaticQuery;
            if(query.requestId().equals(previous.requestId())||!query.actorSession().equals(previous.actorSession())
                ||query.panel()!=previous.panel()||!query.filter().equals(previous.filter())||!query.cursor().equals(previous.cursor())
                ||!query.expectedSnapshotRevision().equals(previous.expectedSnapshotRevision())||query.limit()!=previous.limit())
                throw new IllegalArgumentException("directory continuation retry tuple/clock");
        }else{
            if(!loadingDirectoryPage()||snapshotQuery==null||snapshotQuery.panel()!=AdminPanel.MEMORY
                ||!query.actorSession().equals(snapshotQuery.actorSession())||!query.filter().equals(snapshotQuery.filter())
                ||query.limit()!=snapshotQuery.limit()||!query.cursor().equals(snapshot.nextCursor())
                ||!query.expectedSnapshotRevision().equals(Optional.of(snapshot.snapshotRevision())))
                throw new IllegalArgumentException("directory continuation cursor/revision");
            automaticBasis=snapshot;
        }
        directoryAutomaticQuery=true;automaticQuery=query;continuationRetryScheduled=false;beginPage(query);return true;
    }
    /** Explicitly marked transport-fragment continuations only. Semantic NEXT
     * and fresh reads never acquire the bounded automatic-rate retry policy. */
    public boolean beginContinuation(AdminQuery query){
        Objects.requireNonNull(query);if(expireAutomaticContinuation())return false;
        if(session==null||pending!=null||!documents.pending()||query.panel()!=AdminPanel.MEMORY
            ||!query.filter().equals(documentFilter)||query.cursor().isEmpty()||query.expectedSnapshotRevision().isEmpty())
            throw new IllegalArgumentException("no exact automatic fragment continuation");
        if(continuationRetryScheduled){
            if(!canRetry(retryAfterTick))return false;
            var previous=automaticQuery;
            if(query.requestId().equals(previous.requestId())||!query.actorSession().equals(previous.actorSession())
                ||query.panel()!=previous.panel()||!query.filter().equals(previous.filter())||!query.cursor().equals(previous.cursor())
                ||!query.expectedSnapshotRevision().equals(previous.expectedSnapshotRevision())||query.limit()!=previous.limit())throw new IllegalArgumentException("automatic continuation retry tuple/clock");
        }else{
            if(snapshot==null||snapshotQuery==null||!query.actorSession().equals(snapshotQuery.actorSession())||query.limit()!=snapshotQuery.limit()
                ||!query.cursor().equals(snapshot.nextCursor())||!query.expectedSnapshotRevision().equals(Optional.of(snapshot.snapshotRevision())))
                throw new IllegalArgumentException("automatic continuation cursor/revision");
            automaticBasis=snapshot;
        }
        directoryAutomaticQuery=false;automaticQuery=query;continuationRetryScheduled=false;beginPage(query);return true;
    }
    /** Only an authoritative quota rejection known to precede cursor consumption
     * can retain a partial document. No send exception or other refusal retries. */
    public boolean deferRateLimitedContinuation(SessionIdentity envelope,AdminCommandResult result,long now,long delay){
        Objects.requireNonNull(result);
        if(session==null||!session.equals(envelope)||pending==null||automaticQuery==null
            ||!pending.requestId().equals(result.commandId())||!automaticQuery.requestId().equals(result.commandId())
            ||result.status()!=AdminStatus.REJECTED||!result.reasonCodes().equals(List.of(fr.proutlost.director.api.error.ReasonCode.RATE_LIMITED))
            ||(!documents.pending()&&!directoryAutomaticQuery)||automaticBasis==null)return false;
        if(now<0||delay<1)throw new IllegalArgumentException("retry clock/bound");
        if(expireAutomaticContinuation()||continuationRateRetries>=MAX_CONTINUATION_RATE_RETRIES)return false;
        long nanos=nanoTime.getAsLong();
        if(!continuationDeadlineSet){continuationDeadlineSet=true;continuationDeadlineNanos=nanos+CONTINUATION_RETRY_WINDOW_NANOS;}
        continuationBackoffNanos=2_000_000_000L<<continuationRateRetries++;
        continuationRetryAfterNanos=nanos+continuationBackoffNanos;refusedAutomaticQuery=pending;
        retryAfterTick=now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;
        pending=null;snapshot=automaticBasis;needsQuery=false;
        failureReason=fr.proutlost.director.api.error.ReasonCode.RATE_LIMITED;continuationRetryScheduled=true;
        return true;
    }

    public void reset(SessionIdentity nextSession) {
        clearContinuationRetry();
        session = Objects.requireNonNull(nextSession);
        failureReason=null;retryAfterTick=0;documents.clear();documentFilter=null;
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    public void close() {
        clearContinuationRetry();
        session = null;
        failureReason=null;retryAfterTick=0;documents.clear();documentFilter=null;
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    /** A tab/filter/page change discards the old visible data immediately. */
    public void begin(AdminQuery query) {
        clearContinuationRetry();beginPage(query);
    }
    private void beginPage(AdminQuery query) {
        if (session == null) throw new IllegalStateException("No active session");
        pending = Objects.requireNonNull(query);
        if(query.cursor().isEmpty()||!query.filter().equals(documentFilter))documents.clear();
        documentFilter=query.filter();
        failureReason=null;
        snapshot = null;snapshotQuery=null;
        needsQuery = false;
    }

    public boolean receive(SessionIdentity envelope, AdminSnapshot response) {
        Objects.requireNonNull(response);
        expireAutomaticContinuation();
        if (session == null || !session.equals(envelope) || pending == null
                || !pending.requestId().equals(response.requestId())) return false;
        if(directoryAutomaticQuery&&automaticBasis!=null
            &&(!response.snapshotRevision().equals(automaticBasis.snapshotRevision())
                ||!response.capturedTick().equals(automaticBasis.capturedTick())
                ||!response.lastDurableSequence().equals(automaticBasis.lastDurableSequence()))){
            invalidate();return false;
        }
        if (response.rows().size() > pending.limit()
                || pending.expectedSnapshotRevision().filter(r -> !r.equals(response.snapshotRevision())).isPresent()
                || response.nextCursor().filter(c -> c.length() > 512).isPresent()) {
            invalidate();
            return false;
        }
        try {
            var rows=new java.util.ArrayList<AdminRow>();
            for(var row:response.rows()){
                if(row.data() instanceof MemoryDocumentPageData||row.data() instanceof RegionMemoryDetailData){
                    if(pending.panel()!=AdminPanel.MEMORY||!(pending.filter() instanceof RegionFilter region)
                        ||row.scope().kind()!=ScopeKind.REGION||region.regionId().isPresent()&&!row.scope().id().equals(region.regionId().get().value()))
                        throw new IllegalArgumentException("memory document wrong filter/scope");
                }
                var decoded=documents.accept(response,row).orElse(row);
                if(pending.filter() instanceof RegionFilter region&&region.regionId().isEmpty()
                    &&decoded.data() instanceof RegionMemoryDetailData data&&data.section()!=RegionMemoryDetailData.Section.REGION)
                    throw new IllegalArgumentException("directory row is not a region reference");
                rows.add(decoded);
            }
            if(response.rows().isEmpty()&&documents.pending())throw new IllegalArgumentException("incomplete memory document omitted");
            if(documents.pending()&&response.nextCursor().isEmpty())throw new IllegalArgumentException("incomplete memory document without continuation");
            snapshot = new AdminSnapshot(response.requestId(),response.snapshotRevision(),response.capturedTick(),response.lastDurableSequence(),rows,response.nextCursor(),response.capabilities());
        }catch(IllegalArgumentException invalid){invalidate();return false;}
        snapshotQuery=pending;
        pending = null;directoryAutomaticQuery=false;continuationDeadlineSet=false;continuationRetryScheduled=false;automaticBasis=null;refusedAutomaticQuery=null;
        if(!documents.pending()){
            var completed=snapshotQuery;clearContinuationRetry();snapshotQuery=completed;
        }
        needsQuery = false;
        return true;
    }

    /** Reject only the outstanding request in the current connection. Retry is explicit:
     * repeated server refusals must not create an automatic refresh loop. */
    public boolean reject(SessionIdentity envelope, RequestId requestId, fr.proutlost.director.api.error.ReasonCode reason, long now, long delay) {
        if(session==null || !session.equals(envelope) || pending==null || !pending.requestId().equals(requestId)) return false;
        Objects.requireNonNull(reason);
        if(now<0||delay<1) throw new IllegalArgumentException("retry clock/bound");
        failureReason=reason;
        retryAfterTick=now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;
        terminalFailure(reason);
        return true;
    }

    /** A delta invalidates the page, including its cursor, for a bounded requery.
     * G0 deltas have no removal keys; blindly merging them could retain stopped instances.
     */
    public void invalidate() {
        clearContinuationRetry();
        documents.clear();documentFilter=null;
        pending = null;
        snapshot = null;
        needsQuery = true;
    }

    public Optional<fr.proutlost.director.api.error.ReasonCode> failureReason() { return Optional.ofNullable(failureReason); }
    public boolean canRetry(long now) { if(expireAutomaticContinuation())return false;return now>=retryAfterTick&&(!continuationRetryScheduled||nanoTime.getAsLong()-continuationRetryAfterNanos>=0); }

    /** Only transport fragments auto-advance. Semantic sections remain normal
     * user-controlled pagination, with the existing network cadence. */
    public Optional<String> documentContinuation(){
        return !continuationRetryScheduled&&pending==null&&documents.pending()&&snapshot!=null?snapshot.nextCursor():Optional.empty();
    }
    public boolean assemblingDocument(){return documents.pending();}
    public boolean loadingDirectoryPage(){return documentFilter instanceof RegionFilter region&&region.regionId().isEmpty()
        &&snapshot!=null&&snapshot.rows().isEmpty()&&snapshot.nextCursor().isPresent();}
    public Optional<String> loadingContinuation(){return pending==null&&loadingDirectoryPage()?snapshot.nextCursor():documentContinuation();}
    public Optional<RegionId> directorySelection(int index){
        if(!(documentFilter instanceof RegionFilter filter)||filter.regionId().isPresent()||snapshot==null||index<0||index>=snapshot.rows().size())return Optional.empty();
        var row=snapshot.rows().get(index);
        return row.data() instanceof RegionMemoryDetailData data&&data.section()==RegionMemoryDetailData.Section.REGION
            &&data.region().isPresent()&&data.regionId().value().equals(row.scope().id())?Optional.of(data.regionId()):Optional.empty();
    }
    public Optional<AdminSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    public boolean pending() { return pending != null; }
    public boolean needsQuery() { return needsQuery; }

    /** Both ticks must be Director ticks; never subtract a Minecraft day-time value. */
    public Optional<Long> age(Tick currentDirectorTick) {
        Objects.requireNonNull(currentDirectorTick);
        if (snapshot == null || currentDirectorTick.compareTo(snapshot.capturedTick()) < 0) return Optional.empty();
        return Optional.of(currentDirectorTick.value() - snapshot.capturedTick().value());
    }
}
