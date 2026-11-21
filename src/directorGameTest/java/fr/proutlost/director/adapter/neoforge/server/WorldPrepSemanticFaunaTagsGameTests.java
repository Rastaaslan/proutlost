package fr.proutlost.director.adapter.neoforge.server;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests loaded datapack tags, never native species AI or population import ACK. */
@GameTestHolder("proutlost_director_fauna_tags")
@PrefixGameTestTemplate(false)
public final class WorldPrepSemanticFaunaTagsGameTests {
    private static final List<String> REQUIRED = List.of(
        "wild_animals", "predators", "prey", "defensive_animals",
        "camp_tolerant", "marine_animals");
    private WorldPrepSemanticFaunaTagsGameTests() {}

    @GameTest(templateNamespace="proutlost_director", template="smoke_empty", timeoutTicks=100)
    public static void requiredSemanticTagsLoadWithVanillaSafeMembers(GameTestHelper helper) {
        var entities=helper.getLevel().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
        for (var name:REQUIRED) {
            var tag=tag(name);
            var loaded=entities.getTag(tag).orElseThrow(
                ()->new IllegalStateException("REQUIRED_SEMANTIC_TAG_NOT_LOADED proutlost:"+name));
            helper.assertTrue(loaded.size()>0,"required semantic tag must not be empty: "+name);
            helper.assertTrue(!EntityType.PLAYER.is(tag),"players must not enter fauna tags: "+name);
        }
        requireMember(helper,entities,"wild_animals","minecraft:rabbit");
        requireMember(helper,entities,"predators","minecraft:wolf");
        requireMember(helper,entities,"prey","minecraft:sheep");
        requireMember(helper,entities,"defensive_animals","minecraft:bee");
        requireMember(helper,entities,"camp_tolerant","minecraft:cow");
        requireMember(helper,entities,"marine_animals","minecraft:dolphin");
        System.out.println("WORLDPREP_SEMANTIC_TAGS_LOADED_PASS tags=6 scope=REGISTRY_DATAPACK_ONLY population_ack=NOT_QUALIFIED");
        helper.succeed();
    }

    /** Fullpack gate: a missing pinned mod must fail, never skip or use vanilla fallback. */
    @GameTest(templateNamespace="proutlost_director", template="smoke_empty", timeoutTicks=100)
    public static void pinnedReanimalSpeciesResolveIntoLoadedSemanticTags(GameTestHelper helper) {
        var entities=helper.getLevel().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
        var expected=Map.of(
            "wild_animals",List.of("capybara","hedgehog","crocodile","butterfly","dragonfly",
                                  "seal","jellyfish","sea_urchin","starfish"),
            "predators",List.of("crocodile","seal"),
            "prey",List.of("butterfly","dragonfly"),
            "defensive_animals",List.of("hedgehog"),
            "marine_animals",List.of("seal","jellyfish","sea_urchin","starfish"));
        for (var entry:expected.entrySet())
            for (var species:entry.getValue())
                requireMember(helper,entities,entry.getKey(),"reanimal:"+species);
        System.out.println("WORLDPREP_REANIMAL_SEMANTIC_TAGS_PASS scope=PINNED_REGISTRY_MEMBERSHIP_ONLY native_ai=NOT_QUALIFIED population_ack=NOT_QUALIFIED");
        helper.succeed();
    }

    private static TagKey<EntityType<?>> tag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("proutlost",name));
    }

    private static void requireMember(GameTestHelper helper,Registry<EntityType<?>> entities,
                                      String name,String id) {
        var key=ResourceKey.create(Registries.ENTITY_TYPE,ResourceLocation.parse(id));
        var holder=entities.getHolder(key).orElseThrow(
            ()->new IllegalStateException("MISSING_REQUIRED_DEPENDENCY_OR_ENTITY "+id));
        helper.assertTrue(holder.is(tag(name)),"loaded tag proutlost:"+name+" must contain "+id);
    }
}

