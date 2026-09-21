package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.geology.GeologyPlanner;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import fr.proutlost.worldprep.storage.PlanManifest;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Shared incremental executor for deterministic BlockState plans and owned rollback journals. */
public final class BlockMutationRuntime {
    private static final TagKey<Block> GEOLOGY=tag("geology_replaceable"),STONE=tag("ore_replaceable/stone"),DEEPSLATE=tag("ore_replaceable/deepslate");
    private record Ore(String name,String stone,String deep,int min,int max,int rarity,long salt){}
    private static final Ore[] ORES={new Ore("coal","coal_ore","deepslate_coal_ore",0,192,95,11),new Ore("iron","iron_ore","deepslate_iron_ore",-64,128,120,17),new Ore("copper","copper_ore","deepslate_copper_ore",-16,112,150,23),new Ore("gold","gold_ore","deepslate_gold_ore",-64,32,260,29),new Ore("redstone","redstone_ore","deepslate_redstone_ore",-64,16,180,31),new Ore("lapis","lapis_ore","deepslate_lapis_ore",-64,48,300,37),new Ore("diamond","diamond_ore","deepslate_diamond_ore",-64,16,650,41),new Ore("emerald","emerald_ore","deepslate_emerald_ore",32,256,900,43)};

    public static BlockWorldPrepData data(ServerLevel level){return level.getDataStorage().computeIfAbsent(BlockWorldPrepData.FACTORY,BlockWorldPrepData.FILE_ID);}
    public static boolean isBlockOperation(WorldPrepSavedData.Operation operation){return switch(operation){case PREVIEW_GEOLOGY,APPLY_GEOLOGY,ROLLBACK_GEOLOGY,PREVIEW_ORES,APPLY_ORES,ROLLBACK_ORES->true;default->false;};}

    public static void prepare(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Operation operation,WorldPrepSavedData.Area area){
        String pass=pass(operation);var blocks=data(level);requireCompatible(blocks);
        if(isApply(operation)){
            var plan=requirePlan(level,data,area,pass);var snapshotId=UUID.randomUUID();
            var journal=new BlockWorldPrepData.Journal(snapshotId,id,plan.planId,area.id(),pass,plan.fingerprint);RuntimePageJournal.initialize(level,journal);blocks.journals().put(snapshotId,journal);
            data.jobs().put(id,data.jobs().get(id).withSnapshot(snapshotId));blocks.changed();data.changed();level.getDataStorage().save();
        }else if(isRollback(operation)){
            var requested=data.jobs().get(id).snapshotId();var journal=blocks.journals().get(requested);
            if(journal==null||!journal.area.equals(area.id())||!journal.pass.equals(pass))throw new IllegalStateException("Exact rollback journal ownership mismatch");
        }
    }

