package fr.proutlost.worldprep.physical;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.util.Set;

/** Explicit empty furnace/bed adapter. Preserve every accepted template field through native loading. */
public final class ExactTemplateBlockEntities {
    private ExactTemplateBlockEntities() {}
    public static String normalized(ServerLevel level, BlockPos pos, BlockState state, CompoundTag source) {
        String id=source.getString("id");
        String block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(!(id.equals("minecraft:bed")&&block.equals("minecraft:red_bed"))
            &&!(id.equals("minecraft:furnace")&&block.equals("minecraft:furnace")))throw new IllegalArgumentException("NBT/block type mismatch");
        Set<String> keys;
        if(id.equals("minecraft:bed")) keys=Set.of("id");
        else if(id.equals("minecraft:furnace")) {
            keys=Set.of("id","BurnTime","CookTime","CookTimeTotal","Items","RecipesUsed");
            for(String key: Set.of("BurnTime","CookTime","CookTimeTotal"))
                if(!source.contains(key,Tag.TAG_SHORT)||source.getShort(key)!=0)throw new IllegalArgumentException("Active/unknown furnace refused");
            if(!source.contains("Items",Tag.TAG_LIST)||!source.getList("Items",Tag.TAG_COMPOUND).isEmpty()
                ||!source.contains("RecipesUsed",Tag.TAG_COMPOUND)||!source.getCompound("RecipesUsed").isEmpty())
                throw new IllegalArgumentException("Inventory/recipes refused");
        } else throw new IllegalArgumentException("Unsupported BlockEntity "+id);
        if(!keys.equals(source.getAllKeys()))throw new IllegalArgumentException("Unknown/missing template NBT fields");
        var entity=BlockEntity.loadStatic(pos,state,source.copy(),level.registryAccess());
        if(entity==null)throw new IllegalArgumentException("Template BlockEntity cannot load");
        var result=entity.saveWithFullMetadata(level.registryAccess());
        if(!result.getString("id").equals(id))throw new IllegalArgumentException("BlockEntity type mismatch");
        return result.toString();
    }
    public static String pristine(ServerLevel level,BlockPos pos,BlockState state) {
        if(!(state.getBlock() instanceof EntityBlock block))throw new IllegalArgumentException("Missing BlockEntity factory");
        var entity=block.newBlockEntity(pos,state);
        if(entity==null)throw new IllegalArgumentException("Missing BlockEntity");
        return entity.saveWithFullMetadata(level.registryAccess()).toString();
    }
    public static CompoundTag parse(String value)throws IOException {
        try{return TagParser.parseTag(value);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IOException("Invalid retained NBT",e);}
    }
    public static boolean matches(String expected,CompoundTag actual)throws IOException {return parse(expected).equals(actual);}
}
