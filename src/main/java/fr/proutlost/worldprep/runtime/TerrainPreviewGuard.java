package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.storage.TerrainInputFingerprint;
import fr.proutlost.worldprep.storage.TerrainInputTrace;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Actor-owned, volatile work cursors. Only the sealed terrain binding is durable authority. */
final class TerrainPreviewGuard {
    record Progress(String phase, long cells, long total, int sliceCells, int maximumSliceCells) {}
    private static final Map<ServerLevel,Map<UUID,Preview>> PREVIEWS = new WeakHashMap<>();
    private static final Map<ServerLevel,Map<UUID,Admission>> ADMISSIONS = new WeakHashMap<>();
    private static final class Preview {
        final WorldPrepSavedData owner;
        final String context;
        final TerrainInputFingerprint.Bounds bounds;
        final TerrainInputFingerprint.Cursor captured, serial;
        TerrainInputFingerprint.Cursor parallelVerification;
        BlockWorldPrepData.Plan parallelPlan;
        int sliceBudget, sliceCells, maximum;
        Preview(WorldPrepSavedData owner, String context, TerrainInputFingerprint.Bounds bounds) {
            this.owner=owner; this.context=context; this.bounds=bounds;
            captured=new TerrainInputFingerprint.Cursor(bounds);
            serial=new TerrainInputFingerprint.Cursor(bounds);
        }
        TerrainInputTrace.Binding binding() {
            return new TerrainInputTrace.Binding(PlanFingerprint.of(context).value(),bounds,captured.fingerprint());
        }
    }
    private static final class Admission {
        final WorldPrepSavedData owner;
        final TerrainInputTrace.Binding binding;
        final TerrainInputFingerprint.Cursor cursor;
        int sliceCells, maximum;
        Admission(WorldPrepSavedData owner, TerrainInputTrace.Binding binding) {
            this.owner=owner; this.binding=binding; cursor=new TerrainInputFingerprint.Cursor(binding.bounds());
        }
    }

