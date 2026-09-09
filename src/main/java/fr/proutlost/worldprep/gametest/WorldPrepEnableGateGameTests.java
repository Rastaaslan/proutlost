package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepEnableGateGameTests {
    private static final String TEMPLATE = "village/plains/houses/plains_small_house_1";

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 40)
    public static void disabledDirectTickDoesNotAdvancePersistedJob(GameTestHelper helper) {
        var level = helper.getLevel();
        var data = WorldPrepRuntime.data(level);
        UUID jobId = UUID.randomUUID();
        data.jobs().put(jobId, new WorldPrepSavedData.Job(jobId,
                WorldPrepSavedData.Operation.PREVIEW_BIOMES,
                "proutlost:disabled_gate_fixture",
                WorldPrepSavedData.JobState.QUEUED,
                0,
                "",
                null));
        data.changed();

        boolean refused = false;
        WorldPrepConfig.ENABLED.set(false);
        try {
            WorldPrepRuntime.tickLevel(level, 1_000_000);
        } catch (IllegalStateException expected) {
            refused = true;
        } finally {
            WorldPrepConfig.ENABLED.set(true);
        }

        var persisted = data.jobs().get(jobId);
        if (!refused) {
            helper.fail("tickLevel executed while WorldPrep was disabled");
            return;
        }
        if (persisted == null || persisted.state() != WorldPrepSavedData.JobState.QUEUED || persisted.cursor() != 0) {
            helper.fail("disabled direct tick advanced the persisted job");
            return;
        }
        data.jobs().remove(jobId);
        data.changed();
        helper.succeed();
    }

    private WorldPrepEnableGateGameTests() {}
}
