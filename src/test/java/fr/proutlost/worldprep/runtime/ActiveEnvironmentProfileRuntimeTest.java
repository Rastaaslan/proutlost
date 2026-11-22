package fr.proutlost.worldprep.runtime;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Active resource cohorts, default fingerprint compatibility, and rejection without fallback. */
final class ActiveEnvironmentProfileRuntimeTest {
    private static String builtin() throws Exception {
        try (var in=ActiveEnvironmentProfileRuntimeTest.class.getResourceAsStream(
                "/data/proutlost/worldprep/profiles/river_test.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static String configured(double flora,double forest,double slope,double cold) throws Exception {
        var json=JsonParser.parseString(builtin()).getAsJsonObject();
        var environment=json.getAsJsonObject("environment");
        environment.addProperty("flora_density",flora);
        environment.addProperty("forest_density",forest);
        environment.addProperty("max_slope",slope);
        environment.addProperty("cold_temperature",cold);
        return json.toString();
    }
    private static ResourceManager manager(String text,AtomicInteger reads) {
        PackResources pack=(PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class},(proxy,method,args)->switch(method.getName()) {
                    case "packId" -> "p008-active-profile-test";
                    case "toString" -> "p008-active-profile-test";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    default -> throw new AssertionError("Unexpected pack call "+method.getName());
                });
        return (ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[]{ResourceManager.class},(proxy,method,args)->switch(method.getName()) {
                    case "getResource" -> {
                        assertEquals(BiomeProfileRuntime.RESOURCE,args[0]);
                        reads.incrementAndGet();
                        yield text==null?Optional.empty():Optional.of(new Resource(pack,
                                ()->new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
                    }
                    case "toString" -> "p008-resource-cohort-"+System.identityHashCode(proxy);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    default -> throw new AssertionError("Unexpected resource call "+method.getName());
                });
    }
    @Test void defaultCohortPreservesExistingSettingsFingerprintAndReadsOnce() throws Exception {
        var reads=new AtomicInteger();
        var manager=manager(builtin(),reads);
        var actual=BiomeProfileRuntime.environment(manager);
        assertEquals(EcologyMutationPlanner.validateProfile(),actual);
        assertEquals(EcologyMutationPlanner.validateProfile().toString(),actual.toString());
        assertSame(actual,BiomeProfileRuntime.environment(manager));
        assertEquals(1,reads.get());
    }
    @Test void activeOverrideUsesAllFourFieldsAndReloadGetsAnIndependentCohort() throws Exception {
        var firstReads=new AtomicInteger();var secondReads=new AtomicInteger();
        var first=manager(configured(0,.2,3,.7),firstReads);
        var second=manager(configured(1,.8,9,1.3),secondReads);
        assertEquals(new EcologyMutationPlanner.Settings(0,.2,3,.7),BiomeProfileRuntime.environment(first));
        assertEquals(new EcologyMutationPlanner.Settings(1,.8,9,1.3),BiomeProfileRuntime.environment(second));
        assertEquals(0,BiomeProfileRuntime.environment(first).floraDensity());
        assertEquals(1,firstReads.get());assertEquals(1,secondReads.get());
    }
    @Test void invalidActiveBoundsNeverFallBackToPackagedSettings() throws Exception {
        for (double[] values:new double[][]{{-0.1,.9,6,.45},{1.1,.9,6,.45},
                {.45,-.1,6,.45},{.45,1.1,6,.45},{.45,.9,-1,.45},
                {.45,.9,17,.45},{.45,.9,6,-.1},{.45,.9,6,2.1}}) {
            var manager=manager(configured(values[0],values[1],values[2],values[3]),new AtomicInteger());
            assertThrows(IllegalStateException.class,()->BiomeProfileRuntime.environment(manager));
        }
        assertEquals(.45,EcologyMutationPlanner.validateProfile().floraDensity());
        assertEquals(0,BiomeProfileRuntime.environment(manager(configured(0,.9,6,.45),new AtomicInteger())).floraDensity());
    }
    @Test void unknownEnvironmentSchemaRefusesTheActiveProfile() throws Exception {
        var json=JsonParser.parseString(builtin()).getAsJsonObject();
        json.getAsJsonObject("environment").addProperty("schema",2);
        var manager=manager(json.toString(),new AtomicInteger());
        assertThrows(IllegalStateException.class,()->BiomeProfileRuntime.environment(manager));
    }
    @Test void missingEnvironmentRefusesEcologyWithoutPackagedFallback() throws Exception {
        var json=JsonParser.parseString(builtin()).getAsJsonObject();json.remove("environment");
        var manager=manager(json.toString(),new AtomicInteger());
        assertThrows(IllegalStateException.class,()->BiomeProfileRuntime.environment(manager));
    }
    @Test void missingActiveResourceRefusesWithoutPackagedFallback() {
        var manager=manager(null,new AtomicInteger());
        assertThrows(IllegalStateException.class,()->BiomeProfileRuntime.environment(manager));
    }
}
