package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.util.*;

/**
 * Server-actor G5 sequencing: pure J03 proposal -> J04 durable audit/CAS ACK
 * -> admission request. This bridge NEVER issues a mutation permit or runs an
 * effect. The AdmissionService and later J05 retain independent veto rights.
 *
 * A selected plan is not admissible until its decision AND world-state changes
 * have one durable J04 ACK. Deferred logical reservations may be refreshed
 * only after the caller has resolved the complete actor-owned admission window.
 */
public final class DirectorDecisionAdmissionBridge {
    public enum Stage {
        WAITING_FOR_AUDIT, AUDIT_REFUSED,
        AUDITED_NO_PLAN, ADMISSION_DEFERRED,
        LOGICAL_RESERVATION_ONLY, RESERVATION_QUARANTINED, ADMISSION_REJECTED
    }
    public record Progress(DecisionId id,Stage stage,
                           Optional<AdmissionResult> admission) {
        public Progress {
            Objects.requireNonNull(id);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(admission);
            if(stage==Stage.LOGICAL_RESERVATION_ONLY &&
               (admission.isEmpty() ||
                admission.orElseThrow().status()!=AdmissionStatus.ACCEPTED))
                throw new IllegalArgumentException("logical reservation not acknowledged");
            if(stage==Stage.RESERVATION_QUARANTINED && admission.isPresent())
                throw new IllegalArgumentException("quarantined reservation must not expose a token");
        }
        /** A reservation is NOT a J05 permit to perform physical gameplay. */
        public boolean needsJ05Prepare() {
            return stage==Stage.LOGICAL_RESERVATION_ONLY;
        }
    }
    private static final class Pending {
        final DecisionProposal proposal;
        AdmissionRequest request;
        Progress progress;
        Pending(DecisionProposal proposal) {
            this.proposal=proposal;
            this.progress=new Progress(proposal.proposalId(),
                Stage.WAITING_FOR_AUDIT,Optional.empty());
        }
    }
    private final Thread actor=Thread.currentThread();
    private final DecisionPort decisions;
    private final DirectorDecisionJournal journal;
    private final AdmissionPort admission;
    private final int capacity;
    private final Map<DecisionId,Pending> requests=new LinkedHashMap<>();

