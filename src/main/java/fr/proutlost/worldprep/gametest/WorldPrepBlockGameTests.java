package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.runtime.RuntimePageJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepBlockGameTests {
    private static final String TEMPLATE="village/plains/houses/plains_small_house_1";

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void geologyApplyUsesRealRuntimeAndLeavesUnrelatedBlock(GameTestHelper h){NativeSaveBoundaryFixture.verify(h);var c=context(h,"geology_apply");var changed=h.absolutePos(new BlockPos(2,2,2));var unrelated=h.absolutePos(new BlockPos(3,2,2));c.level.setBlockAndUpdate(changed,Blocks.STONE.defaultBlockState());c.level.setBlockAndUpdate(unrelated,Blocks.GOLD_BLOCK.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,changed,"minecraft:stone","minecraft:granite","GRANITIC");applyWithDelayedAck(c,p);check(c.level.getBlockState(changed).is(Blocks.GRANITE),h,"geology did not mutate real BlockState");check(c.level.getBlockState(unrelated).is(Blocks.GOLD_BLOCK),h,"unrelated block changed");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void geologyProtectionExcludesProtectedButAllowsEligible(GameTestHelper h){var c=context(h,"geology_protection");var protectedPos=h.absolutePos(new BlockPos(2,2,2));var open=h.absolutePos(new BlockPos(3,2,2));c.level.setBlockAndUpdate(protectedPos,Blocks.STONE.defaultBlockState());c.level.setBlockAndUpdate(open,Blocks.STONE.defaultBlockState());c.data.protections().put(c.id,new WorldPrepSavedData.ProtectedZone(c.id,c.area.dimension(),protectedPos.getX(),protectedPos.getZ(),protectedPos.getX(),protectedPos.getZ()));var p=plan(c,"GEOLOGY");change(p,protectedPos,"minecraft:stone","minecraft:tuff","TUFF_RICH");change(p,open,"minecraft:stone","minecraft:andesite","MIXED_STONE");applyFails(c,p,WorldPrepSavedData.Operation.APPLY_GEOLOGY);check(c.level.getBlockState(protectedPos).is(Blocks.STONE),h,"protected geology changed");check(c.level.getBlockState(open).is(Blocks.STONE),h,"sealed batch partially applied before conflict");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void geologyRollbackRestoresExactOriginalState(GameTestHelper h){var c=context(h,"geology_rollback");var pos=h.absolutePos(new BlockPos(2,2,2));c.level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,pos,"minecraft:stone","minecraft:diorite","MIXED_STONE");apply(c,p,WorldPrepSavedData.Operation.APPLY_GEOLOGY);check(c.level.getBlockState(pos).is(Blocks.DIORITE),h,"geology apply absent");UUID snapshot=lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_GEOLOGY);run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,snapshot));check(c.level.getBlockState(pos).is(Blocks.STONE),h,"exact geology rollback failed");UUID duplicate=WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,snapshot);c.data.jobs().put(duplicate,c.data.jobs().get(duplicate).withCursor(10_000));run(c,duplicate);check(c.data.jobs().get(duplicate).state()==WorldPrepSavedData.JobState.COMPLETED&&c.level.getBlockState(pos).is(Blocks.STONE),h,"duplicate rollback/cursor-ahead reconciliation failed");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void oreApplyHonorsHostsAndVerticalRanges(GameTestHelper h){var c=context(h,"ore_apply");orePrerequisite(c);var deep=h.absolutePos(new BlockPos(2,2,2));var stone=h.absolutePos(new BlockPos(3,70,2));var outOfRange=h.absolutePos(new BlockPos(4,100,2));c.level.setBlockAndUpdate(deep,Blocks.DEEPSLATE.defaultBlockState());c.level.setBlockAndUpdate(stone,Blocks.STONE.defaultBlockState());c.level.setBlockAndUpdate(outOfRange,Blocks.STONE.defaultBlockState());check(c.level.getBlockState(deep).is(net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("proutlost","ore_replaceable/deepslate"))),h,"deepslate host tag missing y="+deep.getY());var p=plan(c,"ORES");change(p,deep,BlockMutationRuntime.stateString(c.level.getBlockState(deep)),"minecraft:deepslate_diamond_ore","diamond");change(p,stone,"minecraft:stone","minecraft:coal_ore","coal");apply(c,p,WorldPrepSavedData.Operation.APPLY_ORES);check(c.level.getBlockState(deep).is(Blocks.DEEPSLATE_DIAMOND_ORE),h,"deepslate ore absent");check(c.level.getBlockState(stone).is(Blocks.COAL_ORE),h,"stone ore absent");check(c.level.getBlockState(outOfRange).is(Blocks.STONE),h,"out-of-range ore was placed");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void oreProtectionBlockEntityAndInvalidHostsRemain(GameTestHelper h){var c=context(h,"ore_safety");orePrerequisite(c);var protectedPos=h.absolutePos(new BlockPos(2,2,2));var chest=h.absolutePos(new BlockPos(3,2,2));var invalid=h.absolutePos(new BlockPos(4,2,2));var open=h.absolutePos(new BlockPos(5,2,2));c.level.setBlockAndUpdate(protectedPos,Blocks.DEEPSLATE.defaultBlockState());c.level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());c.level.setBlockAndUpdate(invalid,Blocks.GOLD_BLOCK.defaultBlockState());c.level.setBlockAndUpdate(open,Blocks.DEEPSLATE.defaultBlockState());c.data.protections().put(c.id,new WorldPrepSavedData.ProtectedZone(c.id,c.area.dimension(),protectedPos.getX(),protectedPos.getZ(),protectedPos.getX(),protectedPos.getZ()));check(c.level.getBlockState(open).is(net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("proutlost","ore_replaceable/deepslate"))),h,"open deepslate host tag missing y="+open.getY());var p=plan(c,"ORES");change(p,protectedPos,BlockMutationRuntime.stateString(c.level.getBlockState(protectedPos)),"minecraft:deepslate_diamond_ore","diamond");change(p,chest,BlockMutationRuntime.stateString(c.level.getBlockState(chest)),"minecraft:deepslate_diamond_ore","diamond");change(p,invalid,"minecraft:gold_block","minecraft:deepslate_diamond_ore","diamond");change(p,open,BlockMutationRuntime.stateString(c.level.getBlockState(open)),"minecraft:deepslate_redstone_ore","redstone");applyFails(c,p,WorldPrepSavedData.Operation.APPLY_ORES);check(c.level.getBlockState(protectedPos).is(Blocks.DEEPSLATE),h,"protected host changed");check(c.level.getBlockState(chest).is(Blocks.CHEST)&&c.level.getBlockEntity(chest)!=null,h,"block entity changed");check(c.level.getBlockState(invalid).is(Blocks.GOLD_BLOCK),h,"invalid host changed");check(c.level.getBlockState(open).is(Blocks.DEEPSLATE),h,"sealed batch partially applied before conflict");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void oreRollbackRestoresExactHosts(GameTestHelper h){var c=context(h,"ore_rollback");orePrerequisite(c);var pos=h.absolutePos(new BlockPos(2,2,2));c.level.setBlockAndUpdate(pos,Blocks.DEEPSLATE.defaultBlockState());check(c.level.getBlockState(pos).is(net.minecraft.tags.BlockTags.create(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("proutlost","ore_replaceable/deepslate"))),h,"rollback deepslate host tag missing y="+pos.getY());var p=plan(c,"ORES");change(p,pos,BlockMutationRuntime.stateString(c.level.getBlockState(pos)),"minecraft:deepslate_diamond_ore","diamond");apply(c,p,WorldPrepSavedData.Operation.APPLY_ORES);check(c.level.getBlockState(pos).is(Blocks.DEEPSLATE_DIAMOND_ORE),h,"ore apply absent");run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_ORES,c.id,lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_ORES)));check(c.level.getBlockState(pos).is(Blocks.DEEPSLATE),h,"ore rollback did not restore host");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void controlledBlockFailurePreservesJournalCursorAndRollback(GameTestHelper h){var c=context(h,"block_failure");var first=h.absolutePos(new BlockPos(2,2,2));var second=h.absolutePos(new BlockPos(3,2,2));c.level.setBlockAndUpdate(first,Blocks.STONE.defaultBlockState());c.level.setBlockAndUpdate(second,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,first,"minecraft:stone","minecraft:granite","GRANITIC");change(p,second,"minecraft:stone","proutlost:missing_persisted_block","GRANITIC");BlockMutationRuntime.seal(p);BlockMutationRuntime.data(c.level).plans().put(BlockWorldPrepData.planKey(c.id,"GEOLOGY"),p);var id=WorldPrepRuntime.enqueue(c.level,WorldPrepSavedData.Operation.APPLY_GEOLOGY,c.id);run(c,id);var failed=c.data.jobs().get(id);check(failed.state()==WorldPrepSavedData.JobState.FAILED,h,"controlled failure did not enter FAILED");check(failed.cursor()==0,h,"failed batch cursor advanced past durable checkpoint");var journal=BlockMutationRuntime.data(c.level).journals().get(failed.snapshotId());check(journal!=null&&journal.entryCount()>0,h,"failure journal missing");check(c.level.getBlockState(first).is(Blocks.GRANITE),h,"failure did not occur after mutation");run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_GEOLOGY)));check(c.level.getBlockState(first).is(Blocks.STONE),h,"rollback unavailable after failure");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=80)
    public static void journalFirstCrashWindowResumesIdempotently(GameTestHelper h){var c=context(h,"journal_resume");var first=h.absolutePos(new BlockPos(2,2,2));var second=h.absolutePos(new BlockPos(3,2,2));c.level.setBlockAndUpdate(first,Blocks.STONE.defaultBlockState());c.level.setBlockAndUpdate(second,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,first,"minecraft:stone","minecraft:granite","GRANITIC");change(p,second,"minecraft:stone","minecraft:diorite","MIXED_STONE");var staged=new java.util.ArrayList<>(p.changes.values());BlockMutationRuntime.publishSealed(c.level,c.data,c.area,p);var blocks=BlockMutationRuntime.data(c.level);blocks.plans().put(BlockWorldPrepData.planKey(c.id,"GEOLOGY"),p);UUID jobId=UUID.randomUUID(),snapshotId=UUID.randomUUID();var journal=new BlockWorldPrepData.Journal(snapshotId,jobId,p.planId(),c.id,"GEOLOGY",p.fingerprint());RuntimePageJournal.publish(c.level,journal,staged);blocks.journals().put(snapshotId,journal);c.level.setBlockAndUpdate(first,Blocks.GRANITE.defaultBlockState());c.data.jobs().put(jobId,new WorldPrepSavedData.Job(jobId,WorldPrepSavedData.Operation.APPLY_GEOLOGY,c.id,WorldPrepSavedData.JobState.QUEUED,0,"",snapshotId));blocks.changed();c.data.changed();c.level.getDataStorage().save();blocks.journals().put(snapshotId,new BlockWorldPrepData.Journal(snapshotId,jobId,p.planId(),c.id,"GEOLOGY",p.fingerprint()));run(c,jobId);check(c.level.getBlockState(first).is(Blocks.GRANITE),h,"already-applied entry was not accepted");check(c.level.getBlockState(second).is(Blocks.DIORITE),h,"journaled-before entry was not resumed");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=80)
    public static void partialBatchRollbackTreatsNeverAppliedAsNoOp(GameTestHelper h){var c=context(h,"partial_rollback");var first=h.absolutePos(new BlockPos(2,2,2));var second=h.absolutePos(new BlockPos(3,2,2));c.level.setBlockAndUpdate(first,Blocks.GRANITE.defaultBlockState());c.level.setBlockAndUpdate(second,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,first,"minecraft:stone","minecraft:granite","GRANITIC");change(p,second,"minecraft:stone","minecraft:diorite","MIXED_STONE");var staged=new java.util.ArrayList<>(p.changes.values());BlockMutationRuntime.publishSealed(c.level,c.data,c.area,p);var blocks=BlockMutationRuntime.data(c.level);UUID snapshot=UUID.randomUUID();var journal=new BlockWorldPrepData.Journal(snapshot,UUID.randomUUID(),p.planId(),c.id,"GEOLOGY",p.fingerprint());RuntimePageJournal.publish(c.level,journal,staged);blocks.journals().put(snapshot,journal);blocks.changed();c.level.getDataStorage().save();run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,snapshot));check(c.level.getBlockState(first).is(Blocks.STONE),h,"applied entry was not restored");check(c.level.getBlockState(second).is(Blocks.STONE),h,"never-applied entry was changed");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=80)
    public static void rollbackRefusesExternalOwnershipConflict(GameTestHelper h){var c=context(h,"ownership_conflict");var pos=h.absolutePos(new BlockPos(2,2,2));c.level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,pos,"minecraft:stone","minecraft:granite","GRANITIC");apply(c,p,WorldPrepSavedData.Operation.APPLY_GEOLOGY);c.level.setBlockAndUpdate(pos,Blocks.GOLD_BLOCK.defaultBlockState());UUID rollback=WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_GEOLOGY));run(c,rollback);check(c.data.jobs().get(rollback).state()==WorldPrepSavedData.JobState.FAILED,h,"ownership conflict did not fail closed");check(c.level.getBlockState(pos).is(Blocks.GOLD_BLOCK),h,"external state was overwritten");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=80)
    public static void missingDurableJournalPageRefusesRollback(GameTestHelper h){var c=context(h,"missing_page");var pos=h.absolutePos(new BlockPos(2,2,2));c.level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());var p=plan(c,"GEOLOGY");change(p,pos,"minecraft:stone","minecraft:granite","GRANITIC");apply(c,p,WorldPrepSavedData.Operation.APPLY_GEOLOGY);UUID snapshot=lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_GEOLOGY);try{java.nio.file.Files.delete(RuntimePageJournal.pagePath(c.level,snapshot,0));}catch(java.io.IOException e){throw new AssertionError(e);}UUID rollback=WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,snapshot);run(c,rollback);check(c.data.jobs().get(rollback).state()==WorldPrepSavedData.JobState.FAILED,h,"missing page did not fail closed");check(c.level.getBlockState(pos).is(Blocks.GRANITE),h,"rollback mutated without durable page");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=200)
    public static void largeRealChunkPlanRunsInBoundedBatches(GameTestHelper h){var c=context(h,"large_plan");var p=plan(c,"GEOLOGY");var base=h.absolutePos(new BlockPos(1,1,1));var positions=new java.util.ArrayList<BlockPos>();for(int i=0;i<600;i++){var pos=new BlockPos(base.getX()+(i%9),c.level.getMinBuildHeight()+(i/27),base.getZ()+((i/9)%3));positions.add(pos);c.level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());change(p,pos,"minecraft:stone","minecraft:granite","GRANITIC");}apply(c,p,WorldPrepSavedData.Operation.APPLY_GEOLOGY);check(p.entryCount()==600,h,"synthetic paged plan was not representative");check(positions.stream().allMatch(pos->c.level.getBlockState(pos).is(Blocks.GRANITE)),h,"large plan did not finish");UUID snapshot=lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_GEOLOGY);var journal=BlockMutationRuntime.data(c.level).journals().get(snapshot);var metrics=RuntimePageJournal.visit(c.level,journal,(sequence,entries)->{});check(metrics.maximumResidentPages()==1&&metrics.maximumResidentEntries()<=256,h,"live journal traversal exceeded one bounded page");run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,c.id,snapshot));check(positions.stream().allMatch(pos->c.level.getBlockState(pos).is(Blocks.STONE)),h,"large streaming rollback did not finish");
        for(var operation:java.util.List.of(WorldPrepSavedData.Operation.PREVIEW_GEOLOGY,WorldPrepSavedData.Operation.PREVIEW_ORES)){
            UUID preview=WorldPrepRuntime.enqueue(c.level,operation,c.id);var queued=c.data.jobs().get(preview);
            BlockMutationRuntime.process(c.level,c.data,preview,queued,1);
            check(c.data.jobs().get(preview).state()!=WorldPrepSavedData.JobState.COMPLETED&&c.data.jobs().get(preview).cursor()==1,h,"block preview ignored the one-position budget");
            run(c,preview);check(c.data.jobs().get(preview).state()==WorldPrepSavedData.JobState.COMPLETED,h,"bounded preview did not seal");
            check(positions.stream().allMatch(pos->c.level.getBlockState(pos).is(Blocks.STONE)),h,"preview changed a real block");
        }
        var masked=c.area.withMask(WorldPrepSavedData.AreaMask.MARINE);
        if(!positions.stream().allMatch(pos->!fr.proutlost.worldprep.runtime.MinecraftTerrainSampler.matchesMask(c.level,WorldPrepSavedData.AreaMask.MARINE,pos.getX(),pos.getZ(),1,1)))h.fail("Dry block-mask fixture unexpectedly contains surface water");
        c.data.areas().put(c.id,masked);c.data.changed();
        for(var operation:java.util.List.of(WorldPrepSavedData.Operation.PREVIEW_GEOLOGY,WorldPrepSavedData.Operation.PREVIEW_ORES)){
            UUID maskedPreview=WorldPrepRuntime.enqueue(c.level,operation,c.id);run(c,maskedPreview);
            String pass=operation==WorldPrepSavedData.Operation.PREVIEW_GEOLOGY?"GEOLOGY":"ORES";var sealed=BlockMutationRuntime.data(c.level).plans().get(BlockWorldPrepData.planKey(c.id,pass));
            check(c.data.jobs().get(maskedPreview).state()==WorldPrepSavedData.JobState.COMPLETED&&sealed.entryCount()==0,h,"Masked block preview included dry-column mutations");
            check(positions.stream().allMatch(pos->c.level.getBlockState(pos).is(Blocks.STONE)),h,"Masked preview changed actual geology/ore hosts");
        }
        c.data.areas().put(c.id,c.area);c.data.changed();
        h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=300)
    public static void largeOreJournalAppliesAndRollsBackInBoundedPages(GameTestHelper h){var c=context(h,"large_ore");orePrerequisite(c);var p=plan(c,"ORES");var base=h.absolutePos(new BlockPos(1,1,1));var positions=new java.util.ArrayList<BlockPos>();for(int i=0;i<600;i++){var pos=new BlockPos(base.getX()+(i%9),c.level.getMinBuildHeight()+(i/27),base.getZ()+((i/9)%3));positions.add(pos);c.level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());change(p,pos,"minecraft:stone","minecraft:iron_ore","iron");}apply(c,p,WorldPrepSavedData.Operation.APPLY_ORES);UUID snapshot=lastSnapshot(c,WorldPrepSavedData.Operation.APPLY_ORES);var journal=BlockMutationRuntime.data(c.level).journals().get(snapshot);var metrics=RuntimePageJournal.visit(c.level,journal,(sequence,entries)->{});check(journal.entryCount()==600&&metrics.maximumResidentPages()==1&&metrics.maximumResidentEntries()<=256,h,"ORES journal was not bounded");run(c,WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.ROLLBACK_ORES,c.id,snapshot));check(positions.stream().allMatch(pos->c.level.getBlockState(pos).is(Blocks.STONE)),h,"large ORES rollback did not restore hosts");h.succeed();}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=400)
    public static void environmentalPassesUseRealPreviewApplyProtectionAndExactRollback(GameTestHelper h){
        for(var pass:java.util.List.of("SOILS","FLORA","TREES","AQUATIC")){
            try(var fixture=environmentFixture(h,pass)){
                var c=fixture.context;var baseline=fixture.states();
                var previewOp=WorldPrepSavedData.Operation.valueOf("PREVIEW_"+pass);
                UUID preview=WorldPrepRuntime.enqueue(c.level,previewOp,c.id);
                BlockMutationRuntime.process(c.level,c.data,preview,c.data.jobs().get(preview),1);
                check(c.data.jobs().get(preview).cursor()==1&&c.data.jobs().get(preview).state()!=WorldPrepSavedData.JobState.COMPLETED,h,pass+" preview ignored budget1");
                check(fixture.matches(baseline),h,pass+" first preview slice changed terrain");
                run(c,preview);check(c.data.jobs().get(preview).state()==WorldPrepSavedData.JobState.COMPLETED,h,pass+" preview failed: "+c.data.jobs().get(preview).error());
                var blocks=BlockMutationRuntime.data(c.level);var sealed=blocks.plans().get(BlockWorldPrepData.planKey(c.id,pass));
                check(sealed!=null&&sealed.entryCount()>0&&sealed.entryCount()<=256,h,pass+" fixture did not produce a positive bounded sealed plan");
                var rows=environmentRows(c,sealed);
                check(fixture.matches(baseline),h,pass+" completed preview changed terrain");
                for(var row:rows)check(BlockMutationRuntime.stateString(c.level.getBlockState(new BlockPos(row.x(),row.y(),row.z()))).equals(row.before()),h,pass+" plan prestate was not the observed real fixture");
                if(pass.equals("SOILS"))check(rows.stream().anyMatch(row->row.y()<fixture.unknown.getY()),h,"SOILS did not plan real subsurface depth");
                if(pass.equals("AQUATIC")){
                    var unsupported=fixture.unknown.offset(-1,0,-1);
                    check(c.level.getBlockState(unsupported).is(Blocks.STONE)&&c.level.getBlockState(unsupported.below()).isAir(),h,"Floating substrate fixture was not real");
                    check(rows.stream().noneMatch(row->row.x()==unsupported.getX()&&row.y()==unsupported.getY()&&row.z()==unsupported.getZ()),h,"Unsupported aquatic sand/gravel entered the sealed mutation plan");
                }
                if(pass.equals("TREES"))check(rows.size()==fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.size(),h,"tree preview omitted part of the controlled template");
                // Author a conflicting test plan from real captured rows under the current protection identity.
                var first=rows.getFirst();String protection=c.id+"_guard";
                c.data.protections().put(protection,new WorldPrepSavedData.ProtectedZone(protection,c.area.dimension(),first.x(),first.z(),first.x(),first.z()));c.data.changed();
                var guarded=plan(c,pass);for(var row:rows)guarded.changes.put(BlockWorldPrepData.posKey(row.x(),row.y(),row.z()),row);
                var applyOp=WorldPrepSavedData.Operation.valueOf("APPLY_"+pass);
                try{applyFails(c,guarded,applyOp);check(fixture.matches(baseline),h,pass+" protected batch partially changed terrain");}
                finally{c.data.protections().remove(protection);c.data.changed();blocks.plans().put(BlockWorldPrepData.planKey(c.id,pass),sealed);blocks.changed();}
                UUID apply=WorldPrepRuntime.enqueue(c.level,applyOp,c.id);
                if(pass.equals("TREES"))for(int slice=1;slice<=2;slice++){
                    BlockMutationRuntime.process(c.level,c.data,apply,c.data.jobs().get(apply),1);
                    check(c.data.jobs().get(apply).state()!=WorldPrepSavedData.JobState.FAILED&&c.data.jobs().get(apply).cursor()==slice,h,"TREE own prefix refused or budget1 did not advance exactly one row");
                }
                run(c,apply);
                check(c.data.jobs().get(apply).state()==WorldPrepSavedData.JobState.COMPLETED,h,pass+" apply failed: "+c.data.jobs().get(apply).error());
                for(var row:rows)check(BlockMutationRuntime.stateString(c.level.getBlockState(new BlockPos(row.x(),row.y(),row.z()))).equals(row.applied()),h,pass+" did not apply the exact sealed row");
                check(c.level.getBlockState(fixture.unknown).is(Blocks.GOLD_BLOCK),h,pass+" replaced an unknown host");
                check(c.level.getBlockState(fixture.chest).is(Blocks.CHEST)&&c.level.getBlockEntity(fixture.chest)!=null,h,pass+" damaged a block entity");
                String floraBeforeRollback=pass.equals("SOILS")?BlockMutationRuntime.inputIdentity(c.level,c.data,c.area,"FLORA"):"";
                UUID rollback=WorldPrepRuntime.enqueueRollback(c.level,WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+pass),c.id,c.data.jobs().get(apply).snapshotId());run(c,rollback);
                if(pass.equals("SOILS"))check(!floraBeforeRollback.equals(BlockMutationRuntime.inputIdentity(c.level,c.data,c.area,"FLORA")),h,"Real SOILS rollback did not invalidate downstream FLORA identity");
                check(c.data.jobs().get(rollback).state()==WorldPrepSavedData.JobState.COMPLETED,h,pass+" rollback failed: "+c.data.jobs().get(rollback).error());
                check(fixture.matches(baseline),h,pass+" rollback did not restore every fixture BlockState");
            }
        }
        h.succeed();
    }

    private static java.util.List<BlockWorldPrepData.Change> environmentRows(Ctx c,BlockWorldPrepData.Plan plan){
        try{
            var directory=fr.proutlost.worldprep.runtime.RuntimePlanStore.directory(c.level,plan.planId());
            var manifest=fr.proutlost.worldprep.storage.PagedPlanStore.readManifest(directory);
            var result=new java.util.ArrayList<BlockWorldPrepData.Change>();
            fr.proutlost.worldprep.storage.PagedPlanStore.read(directory,manifest,fr.proutlost.worldprep.runtime.RuntimePlanStore.BLOCK_CODEC,result::add);
            return java.util.List.copyOf(result);
        }catch(java.io.IOException failure){throw new AssertionError("Real environment sealed pages unavailable",failure);}
    }
    private static final class EnvironmentFixture implements AutoCloseable{
        final Ctx context;final BlockPos unknown,chest;
        final java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> original;
        final java.util.List<WorldPrepSavedData.SnapshotCell> biomes;
        EnvironmentFixture(Ctx context,BlockPos unknown,BlockPos chest,java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> original,java.util.List<WorldPrepSavedData.SnapshotCell> biomes){this.context=context;this.unknown=unknown;this.chest=chest;this.original=original;this.biomes=biomes;}
        java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> states(){var result=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();for(var pos:original.keySet())result.put(pos,context.level.getBlockState(pos));return result;}
        boolean matches(java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> expected){return expected.entrySet().stream().allMatch(e->context.level.getBlockState(e.getKey()).equals(e.getValue()));}
        public void close(){
            var entries=new java.util.ArrayList<>(original.entrySet());java.util.Collections.reverse(entries);
            for(var entry:entries)context.level.setBlock(entry.getKey(),entry.getValue(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            for(var cell:biomes)fr.proutlost.worldprep.runtime.BiomeRuntime.restoreOwnedCell(context.level,cell);
            if(!matches(original))throw new AssertionError("Environment test fixture was not restored");
        }
    }
    private static EnvironmentFixture environmentFixture(GameTestHelper h,String pass){
        WorldPrepConfig.ENABLED.set(true);var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var profile=fr.proutlost.worldprep.runtime.EcologyMutationPlanner.validateProfile();var base=h.absolutePos(BlockPos.ZERO);
        int floor=Math.max(level.getMinBuildHeight()+8,Math.min(level.getMaxBuildHeight()-12,level.getSeaLevel()+16));
        double altitude=Math.max(0,Math.min(1,(floor-level.getMinBuildHeight())/(double)(level.getMaxBuildHeight()-level.getMinBuildHeight())));
        double temperature=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getHolder(net.minecraft.resources.ResourceLocation.parse("minecraft:forest")).orElseThrow().value().getBaseTemperature();
        double altitudeFactor=Math.max(.1,1-altitude),climateFactor=temperature<profile.coldTemperature()?.8:1;
        double treeDensity=profile.forestDensity()*.65*altitudeFactor*climateFactor;
        var noise=new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(level.getSeed()));
        BlockPos root=null;int selectedX=0,selectedZ=0;
        search:for(int radius=0;radius<=4;radius++)for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            if(radius>0&&Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
            int cx=(base.getX()>>4)+dx,cz=(base.getZ()>>4)+dz;boolean loaded=true;
            for(int hz=-2;hz<=2;hz++)for(int hx=-2;hx<=2;hx++)if(level.getChunkSource().getChunkNow(cx+hx,cz+hz)==null)loaded=false;
            if(!loaded)continue;
            int x=fr.proutlost.worldprep.ecology.ControlledOak.rootX(level.getSeed(),cx,cz),z=fr.proutlost.worldprep.ecology.ControlledOak.rootZ(level.getSeed(),cx,cz);
            double clearing=noise.getValue(x/128.0,z/128.0)*.5+.5;
            if(!fr.proutlost.worldprep.ecology.VegetationPlanner.treeCandidate(level.getSeed(),cx,cz,fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.DENSE,treeDensity,clearing))continue;
            boolean flora=false;
            for(int pz=z-2;pz<=z+2;pz++)for(int px=x-2;px<=x+2;px++)if(px-cx*16>=4&&px-cx*16<=11&&pz-cz*16>=4&&pz-cz*16<=11&&!(px==x-2&&pz==z-2)&&!(px==x+2&&pz==z+2)&&fr.proutlost.worldprep.ecology.VegetationPlanner.flora(level.getSeed(),px,pz,profile.floraDensity(),altitudeFactor))flora=true;
            if(!flora)continue;root=new BlockPos(x,floor,z);selectedX=cx;selectedZ=cz;break search;
        }
        if(root==null)throw new AssertionError("No genuine DENSE/tree+flora candidate with already-loaded sampler halo in bounded 9x9 chunk search");
        String id="proutlost:environment_"+pass.toLowerCase(java.util.Locale.ROOT)+UUID.randomUUID().toString().replace("-","");
        var area=new WorldPrepSavedData.Area(id,level.dimension().location().toString(),root.getX()-2,root.getZ()-2,root.getX()+2,root.getZ()+2)
                .withTreeMode(fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.DENSE);
        data.areas().put(id,area);data.changed();var c=new Ctx(h,level,data,id,area);
        var original=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(int z=selectedZ*16;z<selectedZ*16+16;z++)for(int x=selectedX*16;x<selectedX*16+16;x++)for(int y=floor-4;y<=floor+9;y++){
            var pos=new BlockPos(x,y,z);fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireSample(level,x,z);
            if(level.getBlockEntity(pos)!=null)throw new AssertionError("Environment fixture refuses to overwrite an existing block entity");original.put(pos,level.getBlockState(pos));
        }
        var biomes=new java.util.ArrayList<WorldPrepSavedData.SnapshotCell>();
        for(int qz=selectedZ*4;qz<selectedZ*4+4;qz++)for(int qx=selectedX*4;qx<selectedX*4+4;qx++)for(int qy=net.minecraft.core.QuartPos.fromBlock(floor-4);qy<=net.minecraft.core.QuartPos.fromBlock(floor+9);qy++)
            biomes.add(new WorldPrepSavedData.SnapshotCell(qx,qy,qz,fr.proutlost.worldprep.runtime.BiomeRuntime.biomeId(level,qx,qy,qz),"minecraft:forest"));
        var unknown=root.offset(2,0,2);var chest=root.offset(-2,1,-2);var fixture=new EnvironmentFixture(c,unknown,chest,original,biomes);
        try{
            for(var pos:original.keySet()){
                var state=pos.getY()<floor?Blocks.STONE.defaultBlockState():pos.getY()==floor?(pass.equals("SOILS")||pass.equals("AQUATIC")?Blocks.STONE:Blocks.GRASS_BLOCK).defaultBlockState():pass.equals("AQUATIC")&&pos.getY()<=floor+3?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                level.setBlock(pos,state,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            }
            for(var cell:biomes)fr.proutlost.worldprep.runtime.BiomeRuntime.applyOwnedCell(level,cell);
            if(pass.equals("AQUATIC"))level.setBlock(root.offset(1,-1,1),Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            level.setBlock(unknown,Blocks.GOLD_BLOCK.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            level.setBlock(chest,Blocks.CHEST.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
            return fixture;
        }catch(RuntimeException|Error failure){fixture.close();throw failure;}
    }

    private record Ctx(GameTestHelper helper,net.minecraft.server.level.ServerLevel level,WorldPrepSavedData data,String id,WorldPrepSavedData.Area area){}
    private static Ctx context(GameTestHelper h,String prefix){WorldPrepConfig.ENABLED.set(true);var level=h.getLevel();var d=WorldPrepRuntime.data(level);var base=h.absolutePos(new BlockPos(1,1,1));String id="proutlost:"+prefix+UUID.randomUUID().toString().replace("-","");var a=new WorldPrepSavedData.Area(id,level.dimension().location().toString(),base.getX(),base.getZ(),base.getX()+8,base.getZ()+2);d.areas().put(id,a);d.changed();return new Ctx(h,level,d,id,a);}
    private static BlockWorldPrepData.Plan plan(Ctx c,String pass){return new BlockWorldPrepData.Plan(c.id,pass,"gametest",BlockMutationRuntime.inputIdentity(c.level,c.data,c.area,pass),"");}
    private static void change(BlockWorldPrepData.Plan p,BlockPos pos,String before,String after,String kind){p.changes.put(BlockWorldPrepData.posKey(pos.getX(),pos.getY(),pos.getZ()),new BlockWorldPrepData.Change(pos.getX(),pos.getY(),pos.getZ(),before,after,kind));}
    private static void apply(Ctx c,BlockWorldPrepData.Plan p,WorldPrepSavedData.Operation operation){BlockMutationRuntime.seal(p);BlockMutationRuntime.data(c.level).plans().put(BlockWorldPrepData.planKey(c.id,p.pass),p);BlockMutationRuntime.data(c.level).changed();run(c,WorldPrepRuntime.enqueue(c.level,operation,c.id));var job=c.data.jobs().values().stream().filter(j->j.area().equals(c.id)&&j.operation()==operation).reduce((x,y)->y).orElseThrow();if(job.state()!=WorldPrepSavedData.JobState.COMPLETED)throw new AssertionError("apply job failed: "+job.error());}
    private static void applyFails(Ctx c,BlockWorldPrepData.Plan p,WorldPrepSavedData.Operation operation){BlockMutationRuntime.seal(p);var bd=BlockMutationRuntime.data(c.level);bd.plans().put(BlockWorldPrepData.planKey(c.id,p.pass),p);bd.changed();var id=WorldPrepRuntime.enqueue(c.level,operation,c.id);run(c,id);if(c.data.jobs().get(id).state()!=WorldPrepSavedData.JobState.FAILED)throw new AssertionError("sealed conflicting plan did not fail");}
    private static UUID lastSnapshot(Ctx c,WorldPrepSavedData.Operation operation){return c.data.jobs().values().stream().filter(j->j.area().equals(c.id)&&j.operation()==operation&&j.snapshotId()!=null).reduce((x,y)->y).orElseThrow().snapshotId();}
    private static void orePrerequisite(Ctx c){var p=plan(c,"GEOLOGY");BlockMutationRuntime.publishSealed(c.level,c.data,c.area,p);var bd=BlockMutationRuntime.data(c.level);bd.plans().put(BlockWorldPrepData.planKey(c.id,"GEOLOGY"),p);var id=UUID.randomUUID();bd.journals().put(id,new BlockWorldPrepData.Journal(id,UUID.randomUUID(),p.planId(),c.id,"GEOLOGY",p.fingerprint()));bd.changed();}
    private static void run(Ctx c,UUID id){for(int i=0;i<8;i++){CheckpointFixture.tick(c.level,1_000_000);var state=c.data.jobs().get(id).state();if(state==WorldPrepSavedData.JobState.COMPLETED||state==WorldPrepSavedData.JobState.FAILED)return;}throw new AssertionError("job did not terminate");}
    private static void check(boolean value,GameTestHelper h,String message){if(!value)h.fail(message);}
    private WorldPrepBlockGameTests(){}
    private static void applyWithDelayedAck(Ctx c,BlockWorldPrepData.Plan p) {
        BlockMutationRuntime.seal(p);
        var blocks=BlockMutationRuntime.data(c.level);
        blocks.plans().put(BlockWorldPrepData.planKey(c.id,p.pass),p);blocks.changed();
        var id=WorldPrepRuntime.enqueue(c.level,WorldPrepSavedData.Operation.APPLY_GEOLOGY,c.id);
        CheckpointFixture.drain(c.level);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pos=c.helper.absolutePos(new BlockPos(2,2,2));
        net.neoforged.neoforge.common.IOUtilities.withIOWorker(()->{
            entered.countDown();try {
                if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture IO timeout");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        });
        try {
            if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture IO not started");
            BlockMutationRuntime.process(c.level,c.data,id,c.data.jobs().get(id),256);
            check(c.level.getBlockState(pos).is(Blocks.STONE),c.helper,"Mutation before metadata acknowledgement");
            check(!blocks.checkpointReady(),c.helper,"Delayed checkpoint unexpectedly acknowledged");
            BlockMutationRuntime.process(c.level,c.data,id,c.data.jobs().get(id),256);
            check(c.level.getBlockState(pos).is(Blocks.STONE),c.helper,"Pending checkpoint permitted mutation");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        finally {release.countDown();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
        run(c,id);
    }

}