    public static void process(ServerLevel level,WorldPrepSavedData data,UUID id,WorldPrepSavedData.Job job,int budget){
        var area=WorldPrepRuntime.requireArea(data,job.area());String pass=pass(job.operation());var blocks=data(level);requireCompatible(blocks);
        if(isRollback(job.operation())){rollback(level,data,blocks,id,job,area,pass,budget);return;}
        String key=BlockWorldPrepData.planKey(area.id(),pass);var plan=blocks.plans().get(key);
        if(job.operation().name().startsWith("PREVIEW")){
            if(job.cursor()==0){plan=new BlockWorldPrepData.Plan(area.id(),pass,"development",input(level,data,area,pass),"");publish(level,data,area,plan);blocks.plans().put(key,plan);if(pass.equals("GEOLOGY"))blocks.plans().remove(BlockWorldPrepData.planKey(area.id(),"ORES"));blocks.changed();data.jobs().put(id,job.withCursor(plan.entryCount).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();}return;
        }
        plan=requirePlan(level,data,area,pass);
        applyBatch(level,data,blocks,id,job,area,plan,budget);
    }

    private static void previewSlice(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,String pass,BlockWorldPrepData.Plan plan,int budget){
        long height=level.getMaxBuildHeight()-level.getMinBuildHeight(),width=(long)area.maxX()-area.minX()+1,depth=(long)area.maxZ()-area.minZ()+1,total=width*depth*height,cursor=job.cursor();
        for(int n=0;n<budget&&cursor<total;n++,cursor++){
            int y=level.getMinBuildHeight()+(int)(cursor%height);long column=cursor/height;int x=area.minX()+(int)(column%width),z=area.minZ()+(int)(column/width);
            preview(level,data,plan,area,pass,x,y,z);data.jobs().put(id,data.jobs().get(id).withCursor(cursor+1));data.changed();
        }
        if(cursor>=total){plan.fingerprint=fingerprint(plan);if(pass.equals("GEOLOGY"))blocks.plans().remove(BlockWorldPrepData.planKey(area.id(),"ORES"));blocks.changed();data.jobs().put(id,data.jobs().get(id).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();}
    }

    /** Two phase protocol: persist the complete batch journal, then perform idempotent writes, then persist its cursor. */
    private static void applyBatch(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,int budget){
        var journal=requireJournal(blocks,job,area,plan);reconcileAppliedJournal(level,data,area,plan,journal);blocks.changed();
        long durable=RuntimePageJournal.durableEntryCount(level,journal);if(durable>plan.entryCount)throw new IllegalStateException("Journal exceeds exact plan");
        List<BlockWorldPrepData.Change> changes=new ArrayList<>();int count=BlockCheckpointPolicy.batchLength(durable,Math.toIntExact(plan.entryCount),budget);
        if(count==0){data.jobs().put(id,job.withCursor(durable).withState(WorldPrepSavedData.JobState.COMPLETED));data.changed();level.getDataStorage().save();return;}
        try{PagedPlanStore.readRange(RuntimePlanStore.directory(level,plan.planId),manifest(level,plan),RuntimePlanStore.BLOCK_CODEC,durable,count,changes::add);}catch(IOException e){throw new IllegalStateException("Paged plan validation failed",e);}
        if(changes.size()!=count)throw new IllegalStateException("Paged plan ended before sealed entry count");
        int start=Math.toIntExact(durable),end=start+count;
        for(var change:changes){validateBounds(level,area,change);var pos=new BlockPos(change.x(),change.y(),change.z());var current=encode(level.getBlockState(pos));
            ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());
            if(current.equals(change.before())&&safeAtBoundary(level,data,area,plan,change,pos)){}
            // Every sealed mutation is mandatory: protection, BlockEntity, or a third state is a conflict.
            else throw ownership(pos);
        }
        var batch=List.copyOf(changes);
        RuntimePageJournal.publish(level,journal,batch);
        blocks.changed();level.getDataStorage().save(); // disk page + ownership are durable before every following mutation
        RuntimePageJournal.reconcile(level,journal);
        for(var change:changes){
            var pos=new BlockPos(change.x(),change.y(),change.z());String current=encode(level.getBlockState(pos));
            if(current.equals(change.before())){level.setBlock(pos,decode(change.applied()),Block.UPDATE_CLIENTS);ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z()).setUnsaved(true);}
            else if(!current.equals(change.applied()))throw ownership(pos);
        }
        var advanced=data.jobs().get(id).withCursor(end);if(end>=plan.entryCount)advanced=advanced.withState(WorldPrepSavedData.JobState.COMPLETED);
        data.jobs().put(id,advanced);data.changed();level.getDataStorage().save();
    }

