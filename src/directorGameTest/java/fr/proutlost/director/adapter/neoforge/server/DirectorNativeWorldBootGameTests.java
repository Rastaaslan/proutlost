package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Verifies the recovered J04 WORLD CAS initialization, with no effect permit. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWorldBootGameTests {
    private DirectorNativeWorldBootGameTests(){}
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
    public static void recoveredDurableWorldNeverAdmitsPhysicalEvents(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        helper.assertTrue(server!=null&&server.isSameThread(),"Minecraft actor");
        helper.succeedWhen(()->{
            var life=DirectorStandaloneMod.directorLifecycle();
            var status=life.worldBootstrapStatus(server);
            helper.assertTrue(status.isPresent(),"waiting for J04 world bootstrap");
            var result=status.orElseThrow();
            helper.assertTrue(result.phase()==
                DirectorNativeWorldStateBootstrap.Phase.VERIFIED_PAUSED,
                "J04 durable WORLD fsync/recovery not proven");
            var world=life.recoveredWorld(server).orElseThrow();
            var epoch=life.verifiedBootEpoch(server).orElseThrow();
            helper.assertTrue(world.bootEpoch().equals(epoch),
                "J04 WORLD bootstrap did not rebind actual recovered boot");
            helper.assertTrue(result.worldRevision()>0 &&
                result.simTick()==world.simTick().value(),
                "WORLD revision/simtick invalid");
            helper.assertTrue(world.healthMode()==HealthMode.READ_ONLY_SAFE &&
                world.runControl()==RunControl.PAUSED &&
                !result.admittedGameplay() &&
                !life.snapshot(server).admitsEvents(),
                "unapproved WORLD bootstrap admitted gameplay");
            helper.assertTrue(life.snapshot(server).missingPortCount()==9,
                "test-only WORLD counted as production provider");
            System.out.println("DIRECTOR_NATIVE_J03_WORLD_PERSISTED_PAUSED_NO_EFFECTS_PASS");
        });
    }
}
