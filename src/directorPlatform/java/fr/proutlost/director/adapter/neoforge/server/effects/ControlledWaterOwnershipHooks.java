package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.model.OwnershipDisposition;
import fr.proutlost.director.api.model.OwnershipRecord;
import fr.proutlost.director.api.model.Tick;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Player bucket changes relinquish existing Director cells on the same server turn.
 * This class owns no store/thread: enqueueDurable is the root's single-writer mailbox.
 * A client prediction never touches server metadata. Already yielded/released cells
 * remain ordinary player-controlled cells; no Director cleanup may reacquire them. */
public final class ControlledWaterOwnershipHooks implements AutoCloseable {
 private static final Map<MinecraftServer,ControlledWaterOwnershipHooks> ACTIVE=
   Collections.synchronizedMap(new IdentityHashMap<>());
 private static final ServerOwnershipAttachments READ_ONLY_FALLBACK=new ServerOwnershipAttachments(64);
 private final MinecraftServer server;
 private final ServerOwnershipAttachments ownership;
 private final Supplier<Tick> now;
 private final Consumer<OwnershipRecord> enqueueDurable;
 private final IEventBus bus;
 private final Consumer<ServerStoppedEvent> stopped=this::stopped;
 private boolean closed;
 private String failure;

 public ControlledWaterOwnershipHooks(MinecraftServer server,ServerOwnershipAttachments ownership,
     Supplier<Tick> now,Consumer<OwnershipRecord> enqueueDurable,IEventBus bus){
  this.server=Objects.requireNonNull(server);this.ownership=Objects.requireNonNull(ownership);
  this.now=Objects.requireNonNull(now);this.enqueueDurable=Objects.requireNonNull(enqueueDurable);
  this.bus=Objects.requireNonNull(bus);actor();
  synchronized(ACTIVE){if(ACTIVE.putIfAbsent(server,this)!=null)throw new IllegalStateException("controlled water ownership hooks already installed");}
  bus.addListener(stopped);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("controlled water ownership actor");}

 /** Called by the custom LiquidBlock BEFORE source removal and by the custom
  * BucketItem BEFORE an actual placement. False leaves the bucket/world untouched. */
 public static boolean beforePlayerChange(Player player,LevelAccessor level,BlockPos pos){
  if(level instanceof Level client&&client.isClientSide())return true;
  if(!(level instanceof ServerLevel actual)||!actual.getServer().isSameThread()||
     actual.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return false;
  var bridge=ACTIVE.get(actual.getServer());
  var ledger=bridge==null?READ_ONLY_FALLBACK:bridge.ownership;
  var old=ledger.read(actual,pos);
  if(old.isEmpty()||old.get().disposition()==OwnershipDisposition.RELEASED||
     old.get().disposition()==OwnershipDisposition.YIELDED_TO_PLAYER)return true;
  // No fake player/source pickup from dispensers, world generation or an unbound recovery phase.
  if(bridge==null||bridge.closed||!(player instanceof ServerPlayer actor)||
     actor.getServer()!=actual.getServer()||actor.serverLevel()!=actual)return false;
  bridge.actor();
  var changed=bridge.ownership.invalidate(actual,pos,true,bridge.now.get());
  if(changed.isEmpty())return true;
  var next=changed.orElseThrow();
  try{
   bridge.enqueueDurable.accept(next);
   System.out.println("DIRECTOR_CONTROLLED_WATER_PLAYER_OVERRIDE_QUEUED cell="+next.resourceKey()+
     " override_epoch="+next.playerOverrideEpoch().value());
  }catch(RuntimeException unavailable){
   // The local YIELD already protects the player's action. Do not roll it back
   // or turn this into a fake durable success; the root must fail its gameplay lane.
   bridge.failure=unavailable.toString();
   System.err.println("DIRECTOR_CONTROLLED_WATER_OVERRIDE_WAL_ENQUEUE_FAILED "+unavailable);
  }
  return true;
 }
 public Optional<String> failure(){actor();return Optional.ofNullable(failure);}
 private void stopped(ServerStoppedEvent event){if(event.getServer()==server)close();}
 @Override public void close(){
  if(closed)return;actor();ACTIVE.remove(server,this);bus.unregister(stopped);closed=true;
 }
}