    static TerrainInputFingerprint.Bounds bounds(ServerLevel level, WorldPrepSavedData.Area area) {
        actor(level);
        return new TerrainInputFingerprint.Bounds(area.minX(),level.getMinBuildHeight(),area.minZ(),
            area.maxX(),level.getMaxBuildHeight(),area.maxZ());
    }
    private static void actor(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Terrain input requires server actor");
    }
    private static TerrainInputFingerprint.Reader reader(ServerLevel level, WorldPrepSavedData.Area area) {
        return (x,y,z)->BlockMutationRuntime.stateString(
            ServerExistingChunkAccess.requireBlock(level,area,x,y,z).getBlockState(new BlockPos(x,y,z)));
    }
    /** A capture slice consumes this invocation's cell budget; planning starts on a later invocation. */
    static boolean preparePreview(ServerLevel level, WorldPrepSavedData data, UUID id,
            WorldPrepSavedData.Job job, WorldPrepSavedData.Area area, String context, int budget) {
        actor(level);
        var states=PREVIEWS.computeIfAbsent(level,k->new HashMap<>());
        var p=states.get(id);
        if(p==null||p.owner!=data) {
            if(job.cursor()!=0)throw new WorldPrepRuntime.PreviewResumeRequired();
            p=new Preview(data,context,bounds(level,area)); states.put(id,p);
        }
        requireContext(p,context,bounds(level,area));
        if(p.captured.complete())return true;
        p.sliceCells=p.captured.advance(budget,reader(level,area));
        p.maximum=Math.max(p.maximum,p.sliceCells);
        return false;
    }
    private static void requireContext(Preview p,String context,TerrainInputFingerprint.Bounds bounds) {
        if(!p.context.equals(context)||!p.bounds.equals(bounds))
            throw new IllegalStateException("PREVIEW STALE: terrain capture context changed");
    }
    private static Preview preview(ServerLevel level,UUID id) {
        actor(level); var states=PREVIEWS.get(level); var p=states==null?null:states.get(id);
        if(p==null||!p.captured.complete())throw new IllegalStateException("Terrain capture not ready");
        return p;
    }
    static void beginSerialSlice(ServerLevel level,UUID id,int budget) {
        if(budget<1)throw new IllegalArgumentException("Positive terrain budget required");
        var p=preview(level,id);p.sliceBudget=budget;p.sliceCells=0;
    }
    static void observeSerialCell(ServerLevel level,UUID id,WorldPrepSavedData.Area area,
            long index,int x,int y,int z) {
        var p=preview(level,id);
        if(index!=p.serial.scanned()||x!=p.bounds.x(index)||y!=p.bounds.y(index)||z!=p.bounds.z(index))
            throw new IllegalStateException("Terrain observation differs from preview traversal");
        if(p.sliceCells>=p.sliceBudget)throw new IllegalStateException("Terrain observation exceeds preview cell budget");
        p.sliceCells+=p.serial.advance(1,reader(level,area));
        p.maximum=Math.max(p.maximum,p.sliceCells);
    }
    static TerrainInputTrace.Binding finishSerial(ServerLevel level,UUID id,String context) {
        var p=preview(level,id);requireContext(p,context,p.bounds);
        var binding=p.binding();binding.requireTerrain(p.serial.fingerprint());return binding;
    }
    static BlockWorldPrepData.Plan pendingParallel(ServerLevel level,UUID id) {
        return preview(level,id).parallelPlan;
    }
    static void pendingParallel(ServerLevel level,UUID id,BlockWorldPrepData.Plan plan) {
        var p=preview(level,id);
        if(p.parallelPlan!=null&&p.parallelPlan!=plan)throw new IllegalStateException("Parallel preview already staged");
        p.parallelPlan=Objects.requireNonNull(plan);
    }
    static Optional<TerrainInputTrace.Binding> verifyParallel(ServerLevel level,UUID id,
            WorldPrepSavedData.Area area,String context,int budget) {
        var p=preview(level,id);requireContext(p,context,bounds(level,area));
        if(p.parallelPlan==null)throw new IllegalStateException("Parallel plan is not staged");
        if(p.parallelVerification==null)p.parallelVerification=new TerrainInputFingerprint.Cursor(p.bounds);
        p.sliceCells=p.parallelVerification.advance(budget,reader(level,area));
        p.maximum=Math.max(p.maximum,p.sliceCells);
        if(!p.parallelVerification.complete())return Optional.empty();
        var binding=p.binding();binding.requireTerrain(p.parallelVerification.fingerprint());return Optional.of(binding);
    }
    static boolean admit(ServerLevel level,WorldPrepSavedData data,UUID job,WorldPrepSavedData.Area area,
            TerrainInputTrace.Binding binding,int budget) {
        actor(level);
        if(!bounds(level,area).equals(binding.bounds()))
            throw new IllegalStateException("PREVIEW STALE: terrain input bounds changed");
        var states=ADMISSIONS.computeIfAbsent(level,k->new HashMap<>());
        var a=states.get(job);
        if(a==null||a.owner!=data){a=new Admission(data,binding);states.put(job,a);}
        if(!a.binding.equals(binding))throw new IllegalStateException("Terrain binding changed during admission");
        a.sliceCells=a.cursor.advance(budget,reader(level,area));
        a.maximum=Math.max(a.maximum,a.sliceCells);
        if(!a.cursor.complete())return false;
        binding.requireTerrain(a.cursor.fingerprint());return true;
    }
    static Optional<Progress> progress(ServerLevel level,UUID id) {
        actor(level);
        var admissions=ADMISSIONS.get(level);var a=admissions==null?null:admissions.get(id);
        if(a!=null)return Optional.of(new Progress("APPLY_INPUT_VALIDATION",a.cursor.scanned(),a.binding.bounds().cells(),a.sliceCells,a.maximum));
        var previews=PREVIEWS.get(level);var p=previews==null?null:previews.get(id);
        if(p==null)return Optional.empty();
        var cursor=!p.captured.complete()?p.captured:p.parallelVerification!=null?p.parallelVerification:p.serial;
        String phase=!p.captured.complete()?"PREVIEW_INPUT_CAPTURE":p.parallelVerification!=null?"PARALLEL_INPUT_VALIDATION":"SERIAL_PLAN_AND_INPUT_VALIDATION";
        return Optional.of(new Progress(phase,cursor.scanned(),p.bounds.cells(),p.sliceCells,p.maximum));
    }
    static void finishPreview(ServerLevel level,UUID id) { remove(PREVIEWS,level,id); }
    static void finishAdmission(ServerLevel level,UUID id) { remove(ADMISSIONS,level,id); }
    static void discard(ServerLevel level,UUID id) { finishPreview(level,id);finishAdmission(level,id); }
    static void discardLevel(ServerLevel level) { PREVIEWS.remove(level);ADMISSIONS.remove(level); }
    static void discardLevelAdmission(ServerLevel level) { ADMISSIONS.remove(level); }
    private static <T> void remove(Map<ServerLevel,Map<UUID,T>> map,ServerLevel level,UUID id) {
        var states=map.get(level);if(states!=null){states.remove(id);if(states.isEmpty())map.remove(level);}
    }
    static void prune(ServerLevel level,WorldPrepSavedData data) {
        var previews=PREVIEWS.get(level);
        if(previews!=null)previews.keySet().removeIf(id->{
            var job=data.jobs().get(id);
            return job==null||(job.state()!=WorldPrepSavedData.JobState.QUEUED
                &&job.state()!=WorldPrepSavedData.JobState.RUNNING&&job.state()!=WorldPrepSavedData.JobState.PAUSED);
        });
        var admissions=ADMISSIONS.get(level);
        if(admissions!=null)admissions.keySet().removeIf(id->{
            var job=data.jobs().get(id);
            return job==null||(job.state()!=WorldPrepSavedData.JobState.QUEUED&&job.state()!=WorldPrepSavedData.JobState.RUNNING);
        });
    }
    private TerrainPreviewGuard() {}
}
