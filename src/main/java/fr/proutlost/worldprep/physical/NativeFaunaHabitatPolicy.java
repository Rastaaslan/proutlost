package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Set;

/**
 * Read-only eligibility for an explicit initial fauna population.
 *
 * The installed registry, physical biome and its effective spawn lists are the
 * authority. No substitute biome, floor, fluid, temperature or probability is
 * installed. All reads are preceded by loaded-only chunk checks. The transient
 * mob is never added, ticked or finalized here; native initialization belongs
 * to the population writer after its own durable intent.
 */
public final class NativeFaunaHabitatPolicy {
    public static final MobSpawnType SPAWN_REASON = MobSpawnType.CHUNK_GENERATION;
    private static final Set<MobCategory> FAUNA = EnumSet.of(
            MobCategory.CREATURE, MobCategory.AMBIENT, MobCategory.WATER_CREATURE,
            MobCategory.WATER_AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.AXOLOTLS);
    // These two installed ReAnimal species walk on the seabed although their
    // registry category is CREATURE. Category alone cannot infer their medium.
    private static final Set<String> BENTHIC_SPECIES =
            Set.of("reanimal:sea_urchin", "reanimal:starfish");
    private static final Set<String> MEDIA = Set.of("LAND", "WATER", "BOTTOM");
    private static final int READ_HALO = 8;

    public record Profile(String species, String medium, int minGroup, int maxGroup,
                          double minimumSeparation, int localRadius, int localTotalLimit,
                          int localSpeciesLimit, boolean persistent) {
        public Profile {
            if (species == null || ResourceLocation.tryParse(species) == null)
                throw new IllegalArgumentException("FAUNA_INVALID_SPECIES");
            if (!MEDIA.contains(medium == null ? "" : medium))
                throw new IllegalArgumentException("FAUNA_INVALID_MEDIUM");
            if (minGroup < 1 || maxGroup < minGroup || maxGroup > 64)
                throw new IllegalArgumentException("FAUNA_INVALID_GROUP_RANGE");
            if (!Double.isFinite(minimumSeparation) || minimumSeparation < 0.5 || minimumSeparation > 128)
                throw new IllegalArgumentException("FAUNA_INVALID_SEPARATION");
            if (localRadius < 1 || localRadius > 128 || localTotalLimit < 1 || localTotalLimit > 4096
                    || localSpeciesLimit < maxGroup || localSpeciesLimit > localTotalLimit)
                throw new IllegalArgumentException("FAUNA_INVALID_LOCAL_BUDGET");
        }
    }

    public record HabitatEvidence(String species, String biome, String category,
                                  String medium, String floor, int waterDepth,
                                  String spawnRulePolicy) {}

    private record NativeGroup(MobCategory listCategory, int min, int max) {}

    public static boolean isFaunaCategory(MobCategory category) {
        return FAUNA.contains(category);
    }

    public static void validateRegistry(ServerLevel level, Profile profile) {
        requireMainThread(level);
        EntityType<?> type = registeredType(profile);
        if (!isFaunaCategory(type.getCategory()))
            throw unsuitable("FAUNA_NON_ANIMAL_CATEGORY", profile.species());
        if (!SpawnPlacements.hasPlacement(type))
            throw unsuitable("FAUNA_NO_NATIVE_PLACEMENT", profile.species());
        if (!Float.isFinite(type.getWidth()) || !Float.isFinite(type.getHeight())
                || type.getWidth() <= 0 || type.getHeight() <= 0
                || type.getWidth() > 16 || type.getHeight() > 16)
            throw unsuitable("FAUNA_UNSUPPORTED_DIMENSIONS", profile.species());
        if (BENTHIC_SPECIES.contains(profile.species()) != profile.medium().equals("BOTTOM"))
            throw unsuitable("FAUNA_BENTHIC_MEDIUM_MISMATCH", profile.species());
        var placement = SpawnPlacements.getPlacementType(type);
        boolean water = !profile.medium().equals("LAND");
        if (water && placement != SpawnPlacementTypes.IN_WATER)
            throw unsuitable("FAUNA_NOT_NATIVE_WATER_PLACEMENT", profile.species());
        if (!water && (placement == SpawnPlacementTypes.IN_WATER || placement == SpawnPlacementTypes.IN_LAVA))
            throw unsuitable("FAUNA_NOT_NATIVE_LAND_PLACEMENT", profile.species());
    }

