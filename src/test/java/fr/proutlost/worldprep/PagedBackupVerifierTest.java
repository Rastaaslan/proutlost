package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import fr.proutlost.worldprep.physical.PhysicalStore;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class PagedBackupVerifierTest {
    @TempDir Path root;
    final Gson json=new Gson();
    Path source,backup,pages,cursor;
    PagedBackupVerifier.Header fixture(int count)throws Exception {
        source=Files.createDirectory(root.resolve("source"));backup=Files.createDirectory(root.resolve("backup"));
        pages=Files.createDirectory(root.resolve("pages"));cursor=root.resolve("audit/cursor.json");
        var entries=new ArrayList<PagedBackupVerifier.Entry>();
        for(int i=0;i<count;i++) {
            String name=i==0?"generation.json":String.format("receipt-%06d.json",i);
            String content=i==0?"quiescent-generation-1":"";
            var a=Files.writeString(source.resolve(name),content);var b=Files.writeString(backup.resolve(name),content);
            entries.add(new PagedBackupVerifier.Entry(name,Files.size(a),PhysicalStore.sha(content),Files.getLastModifiedTime(a).toMillis(),Files.getLastModifiedTime(b).toMillis()));
        }
        String previous=PagedBackupVerifier.ZERO;long index=0;
        for(int i=0;i<entries.size();i+=128) {
            String text=json.toJson(new PagedBackupVerifier.Page(2,index,previous,entries.subList(i,Math.min(i+128,entries.size()))));
            Files.writeString(pages.resolve("page-"+index+".json"),text);previous=PhysicalStore.sha(text);index++;
        }
        return new PagedBackupVerifier.Header(2,source.toString(),backup.toString(),"generation.json",PhysicalStore.sha("quiescent-generation-1"),index,count,previous);
    }
    PagedBackupVerifier open(PagedBackupVerifier.Header header)throws Exception {return new PagedBackupVerifier(header,source,pages,cursor,Set.of());}
    void finish(PagedBackupVerifier verifier)throws Exception {for(int n=0;n<30000;n++)if(verifier.step())return;fail("Bounded verification did not finish");}
    int finishBatched(PagedBackupVerifier verifier)throws Exception {
        for(int n=1;n<30000;n++)if(verifier.stepBounded())return n;
        fail("Bounded batch verification did not finish");return -1;
    }
    @Test void boundedBatchProgressesFasterWithoutSkippingDurablePages()throws Exception {
        var header=fixture(257);
        try(var verifier=open(header)) {
            int ticks=finishBatched(verifier);
            assertTrue(ticks<50,"Small-file backup should use multiple bounded steps per tick");
            assertEquals(3,verifier.completedPages());
            assertTrue(Files.isRegularFile(cursor.getParent().resolve("backup-page-receipts/page-3.json")));
        }
    }
    @Test void boundedBatchResumesAcrossPageBoundaryWithoutReplayingReceipt()throws Exception {
        var header=fixture(257);
        try(var verifier=open(header)) {while(verifier.completedPages()<1)assertFalse(verifier.stepBounded());}
        String retained=Files.readString(cursor.getParent().resolve("backup-page-receipts/page-1.json"));
        try(var resumed=open(header)) {assertEquals(1,resumed.completedPages());finishBatched(resumed);}
        assertEquals(retained,Files.readString(cursor.getParent().resolve("backup-page-receipts/page-1.json")));
    }
    @Test void boundedBatchStillRefusesTamperedBackupContents()throws Exception {
        var header=fixture(257);
        Files.writeString(backup.resolve("receipt-000150.json"),"altered");
        try(var verifier=open(header)){assertThrows(IOException.class,()->finishBatched(verifier));}
    }
    @Test void boundedBatchChecksQuiescentPinBetweenEveryBatch()throws Exception {
        var header=fixture(257);
        try(var verifier=open(header)) {
            assertFalse(verifier.stepBounded());
            Files.writeString(backup.resolve("generation.json"),"unauthorized-change");
            assertThrows(IOException.class,verifier::stepBounded);
            assertThrows(IOException.class,verifier::stepBounded,
                "Failed verifier must not resume without a new durable audit owner");
        }
    }
    @Test void boundedBatchRejectsChangedPinBeforeAnyWork()throws Exception {
        var header=fixture(257);
        try(var verifier=open(header)) {
            Files.writeString(source.resolve("generation.json"),"unauthorized-change");
            assertThrows(IOException.class,verifier::stepBounded);
            assertEquals(0,verifier.completedPages());
        }
    }
    @Test void boundedReplayValidatesSeveralRetainedPagesPerTickWithoutNewContentWrites()throws Exception {
        var header=fixture(768); // six 128-file pages
        try(var verifier=open(header)){
            while(verifier.completedPages()<6)
                assertFalse(verifier.stepBounded());
        }
        try(var reopened=open(header)){
            assertEquals(6,reopened.completedPages());
            assertFalse(reopened.stepBounded(),"Durable replay never returns full PASS");
            // All six retained pages are rehashed/ordered within the same bounded tick.
            assertTrue(finishBatched(reopened)<100,"Full source/backup coverage still mandatory");
        }
    }

    @Test void boundedReplayRefusesASubstitutedRetainedPage()throws Exception {
        var header=fixture(260);
        try(var verifier=open(header)){
            while(verifier.completedPages()<1)verifier.stepBounded();
        }
        Files.writeString(pages.resolve("page-0.json"),
            Files.readString(pages.resolve("page-0.json"))+" ");
        try(var reopened=open(header)) {
            assertThrows(IOException.class,reopened::stepBounded);
            assertThrows(IOException.class,reopened::stepBounded);
        }
    }

    @Test void moreThan4096FilesAndPageBoundaryRestartRetainWholeHistory()throws Exception {
        var header=fixture(4101);
        try(var v=open(header)){while(v.completedPages()<2)assertFalse(v.step());}
        try(var resumed=open(header)){assertEquals(2,resumed.completedPages());finish(resumed);}
        try(var inventory=Files.list(source)){assertEquals(4101,inventory.count());}
        assertTrue(Files.isRegularFile(cursor.getParent().resolve("backup-page-receipts/page-33.json")));
    }
    @Test void partialPageFailsWithoutAdvancingDurableCursor()throws Exception {
        var header=fixture(129);Files.writeString(pages.resolve("page-1.json"),"{");
        try(var v=open(header)) {
            while(v.completedPages()<1)v.step();
            assertThrows(IOException.class,v::step);assertEquals(1,v.completedPages());
        }
    }
    @Test void addedBackupFileIsRejectedByCompleteCoverage()throws Exception {
        var header=fixture(3);Files.writeString(backup.resolve("unlisted.mca"),"world");
        try(var v=open(header)){assertThrows(IOException.class,()->finish(v));}
    }
    @Test void boundedCoverageRejectsUnlistedBackupFilesAfterAllPagesPass()throws Exception {
        var header=fixture(257);
        Files.writeString(backup.resolve("unlisted.mca"),"outside inventory");
        try(var v=open(header)) {
            IOException failure=assertThrows(IOException.class,()->finishBatched(v));
            assertTrue(failure.getMessage().contains("coverage"));
            assertEquals(header.pages(),v.completedPages());
            assertThrows(IOException.class,v::stepBounded);
        }
    }
    @Test void boundedCoverageAllowsExactTwoCopies()throws Exception {
        var header=fixture(257);
        try(var v=open(header)){assertTrue(finishBatched(v)<40);}
    }
    @Test void alteredQuiescentPinRefusesResume()throws Exception {
        var header=fixture(3);try(var v=open(header)){while(v.completedPages()==0)v.step();}
        Files.writeString(source.resolve("generation.json"),"generation-2");
        assertThrows(IOException.class,()->open(header));
    }
    @Test void missingFileAndCorruptCursorFailClosed()throws Exception {
        var header=fixture(3);Files.move(backup.resolve("receipt-000001.json"),backup.resolve("renamed"));
        try(var v=open(header)){assertThrows(IOException.class,()->finish(v));}
        Files.createDirectories(cursor.getParent());Files.writeString(cursor,"partial");
        assertThrows(Exception.class,()->open(header));
    }
    @Test void retainedPageTamperingCannotUseOldCompletionCursor()throws Exception {
        var header=fixture(129);try(var v=open(header)){while(v.completedPages()<1)v.step();}
        Files.writeString(pages.resolve("page-0.json"),Files.readString(pages.resolve("page-0.json"))+" ");
        try(var resumed=open(header)){assertThrows(IOException.class,resumed::step);}
    }
    @Test void hardLinkedCopiesAreRefused()throws Exception {
        var header=fixture(3);Files.move(backup.resolve("receipt-000001.json"),root.resolve("retained-original"));
        Files.createLink(backup.resolve("receipt-000001.json"),source.resolve("receipt-000001.json"));
        try(var v=open(header)){assertThrows(IOException.class,()->finish(v));}
    }
    @Test void sourceOmissionAndSymlinkAreRefusedByCoverage()throws Exception {
        var header=fixture(3);Files.createSymbolicLink(source.resolve("unlisted-link"),source.resolve("receipt-000001.json"));
        try(var v=open(header)){assertThrows(IOException.class,()->finish(v));}
    }
    @Test void changedPreviouslyVerifiedFileIsDetectedAfterResume()throws Exception {
        var header=fixture(129);try(var v=open(header)){while(v.completedPages()<1)v.step();}
        Files.writeString(source.resolve("receipt-000001.json"),"changed after receipt");
        try(var v=open(header)){assertThrows(IOException.class,()->finish(v));}
    }
    @Test void checkpointIoFailureCannotBecomeSuccessOnNextTick()throws Exception {
        var header=fixture(3);Files.writeString(cursor.getParent(),"not a directory");
        try(var v=open(header)) {
            assertThrows(IOException.class,()->finish(v));assertEquals(0,v.completedPages());
            assertThrows(IOException.class,v::step);
        }
    }

    PagedBackupVerifier.Header secondCopy(PagedBackupVerifier.Header first,Path copy,Path copyPages)throws Exception {
        Files.createDirectory(copy);Files.createDirectory(copyPages);
        String previous=PagedBackupVerifier.ZERO;
        for(int n=0;n<first.pages();n++){
            var page=json.fromJson(Files.readString(pages.resolve("page-"+n+".json")),PagedBackupVerifier.Page.class);
            var entries=new ArrayList<PagedBackupVerifier.Entry>();
            for(var e:page.entries()){
                Path target=Files.copy(source.resolve(e.path()),copy.resolve(e.path()));
                entries.add(new PagedBackupVerifier.Entry(e.path(),e.bytes(),e.sha256(),e.sourceModifiedMillis(),Files.getLastModifiedTime(target).toMillis()));
            }
            String text=json.toJson(new PagedBackupVerifier.Page(2,n,previous,entries));
            Files.writeString(copyPages.resolve("page-"+n+".json"),text);previous=PhysicalStore.sha(text);
        }
        return new PagedBackupVerifier.Header(2,source.toString(),copy.toString(),first.quiescentPinPath(),first.quiescentPinSha256(),first.pages(),first.files(),previous);
    }
    @Test void nativePairNamespacesVerifyTwoRealCopiesAndResumeSameIdentities()throws Exception {
        var a=fixture(129);Path copy=root.resolve("copy-b"),bp=root.resolve("pages-b"),dir=root.resolve("pair");
        var b=secondCopy(a,copy,bp);assertNotEquals(a,b);
        try(var pair=fr.proutlost.worldprep.orchestration.BackupAuditPair.open(a,b,source,pages,bp,dir,Set.of())){
            while(pair.first().completedPages()<1)pair.first().step();
            while(pair.second().completedPages()<1)pair.second().step();
        }
        String ar=Files.readString(dir.resolve("backup-a/backup-page-receipts/page-1.json"));
        String br=Files.readString(dir.resolve("backup-b/backup-page-receipts/page-1.json"));assertNotEquals(ar,br);
        try(var pair=fr.proutlost.worldprep.orchestration.BackupAuditPair.open(a,b,source,pages,bp,dir,Set.of())){
            assertEquals(1,pair.first().completedPages());assertEquals(1,pair.second().completedPages());finish(pair.first());finish(pair.second());
        }
        assertEquals(ar,Files.readString(dir.resolve("backup-a/backup-page-receipts/page-1.json")));
        assertEquals(br,Files.readString(dir.resolve("backup-b/backup-page-receipts/page-1.json")));
        assertThrows(IOException.class,()->fr.proutlost.worldprep.orchestration.BackupAuditPair.open(b,a,source,bp,pages,dir,Set.of()));
        Files.writeString(source.resolve("receipt-000001.json"),"changed");
        try(var pair=fr.proutlost.worldprep.orchestration.BackupAuditPair.open(a,b,source,pages,bp,dir,Set.of())){assertThrows(IOException.class,()->finish(pair.first()));}
    }
    @Test void oldSharedParentActuallyCollidesAndLegacyLayoutIsRetained()throws Exception {
        var a=fixture(129);Path bp=root.resolve("pages-b"),dir=root.resolve("legacy");
        var b=secondCopy(a,root.resolve("copy-b"),bp);
        try(var av=new PagedBackupVerifier(a,source,pages,dir.resolve("backup-a-cursor.json"),Set.of())){finish(av);}
        Path retained=dir.resolve("backup-page-receipts/page-1.json");String original=Files.readString(retained);
        try(var bv=new PagedBackupVerifier(b,source,bp,dir.resolve("backup-b-cursor.json"),Set.of())){assertThrows(IOException.class,()->finish(bv));}
        assertThrows(IOException.class,()->fr.proutlost.worldprep.orchestration.BackupAuditPair.open(a,b,source,pages,bp,dir,Set.of()));
        assertEquals(original,Files.readString(retained));assertFalse(Files.exists(dir.resolve("backup-a")));
    }
    @Test void eachLegacyMarkerIncludingPartialAndSymlinkRefusesWithoutMutation()throws Exception {
        for(String name:List.of("backup-a-cursor.json","backup-b-cursor.json","backup-page-receipts")){
            Path dir=Files.createTempDirectory(root,"legacy-");Path marker=Files.writeString(dir.resolve(name),"partial retained");
            assertThrows(IOException.class,()->fr.proutlost.worldprep.orchestration.BackupAuditPair.requireNoLegacy(dir));assertEquals("partial retained",Files.readString(marker));
        }
        Path dir=Files.createDirectory(root.resolve("symlink"));Files.createSymbolicLink(dir.resolve("backup-a-cursor.json"),root.resolve("absent"));
        assertThrows(IOException.class,()->fr.proutlost.worldprep.orchestration.BackupAuditPair.requireNoLegacy(dir));
    }
}
