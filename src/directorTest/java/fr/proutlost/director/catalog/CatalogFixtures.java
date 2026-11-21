package fr.proutlost.director.catalog;

import fr.proutlost.director.api.model.*;
import java.util.*;
import static fr.proutlost.director.catalog.ContentReferences.Kind;

/** Synthetic capability contracts only. No runtime effect or Minecraft qualification is claimed. */
final class CatalogFixtures {
    static ResourceId id(String path) { return new ResourceId("proutlost:" + path); }
    static final Map<String, Set<CapabilityId>> CAPS = Map.ofEntries(
        Map.entry("target_sound", caps("perception.sound_v1")), Map.entry("target_particles", caps("perception.particles_v1")),
        Map.entry("apparition", caps("perception.silhouette_v1")), Map.entry("local_weather", caps("perception.local_weather_v1")),
        Map.entry("camp_growth", caps("mutation.plants_v1")), Map.entry("fauna_migrate", caps("entity.migration_v1", "habitat.corridor_v1")),
        Map.entry("shared_choice", caps("interaction.shared_anchor_v1", "perception.symbol_v1")), Map.entry("goat_visit", caps("entity.goat_v1")),
        Map.entry("warning", caps("warning.readable_v1")), Map.entry("landslide", caps("mutation.terrain_v1", "danger.debris_v1")),
        Map.entry("trace", caps("anchor.state_v1")));
    static Set<CapabilityId> caps(String... values) { Set<CapabilityId> s = new HashSet<>(); for (String v : values) s.add(new CapabilityId(v)); return Set.copyOf(s); }
    static BudgetVector cost(long blocks) { return new BudgetVector(0.01, 0.01, 0.01, 0, 0, blocks, 64, 0.01, 32, 0); }
    static PrimitiveRegistry primitives() {
        List<PrimitiveDescriptor> result = new ArrayList<>();
        for (var entry : CAPS.entrySet()) {
            String name = entry.getKey(); boolean danger = name.equals("landslide");
            result.add(new PrimitiveDescriptor(new PrimitiveId("proutlost:" + name), 1, id("codec_" + name), entry.getValue(), cost(0),
                id("footprint"), Set.of(id("block_codec")), Set.of(switch(name) {
                    case "landslide", "camp_growth" -> OwnershipDomain.BLOCK;
                    case "fauna_migrate", "goat_visit" -> OwnershipDomain.ENTITY;
                    case "trace", "shared_choice" -> OwnershipDomain.ANCHOR;
                    default -> OwnershipDomain.PERCEPTION;
                }),
                danger ? Set.of(NoninvertibleEffect.DAMAGE) : name.equals("target_sound") ? Set.of(NoninvertibleEffect.SOUND_EXPOSURE) : Set.of(), new DurationTicks(6000), danger ? PausePolicy.STABILIZE : PausePolicy.CLEANUP,
                RecoveryMode.RECOVER, id("cleanup"), Optional.empty(), Optional.empty(), ProviderMode.TEST_ONLY));
        }
        return new PrimitiveRegistry(result, ProviderMode.TEST_ONLY);
    }
    static ContentReferences references() {
        Map<Kind, Set<ResourceId>> known = new EnumMap<>(Kind.class);
        put(known, Kind.TAG, "proutlost:fauna_corridor", "proutlost:fauna_settled", "proutlost:camp_abandoned", "proutlost:abandoned_camp", "proutlost:regrown_camp",
            "proutlost:landslide_scar", "proutlost:camp", "proutlost:clearing", "proutlost:signal_anchor", "proutlost:signal_revealed", "proutlost:clearing_choice",
            "proutlost:remembered_camp", "proutlost:scar_still_relevant", "proutlost:recovering_landscape", "proutlost:unfinished_recovery");
        put(known, Kind.FACT, "proutlost:animal_migration_observed", "proutlost:camp_regrowth_observed", "proutlost:controlled_landslide_observed",
            "proutlost:goat_guest_observed", "proutlost:local_mist_observed", "proutlost:parallel_steps_observed", "proutlost:required_signal_observed",
            "proutlost:safe_micro_observed", "proutlost:shared_choice_observed", "proutlost:context_lost");
        put(known, Kind.SOUND, "minecraft:block.gravel.step", "minecraft:block.campfire.crackle");
        put(known, Kind.DAMAGE_TYPE, "minecraft:falling_block");
        put(known, Kind.PARTICLE, "minecraft:smoke");
        put(known, Kind.MODEL, "proutlost:test_silhouette", "proutlost:warning_sign", "proutlost:warning_cancel");
        put(known, Kind.LOCAL_WEATHER, "proutlost:test_mist"); put(known, Kind.ENTITY, "minecraft:sheep");
        put(known, Kind.CHOICE, "proutlost:listen", "proutlost:leave_trace"); put(known, Kind.RISK, "proutlost:landslide");
        Set<ResourceId> codecs = new HashSet<>(Set.of(id("block_codec"), id("proof"), id("hit_ledger")));
        CAPS.keySet().forEach(n -> codecs.add(id("codec_" + n))); known.put(Kind.CODEC, codecs);
        put(known, Kind.RESOLVER, "proutlost:footprint", "proutlost:mask", "proutlost:audience", "proutlost:visibility", "proutlost:new_arrival");
        put(known, Kind.CLEANUP, "proutlost:cleanup");
        Map<ResourceId, MutationProfile> mutations = new HashMap<>();
        for (String name : List.of("test_camp_plants", "test_bounded_landslide")) {
            boolean danger = name.contains("landslide"); PrimitiveId primitive = new PrimitiveId("proutlost:" + (danger ? "landslide" : "camp_growth"));
            DimensionId dimension = new DimensionId("minecraft:overworld");
            MutationProfile profile = new MutationProfile(id(name), 1, Set.of(primitive), Set.of(id("block_codec")), id("footprint"), new ReadSet(Map.of(), Map.of(), Map.of()),
                id("mask"), cost(danger ? 1024 : 64), 1, List.of(new RouteSegment(new BlockPosition(dimension, 0, 64, 0), new BlockPosition(dimension, 0, 64, 20), 2)),
                PlayerEditPolicy.YIELD, Optional.empty(), primitive, danger ? Set.of(NoninvertibleEffect.DAMAGE) : Set.of(), RecoveryMode.RECOVER, Set.of("TEST_ONLY:T-MUT"));
            mutations.put(profile.recipeId(), profile);
        }
        WarningProfile warning = new WarningProfile(id("test_landslide_warning"), Set.of(id("warning_sign")), id("audience"), Set.of(id("visibility")), new DurationTicks(400),
            Set.of(id("warning_sign")), id("new_arrival"), id("warning_cancel"), id("proof"));
        DamageProfile damage = new DamageProfile(id("test_debris_damage"), Set.of(new ResourceId("minecraft:falling_block")), 2, 6, new DurationTicks(20), List.of(),
            Set.of(fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING), id("hit_ledger"));
        Set<CapabilityId> caps = new HashSet<>(); CAPS.values().forEach(caps::addAll);
        return new ContentReferences(ProviderMode.TEST_ONLY, known, mutations, Map.of(warning.id(), warning), Map.of(damage.id(), damage), caps, Set.of("ENTITY:COHORT", "NAVIGATION:CORRIDOR", "BLOCKS:FOOTPRINT", "DANGER:FOOTPRINT", "ENTITY:GOAT_SLOT", "PERCEPTION:WITNESS", "ANCHOR:STATE"));
    }
    private static void put(Map<Kind, Set<ResourceId>> map, Kind kind, String... ids) {
        Set<ResourceId> values = new HashSet<>(); for (String id : ids) values.add(new ResourceId(id)); map.put(kind, values);
    }
}