    public static HabitatEvidence validate(ServerLevel level, Profile profile,
                                           BlockPos pos, String expectedBiome, long seed) {
        validateRegistry(level, profile);
        if (pos == null || expectedBiome == null || ResourceLocation.tryParse(expectedBiome) == null)
            throw unsuitable("FAUNA_INVALID_HABITAT_REFERENCE", String.valueOf(expectedBiome));
        EntityType<?> type = registeredType(profile);
        AABB bounds = type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        requireReadEnvelope(level, bounds);
        if (pos.getY() <= level.getMinBuildHeight() || pos.getY() + 1 >= level.getMaxBuildHeight())
            throw unsuitable("FAUNA_OUTSIDE_BUILD_HEIGHT", pos.toShortString());

        var biome = level.getBiome(pos);
        String actualBiome = biome.unwrapKey().orElseThrow(
                () -> unsuitable("FAUNA_UNREGISTERED_BIOME", pos.toShortString())).location().toString();
        if (!actualBiome.equals(expectedBiome))
            throw unsuitable("FAUNA_BIOME_CHANGED", expectedBiome + " != " + actualBiome);
        NativeGroup nativeGroup = nativeGroup(biome.value().getMobSettings(), type, profile);

        boolean water = !profile.medium().equals("LAND");
        requireBodyMedium(level, type, bounds, water);
        if (!water || profile.medium().equals("BOTTOM"))
            requireSupport(level, type, bounds, pos.getY() - 1);

        if (!SpawnPlacements.isSpawnPositionOk(type, level, pos))
            throw unsuitable("FAUNA_NATIVE_PLACEMENT_REFUSED", profile.species() + " " + pos.toShortString());
        if (!SpawnPlacements.checkSpawnRules(type, level, SPAWN_REASON, pos, RandomSource.create(seed)))
            throw unsuitable("FAUNA_NATIVE_SPAWN_RULE_REFUSED", profile.species() + " " + pos.toShortString());
        if (!level.noCollision(bounds))
            throw unsuitable("FAUNA_COLLISION", pos.toShortString());

        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob))
            throw unsuitable("FAUNA_FACTORY_NOT_MOB", profile.species());
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        mob.getRandom().setSeed(seed);
        // A mod may adjust its actual bounding box in its constructor.
        requireReadEnvelope(level, mob.getBoundingBox());
        requireBodyMedium(level, type, mob.getBoundingBox(), water);
        if (!water || profile.medium().equals("BOTTOM"))
            requireSupport(level, type, mob.getBoundingBox(), pos.getY() - 1);
        if (!mob.checkSpawnRules(level, SPAWN_REASON))
            throw unsuitable("FAUNA_NATIVE_MOB_RULE_REFUSED", profile.species());
        if (!mob.checkSpawnObstruction(level) || !level.noCollision(mob))
            throw unsuitable("FAUNA_NATIVE_OBSTRUCTION_REFUSED", profile.species());

        int waterDepth = 0;
        BlockPos floorPos = pos.below();
        if (water) {
            int bottom = pos.getY(), top = pos.getY();
            while (bottom > level.getMinBuildHeight()
                    && level.getFluidState(new BlockPos(pos.getX(), bottom - 1, pos.getZ())).is(FluidTags.WATER))
                bottom--;
            while (top + 1 < level.getMaxBuildHeight()
                    && level.getFluidState(new BlockPos(pos.getX(), top + 1, pos.getZ())).is(FluidTags.WATER))
                top++;
            waterDepth = top - bottom + 1;
            floorPos = new BlockPos(pos.getX(), bottom - 1, pos.getZ());
            if (floorPos.getY() < level.getMinBuildHeight())
                throw unsuitable("FAUNA_WATER_COLUMN_WITHOUT_FLOOR", pos.toShortString());
        }
        String floor = BuiltInRegistries.BLOCK.getKey(level.getBlockState(floorPos).getBlock()).toString();
        return new HabitatEvidence(profile.species(), actualBiome, type.getCategory().name(),
                profile.medium(), floor, waterDepth,
                "NATIVE_CHUNK_GENERATION_V1;spawnList=" + nativeGroup.listCategory().name()
                        + ";nativeGroup=" + nativeGroup.min() + ".." + nativeGroup.max()
                        + ";placement+predicate+mobRules+obstruction+actualAABB;noBiomeOverride");
    }

    /** Recheck the exact finalized/restored body before any collision query or add. */
    public static void validateFinalizedBody(ServerLevel level, Profile profile, Mob mob, BlockPos pos) {
        validateRegistry(level, profile);
        EntityType<?> type = registeredType(profile);
        if (mob == null || pos == null || mob.getType() != type || !mob.isAlive()
                || mob.isRemoved() || mob.isAddedToLevel() || mob.isSpawnCancelled()
                || mob.isPassenger() || !mob.getPassengers().isEmpty())
            throw unsuitable("FAUNA_INVALID_FINALIZED_ENTITY", profile.species());
        if (Math.abs(mob.getX() - (pos.getX() + 0.5)) > 1.0E-6
                || Math.abs(mob.getY() - pos.getY()) > 1.0E-6
                || Math.abs(mob.getZ() - (pos.getZ() + 0.5)) > 1.0E-6)
            throw unsuitable("FAUNA_FINALIZED_POSITION_CHANGED", pos.toShortString());
        AABB box = mob.getBoundingBox();
        requireReadEnvelope(level, box);
        AABB adult = type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (box.minX < adult.minX - 1.0E-6 || box.minY < adult.minY - 1.0E-6
                || box.minZ < adult.minZ - 1.0E-6 || box.maxX > adult.maxX + 1.0E-6
                || box.maxY > adult.maxY + 1.0E-6 || box.maxZ > adult.maxZ + 1.0E-6)
            throw unsuitable("FAUNA_FINALIZED_BODY_EXCEEDS_NATIVE_ADULT_ENVELOPE", profile.species());
        boolean water = !profile.medium().equals("LAND");
        requireBodyMedium(level, type, box, water);
        if (!water || profile.medium().equals("BOTTOM"))
            requireSupport(level, type, box, pos.getY() - 1);
        if (!mob.checkSpawnRules(level, SPAWN_REASON) || !mob.checkSpawnObstruction(level) || !level.noCollision(mob))
            throw unsuitable("FAUNA_FINALIZED_NATIVE_RULE_OR_OBSTRUCTION", profile.species());
    }

    private static EntityType<?> registeredType(Profile profile) {
        if (profile == null) throw unsuitable("FAUNA_MISSING_PROFILE", "");
        ResourceLocation id = ResourceLocation.parse(profile.species());
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id))
            throw unsuitable("FAUNA_MISSING_REGISTRY_ENTRY", profile.species());
        return BuiltInRegistries.ENTITY_TYPE.get(id);
    }

    private static NativeGroup nativeGroup(MobSpawnSettings settings, EntityType<?> type, Profile profile) {
        boolean present = false;
        // Vanilla ocelots, for example, are CREATURE types in a MONSTER spawn
        // list. Require the actual animal type, then inspect every native list.
        for (MobCategory category : MobCategory.values()) {
            for (MobSpawnSettings.SpawnerData data : settings.getMobs(category).unwrap()) {
                if (data.type == type && data.getWeight().asInt() > 0) {
                    present = true;
                    if (fitsNativeGroup(profile, data.minCount, data.maxCount))
                        return new NativeGroup(category, data.minCount, data.maxCount);
                }
            }
        }
        throw unsuitable(present ? "FAUNA_GROUP_OUTSIDE_NATIVE_RANGE" : "FAUNA_ABSENT_FROM_PHYSICAL_BIOME",
                profile.species());
    }

    static boolean fitsNativeGroup(Profile profile, int nativeMin, int nativeMax) {
        return nativeMin >= 1 && nativeMax >= nativeMin
                && profile.minGroup() >= nativeMin && profile.maxGroup() <= nativeMax;
    }

    private static void requireBodyMedium(ServerLevel level, EntityType<?> type, AABB box, boolean water) {
        for (BlockPos p : BlockPos.betweenClosed(
                (int)Math.floor(box.minX + 1.0E-6), (int)Math.floor(box.minY + 1.0E-6),
                (int)Math.floor(box.minZ + 1.0E-6), (int)Math.floor(box.maxX - 1.0E-6),
                (int)Math.floor(box.maxY - 1.0E-6), (int)Math.floor(box.maxZ - 1.0E-6))) {
            var fluid = level.getFluidState(p);
            if (water ? !fluid.is(FluidTags.WATER) : !fluid.isEmpty())
                throw unsuitable("FAUNA_BODY_MEDIUM_MISMATCH", p.toShortString());
            if (type.isBlockDangerous(level.getBlockState(p)))
                throw unsuitable("FAUNA_DANGEROUS_BODY_BLOCK", p.toShortString());
        }
    }

    private static void requireSupport(ServerLevel level, EntityType<?> type, AABB box, int y) {
        for (BlockPos p : BlockPos.betweenClosed(
                (int)Math.floor(box.minX + 1.0E-6), y, (int)Math.floor(box.minZ + 1.0E-6),
                (int)Math.floor(box.maxX - 1.0E-6), y, (int)Math.floor(box.maxZ - 1.0E-6))) {
            BlockState floor = level.getBlockState(p);
            if (!floor.isFaceSturdy(level, p, Direction.UP) || type.isBlockDangerous(floor))
                throw unsuitable("FAUNA_UNSUPPORTED_FOOTPRINT", p.toShortString());
        }
    }

    private static void requireReadEnvelope(ServerLevel level, AABB box) {
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                || box.getXsize() <= 0 || box.getYsize() <= 0 || box.getZsize() <= 0
                || box.getXsize() > 16 || box.getYsize() > 16 || box.getZsize() > 16)
            throw unsuitable("FAUNA_INVALID_ACTUAL_DIMENSIONS", box.toString());
        if (box.minY < level.getMinBuildHeight() + 1 || box.maxY >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box))
            throw unsuitable("FAUNA_OUTSIDE_WORLD_ENVELOPE", box.toString());
        int minCX = ((int)Math.floor(box.minX) - READ_HALO) >> 4;
        int maxCX = ((int)Math.floor(box.maxX) + READ_HALO) >> 4;
        int minCZ = ((int)Math.floor(box.minZ) - READ_HALO) >> 4;
        int maxCZ = ((int)Math.floor(box.maxZ) + READ_HALO) >> 4;
        for (int cz = minCZ; cz <= maxCZ; cz++)
            for (int cx = minCX; cx <= maxCX; cx++)
                ServerExistingChunkAccess.requireChunk(level, cx, cz);
    }

    private static void requireMainThread(ServerLevel level) {
        if (level == null || !level.getServer().isSameThread())
            throw new IllegalStateException("FAUNA_POLICY_REQUIRES_SERVER_THREAD");
    }

    private static IllegalArgumentException unsuitable(String reason, String detail) {
        return new IllegalArgumentException(reason + (detail.isEmpty() ? "" : ": " + detail));
    }

    private NativeFaunaHabitatPolicy() {}
}
