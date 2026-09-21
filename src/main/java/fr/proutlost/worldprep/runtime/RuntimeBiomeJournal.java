package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Exact, manifest-bound BIOMES ownership journal; SavedData retains metadata only. */
public final class RuntimeBiomeJournal {
    private static final PagedPlanStore.EntryCodec<WorldPrepSavedData.SnapshotCell> CODEC=new PagedPlanStore.EntryCodec<>(){
        public void write(DataOutput out,WorldPrepSavedData.SnapshotCell c)throws IOException{out.writeInt(c.quartX());out.writeInt(c.quartY());out.writeInt(c.quartZ());out.writeUTF(c.beforeBiome());out.writeUTF(c.appliedBiome());}
        public WorldPrepSavedData.SnapshotCell read(DataInput in)throws IOException{return new WorldPrepSavedData.SnapshotCell(in.readInt(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF());}
    };
    public static PagedJournalStore.Manifest initialize(ServerLevel level,WorldPrepSavedData.Snapshot snapshot){try{var manifest=PagedJournalStore.initialize(directory(level,snapshot.id),PagedJournalStore.empty(snapshot.id,snapshot.planId,snapshot.planFingerprint,PassId.BIOMES,snapshot.dimension,snapshot.area));update(snapshot,manifest);return manifest;}catch(IOException e){throw new IllegalStateException("Durable biome journal initialization failed",e);}}

    public static List<WorldPrepSavedData.SnapshotCell> publishOrRead(ServerLevel level,WorldPrepSavedData.Snapshot snapshot,List<WorldPrepSavedData.SnapshotCell> proposed){
        try{
            var current=manifestOrEmpty(level,snapshot);long sequence=current.pages().size();
            var advanced=PagedJournalStore.append(directory(level,snapshot.id),current,proposed,CODEC);update(snapshot,advanced);
            var result=new AtomicReference<List<WorldPrepSavedData.SnapshotCell>>();
            PagedJournalStore.visitPages(directory(level,snapshot.id),advanced,CODEC,(page,entries)->{if(page==sequence)result.set(List.copyOf(entries));});
            return result.get();
        }catch(IOException e){throw new IllegalStateException("Durable biome journal publication failed",e);}
    }

    public static PagedJournalStore.Manifest requireManifest(ServerLevel level,WorldPrepSavedData.Snapshot snapshot){try{var manifest=PagedJournalStore.readManifest(directory(level,snapshot.id));requireIdentity(snapshot,manifest);PagedJournalStore.validateAll(directory(level,snapshot.id),manifest);update(snapshot,manifest);return manifest;}catch(IOException e){throw new IllegalStateException("Biome journal recovery required",e);}}
    public static PagedJournalStore.Metrics visit(ServerLevel level,WorldPrepSavedData.Snapshot snapshot,PagedJournalStore.PageConsumer<WorldPrepSavedData.SnapshotCell> consumer){try{var manifest=requireManifest(level,snapshot);return PagedJournalStore.visitPages(directory(level,snapshot.id),manifest,CODEC,consumer);}catch(IOException e){throw new IllegalStateException("Biome journal recovery required",e);}}
    public static long durablePageCount(ServerLevel level,WorldPrepSavedData.Snapshot snapshot){Path manifest=PagedJournalStore.manifestPath(directory(level,snapshot.id));if(!Files.exists(manifest))return 0;try{return PagedJournalStore.readManifest(directory(level,snapshot.id)).pages().size();}catch(IOException e){throw new IllegalStateException("Biome journal recovery required",e);}}
    private static PagedJournalStore.Manifest manifestOrEmpty(ServerLevel level,WorldPrepSavedData.Snapshot s)throws IOException{Path directory=directory(level,s.id);if(Files.exists(PagedJournalStore.manifestPath(directory))){var m=PagedJournalStore.readManifest(directory);if(!m.journalId().equals(s.id)||!m.planId().equals(s.planId)||!m.planRoot().equals(s.planFingerprint)||m.pass()!=PassId.BIOMES||!m.dimension().equals(s.dimension)||!m.area().equals(s.area))throw new IOException("Biome journal identity mismatch");return m;}return PagedJournalStore.empty(s.id,s.planId,s.planFingerprint,PassId.BIOMES,s.dimension,s.area);}
    private static void update(WorldPrepSavedData.Snapshot s,PagedJournalStore.Manifest m){s.pageCount=m.pages().size();s.entryCount=m.entryCount();s.journalRoot=m.root();}
    private static void requireIdentity(WorldPrepSavedData.Snapshot s,PagedJournalStore.Manifest m)throws IOException{if(!m.journalId().equals(s.id)||!m.planId().equals(s.planId)||!m.planRoot().equals(s.planFingerprint)||m.pass()!=PassId.BIOMES||!m.dimension().equals(s.dimension)||!m.area().equals(s.area))throw new IOException("Exact biome journal identity mismatch");}
    public static Path directory(ServerLevel level,java.util.UUID id){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2").resolve("biome-journals").resolve(id.toString());}
    public static Path pagePath(ServerLevel level,java.util.UUID id,long sequence){return PagedJournalStore.pagePath(directory(level,id),sequence);}
    private RuntimeBiomeJournal(){}
}
