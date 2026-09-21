package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.world.ServerExistingChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepExistingChunkGameTests {
    private static final String TEMPLATE = "village/plains/houses/plains_small_house_1";

    @GameTest(templateNamespace="minecraft", template=TEMPLATE, timeoutTicks=40)
    public static void missingChunkPreflightDoesNotGenerateOrPromote(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        int chunkX = (origin.getX() >> 4) + 100_000;
        int chunkZ = (origin.getZ() >> 4) + 100_000;
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) helper.fail("test chunk unexpectedly loaded");
        try {
            ServerExistingChunkAccess.requireChunk(level, chunkX, chunkZ);
            helper.fail("missing chunk was accepted");
        } catch (ServerExistingChunkAccess.Refused expected) {
            if (expected.refusal() != ServerExistingChunkAccess.Refusal.MISSING_OR_INCOMPLETE_CHUNK) {
                helper.fail("wrong refusal: " + expected.refusal());
            }
        }
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) helper.fail("preflight generated or loaded chunk");
        helper.succeed();
    }

    private WorldPrepExistingChunkGameTests() {}
}
