package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.ControlledWaterBinding;
import fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledWater;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ControlledWaterPlanner;
import fr.proutlost.director.runtime.ControlledWaterPulsePlanner;
import java.util.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registry/codec and PURE planner checks only. No world mutation, J04 permit,
 * player/client receipt, physical transfer or FLOOD qualification is fabricated. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class NativeControlledWaterPlanningGameTests {
 private NativeControlledWaterPlanningGameTests(){}
 private static Map<String,JsonValue> object(JsonValue v){return ((JsonValue.ObjectValue)v).values();}
 private static String encodedLevel(BlockState state){
  var b=BlockTransformBinding.stateBlob(state);var root=object(CanonicalJsonV1.decode(b.bytes()));
  return ((JsonValue.Text)object(root.get("properties")).get("level")).value();
 }
 private static ControlledWaterPlanner.Cell cell(int amount,boolean yielded){
  return new ControlledWaterPlanner.Cell(amount,new Epoch(1),yielded);
 }
 private static long total(Map<BlockPosition,ControlledWaterPlanner.Cell> cells){
  return cells.values().stream().mapToLong(ControlledWaterPlanner.Cell::volume).sum();
 }
 private static List<BlockPosition> line(GameTestHelper h){
  var d=new DimensionId(h.getLevel().dimension().location().toString());
  return java.util.stream.IntStream.range(0,4).mapToObj(x->new BlockPosition(d,x,64,0)).toList();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void horizontalNativeWaterAmountsAndRejectedInputs(GameTestHelper h){
  var hashes=new HashSet<Fingerprint>();
  for(int level=0;level<8;level++){
   var state=Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,level);var fluid=state.getFluidState();
   h.assertTrue(ControlledWaterBinding.nativeVolume(state)==8-level,"native LEVEL/volume mismatch "+level);
   h.assertTrue(fluid.getAmount()==8-level&&fluid.isSource()==(level==0),"native fluid amount/source "+level);
   h.assertTrue(fluid.hasProperty(FlowingFluid.FALLING)&&!fluid.getValue(FlowingFluid.FALLING),"native horizontal state falling");
   h.assertTrue(encodedLevel(state).equals(Integer.toString(level)),"stateBlob lost native level");
   hashes.add(BlockTransformBinding.stateBlob(state).reference().hash());
  }
  for(int level=8;level<=15;level++)h.assertTrue(ControlledWaterBinding.nativeVolume(
   Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,level))==-1,"falling native water became import credit");
  var waterlogged=Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true);
  h.assertTrue(!waterlogged.getFluidState().isEmpty()&&ControlledWaterBinding.nativeVolume(waterlogged)==-1,"waterlogged slab became import credit");
  h.assertTrue(ControlledWaterBinding.nativeVolume(Blocks.AIR.defaultBlockState())==-1&&hashes.size()==8,"native water encoding/rejection");
  h.succeed();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void registeredControlledLevelsRoundTripWithoutWorldWrites(GameTestHelper h){
  h.assertTrue(DirectorControlledWater.stateForVolume(0).isAir(),"zero volume must be AIR");
  var hashes=new HashSet<Fingerprint>();
  for(int v=1;v<=8;v++){
   var state=DirectorControlledWater.stateForVolume(v);var fluid=state.getFluidState();
   h.assertTrue(DirectorControlledWater.volume(state)==v&&fluid.getAmount()==v,"controlled volume roundtrip "+v);
   h.assertTrue(fluid.isSource()==(v==8)&&!fluid.getValue(FlowingFluid.FALLING),"controlled source/falling flag");
   h.assertTrue(DirectorControlledWater.isControlled(fluid.getType())&&fluid.is(FluidTags.WATER),"controlled water registry/tag");
   h.assertTrue(encodedLevel(state).equals(Integer.toString(8-v)),"controlled blob lost level");
   h.assertTrue(ControlledWaterBinding.nativeVolume(state)==-1,"custom water must not create vanilla import credit");
   hashes.add(BlockTransformBinding.stateBlob(state).reference().hash());
  }
  for(int level=8;level<=15;level++)h.assertTrue(DirectorControlledWater.volume(
   DirectorControlledWater.block().defaultBlockState().setValue(LiquidBlock.LEVEL,level))==-1,"unsupported falling custom level accepted");
  for(int invalid:new int[]{-1,9}){boolean rejected=false;try{DirectorControlledWater.stateForVolume(invalid);}
   catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"invalid native volume accepted");}
  h.assertTrue(hashes.size()==8&&DirectorControlledWater.volume(Blocks.STONE.defaultBlockState())==-1,"controlled encoding uniqueness/foreign block");
  h.succeed();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void smoothNativeLevelFixtureIsHonestlyIdlePurePlan(GameTestHelper h){
  var p=line(h);var input=new LinkedHashMap<BlockPosition,ControlledWaterPlanner.Cell>();
  for(int i=0;i<4;i++)input.put(p.get(i),cell(ControlledWaterBinding.nativeVolume(
   Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,i)),false));
  var frozen=Map.copyOf(input);var plan=ControlledWaterPlanner.plan(input,Set.copyOf(p),8,32,17);
  h.assertTrue(total(input)==26&&plan.remainingVolume()==26,"pure smooth fixture volume");
  h.assertTrue(plan.transfers().isEmpty()&&plan.after().equals(frozen)&&input.equals(frozen),
   "8/7/6/5 adjacent gradient1 must not claim a physical transfer");
  h.succeed();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void syntheticGradientPurePlanConservesAndRespectsUnknownAndYield(GameTestHelper h){
  // Explicit synthetic PURE input, not measured native terrain and never placed in the world.
  var p=line(h);var input=new LinkedHashMap<BlockPosition,ControlledWaterPlanner.Cell>();
  int[] amounts={8,5,7,6};for(int i=0;i<4;i++)input.put(p.get(i),cell(amounts[i],false));
  var unknown=new BlockPosition(p.getFirst().dimension(),0,64,1);
  var yielded=new BlockPosition(p.getFirst().dimension(),0,64,-1);input.put(yielded,cell(0,true));
  var mask=new HashSet<>(input.keySet());mask.add(unknown);var frozen=Map.copyOf(input);
  var plan=ControlledWaterPlanner.plan(input,mask,8,4,17);
  h.assertTrue(!plan.transfers().isEmpty()&&plan.remainingVolume()==26&&total(plan.after())==26,"pure transfer must conserve26");
  h.assertTrue(plan.after().get(yielded).equals(frozen.get(yielded))&&!plan.after().containsKey(unknown),"yielded/unknown impermeability");
  h.assertTrue(input.equals(frozen)&&plan.equals(ControlledWaterPlanner.plan(input,mask,8,4,17)),"pure input/determinism");
  long moved=0;for(var t:plan.transfers()){
   h.assertTrue(mask.contains(t.from())&&mask.contains(t.to())&&!t.beforeFrom().yielded()&&!t.beforeTo().yielded(),"pure transfer boundary/yield");
   h.assertTrue(t.units()>0&&t.beforeFrom().volume()-t.afterFrom().volume()==t.units()
    &&t.afterTo().volume()-t.beforeTo().volume()==t.units(),"per-transfer conservation");moved+=t.units();
  }
  h.assertTrue(moved<=4&&plan.after().values().stream().allMatch(c->c.volume()>=0&&c.volume()<=8&&c.ownerEpoch().equals(new Epoch(1))),"pure budget/level/epoch");
  h.succeed();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void pureSinglePulseMovesExactlyOneUnitThenOrdinaryPlannerConserves(GameTestHelper h){
  // Explicit PURE 8/7/6/5 input: no native mutation, J04 delivery or client evidence.
  var p=line(h);var input=new LinkedHashMap<BlockPosition,ControlledWaterPlanner.Cell>();
  int[] amounts={8,7,6,5};for(int i=0;i<4;i++)input.put(p.get(i),new ControlledWaterPlanner.Cell(amounts[i],new Epoch(i+1),false));
  var frozen=Map.copyOf(input);var mask=Set.copyOf(p);
  var pulse=ControlledWaterPulsePlanner.plan(input,mask,8,1);
  h.assertTrue(pulse.transfers().size()==1&&pulse.remainingVolume()==26&&total(pulse.after())==26,"one pure pulse must conserve26");
  var t=pulse.transfers().getFirst();
  h.assertTrue(t.from().equals(p.get(0))&&t.to().equals(p.get(1))&&t.units()==1,"pure pulse must choose actual8->7 neighboring pair");
  int[] expected={7,8,6,5};for(int i=0;i<4;i++)h.assertTrue(
   pulse.after().get(p.get(i)).volume()==expected[i]&&pulse.after().get(p.get(i)).ownerEpoch().equals(frozen.get(p.get(i)).ownerEpoch()),
   "pure pulse exact levels and preserved epochs");
  h.assertTrue(input.equals(frozen)&&pulse.equals(ControlledWaterPulsePlanner.plan(input,mask,8,1)),"pure pulse input/determinism");
  var balanced=ControlledWaterPlanner.plan(pulse.after(),mask,8,32,17);
  h.assertTrue(!balanced.transfers().isEmpty()&&balanced.remainingVolume()==26&&total(balanced.after())==26,
   "ordinary pure planner can rebalance pulse without adding volume");
  h.succeed();
 }
 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=100)
 public static void purePulseRejectsBudgetsAndCannotUseUnknownOrYieldedPair(GameTestHelper h){
  var p=line(h);var unknown=new BlockPosition(p.getFirst().dimension(),0,64,1);
  var mask=new HashSet<>(p);mask.add(unknown);
  for(boolean sourceYielded:new boolean[]{false,true}){
   var input=Map.of(p.get(0),cell(8,sourceYielded),p.get(1),cell(7,!sourceYielded),p.get(2),cell(6,false),p.get(3),cell(5,false));
   var idle=ControlledWaterPulsePlanner.plan(input,mask,8,1);
   h.assertTrue(idle.transfers().isEmpty()&&idle.after().equals(input)&&idle.remainingVolume()==26&&!idle.after().containsKey(unknown),
    "pure pulse must not fabricate unknown destination or cross yielded source/destination");
  }
  var input=Map.of(p.get(0),cell(8,false),p.get(1),cell(7,false));
  for(int[] invalid:new int[][]{{8,0},{8,2},{7,1}}){
   boolean rejected=false;try{ControlledWaterPulsePlanner.plan(input,mask,invalid[0],invalid[1]);}
   catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"pulse accepted non-single-unit or wrong max level");
  }
  h.assertTrue(input.get(p.get(0)).volume()==8&&input.get(p.get(1)).volume()==7,"rejected pure pulse mutated input");
  h.succeed();
 }
}