    public DirectorDecisionAdmissionBridge(DecisionPort decisions,
            DirectorDecisionJournal journal,AdmissionPort admission,int capacity) {
        this.decisions=Objects.requireNonNull(decisions);
        this.journal=Objects.requireNonNull(journal);
        this.admission=Objects.requireNonNull(admission);
        if(capacity<1 || capacity>4096)
            throw new IllegalArgumentException("invalid admission bridge bound");
        this.capacity=capacity;
    }
    private void actor() {
        if(Thread.currentThread()!=actor)
            throw new IllegalStateException("Director admission actor required");
    }
    /** Returns only an ID; no plan or reservation can be used at this step. */
    public DecisionId begin(DecisionInput captured) {
        actor();
        Objects.requireNonNull(captured);
        DecisionProposal proposal=decisions.evaluate(captured);
        var previous=requests.get(proposal.proposalId());
        if(previous!=null) {
            if(!previous.proposal.equals(proposal))
                throw new IllegalArgumentException("decision ID reused with new contents");
            return proposal.proposalId();
        }
        if(requests.size()>=capacity)
            throw new IllegalStateException("G5 decision queue capacity reached");
        var receipt=journal.submit(proposal);
        Pending pending=new Pending(proposal);
        if(receipt.state()==DirectorDecisionJournal.State.UNAVAILABLE ||
           receipt.state()==DirectorDecisionJournal.State.REJECTED)
            pending.progress=new Progress(proposal.proposalId(),
                Stage.AUDIT_REFUSED,Optional.empty());
        requests.put(proposal.proposalId(),pending);
        return proposal.proposalId();
    }
    /** A cached accepted reservation is not perpetual WAL authority. The
     * arbiter may already own its token; quarantine rather than mark it as a
     * retireable rejection. Do not release an unknown live claim here. */
    static Progress quarantineAfterAuthorityLoss(Progress progress) {
        return switch(progress.stage()) {
            case ADMISSION_DEFERRED, LOGICAL_RESERVATION_ONLY ->
                new Progress(progress.id(),Stage.RESERVATION_QUARANTINED,Optional.empty());
            default -> progress;
        };
    }
    private Progress recheckCached(Pending pending) {
        var stage=pending.progress.stage();
        if((stage==Stage.ADMISSION_DEFERRED || stage==Stage.LOGICAL_RESERVATION_ONLY)
                && !auditStillDurable(journal,pending.proposal.proposalId()))
            pending.progress=quarantineAfterAuthorityLoss(pending.progress);
        return pending.progress;
    }
    public Progress poll(DecisionId id) {
        actor();
        Pending pending=Objects.requireNonNull(requests.get(id),"unknown decision");
        if(pending.progress.stage()!=Stage.WAITING_FOR_AUDIT)
            return recheckCached(pending);
        DirectorDecisionJournal.Receipt wal;
        try {wal=journal.poll(id);}
        catch(RuntimeException failedWal) {
            pending.progress=new Progress(id,Stage.AUDIT_REFUSED,Optional.empty());
            return pending.progress;
        }
        if(wal.state()==DirectorDecisionJournal.State.WAITING)
            return pending.progress;
        if(!wal.mayProceedToAdmission()) {
            pending.progress=new Progress(id,Stage.AUDIT_REFUSED,Optional.empty());
            return pending.progress;
        }
        var selected=pending.proposal.selectedPlan();
        if(selected.isEmpty()) {
            pending.progress=new Progress(id,Stage.AUDITED_NO_PLAN,Optional.empty());
            return pending.progress;
        }
        var plan=selected.orElseThrow();
        // AdmissionService.HardValidator remains responsible for expanding the
        // real primitive footprint and validating all required live evidence.
        // This bridge invents no resource claims.
        AdmissionRequest request=new AdmissionRequest(id,plan.readSet(),
            List.of(),plan.roles(),plan);
        pending.request=request;
        try {pending.progress=map(id,admission.admit(request));}
        catch(RuntimeException rejected) {
            pending.progress=new Progress(id,Stage.ADMISSION_REJECTED,Optional.empty());
        }
        return recheckCached(pending);
    }
    /** Called AFTER complete AdmissionService.resolveWindow on the actor. */
    public Progress refreshResolvedWindow(DecisionId id) {
        actor();
        var pending=Objects.requireNonNull(requests.get(id),"unknown decision");
        if(pending.progress.stage()!=Stage.ADMISSION_DEFERRED ||
           pending.request==null)
            throw new IllegalStateException("no pending admission resolution");
        // A previously forced J04 audit is not perpetual admission authority.
        // Another boot, WAL session or writer loss may occur while J03 awaits
        // the complete arbitration window. Check the exact cached durable ACK
        // again BEFORE the admission provider can issue a new reservation.
        if(!auditStillDurable(journal,id)) {
            pending.progress=quarantineAfterAuthorityLoss(pending.progress);
            return pending.progress;
        }
        try {pending.progress=map(id,admission.admit(pending.request));}
        catch(RuntimeException rejected) {
            pending.progress=new Progress(id,Stage.ADMISSION_REJECTED,Optional.empty());
        }
        return recheckCached(pending);
    }
    /** An old audit receipt cannot be reused after loss of J04 authority. */
    static boolean auditStillDurable(DirectorDecisionJournal journal,DecisionId id) {
        try {return journal.poll(id).mayProceedToAdmission();}
        catch(RuntimeException lostStoreOrSession) {return false;}
    }
    private static Progress map(DecisionId id,AdmissionResult result) {
        var stage=switch(result.status()) {
            case ACCEPTED -> Stage.LOGICAL_RESERVATION_ONLY;
            case DEFERRED -> Stage.ADMISSION_DEFERRED;
            case REJECTED -> Stage.ADMISSION_REJECTED;
        };
        return new Progress(id,stage,Optional.of(result));
    }
    public Optional<Progress> snapshot(DecisionId id) {
        actor();
        var pending=requests.get(id);
        return pending==null?Optional.empty():Optional.of(recheckCached(pending));
    }
    /** Only terminal non-reservation entries can be retired without the full
     * ownership/resource-release path. An accepted reservation cannot vanish.
     */
    public void retireNonAccepted(DecisionId id) {
        actor();
        var pending=Objects.requireNonNull(requests.get(id),"unknown decision");
        if(pending.progress.stage()==Stage.WAITING_FOR_AUDIT ||
           pending.progress.stage()==Stage.ADMISSION_DEFERRED ||
           pending.progress.stage()==Stage.LOGICAL_RESERVATION_ONLY ||
           pending.progress.stage()==Stage.RESERVATION_QUARANTINED)
            throw new IllegalStateException("active decision cannot be retired");
        requests.remove(id);
        // Only terminal WAL outcomes may be removed from the actor cache.
        // Replaying a decision remains fenced by J04's permanent request digest.
        journal.retireTerminal(id);
    }
    public int residentCount() {
        actor();
        return requests.size();
    }
}
