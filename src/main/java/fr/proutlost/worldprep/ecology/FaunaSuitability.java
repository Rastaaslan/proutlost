package fr.proutlost.worldprep.ecology;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Versioned, conservative development rules. Suitability metadata never spawns entities. */
public final class FaunaSuitability {
    public static final String VERSION="vanilla-fauna-v1";
    public static final List<String> SPECIES=List.of("minecraft:cod","minecraft:cow","minecraft:rabbit","minecraft:salmon","minecraft:sheep");
    private static final Set<String> LAND_BIOMES=Set.of("minecraft:plains","minecraft:sunflower_plains","minecraft:meadow","minecraft:savanna","minecraft:savanna_plateau","minecraft:beach","minecraft:desert");
    private static final Set<String> COD_BIOMES=Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:cold_ocean","minecraft:deep_cold_ocean","minecraft:lukewarm_ocean","minecraft:deep_lukewarm_ocean");
    private static final Set<String> SALMON_BIOMES=Set.of("minecraft:river","minecraft:frozen_river","minecraft:cold_ocean","minecraft:deep_cold_ocean","minecraft:frozen_ocean","minecraft:deep_frozen_ocean");
    public record Evidence(int x,int y,int z,String habitat,String biome,String floorBlock,int waterDepth,int clearance,boolean protectedColumn) {
        public Evidence { if(habitat==null||biome==null||floorBlock==null||waterDepth<0||clearance<0||clearance>2)throw new IllegalArgumentException(); }
    }
    public record Result(String rulesVersion,Evidence evidence,Map<String,String> species,String populationPolicy) {
        public Result { species=java.util.Collections.unmodifiableMap(new TreeMap<>(species)); }
    }
    public static Result evaluate(Evidence evidence,Set<String> registered) {
        var present=Set.copyOf(registered);var results=new TreeMap<String,String>();
        for(String species:SPECIES)results.put(species,rule(species,evidence,present));
        return new Result(VERSION,evidence,results,"NO_RUNTIME_OR_INITIAL_SPAWNING");
    }
    /** Registry-driven initial-population policy; no external implementation imports or species table. */
    public record InitialPolicy(String species,Set<String> habitats,Set<String> biomes,Set<String> floors,
                                int minWater,int maxWater,int clearance) {
        public InitialPolicy {
            habitats=Set.copyOf(habitats);biomes=Set.copyOf(biomes);floors=Set.copyOf(floors);
            if(species==null||habitats.isEmpty()||habitats.size()>32||biomes.isEmpty()||biomes.size()>128
                ||floors.isEmpty()||floors.size()>128||minWater<0||maxWater<minWater||maxWater>32||clearance<0||clearance>2)
                throw new IllegalArgumentException("Invalid initial habitat policy");
        }
    }
    public static String evaluateInitial(Evidence evidence,Set<String> registered,InitialPolicy policy) {
        if(!registered.contains(policy.species()))return "MISSING_REGISTRY_ENTRY";
        if(evidence.protectedColumn())return "UNSUITABLE_PROTECTED";
        if(!policy.habitats().contains(evidence.habitat()))return "UNSUPPORTED_HABITAT";
        if(!policy.biomes().contains(evidence.biome()))return "UNSUPPORTED_BIOME";
        if(!policy.floors().contains(evidence.floorBlock()))return "UNSUITABLE_OR_UNKNOWN_SUPPORT";
        if(evidence.waterDepth()<policy.minWater()||evidence.waterDepth()>policy.maxWater()||evidence.clearance()<policy.clearance())return "UNSUITABLE_CLEARANCE_OR_WATER";
        return "SUITABLE_BY_RULE";
    }
    private static String rule(String species,Evidence e,Set<String> registered) {
        if(!registered.contains(species))return "MISSING_REGISTRY_ENTRY";
        if(e.protectedColumn())return "UNSUITABLE_PROTECTED";
        if(species.equals("minecraft:cod")||species.equals("minecraft:salmon")){
            if(e.waterDepth()<2||e.waterDepth()>32)return "UNSUITABLE_WATER_DEPTH";
            var biomes=species.equals("minecraft:cod")?COD_BIOMES:SALMON_BIOMES;
            return biomes.contains(e.biome())?"SUITABLE_BY_RULE":"UNSUPPORTED_BIOME";
        }
        if(e.waterDepth()!=0||e.clearance()<2)return "UNSUITABLE_CLEARANCE_OR_WATER";
        if(!Set.of("OPEN_GRASSLAND","COAST").contains(e.habitat()))return "UNSUPPORTED_HABITAT";
        if(!LAND_BIOMES.contains(e.biome()))return "UNSUPPORTED_BIOME";
        boolean ground=e.floorBlock().equals("minecraft:grass_block")
                ||(species.equals("minecraft:rabbit")&&e.floorBlock().equals("minecraft:sand"));
        return ground?"SUITABLE_BY_RULE":"UNSUITABLE_OR_UNKNOWN_SUPPORT";
    }
    private FaunaSuitability() {}
}
