package fr.proutlost.worldprep;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.DurablePageStore;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PagedJournalStoreTest {
    @TempDir Path root;
    private static final PagedPlanStore.EntryCodec<Integer> CODEC=new PagedPlanStore.EntryCodec<>(){
        public void write(DataOutput out,Integer value)throws IOException{out.writeInt(value);}
        public Integer read(DataInput in)throws IOException{return in.readInt();}
    };
    private PagedJournalStore.Manifest empty(UUID journal,UUID plan){return PagedJournalStore.empty(journal,plan,PlanFingerprint.of("plan").value(),PassId.GEOLOGY,"minecraft:overworld","proutlost:test");}

    @Test void hundredsOfPagesRemainOnePageResidentAndOrdered()throws Exception{
        UUID journal=UUID.randomUUID(),plan=UUID.randomUUID();Path directory=root.resolve(journal.toString());var manifest=PagedJournalStore.initialize(directory,empty(journal,plan));
        for(int page=0;page<300;page++){var entries=new ArrayList<Integer>();for(int i=0;i<7;i++)entries.add(page*7+i);manifest=PagedJournalStore.append(directory,manifest,entries,CODEC);}
        var observed=new ArrayList<Integer>();var metrics=PagedJournalStore.visitPages(directory,manifest,CODEC,(sequence,entries)->observed.addAll(entries));
        assertEquals(2100,observed.size());assertEquals(0,observed.getFirst());assertEquals(2099,observed.getLast());assertEquals(300,metrics.pages());assertEquals(1,metrics.maximumResidentPages());assertEquals(7,metrics.maximumResidentEntries());
    }

    @Test void missingCorruptTrailingAndWrongExpectedManifestFailClosed()throws Exception{
        UUID journal=UUID.randomUUID(),plan=UUID.randomUUID();Path directory=root.resolve("journal");var manifest=PagedJournalStore.initialize(directory,empty(journal,plan));manifest=PagedJournalStore.append(directory,manifest,List.of(1,2,3),CODEC);var sealed=manifest;var missingDirectory=directory;
        Files.delete(PagedJournalStore.pagePath(directory,0));assertThrows(IOException.class,()->PagedJournalStore.validateAll(missingDirectory,sealed));
        directory=root.resolve("trailing");manifest=PagedJournalStore.initialize(directory,empty(journal,plan));manifest=PagedJournalStore.append(directory,manifest,List.of(1),CODEC);Files.write(PagedJournalStore.pagePath(directory,0),new byte[]{1},StandardOpenOption.APPEND);var trailing=manifest;var trailingDirectory=directory;assertThrows(IOException.class,()->PagedJournalStore.validateAll(trailingDirectory,trailing));
        directory=root.resolve("wrong");manifest=PagedJournalStore.initialize(directory,empty(journal,plan));manifest=PagedJournalStore.append(directory,manifest,List.of(1),CODEC);var wrong=empty(journal,UUID.randomUUID());Path exact=directory;assertThrows(IOException.class,()->PagedJournalStore.validateAll(exact,wrong));
    }

    @Test void conflictingOrphanPageIsNeverAdopted()throws Exception{
        UUID journal=UUID.randomUUID(),plan=UUID.randomUUID();Path directory=root.resolve("orphan");var expected=empty(journal,plan);PagedJournalStore.initialize(directory,expected);Files.delete(PagedJournalStore.manifestPath(directory));
        byte[] payload;try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeInt(1);out.writeInt(99);payload=bytes.toByteArray();}
        var wrong=DurablePageStore.page(DurablePageStore.StorageKind.JOURNAL,journal,PassId.GEOLOGY,"minecraft:overworld",0,0,0,1,payload);DurablePageStore.publish(PagedJournalStore.pagePath(directory,0),wrong);
        assertThrows(IOException.class,()->PagedJournalStore.append(directory,expected,List.of(1),CODEC));
    }
}
