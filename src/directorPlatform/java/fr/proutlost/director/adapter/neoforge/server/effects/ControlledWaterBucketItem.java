package fr.proutlost.director.adapter.neoforge.server.effects;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;

/** Eight-unit player container for the custom controlled source.
 * Inherited BucketItem.use performs permission/reach checks, inventory exchange,
 * criteria, statistics and empty-bucket handling. Placement is deliberately AIR
 * only: no hidden waterlogging, block destruction or replacement of partial cells. */
public final class ControlledWaterBucketItem extends BucketItem {
 public ControlledWaterBucketItem(Fluid content,Item.Properties properties){
  super(content,properties);
  if(!(content instanceof FlowingFluid))throw new IllegalArgumentException("controlled flowing-fluid family required");
 }
 @Override
 public boolean emptyContents(@Nullable Player player,Level level,BlockPos pos,
     @Nullable BlockHitResult hit,@Nullable ItemStack container){
  var old=level.getBlockState(pos);
  if(!old.isAir()||!old.getFluidState().isEmpty()||old.hasBlockEntity()){
   return hit!=null&&emptyContents(player,level,hit.getBlockPos().relative(hit.getDirection()),null,container);
  }
  if(level.dimensionType().ultraWarm())return false;
  var target=this.content.defaultFluidState().createLegacyBlock();
  if(!level.getWorldBorder().isWithinBounds(pos)||target.isAir()||!target.getFluidState().isSource())return false;
  if(level.isClientSide())return true; // Prediction only: never server ledger or world authority.
  if(!(level instanceof ServerLevel actual)||player==null||
     actual.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return false;
  if(!ControlledWaterOwnershipHooks.beforePlayerChange(player,actual,pos))return false;
  // The authoritative player action occurs only after the old Director owner was yielded.
  if(!actual.setBlock(pos,target,11))return false;
  if(!actual.getBlockState(pos).equals(target))return false;
  playEmptySound(player,actual,pos);
  return true;
 }
}
