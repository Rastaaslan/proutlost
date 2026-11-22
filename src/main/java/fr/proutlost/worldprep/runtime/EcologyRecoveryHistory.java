package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.Job;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData.JobState;
import fr.proutlost.worldprep.storage.BatchManifestStore.Stage;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Authenticated later APPLY receipts in this tile, absent when an earlier preview was sealed.
 * They affect only the historical input identity; terrain ownership/replay stays unchanged.
 */
record EcologyRecoveryHistory(Set<UUID> jobs, Set<UUID> journals) {
    static final EcologyRecoveryHistory EMPTY = new EcologyRecoveryHistory(Set.of(), Set.of());
    EcologyRecoveryHistory {
        jobs = Set.copyOf(jobs);
        journals = Set.copyOf(journals);
    }
    EcologyRecoveryHistory withLater(Stage current, long durableCursor, Stage later,
            Job child, BlockWorldPrepData.Plan plan, BlockWorldPrepData.Journal journal) throws IOException {
        int size=EcologyBatchRuntime.STAGES.size();
        if(current==null||later==null||!current.batchId().equals(later.batchId())
                ||later.sequence()<=current.sequence()||later.sequence()>=durableCursor
                ||later.tileIndex()!=current.tileIndex()||later.sequence()/size!=current.sequence()/size
                ||!later.tileId().equals(current.tileId())
                ||!later.stage().startsWith("APPLY_")
                ||!EcologyBatchRuntime.STAGES.get((int)(later.sequence()%size)).equals(later.stage())
                ||child==null||plan==null||journal==null
                ||child.state()!=JobState.COMPLETED||!child.id().equals(later.childId())
                ||!child.operation().name().equals(later.stage())||!child.area().equals(later.tileId())
                ||!java.util.Objects.equals(child.snapshotId(),later.snapshotId())
                ||child.cursor()!=later.plannedChanges()||later.appliedPlanEntries()!=later.plannedChanges()
                ||!plan.area.equals(later.tileId())||!plan.pass.equals(later.stage().substring(6))
                ||!java.util.Objects.equals(plan.planId(),later.planId())||plan.entryCount()!=later.plannedChanges()
                ||!journal.id.equals(later.snapshotId())||!journal.job.equals(child.id())
                ||!journal.area.equals(plan.area)||!journal.pass.equals(plan.pass)
                ||!journal.planId().equals(plan.planId())||!journal.fingerprint().equals(plan.fingerprint())
                ||journal.entryCount()!=plan.entryCount())
            throw new IOException("Later batch receipt cannot define the original input boundary");
        var nextJobs=new HashSet<>(jobs);var nextJournals=new HashSet<>(journals);
        if(!nextJobs.add(child.id())||!nextJournals.add(journal.id))
            throw new IOException("Duplicate later batch receipt ownership");
        return new EcologyRecoveryHistory(nextJobs,nextJournals);
    }
}
