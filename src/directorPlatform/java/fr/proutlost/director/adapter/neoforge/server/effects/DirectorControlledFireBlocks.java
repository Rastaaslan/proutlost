package fr.proutlost.director.adapter.neoforge.server.effects;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registry only; no item, event activation, provider qualification or gameplay admission. */
public final class DirectorControlledFireBlocks {
 private static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(Registries.BLOCK,"proutlost_director");
 public static final DeferredHolder<Block,ControlledFireBlock> CONTROLLED_FIRE=BLOCKS.register(
  "controlled_fire",()->new ControlledFireBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.FIRE).noLootTable()));
 private DirectorControlledFireBlocks(){}
 public static void register(IEventBus modBus){BLOCKS.register(modBus);}
}
