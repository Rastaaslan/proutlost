package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.environment.EnvironmentalMutation;
import fr.proutlost.worldprep.environment.EnvironmentalMutationCodec;
import fr.proutlost.worldprep.environment.ExactMutationExecutor;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.Direction;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.DurableJournal;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.Snapshot;
import fr.proutlost.worldprep.environment.ExactMutationExecutor.WorldAccess;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.storage.PagedJournalStore;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.storage.PlanManifest;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The single destructive runtime for SOILS, AQUATIC, STRUCTURES, FLORA and TREES.
 * It streams a sealed plan, assembles complete contiguous logical groups across
 * page boundaries, preflights them, publishes exact ownership, and only then
 * touches the live level.
 */
public final class EnvironmentalMutationRuntime {
    public static final int MAX_GROUP_ENTRIES = 4096;
    public static final int MAX_PLAN_ENTRIES = 1_000_000;
    private static final Set<fr.proutlost.worldprep.pipeline.PassId> LIVE_PASSES = Set.of(
            fr.proutlost.worldprep.pipeline.PassId.SOILS,
            fr.proutlost.worldprep.pipeline.PassId.AQUATIC,
            fr.proutlost.worldprep.pipeline.PassId.STRUCTURES,
            fr.proutlost.worldprep.pipeline.PassId.FLORA,
            fr.proutlost.worldprep.pipeline.PassId.TREES);

