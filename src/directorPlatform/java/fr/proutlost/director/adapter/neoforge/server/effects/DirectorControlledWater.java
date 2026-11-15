package fr.proutlost.director.adapter.neoforge.server.effects;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

/** DRAFT native fluid substrate. Its volume changes only through the Director
 * planner/J04 provider or an explicit player interaction that yields ownership.
 * SOURCE/FLOWING remain real shared fluids with native immersion and swimming.
 * Native drowning and farmland hydration are intentionally not unbounded side effects. */
public final class DirectorControlledWater {
 public static final String MOD_ID="proutlost_director";
 private DirectorControlledWater(){}
 private static final DeferredRegister<FluidType> TYPES=DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES,MOD_ID);
 private static final DeferredRegister<Fluid> FLUIDS=DeferredRegister.create(BuiltInRegistries.FLUID,MOD_ID);
 private static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(BuiltInRegistries.BLOCK,MOD_ID);
 private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(BuiltInRegistries.ITEM,MOD_ID);
 private static final DeferredHolder<FluidType,FluidType> TYPE=TYPES.register("controlled_water",()->
  new FluidType(FluidType.Properties.create().descriptionId("fluid_type.proutlost_director.controlled_water")
   .canSwim(true).canDrown(false).canPushEntity(true).supportsBoating(true)
   .motionScale(.014D).fallDistanceModifier(0F).canExtinguish(true)
   .canConvertToSource(false).canHydrate(false).density(1000).viscosity(1000).temperature(300)
   .sound(SoundActions.BUCKET_FILL,SoundEvents.BUCKET_FILL)
   .sound(SoundActions.BUCKET_EMPTY,SoundEvents.BUCKET_EMPTY)));
 private static final DeferredHolder<Fluid,Source> SOURCE=FLUIDS.register("controlled_water",Source::new);
 private static final DeferredHolder<Fluid,Flowing> FLOWING=FLUIDS.register("flowing_controlled_water",Flowing::new);
 private static final DeferredHolder<Block,ControlledBlock> BLOCK=BLOCKS.register("controlled_water",()->
  new ControlledBlock(source(),BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).noLootTable()));
 private static final DeferredHolder<Item,ControlledWaterBucketItem> BUCKET=ITEMS.register("controlled_water_bucket",()->
  new ControlledWaterBucketItem(source(),new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
 public static void register(IEventBus modBus){
  Objects.requireNonNull(modBus);TYPES.register(modBus);FLUIDS.register(modBus);BLOCKS.register(modBus);ITEMS.register(modBus);
 }
 public static FluidType type(){return TYPE.get();}
 public static FlowingFluid source(){return SOURCE.get();}
 public static FlowingFluid flowing(){return FLOWING.get();}
 public static LiquidBlock block(){return BLOCK.get();}
 public static Item bucket(){return BUCKET.get();}
 private static BaseFlowingFluid.Properties properties(){
  return new BaseFlowingFluid.Properties(DirectorControlledWater::type,DirectorControlledWater::source,
   DirectorControlledWater::flowing).block(DirectorControlledWater::block)
   .bucket(DirectorControlledWater::bucket).slopeFindDistance(0).levelDecreasePerBlock(1)
   .tickRate(5).explosionResistance(100F);
 }
 /** Integer volume is the physical fluid amount: eight is a source, one..seven are lower shared levels. */
 public static BlockState stateForVolume(int volume){
  if(volume<0||volume>8)throw new IllegalArgumentException("controlled water volume 0..8");
  if(volume==0)return Blocks.AIR.defaultBlockState();
  return volume==8?source().getSource(false).createLegacyBlock():
   flowing().getFlowing(volume,false).createLegacyBlock();
 }
 /** -1 means unrelated/unsupported, including falling states never produced by this implementation. */
 public static int volume(BlockState state){
  if(state.isAir())return 0;
  if(state.getBlock()!=block())return -1;
  int level=state.getValue(LiquidBlock.LEVEL);return level>=0&&level<=7?8-level:-1;
 }
 public static boolean isControlled(Fluid fluid){return fluid==source()||fluid==flowing();}
 public static boolean isControlled(BlockState state){return state.getBlock()==block()&&volume(state)>0;}
 private abstract static class ControlledFluid extends BaseFlowingFluid {
  ControlledFluid(){super(properties());}
  @Override public final void tick(Level level,BlockPos pos,FluidState state){}
  @Override protected final void randomTick(Level level,BlockPos pos,FluidState state,RandomSource random){}
  @Override protected final boolean isRandomlyTicking(){return false;}
  @Override protected final boolean canConvertToSource(Level level){return false;}
  @Override public final boolean canConvertToSource(FluidState state,Level level,BlockPos pos){return false;}
  @Override public final Vec3 getFlow(BlockGetter level,BlockPos pos,FluidState state){return Vec3.ZERO;}
  @Override protected final void spreadTo(LevelAccessor level,BlockPos pos,BlockState state,Direction direction,FluidState fluid){}
  @Override protected final void beforeDestroyingBlock(LevelAccessor level,BlockPos pos,BlockState state){}
  @Override protected final boolean canBeReplacedWith(FluidState state,BlockGetter level,BlockPos pos,Fluid fluid,Direction direction){return false;}
 }
 private static final class Source extends ControlledFluid {
  @Override public int getAmount(FluidState state){return 8;}
  @Override public boolean isSource(FluidState state){return true;}
 }
 private static final class Flowing extends ControlledFluid {
  Flowing(){registerDefaultState(getStateDefinition().any().setValue(LEVEL,7));}
  @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid,FluidState> builder){
   super.createFluidStateDefinition(builder);builder.add(LEVEL);
  }
  @Override public int getAmount(FluidState state){return state.getValue(LEVEL);}
  @Override public boolean isSource(FluidState state){return false;}
 }
 /** No autonomous schedule or vanilla fluid-interaction callback, including after wall breaks. */
 private static final class ControlledBlock extends LiquidBlock {
  ControlledBlock(FlowingFluid fluid,BlockBehaviour.Properties properties){super(fluid,properties);}
  @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState oldState,boolean moving){}
  @Override protected BlockState updateShape(BlockState state,Direction side,BlockState neighbor,
    LevelAccessor level,BlockPos pos,BlockPos neighborPos){return state;}
  @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block neighbor,
    BlockPos neighborPos,boolean moving){}
  @Override protected void randomTick(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos pos,RandomSource random){}
  @Override public ItemStack pickupBlock(@Nullable Player player,LevelAccessor level,BlockPos pos,BlockState state){
   if(state.getBlock()!=this||state.getValue(LEVEL)!=0)return ItemStack.EMPTY;
   if(!ControlledWaterOwnershipHooks.beforePlayerChange(player,level,pos))return ItemStack.EMPTY;
   return level.setBlock(pos,Blocks.AIR.defaultBlockState(),11)?new ItemStack(bucket()):ItemStack.EMPTY;
  }
 }
}
