
package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One-shot, explicitly isolated real-ecology PREVIEW population permit.
 *
 * NOT part of the frozen v5 native runtime or a G5 Director admission.
 * Uses the normal shared population baseline/reservation and native owner
 * claims, never bypasses budget or habitat checks. Development only.
 */
public final class NativeDevS2PopulationPermit {
    public static Optional<NativeFaunaPass> begin(
        ServerLevel level,NativeFaunaPass.Plan plan,Path operationDirectory,
        Path expectedWorld,WorldPrepSavedData.Area completeEcology)throws IOException {
        NativePhysicalRuntime.requireThread(level);

        String token=System.getProperty("proutlost.s2preview.explicit", "");
        boolean preview30="PREVIEW_ONLY_NOT_FINAL".equals(token)
            && expectedWorld.endsWith("native-s2-fauna-preview-lab/world");
        boolean preview25="PREVIEW_25_ONLY_NOT_FINAL".equals(token)
            && expectedWorld.endsWith("native-s2-fauna-25-lab/world");

        if(FMLLoader.isProduction()
            ||!(preview30||preview25)
            ||level.getServer().getPlayerCount()!=0
            ||!level.getServer().getWorldPath(LevelResource.ROOT).toRealPath()
                .equals(expectedWorld.toRealPath())
            ||level.getSeed()!=-355732122L
            ||plan.animals().size()!=1
            ||!plan.rule().persistent()
            ||!Set.of("minecraft:cow","minecraft:cod").contains(plan.rule().species()))
            throw new IOException("Only prequalified isolated S2 preview population permitted");
        var exact=WorldPrepRuntime.requireArea(
            WorldPrepRuntime.data(level),"proutlost:s2_full_ecology");
        if(!completeEcology.equals(exact)
            ||exact.minX()!=4496||exact.minZ()!=-1666
            ||exact.maxX()!=5236||exact.maxZ()!=-943
            ||!exact.dimension().equals("minecraft:overworld"))
            throw new IOException("Foreign target area for preview population");
        NativePhysicalRuntime.guard(level,exact);
        var populationArea=plan.area();
        if(!populationArea.dimension().equals(exact.dimension())
            ||populationArea.minX()<exact.minX()
            ||populationArea.maxX()>exact.maxX()
            ||populationArea.minZ()<exact.minZ()
            ||populationArea.maxZ()>exact.maxZ()
            ||plan.animals().stream().anyMatch(a->
                a.x()<populationArea.minX()||a.x()>populationArea.maxX()
                ||a.z()<populationArea.minZ()||a.z()>populationArea.maxZ()))
            throw new IOException("Population plan exceeds exact preview area");
        var ledgerFile=expectedWorld.resolve(
            "worldprep-v2/physical-population-budget/disk-baseline.json");
        if(!Files.isRegularFile(ledgerFile,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Certified existing-fauna disk baseline required");
        // Baseline applies to this independently cloned pre-population S2 world,
        // audited before any new entity is issued. Native reserved slots remain
        // immutable and cannot be reissued after restart.
        var store=new PhysicalStore(operationDirectory);
        if(store.exists("plan")) {
            NativePhysicalRuntime.requireRetainedPopulation(level,plan);
        } else {
            try {NativePhysicalRuntime.reservePopulation(level,plan);}
            catch(NativePhysicalRuntime.PopulationBudgetRefusal budget){
                return Optional.empty();
            }
        }
        NativePhysicalRuntime.claim(level,"fauna",populationArea,plan.operation());
        return Optional.of(new NativeFaunaPass(plan,store));
    }
    private NativeDevS2PopulationPermit(){}
}
