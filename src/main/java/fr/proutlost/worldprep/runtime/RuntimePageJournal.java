package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Exact manifest-bound GEOLOGY/ORES ownership journal. */
public final class RuntimePageJournal {
    public static PagedJournalStore.Manifest initialize(ServerLevel level,BlockWorldPrepData.Journal journal){try{var manifest=PagedJournalStore.initialize(directory(level,journal.id),PagedJournalStore.empty(journal.id,journal.planId,journal.fingerprint,PassId.valueOf(journal.pass),level.dimension().location().toString(),journal.area));update(journal,manifest);return manifest;}catch(IOException e){throw new IllegalStateException("Durable journal initialization failed",e);}}
    public static PagedJournalStore.Manifest publish(ServerLevel level,BlockWorldPrepData.Journal journal,List<BlockWorldPrepData.Change> changes){try{var current=manifestOrEmpty(level,journal);var advanced=PagedJournalStore.append(directory(level,journal.id),current,changes,RuntimePlanStore.BLOCK_CODEC);update(journal,advanced);return advanced;}catch(IOException e){throw new IllegalStateException("Durable journal publication failed",e);}}
    public static PagedJournalStore.Manifest reconcile(ServerLevel level,BlockWorldPrepData.Journal journal){try{var manifest=PagedJournalStore.readManifest(directory(level,journal.id));requireIdentity(level,journal,manifest);PagedJournalStore.validateAll(directory(level,journal.id),manifest);update(journal,manifest);return manifest;}catch(IOException e){throw new IllegalStateException("Journal recovery required",e);}}
    public static PagedJournalStore.Metrics visit(ServerLevel level,BlockWorldPrepData.Journal journal,PagedJournalStore.PageConsumer<BlockWorldPrepData.Change> consumer){try{var manifest=reconcile(level,journal);return PagedJournalStore.visitPages(directory(level,journal.id),manifest,RuntimePlanStore.BLOCK_CODEC,consumer);}catch(IOException e){throw new IllegalStateException("Journal recovery required",e);}}
    public static long durableEntryCount(ServerLevel level,BlockWorldPrepData.Journal journal){Path path=PagedJournalStore.manifestPath(directory(level,journal.id));if(!Files.exists(path))return 0;return reconcile(level,journal).entryCount();}
    private static PagedJournalStore.Manifest manifestOrEmpty(ServerLevel level,BlockWorldPrepData.Journal j)throws IOException{Path directory=directory(level,j.id);if(Files.exists(PagedJournalStore.manifestPath(directory))){var m=PagedJournalStore.readManifest(directory);requireIdentity(level,j,m);return m;}return PagedJournalStore.empty(j.id,j.planId,j.fingerprint,PassId.valueOf(j.pass),level.dimension().location().toString(),j.area);}
    private static void requireIdentity(ServerLevel level,BlockWorldPrepData.Journal j,PagedJournalStore.Manifest m)throws IOException{if(!m.journalId().equals(j.id)||!m.planId().equals(j.planId)||!m.planRoot().equals(j.fingerprint)||m.pass()!=PassId.valueOf(j.pass)||!m.dimension().equals(level.dimension().location().toString())||!m.area().equals(j.area))throw new IOException("Journal ownership identity mismatch");}
    private static void update(BlockWorldPrepData.Journal j,PagedJournalStore.Manifest m){j.pageCount=m.pages().size();j.entryCount=m.entryCount();j.journalRoot=m.root();}
    public static Path directory(ServerLevel level,java.util.UUID id){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2").resolve("journals").resolve(id.toString());}
    public static Path pagePath(ServerLevel level,java.util.UUID id,long sequence){return PagedJournalStore.pagePath(directory(level,id),sequence);}
    private RuntimePageJournal(){}
}
