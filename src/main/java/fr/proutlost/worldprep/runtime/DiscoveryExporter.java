package fr.proutlost.worldprep.runtime;

import com.google.gson.GsonBuilder;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.ecology.HabitatDiscovery;
import fr.proutlost.worldprep.ecology.FaunaSuitability;
import fr.proutlost.worldprep.validation.SpawnAccess;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Evidence export, deliberately NOT an accepted Director READY catalog. No world mutation or mob spawning. */
public final class DiscoveryExporter {
    public record StructureEvidence(String stableId, String registryId, List<Integer> bounds,
            String origin, String support, String protection, List<String> anchors) {}
    public record SpawnEvidence(List<Integer> position, String support, List<SpawnAccess.Position> reachableColumns) {}
    public record LocationEvidence(String stableId,String type,SpawnAccess.Position anchor,String origin,String support) {}
    public record Document(int schemaVersion, String status, String directorImport, WorldPrepSavedData.Area area,
            List<HabitatDiscovery.Cell> habitatMap, List<StructureEvidence> structureRegistry,
            List<LocationEvidence> locationRegistry, Map<String,String> faunaRegistry, Map<String,String> structureCapabilities,
            Map<String,List<Long>> structureReferences, List<FaunaSuitability.Result> faunaSuitability, SpawnEvidence spawn,
            List<String> limitations) {}