    /** Executes each complete group without ever retaining the complete plan. */
    public static long executePlan(Path planDirectory, PlanManifest manifest, Direction direction,
            WorldAccess world, DurableJournal journal) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        if (!LIVE_PASSES.contains(manifest.passId())) throw new IllegalArgumentException("Not a destructive environmental pass: " + manifest.passId());
        long entries = manifest.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum();
        if (entries > MAX_PLAN_ENTRIES) throw new IllegalStateException("Environmental plan exceeds bounded validation limit");
        // Validate the complete sealed stream before the first owned write. The fixed
        // plan limit makes these identity sets bounded, while mutations remain paged.
        var validator = new PlanValidator(manifest);
        PagedPlanStore.read(planDirectory, manifest, EnvironmentalMutationCodec.INSTANCE, validator::accept);
        validator.finish();
        var assembler = new GroupAssembler(direction, world, journal);
        PagedPlanStore.read(planDirectory, manifest, EnvironmentalMutationCodec.INSTANCE, assembler::accept);
        assembler.finish();
        return assembler.executed;
    }

    private static final class PlanValidator {
        private final PlanManifest manifest;
        private final HashSet<EnvironmentalMutation.Position> positions=new HashSet<>();
        private final HashSet<UUID> closed=new HashSet<>();
        private UUID current; private int groupSize; private boolean singleton;
        PlanValidator(PlanManifest manifest){this.manifest=manifest;}
        void accept(EnvironmentalMutation mutation){
            if(mutation.pass()!=manifest.passId())throw new IllegalStateException("Mutation pass does not match sealed manifest");
            if(!positions.add(mutation.position()))throw new IllegalStateException("Duplicate position in environmental plan: "+mutation.position());
            UUID id=mutation.groupId();boolean nextSingleton=id==null;
            if(groupSize>0&&(singleton||nextSingleton||!Objects.equals(current,id)))close();
            if(!nextSingleton&&closed.contains(id))throw new IllegalStateException("MutationGroup occurs in disjoint plan ranges: "+id);
            if(groupSize==0){current=id;singleton=nextSingleton;}
            if(++groupSize>MAX_GROUP_ENTRIES)throw new IllegalStateException("MutationGroup exceeds safe maximum of "+MAX_GROUP_ENTRIES);
            if(nextSingleton)close();
        }
        void finish(){if(groupSize>0)close();}
        private void close(){if(current!=null)closed.add(current);current=null;groupSize=0;singleton=false;}
    }

    /** Replays only durable journal ownership; a plan is deliberately not accepted here. */
    public static long rollbackJournal(Path journalDirectory, PagedJournalStore.Manifest manifest,
            WorldAccess world) throws IOException {
        if (!LIVE_PASSES.contains(manifest.pass())) throw new IllegalArgumentException("Not a destructive environmental pass: " + manifest.pass());
        var assembler = new GroupAssembler(Direction.ROLLBACK, world, (id, group) -> { });
        PagedJournalStore.visitPages(journalDirectory, manifest, EnvironmentalMutationCodec.INSTANCE,
                (sequence, entries) -> { for (var entry : entries) assembler.accept(entry); });
        assembler.finish();
        return assembler.executed;
    }

    /** Journal implementation whose return means page and advanced manifest reread successfully. */
    public static final class PagedJournal implements DurableJournal {
        private final Path directory;
        private PagedJournalStore.Manifest manifest;
        public PagedJournal(Path directory, PagedJournalStore.Manifest identity) throws IOException {
            this.directory = Objects.requireNonNull(directory);
            this.manifest = PagedJournalStore.initialize(directory, Objects.requireNonNull(identity));
            PagedJournalStore.validateAll(directory, manifest);
        }
        @Override public synchronized void append(UUID groupId, List<EnvironmentalMutation> group) {
            try {
                manifest = PagedJournalStore.append(directory, manifest, group, EnvironmentalMutationCodec.INSTANCE);
                PagedJournalStore.validateAll(directory, manifest);
            } catch (IOException exception) {
                throw new IllegalStateException("Environmental journal publication failed", exception);
            }
        }
        public PagedJournalStore.Manifest manifest() { return manifest; }
    }

    private static final class GroupAssembler {
        private final Direction direction; private final WorldAccess world; private final DurableJournal journal;
        private final ArrayList<EnvironmentalMutation> group = new ArrayList<>();
        private final HashSet<UUID> closedGroups = new HashSet<>();
        private final HashSet<EnvironmentalMutation.Position> positions = new HashSet<>();
        private UUID current; private boolean currentIsSingleton; private long executed;
        GroupAssembler(Direction direction, WorldAccess world, DurableJournal journal) { this.direction=direction;this.world=world;this.journal=journal; }
        void accept(EnvironmentalMutation mutation) {
            if (!positions.add(mutation.position())) throw new IllegalStateException("Duplicate position in environmental plan: " + mutation.position());
            UUID id=mutation.groupId(); boolean singleton=id==null;
            if (!group.isEmpty() && (currentIsSingleton || singleton || !Objects.equals(current,id))) flush();
            if (!singleton && closedGroups.contains(id)) throw new IllegalStateException("MutationGroup occurs in disjoint plan ranges: " + id);
            if (group.isEmpty()) { current=id;currentIsSingleton=singleton; }
            group.add(mutation);
            if (group.size()>MAX_GROUP_ENTRIES) throw new IllegalStateException("MutationGroup exceeds safe maximum of " + MAX_GROUP_ENTRIES);
            if (singleton) flush();
        }
        void finish(){if(!group.isEmpty())flush();}
        private void flush(){
            UUID id=current;
            new ExactMutationExecutor().executeGroup(direction,id,List.copyOf(group),world,journal);
            executed+=group.size();if(id!=null)closedGroups.add(id);group.clear();current=null;currentIsSingleton=false;
        }
    }

    /** Exact ServerLevel adapter. Reads never load or generate a chunk. */
    public static final class ServerWorldAccess implements WorldAccess {
        private final ServerLevel level; private final WorldPrepSavedData data; private final WorldPrepSavedData.Area area;
        public ServerWorldAccess(ServerLevel level, WorldPrepSavedData data, WorldPrepSavedData.Area area) {
            this.level=Objects.requireNonNull(level);this.data=Objects.requireNonNull(data);this.area=Objects.requireNonNull(area);
        }
        @Override public Snapshot read(EnvironmentalMutation.Position position) {
            BlockPos pos=position(position);ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ());
            BlockEntity entity=level.getBlockEntity(pos);
            return new Snapshot(encode(level.getBlockState(pos)),entity==null?null:type(entity),entity==null?null:canonical(entity.saveWithFullMetadata(level.registryAccess())));
        }
        @Override public boolean writable(EnvironmentalMutation.Position position) {
            if(data.protectedBlock(area.dimension(),position.x(),position.z()))return false;
            try { ServerExistingChunkAccess.requireBlock(level,area,position.x(),position.y(),position.z());return true; }
            catch(ServerExistingChunkAccess.Refused refused){return false;}
        }
        @Override public void write(EnvironmentalMutation.Position position,Snapshot snapshot) {
            BlockPos pos=position(position);BlockState target=decode(snapshot.state());
            level.setBlock(pos,target,Block.UPDATE_CLIENTS);
            BlockEntity entity=level.getBlockEntity(pos);
            if(snapshot.blockEntityType()==null){if(entity!=null)throw new IllegalStateException("Unexpected BlockEntity after write");}
            else {
                if(entity==null||!type(entity).equals(snapshot.blockEntityType()))throw new IllegalStateException("BlockEntity type cannot be installed exactly");
                entity.loadWithComponents(parse(snapshot.blockEntityNbt()),level.registryAccess());entity.setChanged();
                if(!canonical(entity.saveWithFullMetadata(level.registryAccess())).equals(canonical(parse(snapshot.blockEntityNbt()))))
                    throw new IllegalStateException("BlockEntity did not restore exactly");
            }
            ServerExistingChunkAccess.requireBlock(level,area,pos.getX(),pos.getY(),pos.getZ()).setUnsaved(true);
        }
        @Override public void validateWrite(EnvironmentalMutation.Position position,Snapshot snapshot) {
            BlockState target=decode(snapshot.state());
            if ((target.getBlock() instanceof EntityBlock) != (snapshot.blockEntityType()!=null))
                throw new IllegalStateException("Unsupported implicit BlockEntity ownership at " + position);
            if(snapshot.blockEntityNbt()!=null)parse(snapshot.blockEntityNbt());
        }
        private static BlockPos position(EnvironmentalMutation.Position p){return new BlockPos(p.x(),p.y(),p.z());}
    }

    private static String type(BlockEntity entity){ResourceLocation key=BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());if(key==null)throw new IllegalStateException("Unregistered BlockEntity type");return key.toString();}
    private static CompoundTag parse(String nbt){try{return TagParser.parseTag(nbt);}catch(Exception e){throw new IllegalStateException("Invalid persisted BlockEntity NBT",e);}}
    private static String canonical(String nbt){return canonical(parse(nbt));}
    private static String canonical(CompoundTag nbt){return nbt.toString();}
    private static String encode(BlockState state){var result=new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());if(!state.getValues().isEmpty()){result.append('[');state.getValues().entrySet().stream().sorted(java.util.Comparator.comparing(e->e.getKey().getName())).forEach(e->result.append(e.getKey().getName()).append('=').append(valueName(e.getKey(),e.getValue())).append(','));result.setCharAt(result.length()-1,']');}return result.toString();}
    private static <T extends Comparable<T>>String valueName(Property<T> property,Comparable<?> value){return property.getName(property.getValueClass().cast(value));}
    @SuppressWarnings({"rawtypes","unchecked"}) private static BlockState decode(String text){int bracket=text.indexOf('[');String name=bracket<0?text:text.substring(0,bracket);ResourceLocation id=ResourceLocation.tryParse(name);if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalStateException("Unknown persisted BlockState "+text);BlockState state=BuiltInRegistries.BLOCK.get(id).defaultBlockState();if(bracket>=0){if(!text.endsWith("]"))throw new IllegalStateException("Invalid persisted BlockState "+text);for(String pair:text.substring(bracket+1,text.length()-1).split(",")){String[] parts=pair.split("=",2);Property property=state.getBlock().getStateDefinition().getProperty(parts[0]);if(property==null||parts.length!=2||property.getValue(parts[1]).isEmpty())throw new IllegalStateException("Invalid persisted BlockState "+text);state=state.setValue(property,(Comparable)property.getValue(parts[1]).get());}}return state;}
    private EnvironmentalMutationRuntime() {}
}
