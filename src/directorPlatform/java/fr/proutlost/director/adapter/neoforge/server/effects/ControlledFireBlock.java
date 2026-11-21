package fr.proutlost.director.adapter.neoforge.server.effects;

import com.mojang.serialization.MapCodec;
import fr.proutlost.director.api.model.BlockPosition;
import fr.proutlost.director.api.model.DimensionId;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Real FireBlock geometry/animation. All server mutation and damage belongs to J04 waves. */
public final class ControlledFireBlock extends FireBlock {
 public static final MapCodec<FireBlock> CODEC=simpleCodec(ControlledFireBlock::new);
 public interface Sink {
  void observe(ServerLevel level,BlockPos position);
  void contact(ServerLevel level,BlockPos position,ServerPlayer player);
 }
 private static final Map<MinecraftServer,Map<BlockPosition,Sink>> SINKS=new IdentityHashMap<>();
 public ControlledFireBlock(BlockBehaviour.Properties properties){super(properties);}
 @Override public MapCodec<FireBlock> codec(){return CODEC;}
 private static BlockPosition key(ServerLevel level,BlockPos p){
  return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());
 }
 public static void bind(MinecraftServer server,Collection<BlockPosition> cells,Sink sink){
  if(!server.isSameThread())throw new IllegalStateException("controlled fire actor");
  var map=SINKS.computeIfAbsent(server,s->new HashMap<>());
  for(var p:cells)if(map.containsKey(p)&&map.get(p)!=sink)throw new IllegalStateException("fire cell already bound");
  for(var p:cells)map.put(p,sink);
 }
 public static void unbind(MinecraftServer server,Sink sink){
  if(!server.isSameThread())throw new IllegalStateException("controlled fire actor");
  var map=SINKS.get(server);if(map==null)return;map.values().removeIf(s->s==sink);
  if(map.isEmpty())SINKS.remove(server);
 }
 private static Sink sink(ServerLevel level,BlockPos p){
  var map=SINKS.get(level.getServer());return map==null?null:map.get(key(level,p));
 }
 @Override public BlockState getStateForPlacement(BlockPlaceContext context){return defaultBlockState();}
 @Override protected BlockState updateShape(BlockState state,Direction side,BlockState other,
   LevelAccessor level,BlockPos p,BlockPos otherPos){
  // No autonomous disappearance/state change: the actor observes support and journals cleanup.
  if(level instanceof ServerLevel server){var sink=sink(server,p);if(sink!=null)sink.observe(server,p);}
  return state;
 }
 @Override protected void onPlace(BlockState state,Level level,BlockPos p,BlockState old,boolean moving){
  // Do not call FireBlock/BaseFireBlock.onPlace: those may create a portal or vanilla fire ticks.
  if(level instanceof ServerLevel server){var sink=sink(server,p);if(sink!=null)sink.observe(server,p);server.scheduleTick(p,this,1);}
 }
 @Override protected void tick(BlockState state,ServerLevel level,BlockPos p,RandomSource random){
  var sink=sink(level,p);if(sink!=null)sink.observe(level,p);
  // Only a bounded observation wake-up. No vanilla spread, aging, damage or direct setBlock.
  level.scheduleTick(p,this,20);
 }
 @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos p,RandomSource random){
  var sink=sink(level,p);if(sink!=null)sink.observe(level,p);
 }
 @Override protected void entityInside(BlockState state,Level level,BlockPos p,Entity entity){
  // Never invoke BaseFireBlock.entityInside: no igniteForSeconds/remainingFireTicks/on_fire tail.
  if(level instanceof ServerLevel server&&entity instanceof ServerPlayer player){
   var sink=sink(server,p);if(sink!=null)sink.contact(server,p,player);
  }
 }
 @Override protected void onRemove(BlockState state,Level level,BlockPos p,BlockState replacement,boolean moving){
  if(level instanceof ServerLevel server){var sink=sink(server,p);if(sink!=null)sink.observe(server,p);}
 }
}
