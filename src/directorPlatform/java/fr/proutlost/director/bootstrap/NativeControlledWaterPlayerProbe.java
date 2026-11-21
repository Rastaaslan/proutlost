package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledWater;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** LAB-only observation of an already connected real player. No movement,
 * world, inventory, permissions, fluid, swimming flag or J06 receipt is set.
 * Caller polls exactly on the server actor, and serializes snapshot() in evidence.
 * This measures native server state; real GUI images/input remain separate proof. */
public final class NativeControlledWaterPlayerProbe {
 public record Sample(long tick,long elapsedNanos,double x,double y,double z,
   double controlledDepth,boolean controlledEye,boolean swimming,String pose,
   boolean sprinting,boolean flying,boolean creative,boolean spectator,
   int air,float health,String feetState){}
 public record Snapshot(UUID player,String dimension,long startTick,int windowTicks,
   String finishedReason,List<Sample> samples,int submergedSamples,
   int submergedSwimmingSamples,double submergedSwimmingHorizontalDistance,
   int minAir,int maxAir,boolean flyingSeen,boolean creativeOrSpectatorSeen){}
 private final MinecraftServer server;private final UUID player;
 private final String dimension;private final long startTick,startedNanos;
 private final int windowTicks;private final List<Sample> samples=new ArrayList<>();
 private String finishedReason="RUNNING";private long lastTick=Long.MIN_VALUE;
 public NativeControlledWaterPlayerProbe(MinecraftServer server,ServerPlayer player,int ticks){
  this.server=Objects.requireNonNull(server);actor();
  if(!Boolean.getBoolean("proutlost.director.nativeLab"))throw new IllegalStateException("native LAB only");
  if(player.getServer()!=server||server.getPlayerList().getPlayer(player.getUUID())!=player)
   throw new IllegalArgumentException("connected real server player required");
  if(!player.hasPermissions(4))throw new IllegalStateException("explicit OP4 LAB probe required");
  if(ticks<20||ticks>2400)throw new IllegalArgumentException("window 20..2400 server ticks");
  this.player=player.getUUID();this.dimension=player.serverLevel().dimension().location().toString();
  this.windowTicks=ticks;this.startTick=server.overworld().getGameTime();this.startedNanos=System.nanoTime();
 }
 public boolean poll(){
  actor();if(!finishedReason.equals("RUNNING"))return true;
  var p=server.getPlayerList().getPlayer(player);long tick=server.overworld().getGameTime();
  if(p==null){finishedReason="DISCONNECTED";return true;}
  if(!p.serverLevel().dimension().location().toString().equals(dimension)){
   finishedReason="DIMENSION_CHANGED";return true;
  }
  if(!p.isAlive()){finishedReason="PLAYER_NOT_ALIVE";return true;}
  if(tick==lastTick)return false;lastTick=tick;
  var type=DirectorControlledWater.type();
  samples.add(new Sample(tick,System.nanoTime()-startedNanos,p.getX(),p.getY(),p.getZ(),
   p.getFluidTypeHeight(type),p.getEyeInFluidType()==type,p.isSwimming(),p.getPose().name(),
   p.isSprinting(),p.getAbilities().flying,p.isCreative(),p.isSpectator(),
   p.getAirSupply(),p.getHealth(),p.serverLevel().getBlockState(p.blockPosition()).toString()));
  if(tick-startTick>=windowTicks)finishedReason="COMPLETE_WINDOW";
  return !finishedReason.equals("RUNNING");
 }
 public Snapshot snapshot(){
  actor();int submerged=0,swimming=0,minAir=Integer.MAX_VALUE,maxAir=Integer.MIN_VALUE;
  double distance=0;boolean flying=false,nonSurvival=false;Sample previous=null;
  for(var s:samples){
   if(s.controlledEye())submerged++;
   if(s.controlledEye()&&s.swimming()){
    swimming++;
    if(previous!=null&&previous.controlledEye()&&previous.swimming())
     distance+=Math.hypot(s.x()-previous.x(),s.z()-previous.z());
   }
   minAir=Math.min(minAir,s.air());maxAir=Math.max(maxAir,s.air());
   flying|=s.flying();nonSurvival|=s.creative()||s.spectator();previous=s;
  }
  return new Snapshot(player,dimension,startTick,windowTicks,finishedReason,List.copyOf(samples),
   submerged,swimming,distance,samples.isEmpty()?0:minAir,samples.isEmpty()?0:maxAir,flying,nonSurvival);
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("server actor required");}
}
