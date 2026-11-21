package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/**
 * J02 real NeoForge classpath integrity + strict unqualified-provider gate.
 * CANDIDATE pack and calibration are unapproved; never count it as
 * production CatalogPort, nor as a gameplay decision authorization.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorCandidateCatalogNativeGameTests {
    private DirectorCandidateCatalogNativeGameTests(){}

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void nativeCandidateManifestElevenSha256Proof(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),
            "J02 native actor required");
        helper.succeedWhen(()->{
            var catalog=DirectorStandaloneMod.directorLifecycle().candidateCatalogView(server);
            helper.assertTrue(catalog.isPresent(),
                "missing J02 candidate manifest validation");
            var view=catalog.orElseThrow();
            helper.assertTrue(view.verifiedEvents()==9&&view.verifiedChains()==2,
                "expected nine real bundled candidate events and two chains");
            helper.assertTrue(view.manifestSha256().matches("[0-9a-f]{64}") &&
                view.testProfileSha256().matches("[0-9a-f]{64}"),
                "resource manifest/profile content hashes missing");
            helper.assertTrue(view.bindingStatus().equals(
                "REQUIRES_REAL_J05_J06_REGISTRIES_AND_QUALIFICATION"),
                "unqualified provider binding misrepresented");
            helper.assertTrue(!view.validatedForProduction() && !view.gameplayAdmitted() &&
                !DirectorStandaloneMod.directorLifecycle().snapshot(server).admitsEvents(),
                "unapproved J02 candidate promoted into gameplay");
            System.out.println("DIRECTOR_NATIVE_J02_PACKAGED_9_EVENTS_2_CHAINS_SHA256_PASS");
        });
    }

    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void noCatalogPromotionWithoutRealPrimitiveProviders(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"J02 actor");
        var testing=DirectorLabObservationProfile.load();
        var candidate=new DirectorNativeCandidateCatalog(testing);
        helper.assertTrue(candidate.candidateDocuments().size()==11,
            "bundled J02 source list");
        helper.assertTrue(candidate.rejectsUnqualifiedProviders(testing),
            "definition loader must atomically reject missing actual J05/J06 primitives");
        boolean refused=false;
        try {
            DirectorBootstrap.compose(ProviderMode.PRODUCTION,List.of(
                new DirectorBootstrap.Provider(candidate,ProviderMode.PRODUCTION,
                    List.of(),List.of())));
        } catch(IllegalArgumentException expected){refused=true;}
        helper.assertTrue(refused,
            "TEST_ONLY J02 integrity checker must not be a production CatalogPort");
        helper.assertTrue(DirectorStandaloneMod.directorLifecycle().snapshot(server)
                .missingPortCount()>=9,
            "J02 candidates removed a mandatory production missing-port gate");
        System.out.println("DIRECTOR_NATIVE_J02_UNQUALIFIED_PRIMITIVES_REJECTED_PASS");
        helper.succeed();
    }


    /** Read the real SHA-verified J02 event and chain dependencies. A TEST_ONLY
     * descriptor is never evidence of a running provider or a physical permit. */
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",
              timeoutTicks=12000)
    public static void nativeCandidateDependencyGraphReportsUnboundProviders(
            GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"J02 server actor");
        var candidate=new DirectorNativeCandidateCatalog(
            DirectorLabObservationProfile.load());
        var index=candidate.dependencies();
        helper.assertTrue(index.eventPrimitives().size()==9 &&
            index.chainEvents().size()==2 &&
            index.requiredPrimitives().size()==10 &&
            index.requiredCapabilities().size()==13,
            "11 candidate definitions must describe exact J05/J06 needs");
        var sound=new PrimitiveId("proutlost:target_sound");
        var camp=new PrimitiveId("proutlost:camp_growth");
        var landslide=new PrimitiveId("proutlost:landslide");
        helper.assertTrue(index.requiredPrimitives().containsAll(
            Set.of(sound,camp,landslide,new PrimitiveId("proutlost:warning"))) &&
            index.affectedEvents(sound).equals(Set.of(
                new DefinitionId("proutlost_director:candidate/parallel_steps"),
                new DefinitionId("proutlost_director:candidate/safe_micro"))),
            "shared client-sound or disaster dependency was silently omitted");
        var shortArc=new DefinitionId("proutlost_director:candidate/camp_short_arc");
        var livingScar=new DefinitionId("proutlost_director:candidate/living_scar");
        helper.assertTrue(index.chainEvents().get(shortArc).equals(Set.of(
                new DefinitionId("proutlost_director:candidate/parallel_steps"),
                new DefinitionId("proutlost_director:candidate/shared_choice"))) &&
            index.chainEvents().get(livingScar).equals(Set.of(
                new DefinitionId("proutlost_director:candidate/animal_migration"),
                new DefinitionId("proutlost_director:candidate/local_mist"))),
            "J02 chain graph referenced unresolved or fabricated event");
        var empty=new fr.proutlost.director.catalog.PrimitiveRegistry(
            List.of(),ProviderMode.TEST_ONLY);
        helper.assertTrue(index.missingDescriptors(empty).size()==10,
            "J02 dependency matrix falsely qualified empty registry");
        var oneTestOnly=new DirectorLabCampRegrowthCatalog(
            DirectorLabObservationProfile.load());
        var withOneTestDescriptor=new fr.proutlost.director.catalog.PrimitiveRegistry(
            oneTestOnly.snapshot().primitives().values(),ProviderMode.TEST_ONLY);
        helper.assertTrue(index.missingDescriptors(withOneTestDescriptor).size()==9 &&
            !index.missingDescriptors(withOneTestDescriptor).contains(camp),
            "J02 diagnostic did not reflect independently registered descriptor");
        boolean immutable=false;
        try{index.eventPrimitives().clear();}
        catch(UnsupportedOperationException expected){immutable=true;}
        helper.assertTrue(immutable && candidate.status().validatedForProduction()==false &&
            !DirectorStandaloneMod.directorLifecycle().snapshot(server).admitsEvents(),
            "J02 dependency metadata must never grant execution rights");
        // The real native J02 candidate loader binds AFTER asynchronous J04
        // recovery. Local SHA checks above are synchronous, but a transient
        // empty lifecycle graph is NOT a final mismatch. Await the actual
        // registered server graph (without fabricating one or sleeping).
        helper.succeedWhen(()->{
            var nativeGraph=DirectorStandaloneMod.directorLifecycle()
                .candidateDependencies(server);
            helper.assertTrue(nativeGraph.isPresent() &&
                nativeGraph.orElseThrow().equals(index),
                "J02 native server graph not yet published/mismatch");
            System.out.println(
                "DIRECTOR_NATIVE_J02_10_PRIMITIVES_13_CAPABILITIES_CHAIN_GRAPH_PASS");
        });
    }
}
