package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.pack.ContentClassification;
import fr.proutlost.worldprep.pack.EnvironmentalPackInspector;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepPackInspectionGameTests {
    private static final String TEMPLATE = "village/plains/houses/plains_small_house_1";

    @GameTest(templateNamespace="minecraft", template=TEMPLATE, timeoutTicks=100)
    public static void actualRuntimePackAndRegistriesProduceCanonicalIdentity(GameTestHelper helper) {
        try {
            var snapshot = new EnvironmentalPackInspector().inspect(helper.getLevel(), Map.of(
                    "minecraft", ContentClassification.ENVIRONMENT_SEMANTIC,
                    "neoforge", ContentClassification.RUNTIME_ONLY,
                    "proutlost", ContentClassification.ENVIRONMENT_SEMANTIC), List.of(), List.of());
            snapshot.requireClassified();
            if (snapshot.registries().size() != 8 || snapshot.fingerprint().length() != 64) {
                helper.fail("runtime pack snapshot was incomplete");
            }
            helper.succeed();
        } catch (Exception exception) {
            helper.fail("runtime pack inspection failed: " + exception);
        }
    }

    private WorldPrepPackInspectionGameTests() {}
}
