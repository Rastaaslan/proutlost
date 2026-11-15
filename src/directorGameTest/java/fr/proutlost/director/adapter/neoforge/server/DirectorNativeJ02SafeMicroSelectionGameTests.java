package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.model.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Genuine bundled J02 safe_micro decoded by DefinitionLoader with strictly
 * TEST_ONLY primitive and sound references. No permission to send J06 audio.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeJ02SafeMicroSelectionGameTests {
    private DirectorNativeJ02SafeMicroSelectionGameTests(){}
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void nativeCandidateSafeMicroCompilesButIsNotProduction(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null && server.isSameThread(),"actor");
        var catalog=new DirectorLabSafeMicroCatalog(DirectorLabObservationProfile.load())
            .snapshot();
        helper.assertTrue(catalog.events().size()==1 && catalog.chains().isEmpty(),
            "J02 safe_micro candidate source not compiled");
        var event=catalog.events().values().iterator().next();
        helper.assertTrue(event.calibration()==Calibration.DEFAULT_FOR_TESTING &&
            event.families().contains(Family.SAFE_MICRO_EVENT) &&
            event.steps().size()==1 &&
            event.steps().getFirst().args() instanceof TargetSound,
            "J02 compiled event shape mismatch");
        helper.assertTrue(catalog.primitives().values().stream().allMatch(
            p->p.mode()==ProviderMode.TEST_ONLY),
            "unapproved J02 event claimed production sound primitive");
        System.out.println("DIRECTOR_NATIVE_J02_REAL_PACKAGED_SAFE_MICRO_COMPILED_TEST_ONLY_PASS");
        helper.succeed();
    }
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void nativeBundledCampRegrowthCompilesForJ05Lab(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"actor");
        var catalog=new DirectorLabCampRegrowthCatalog(
            DirectorLabObservationProfile.load()).snapshot();
        helper.assertTrue(catalog.events().size()==1 && catalog.chains().isEmpty(),
            "J02 bundled camp_regrowth not compiled");
        var event=catalog.events().values().iterator().next();
        helper.assertTrue(event.steps().size()==1 &&
            event.steps().getFirst().args() instanceof CampGrowth &&
            event.calibration()==Calibration.DEFAULT_FOR_TESTING &&
            catalog.primitives().values().stream().allMatch(p->p.mode()==ProviderMode.TEST_ONLY),
            "J02 growth silently promoted to production");
        System.out.println("DIRECTOR_NATIVE_J02_REAL_CAMP_REGROWTH_COMPILED_PASS");
        helper.succeed();
    }
}