    public static Path export(ServerLevel level, String areaId) throws IOException, InterruptedException {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Discovery must snapshot on the server thread");
        var data = WorldPrepRuntime.data(level);
        var area = WorldPrepRuntime.requireArea(data, areaId);
        EcologyMutationPlanner.requireBounded(area);
        var samples = new ArrayList<HabitatDiscovery.Sample>();
        var structures = new TreeMap<String, StructureEvidence>();
        var observations=new ArrayList<FaunaSuitability.Evidence>();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var capabilities = new TreeMap<String,String>();
        registry.keySet().forEach(key -> capabilities.put(key.toString(), structureSupport(level,key)));
        var references = new TreeMap<String,List<Long>>();
        for (int z = area.minZ(); z <= area.maxZ(); z += 4) for (int x = area.minX(); x <= area.maxX(); x += 4) {
            var chunk = ServerExistingChunkAccess.requireBlock(level, area, x, level.getMinBuildHeight(), z);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            var pos = new BlockPos(x, Math.max(level.getMinBuildHeight(), floor), z);
            // Height differences alone can be vegetation: require actual water above the floor.
            int waterDepth = floor + 1 < level.getMaxBuildHeight()
                    && chunk.getBlockState(pos.above()).getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                    ? Math.max(0, surface - floor) : 0;
            boolean protectedColumn=data.protectedBlock(area.dimension(),x,z)||!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty();
            samples.add(new HabitatDiscovery.Sample(x, z, floor, waterDepth,
                    level.getBiome(pos).is(BiomeTags.IS_FOREST), protectedColumn));
            int clearance=0;
            for(int dy=1;dy<=2&&floor+dy<level.getMaxBuildHeight();dy++){
                if(!chunk.getBlockState(pos.above(dy)).isAir())break;
                clearance++;
            }
            observations.add(new FaunaSuitability.Evidence(x,floor,z,"PENDING_CLASSIFICATION",
                    level.getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse("UNREGISTERED"),
                    BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString(),waterDepth,clearance,protectedColumn));
        }
        // Visit every intersecting chunk, including narrow edge tiles that the four-block sample grid misses.
        for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            var chunk = ServerExistingChunkAccess.requireChunk(level, cx, cz);
            chunk.getAllReferences().forEach((structure, starts) -> {
                var key=registry.getKey(structure);
                if(key==null)throw new IllegalStateException("Unregistered structure reference");
                references.put(chunk.getPos().x+","+chunk.getPos().z+"/"+key, java.util.Arrays.stream(starts.toLongArray()).sorted().boxed().toList());
            });
            for (var entry : chunk.getAllStarts().entrySet()) {
                var start = entry.getValue();
                if (!start.isValid()) continue;
                var key = registry.getKey(entry.getKey());
                if (key == null) throw new IllegalStateException("Unregistered structure start");
                var box = start.getBoundingBox();
                if (box.maxX() < area.minX() || box.minX() > area.maxX() || box.maxZ() < area.minZ() || box.minZ() > area.maxZ()) continue;
                String stable = area.dimension() + "/" + key + "/" + start.getChunkPos().x + "/" + start.getChunkPos().z;
                structures.put(stable, new StructureEvidence(stable, key.toString(),
                        List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                        "PERSISTED_STRUCTURE_START", "OBSERVED_START_ONLY", "ECOLOGY_SKIPS_START_AND_REFERENCE_CHUNKS", List.of()));
            }
        }
        var fauna = new TreeMap<String,String>();
        for (String species : FaunaSuitability.SPECIES)
            fauna.put(species, BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(species))
                    ? "REGISTERED_RULES_AVAILABLE_NO_SPAWNING" : "MISSING");
        var spawn = level.getSharedSpawnPos();
        var walking=new ArrayList<SpawnAccess.Column>();
        if(spawn.getX()>=area.minX()&&spawn.getX()<=area.maxX()&&spawn.getZ()>=area.minZ()&&spawn.getZ()<=area.maxZ()){
            for(int z=area.minZ();z<=area.maxZ();z++)for(int x=area.minX();x<=area.maxX();x++){
                var chunk=ServerExistingChunkAccess.requireBlock(level,area,x,level.getMinBuildHeight(),z);
                int floor=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                boolean safe=floor>=level.getMinBuildHeight()&&floor+3<level.getMaxBuildHeight();
                var ground=new BlockPos(x,Math.max(floor,level.getMinBuildHeight()),z);
                safe &= SpawnAccess.SAFE_FLOORS.contains(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(ground).getBlock()).toString());
                for(int dy=1;safe&&dy<=3;dy++)safe=chunk.getBlockState(ground.above(dy)).isAir();
                walking.add(new SpawnAccess.Column(x,z,floor,safe));
            }
        }
        var spawnPos=new SpawnAccess.Position(spawn.getX(),spawn.getY(),spawn.getZ());
        var access=SpawnAccess.validate(spawnPos,walking);
        var locations=new ArrayList<LocationEvidence>();
        if(access.status().equals("LOCAL_ACCESS_VERIFIED_GLOBAL_ROUTE_UNCHECKED"))
            locations.add(new LocationEvidence(area.dimension()+"/player_spawn/"+spawn.getX()+"/"+spawn.getY()+"/"+spawn.getZ(),
                    "PLAYER_SPAWN",spawnPos,"LEVEL_SHARED_SPAWN_AND_OBSERVED_COLUMNS",access.status()));
        var habitats=HabitatDiscovery.classify(samples, WorldPrepConfig.PARALLEL_DISCOVERY.get());
        var registered=fauna.entrySet().stream().filter(e->!e.getValue().equals("MISSING")).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var suitability=new ArrayList<FaunaSuitability.Result>();
        for(int i=0;i<habitats.size();i++){
            var e=observations.get(i);
            suitability.add(FaunaSuitability.evaluate(new FaunaSuitability.Evidence(e.x(),e.y(),e.z(),habitats.get(i).habitat(),e.biome(),e.floorBlock(),e.waterDepth(),e.clearance(),e.protectedColumn()),registered));
        }
        var document = new Document(4, "DISCOVERY_ONLY", "NOT_TESTED_NOT_READY", area,
                habitats, List.copyOf(structures.values()),
                locations, fauna, capabilities, references, suitability, new SpawnEvidence(List.of(spawn.getX(), spawn.getY(), spawn.getZ()), access.status(), access.reachable()),
                List.of("Only loaded chunks inspected; referenced starts outside tile are not resolved",
                        "Only observed player spawn is eligible for location evidence; Director contract/import remains unvalidated",
                        "Structure placement unsupported; manual builds cannot be identified from starts",
                        "Fauna suitability uses vanilla-fauna-v1 development rules; population limits and spawn access remain unvalidated"));
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(document).getBytes(StandardCharsets.UTF_8);
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2/discovery");
        Files.createDirectories(directory);
        Path target = directory.resolve(sha256(bytes) + ".json");
        if (Files.exists(target)) {
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(target))) throw new IOException("Immutable discovery hash conflict");
            return target;
        }
        Path staging = Files.createTempFile(directory, ".discovery-", ".partial");
        try(var channel=java.nio.channels.FileChannel.open(staging,java.nio.file.StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        try(var channel=java.nio.channels.FileChannel.open(directory,java.nio.file.StandardOpenOption.READ)){channel.force(true);}
        catch(UnsupportedOperationException e){/* Directory fsync is not available from this provider. */}
        return target;
    }
    public static String structureSupport(ServerLevel level,ResourceLocation id){
        return level.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(id)
                ?"REGISTERED_PLACEMENT_UNSUPPORTED_NO_SAFE_ADAPTER":"MISSING_REGISTRY_ENTRY";
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
    private DiscoveryExporter() {}
}
