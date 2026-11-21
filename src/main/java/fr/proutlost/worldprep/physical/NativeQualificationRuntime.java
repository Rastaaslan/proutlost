package fr.proutlost.worldprep.physical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** No auto-start on server load. Explicit candidate config, explicit start/resume, default disabled. */
public final class NativeQualificationRuntime {
    private static final Map<ServerLevel,NativeQualificationHandle> ACTIVE=new WeakHashMap<>();
    public static boolean active(ServerLevel level){return ACTIVE.containsKey(level);}
    public static UUID start(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(active(level)||NativePhysicalRuntime.hasActive(level)||NativeStructureReconciliation.active(level)||WorldPrepFaunaModule.active(level))throw new IOException("Dimension already owned");
        Path file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-qualification.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Missing/bounded qualification configuration");
        String exact=fr.proutlost.worldprep.orchestration.QualificationBinding.read(file,4096);
        var input=fr.proutlost.worldprep.orchestration.QualificationBinding.object(exact);
        int version=input.get("version").getAsInt();
        boolean production=net.neoforged.fml.loading.FMLLoader.isProduction();
        if(version==5) {
            // No V3/V4 fallback or guessed migration for multi-species V5.
            var v5=fr.proutlost.worldprep.orchestration.QualificationBinding.decode(
                exact,NativeV5QualificationIntent.class);
            QualificationRuntimeGate.requireAllowed(v5.directorReceipt(),production);
            var area=fr.proutlost.worldprep.runtime.WorldPrepRuntime.requireArea(
                fr.proutlost.worldprep.runtime.WorldPrepRuntime.data(level),v5.area());
            NativePhysicalRuntime.guard(level,area);
            ACTIVE.put(level,new NativeV5QualificationSession(level,v5,exact));
            return v5.batch();
        }
        if(version!=3&&version!=4)
            throw new IOException("LEGACY_REQUEST_V1_V2_REFUSED: retain old journals; explicit V3/V4/V5 bindings required");
        var request=fr.proutlost.worldprep.orchestration.QualificationBinding.decode(
            exact,NativeQualificationSession.Request.class);
        QualificationRuntimeGate.requireAllowed(request.directorReceipt(),production);
        NativePhysicalRuntime.guard(level,fr.proutlost.worldprep.runtime.WorldPrepRuntime.requireArea(
            fr.proutlost.worldprep.runtime.WorldPrepRuntime.data(level),request.area()));
        ACTIVE.put(level,new NativeQualificationSession(level,request));return request.batch();
    }
    public static void pause(ServerLevel level){NativePhysicalRuntime.requireThread(level);var session=ACTIVE.get(level);if(session!=null)session.pause();}
    public static void resume(ServerLevel level){NativePhysicalRuntime.requireThread(level);var session=ACTIVE.get(level);if(session==null)throw new IllegalStateException("Start same archived batch first");session.resume();}
    public static String status(ServerLevel level){var session=ACTIVE.get(level);return session==null?"DISABLED_OR_NOT_STARTED":session.status();}
    public static void tick(ServerTickEvent.Post event){for(var level:event.getServer().getAllLevels()){var session=ACTIVE.get(level);if(session!=null)session.tick();}}
    public static void unload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level) {
            var session=ACTIVE.remove(level);if(session!=null)try{session.close();}catch(IOException e){throw new IllegalStateException("Cannot release qualification fence",e);}finally{NativeChunkWindow.closeAll(level);}
            if(session==null)NativeChunkWindow.closeAll(level);
        }
    }
    private NativeQualificationRuntime(){}
}
