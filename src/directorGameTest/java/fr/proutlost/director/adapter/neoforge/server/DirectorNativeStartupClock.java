package fr.proutlost.director.adapter.neoforge.server;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.bootstrap.NativeGrowthMutationJournalGameTests;
import java.util.*;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The GameTest server skips vanilla waiting; allow asynchronous WAL boot its real tick time.
 * No tests/timeouts change, and this class is absent from the shipped Director JAR. */
public final class DirectorNativeStartupClock {
 private DirectorNativeStartupClock(){}
 private static final Set<MinecraftServer> READY=Collections.newSetFromMap(new IdentityHashMap<>());
 static {
  if(Boolean.getBoolean("proutlost.director.nativeGameTests"))
   NeoForge.EVENT_BUS.addListener(DirectorNativeStartupClock::pace);
 }
 private static void pace(ServerTickEvent.Post event){
  var server=event.getServer();
  if(!(server instanceof GameTestServer))return;
  var life=DirectorStandaloneMod.directorLifecycle();
  boolean faunaActive=Boolean.getBoolean("proutlost.director.nativeFaunaGameTest")&&life.labGameplay(server)
   .map(g->{var status=g.labFaunaStatus();return status.active()&&!status.failed();}).orElse(false);
  if(READY.contains(server)&&!faunaActive&&!NativeTerrainDisasterGameTests.active(server)&&!NativeFloodProviderGameTests.active(server)&&!NativeFireProviderGameTests.active(server)&&!NativeGrowthMutationJournalGameTests.active(server)&&!NativeWriterLossCases.active(server)&&!DirectorNativePerceptionColdEventGameTests.active(server)&&!Boolean.getBoolean("proutlost.director.nativeWeatherCycleGameTest"))return;
  boolean ready=life.verifiedBootEpoch(server).isPresent()
   &&life.memoryProjectionView(server).filter(v->v.complete()&&!v.failClosed()).isPresent()
   &&life.observationSnapshot(server).isPresent()
   &&life.candidateDependencies(server).isPresent()
   &&life.decisionSchedulerStatus(server).filter(v->v.committed()>0).isPresent()
   &&life.autoSnapshot(server).evaluations()>0;
  if(ready&&READY.add(server))System.out.println("DIRECTOR_NATIVE_STARTUP_CLOCK_READY tick="+server.getTickCount());
  // Native mobs and asynchronous fsync require actual tick time during this physical lane.
  // Keep ordinary accelerated GameTests after transfer/cleanup; no assertion or timeout changes.
  if(READY.contains(server)&&!faunaActive&&!NativeTerrainDisasterGameTests.active(server)&&!NativeFloodProviderGameTests.active(server)&&!NativeFireProviderGameTests.active(server)&&!NativeGrowthMutationJournalGameTests.active(server)&&!NativeWriterLossCases.active(server)&&!DirectorNativePerceptionColdEventGameTests.active(server)&&!Boolean.getBoolean("proutlost.director.nativeWeatherCycleGameTest"))return;
  long deadline=System.nanoTime()+50_000_000L;
  server.managedBlock(()->!server.isRunning()||System.nanoTime()>=deadline);
 }
}
