package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.NativeFaunaHabitat;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual QA biome fixture, registry-native placement and unknown-profile protection. */
@GameTestHolder("proutlost_director_modded_fauna")
@PrefixGameTestTemplate(false)
public final class DirectorNativeModdedFaunaHabitatGameTests {
    private DirectorNativeModdedFaunaHabitatGameTests() {}
    @GameTest(templateNamespace="proutlost_director",template="fullpack_fauna_lab",timeoutTicks=200)
    public static void actualJungleBiomeRequiredWithoutRuntimeSubstitution(GameTestHelper helper) {
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,3,2));
        for(int x=-2;x<=6;x++)for(int z=-2;z<=2;z++){
            level.setBlockAndUpdate(at.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=0;y<=3;y++)level.setBlockAndUpdate(at.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        var dimension=new DimensionId(level.dimension().location().toString());
        var route=new ArrayList<BlockPosition>();
        for(int x=0;x<=4;x++)route.add(new BlockPosition(dimension,at.getX()+x,at.getY(),at.getZ()));
        helper.assertTrue(!NativeFaunaHabitat.qualified(level,route,route.getFirst(),false,
            new ResourceId("unknown:unqualified_animal")),"unknown species must remain protected");
        // Operator fixture setup changes the real disposable QA biome; the runtime only reads it.
        var source=level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
        String bounds=(at.getX()-2)+" "+(at.getY()-1)+" "+(at.getZ()-2)+" "+(at.getX()+6)+" "+(at.getY()+3)+" "+(at.getZ()+2);
        level.getServer().getCommands().performPrefixedCommand(source,"fillbiome "+bounds+" minecraft:plains");
        helper.assertTrue(!level.getBiome(at).is(BiomeTags.IS_JUNGLE),"negative fixture must be physically non-jungle");
        helper.assertTrue(!NativeFaunaHabitat.qualified(level,route,route.getFirst(),false,NativeFaunaHabitat.CAPYBARA),
            "ReAnimal capybara must not be admitted by merely changing a declared habitat label");
        level.getServer().getCommands().performPrefixedCommand(source,"fillbiome "+bounds+" minecraft:jungle");
        helper.assertTrue(level.getBiome(at).is(BiomeTags.IS_JUNGLE),"positive fixture must be a real jungle registry biome");
        helper.assertTrue(NativeFaunaHabitat.qualified(level,route,route.getFirst(),false,NativeFaunaHabitat.CAPYBARA),
            "actual known jungle corridor must qualify the explicit capybara body profile");
        helper.assertTrue(NativeFaunaHabitat.qualified(level,route,route.getFirst(),true,NativeFaunaHabitat.CAPYBARA),
            "native ReAnimal placement and spawn predicate must qualify the physical start");
        level.setBlockAndUpdate(at.above(),Blocks.STONE.defaultBlockState());
        helper.assertTrue(!NativeFaunaHabitat.qualified(level,route,route.getFirst(),false,NativeFaunaHabitat.CAPYBARA),
            "real headroom obstruction must reject the same jungle habitat");
        System.out.println("DIRECTOR_FULLPACK_REANIMAL_ACTUAL_HABITAT_PROFILE_PASS");
        helper.succeed();
    }
}
