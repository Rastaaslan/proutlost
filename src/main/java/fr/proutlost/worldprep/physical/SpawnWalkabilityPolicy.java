package fr.proutlost.worldprep.physical;

/** Pure finite allowlist used by native MAP spawn audit.
 * String-only so the policy is unit-testable without Minecraft registry boot.
 * Any unknown or modded block is refused, regardless of collision shape.
 */
public final class SpawnWalkabilityPolicy {
    public static boolean clearance(String id) {
        return switch(id) {
            case "minecraft:air","minecraft:cave_air","minecraft:void_air",
                 "minecraft:short_grass","minecraft:tall_grass" -> true;
            default -> false;
        };
    }
    public static boolean footing(String id) {
        return switch(id) {
            case "minecraft:stone","minecraft:dirt","minecraft:grass_block",
                 "minecraft:dirt_path","minecraft:coarse_dirt",
                 "minecraft:cobblestone","minecraft:deepslate",
                 "minecraft:smooth_stone","minecraft:snow_block" -> true;
            default -> false;
        };
    }
    private SpawnWalkabilityPolicy(){}
}
