package fr.proutlost.worldprep.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A verified no-op readback MUST NOT create a new multi-megabyte checkpoint. */
class EcologyRecoveryPersistencePolicyTest {
    private static boolean persist(boolean changed,long oldPages,long pages,
                                   long oldEntries,long entries,
                                   String oldRoot,String root) {
        return BlockMutationRuntime.recoveryPersistenceRequired(
            changed,oldPages,pages,oldEntries,entries,oldRoot,root);
    }
    @Test void identicalCompletedJournalAndUnchangedBlocksSkipCheckpoint(){
        assertFalse(persist(false,3,3,45,45,"a".repeat(64),"a".repeat(64)));
    }
    @Test void actualTerrainReplayRequiresCheckpoint(){
        assertTrue(persist(true,3,3,45,45,"a".repeat(64),"a".repeat(64)));
    }
    @Test void changedJournalPageCountRequiresCheckpoint(){
        assertTrue(persist(false,0,3,45,45,"a".repeat(64),"a".repeat(64)));
    }
    @Test void changedJournalEntryCountRequiresCheckpoint(){
        assertTrue(persist(false,3,3,44,45,"a".repeat(64),"a".repeat(64)));
    }
    @Test void changedRootRequiresCheckpoint(){
        assertTrue(persist(false,3,3,45,45,"a".repeat(64),"b".repeat(64)));
    }
    @Test void missingRootToActualRootRequiresCheckpoint(){
        assertTrue(persist(false,0,0,0,0,null,"a".repeat(64)));
    }
    @Test void bothNullRootsWithMatchingCountsSkipCheckpoint(){
        assertFalse(persist(false,0,0,0,0,null,null));
    }
}
