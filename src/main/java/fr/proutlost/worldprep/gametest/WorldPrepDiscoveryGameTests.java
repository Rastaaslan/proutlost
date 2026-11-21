package fr.proutlost.worldprep.gametest;

import com.google.gson.Gson;
import fr.proutlost.worldprep.runtime.DiscoveryExporter;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepDiscoveryGameTests {
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=100)
    public static void observedStartReferenceAndMissingRegistryAreExplicit(GameTestHelper h)throws Exception {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(2,2,2));
        var chunk=fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireChunk(level,pos.getX()>>4,pos.getZ()>>4);
        var starts=new java.util.HashMap<>(chunk.getAllStarts());
        var references=new java.util.HashMap<>(chunk.getAllReferences());
        var id=ResourceLocation.parse("minecraft:buried_treasure");
        var structure=level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id);
        h.assertTrue(structure!=null,"Pinned vanilla structure registry is absent");
        String areaId="proutlost:discovery_fixture_"+UUID.randomUUID().toString().replace("-","");
        WorldPrepRuntime.data(level).areas().put(areaId,new WorldPrepSavedData.Area(areaId,level.dimension().location().toString(),pos.getX(),pos.getZ(),pos.getX(),pos.getZ()));
        try {
            // Synthetic start evidence using a real registered vanilla structure and serializable piece.
            // Does not invoke postProcess/worldgen or claim this fixture was generated on the candidate.
            chunk.setStartForStructure(structure,new StructureStart(structure,chunk.getPos(),0,
                    new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(pos)))));
            var newReferences=new java.util.HashMap<>(references);
            newReferences.put(structure,new it.unimi.dsi.fastutil.longs.LongOpenHashSet(new long[]{chunk.getPos().toLong()}));
            chunk.setAllReferences(newReferences);
            var file=DiscoveryExporter.export(level,areaId);
            var document=new Gson().fromJson(Files.readString(file),DiscoveryExporter.Document.class);
            var evidence=document.structureRegistry().stream().filter(e->e.registryId().equals(id.toString())).findFirst().orElseThrow();
            h.assertTrue(evidence.bounds().equals(List.of(pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY(),pos.getZ())),"Bounding box was not observed exactly");
            h.assertTrue(document.structureReferences().values().stream().anyMatch(v->v.contains(chunk.getPos().toLong())),"Start reference omitted");
            h.assertTrue(DiscoveryExporter.structureSupport(level,ResourceLocation.parse("proutlost:missing_structure_fixture")).equals("MISSING_REGISTRY_ENTRY"),"Unavailable structure not refused");
            h.assertTrue(document.structureCapabilities().get(id.toString()).contains("UNSUPPORTED"),"Registered structure incorrectly treated as safely placeable");
            h.assertTrue(document.directorImport().equals("NOT_TESTED_NOT_READY"),"Discovery claimed Director acceptance");
        } finally {chunk.setAllStarts(starts);chunk.setAllReferences(references);}
        h.succeed();
    }
    private WorldPrepDiscoveryGameTests(){}
}