    private static void preview(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData.Plan plan,WorldPrepSavedData.Area area,String pass,int x,int y,int z){
        ServerExistingChunkAccess.requireBlock(level,area,x,y,z);var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);boolean protectedAt=data.protectedBlock(area.dimension(),x,z)||level.getBlockEntity(pos)!=null;
        if(pass.equals("GEOLOGY")){
            if(!state.is(GEOLOGY)){if(protectedAt)plan.skipped++;return;}plan.eligible++;if(protectedAt){plan.skipped++;return;}
            var province=GeologyPlanner.province(level.getSeed(),x,y,z);String target=GeologyPlanner.replacement(level.getSeed(),x,y,z,province);
            if(!id(state).equals(target))plan.add(new BlockWorldPrepData.Change(x,y,z,encode(state),target,province.name()));
        }else{
            boolean stone=state.is(STONE),deep=state.is(DEEPSLATE);if(!stone&&!deep){if(protectedAt)plan.skipped++;return;}plan.eligible++;if(protectedAt){plan.skipped++;return;}
            for(var ore:ORES)if(y>=ore.min&&y<=ore.max&&Math.floorMod(GeologyPlanner.mix(level.getSeed()^ore.salt^x*31L^y*131L^z*8191L),ore.rarity)==0){plan.add(new BlockWorldPrepData.Change(x,y,z,encode(state),"minecraft:"+(deep?ore.deep:ore.stone),ore.name));break;}
        }
    }

    private static void rollback(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData blocks,UUID id,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,String pass,int budget){
        var journal=blocks.journals().get(job.snapshotId());if(journal==null||!journal.area.equals(area.id())||!journal.pass.equals(pass))throw new IllegalStateException("Snapshot ownership mismatch");
        RuntimePageJournal.reconcile(level,journal);preflightBlockRollback(level,data,area,journal);
        int[] remaining={Math.max(1,budget)},restored={0};boolean[] pending={false};
        RuntimePageJournal.visit(level,journal,(sequence,changes)->{for(var change:changes){var pos=new BlockPos(change.x(),change.y(),change.z());String current=encode(level.getBlockState(pos));if(current.equals(change.applied())){if(remaining[0]>0){level.setBlock(pos,decode(change.before()),Block.UPDATE_CLIENTS);ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z()).setUnsaved(true);remaining[0]--;restored[0]++;}else pending[0]=true;}}});
        var advanced=data.jobs().get(id).withCursor(job.cursor()+restored[0]);if(!pending[0])advanced=advanced.withState(WorldPrepSavedData.JobState.COMPLETED);data.jobs().put(id,advanced);data.changed();blocks.changed();level.getDataStorage().save();
    }

    private static void reconcileAppliedJournal(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,BlockWorldPrepData.Journal journal){if(journal.pageCount==0&&!java.nio.file.Files.exists(fr.proutlost.worldprep.storage.PagedJournalStore.manifestPath(RuntimePageJournal.directory(level,journal.id))))return;RuntimePageJournal.reconcile(level,journal);RuntimePageJournal.visit(level,journal,(sequence,changes)->{for(var change:changes){validateBounds(level,area,change);var pos=new BlockPos(change.x(),change.y(),change.z());ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());String current=encode(level.getBlockState(pos));if(current.equals(change.before())){if(!safeAtBoundary(level,data,area,plan,change,pos))throw ownership(pos);}else if(!current.equals(change.applied()))throw ownership(pos);}});RuntimePageJournal.visit(level,journal,(sequence,changes)->{for(var change:changes){var pos=new BlockPos(change.x(),change.y(),change.z());if(encode(level.getBlockState(pos)).equals(change.before())){level.setBlock(pos,decode(change.applied()),Block.UPDATE_CLIENTS);ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z()).setUnsaved(true);}}});}
    private static void preflightBlockRollback(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Journal journal){RuntimePageJournal.visit(level,journal,(sequence,changes)->{for(var change:changes){validateBounds(level,area,change);if(data.protectedBlock(area.dimension(),change.x(),change.z()))throw new IllegalStateException("Rollback location is protected");var pos=new BlockPos(change.x(),change.y(),change.z());ServerExistingChunkAccess.requireBlock(level,area,change.x(),change.y(),change.z());String current=encode(level.getBlockState(pos));if(!current.equals(change.applied())&&!current.equals(change.before()))throw ownership(pos);}});}

    private static boolean safeAtBoundary(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan,BlockWorldPrepData.Change change,BlockPos pos){
        if(data.protectedBlock(area.dimension(),change.x(),change.z())||level.getBlockEntity(pos)!=null)return false;
        var current=level.getBlockState(pos);return plan.pass.equals("GEOLOGY")?current.is(GEOLOGY):(current.is(STONE)||current.is(DEEPSLATE))&&validOreTarget(change.applied(),change.y());
    }
    private static void validateBounds(ServerLevel level,WorldPrepSavedData.Area area,BlockWorldPrepData.Change change){if(change.x()<area.minX()||change.x()>area.maxX()||change.z()<area.minZ()||change.z()>area.maxZ()||change.y()<level.getMinBuildHeight()||change.y()>=level.getMaxBuildHeight())throw new IllegalStateException("Persisted change outside area/build bounds");}
    private static IllegalStateException ownership(BlockPos pos){return new IllegalStateException("Block ownership ambiguous at "+pos.toShortString());}
    private static BlockWorldPrepData.Journal requireJournal(BlockWorldPrepData blocks,WorldPrepSavedData.Job job,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){var journal=blocks.journals().get(job.snapshotId());if(journal==null||!journal.job.equals(job.id())||!journal.area.equals(area.id())||!journal.pass.equals(plan.pass)||!journal.fingerprint.equals(plan.fingerprint))throw new IllegalStateException("Snapshot ownership mismatch");return journal;}
    private static List<BlockWorldPrepData.Change> ordered(java.util.Collection<BlockWorldPrepData.Change> values){var result=new ArrayList<>(values);result.sort(Comparator.comparingInt(BlockWorldPrepData.Change::z).thenComparingInt(BlockWorldPrepData.Change::x).thenComparingInt(BlockWorldPrepData.Change::y));return result;}
    private static void requireCompatible(BlockWorldPrepData data){if(!data.compatible())throw new IllegalStateException("Legacy block persistence format is incompatible; block job refused");}
    private static boolean validOreTarget(String target,int y){for(var ore:ORES)if((target.equals("minecraft:"+ore.stone)||target.equals("minecraft:"+ore.deep))&&y>=ore.min&&y<=ore.max)return true;return false;}
    private static BlockWorldPrepData.Plan requirePlan(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){var blocks=data(level);requireCompatible(blocks);var plan=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),pass));if(plan==null||plan.fingerprint.isEmpty())throw new IllegalStateException("No persisted "+pass+" preview");if(plan.planId==null)publish(level,data,area,plan);if(!plan.input.equals(input(level,data,area,pass)))throw new IllegalStateException("Stale "+pass+" preview");if(pass.equals("ORES")){var geology=blocks.plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology==null||plan.upstreamPlanId==null||!plan.upstreamPlanId.equals(geology.planId)||!plan.upstreamRoot.equals(geology.fingerprint))throw new IllegalStateException("ORES exact GEOLOGY plan identity is stale");if(!hasAppliedGeology(blocks,area.id()))throw new IllegalStateException("ORES requires applied GEOLOGY");}manifest(level,plan);return plan;}
    private static boolean hasAppliedGeology(BlockWorldPrepData data,String area){return data.journals().values().stream().anyMatch(j->j.area.equals(area)&&j.pass.equals("GEOLOGY"));}
    public static String inputIdentity(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){if(pass.equals("ORES")){var geology=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology!=null&&geology.planId==null)publish(level,data,area,geology);}return input(level,data,area,pass);}
    public static void seal(BlockWorldPrepData.Plan plan){plan.fingerprint=fingerprint(plan);}
    /** Publishes hand-authored acceptance-test plans through the same production sealing path. */
    public static void publishSealed(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){seal(plan);publish(level,data,area,plan);}
    public static String stateString(BlockState state){return encode(state);}
    private static String input(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,String pass){long protections=data.protections().values().stream().filter(p->p.dimension().equals(area.dimension())).mapToLong(Object::hashCode).sum();String geology=pass.equals("ORES")?java.util.Optional.ofNullable(data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"))).map(p->p.fingerprint).orElse(""):"";return area.dimension()+":"+area.minX()+":"+area.minZ()+":"+area.maxX()+":"+area.maxZ()+":"+level.getSeed()+":"+protections+":"+geology;}
    private static String fingerprint(BlockWorldPrepData.Plan plan){String changes=PlanFingerprint.of(plan.changes.values().stream().map(c->c.x()+","+c.y()+","+c.z()+","+c.before()+","+c.applied()).sorted().toArray(String[]::new)).value();return PlanFingerprint.of(plan.input,changes).value();}

    private static void publish(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){
        try{
            if(plan.planId!=null){manifest(level,plan);return;}
            Iterable<BlockWorldPrepData.Change> entries=plan.changes.isEmpty()?plannedEntries(level,data,area,plan):ordered(plan.changes.values());
            plan.planId=UUID.randomUUID();
            var publication=PagedPlanStore.publish(RuntimePlanStore.root(level),plan.planId,RuntimePlanStore.pass(plan.pass),plan.area,area.dimension(),plan.profile,PlanFingerprint.of(plan.input).value(),RuntimePlanStore.ENGINE_VERSION,RuntimePlanStore.PLANNER_VERSION,RuntimePlanStore.ENTRIES_PER_PAGE,entries,RuntimePlanStore.BLOCK_CODEC);
            plan.entryCount=publication.manifest().pages().stream().mapToLong(PlanManifest.PageReference::entryCount).sum();plan.fingerprint=publication.manifest().rootFingerprint();
            if(plan.pass.equals("ORES")){var geology=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(area.id(),"GEOLOGY"));if(geology==null)throw new IllegalStateException("ORES requires exact GEOLOGY plan");if(geology.planId==null)publish(level,data,area,geology);plan.upstreamPlanId=geology.planId;plan.upstreamRoot=geology.fingerprint;}
            plan.changes.clear();
        }catch(IOException e){throw new IllegalStateException("Plan publication failed",e);}
    }
    private static Iterable<BlockWorldPrepData.Change> plannedEntries(ServerLevel level,WorldPrepSavedData data,WorldPrepSavedData.Area area,BlockWorldPrepData.Plan plan){return()->new Iterator<>(){
        final long height=level.getMaxBuildHeight()-level.getMinBuildHeight(),width=(long)area.maxX()-area.minX()+1,total=width*((long)area.maxZ()-area.minZ()+1)*height;long cursor;BlockWorldPrepData.Change next;boolean ready;
        private void advance(){while(!ready&&cursor<total){int y=level.getMinBuildHeight()+(int)(cursor%height);long column=cursor++/height;int x=area.minX()+(int)(column%width),z=area.minZ()+(int)(column/width);next=plannedChange(level,data,plan,area,x,y,z);ready=next!=null;}}
        public boolean hasNext(){advance();return ready;}public BlockWorldPrepData.Change next(){advance();if(!ready)throw new NoSuchElementException();ready=false;return next;}
    };}
    private static BlockWorldPrepData.Change plannedChange(ServerLevel level,WorldPrepSavedData data,BlockWorldPrepData.Plan plan,WorldPrepSavedData.Area area,int x,int y,int z){
        ServerExistingChunkAccess.requireBlock(level,area,x,y,z);var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);boolean protectedAt=data.protectedBlock(area.dimension(),x,z)||level.getBlockEntity(pos)!=null;
        if(plan.pass.equals("GEOLOGY")){if(!state.is(GEOLOGY)){if(protectedAt)plan.skipped++;return null;}plan.eligible++;if(protectedAt){plan.skipped++;return null;}var province=GeologyPlanner.province(level.getSeed(),x,y,z);String target=GeologyPlanner.replacement(level.getSeed(),x,y,z,province);return id(state).equals(target)?null:new BlockWorldPrepData.Change(x,y,z,encode(state),target,province.name());}
        boolean stone=state.is(STONE),deep=state.is(DEEPSLATE);if(!stone&&!deep){if(protectedAt)plan.skipped++;return null;}plan.eligible++;if(protectedAt){plan.skipped++;return null;}for(var ore:ORES)if(y>=ore.min&&y<=ore.max&&Math.floorMod(GeologyPlanner.mix(level.getSeed()^ore.salt^x*31L^y*131L^z*8191L),ore.rarity)==0)return new BlockWorldPrepData.Change(x,y,z,encode(state),"minecraft:"+(deep?ore.deep:ore.stone),ore.name);return null;
    }
    private static PlanManifest manifest(ServerLevel level,BlockWorldPrepData.Plan plan){try{var m=PagedPlanStore.readManifest(RuntimePlanStore.directory(level,plan.planId));if(!m.planId().equals(plan.planId)||m.passId()!=RuntimePlanStore.pass(plan.pass)||!m.rootFingerprint().equals(plan.fingerprint))throw new IOException("Exact plan metadata mismatch");m.requireApplicable();return m;}catch(IOException e){throw new IllegalStateException("Sealed plan manifest unavailable",e);}}
    private static String pass(WorldPrepSavedData.Operation operation){return operation.name().contains("GEOLOGY")?"GEOLOGY":"ORES";}private static boolean isApply(WorldPrepSavedData.Operation operation){return operation.name().startsWith("APPLY");}private static boolean isRollback(WorldPrepSavedData.Operation operation){return operation.name().startsWith("ROLLBACK");}
    private static TagKey<Block> tag(String path){return BlockTags.create(ResourceLocation.fromNamespaceAndPath("proutlost",path));}private static String id(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    private static String encode(BlockState state){var result=new StringBuilder(id(state));if(!state.getValues().isEmpty()){result.append('[');state.getValues().entrySet().stream().sorted(Comparator.comparing(e->e.getKey().getName())).forEach(e->result.append(e.getKey().getName()).append('=').append(valueName(e.getKey(),e.getValue())).append(','));result.setCharAt(result.length()-1,']');}return result.toString();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String valueName(Property property,Comparable value){return property.getName(value);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static BlockState decode(String text){int bracket=text.indexOf('[');String name=bracket<0?text:text.substring(0,bracket);BlockState state=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name)).defaultBlockState();if(bracket>=0){for(String pair:text.substring(bracket+1,text.length()-1).split(",")){String[] parts=pair.split("=",2);Property property=state.getBlock().getStateDefinition().getProperty(parts[0]);if(property==null||property.getValue(parts[1]).isEmpty())throw new IllegalStateException("Invalid persisted BlockState "+text);state=state.setValue(property,(Comparable)property.getValue(parts[1]).get());}}return state;}
    private BlockMutationRuntime(){}
}
