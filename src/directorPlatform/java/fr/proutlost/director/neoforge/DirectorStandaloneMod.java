package fr.proutlost.director.neoforge;

import fr.proutlost.director.bootstrap.DirectorServerLifecycle;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.adapter.neoforge.server.commands.DirectorRealtimeAdminCommands;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.persistence.StoreOptions;
import java.util.Objects;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Standalone Director adapter. No WorldPrep class, no automatic gameplay.
 * Opt-in proof lane may run only in an isolated disposable game directory.
 */
@Mod(DirectorStandaloneMod.MOD_ID)
public final class DirectorStandaloneMod {
    public static final String MOD_ID="proutlost_director";
    private static DirectorServerLifecycle directorLifecycle;

    public static DirectorServerLifecycle directorLifecycle() {
        return Objects.requireNonNull(directorLifecycle,"Director not constructed");
    }

    public DirectorStandaloneMod(IEventBus modBus,ModContainer container) {
        Objects.requireNonNull(modBus);
        Objects.requireNonNull(container);
        if(directorLifecycle!=null)throw new IllegalStateException(
            "duplicate standalone World Director bootstrap");
        // J04 store identity is pinned to SavedData. Never rotate WAL
        // beneath the same world: disposable native GameTests must use a
        // separate full gameDirectory (directorTestRunId Gradle property).
        var labPlan=Boolean.getBoolean("proutlost.director.nativeLab")
            ?Optional.of(new DirectorServerLifecycle.LabStorePlan(
                "director-lab-standalone",new StoreOptions(
                    1_048_620,2_097_152,4_194_304,16_777_216,32,0,128)))
            :Optional.<DirectorServerLifecycle.LabStorePlan>empty();
        var manifestPin=System.getProperty("proutlost.director.productionManifestSha256","").strip();
        var production=manifestPin.isEmpty()?java.util.Optional.<fr.proutlost.director.api.model.Fingerprint>empty():
            java.util.Optional.of(new fr.proutlost.director.api.model.Fingerprint(manifestPin));
        directorLifecycle=new DirectorServerLifecycle(NeoForge.EVENT_BUS,labPlan,production);
        ServerOwnershipAttachments.register(modBus);
        if(net.neoforged.fml.ModList.get().isLoaded("weathercore")){
            try{
                Class.forName("fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreIntegration")
                    .getMethod("register",IEventBus.class).invoke(null,modBus);
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("WeatherCore integration registration failed",failure);}
        }
        // Persist the original UUID + owner token of any future J05 fauna slot.
        // Registry-only: does not enable spawning or gameplay admission.
        EntityDirectorAttachment.register(modBus);
        fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledWater.register(modBus);
        fr.proutlost.director.adapter.neoforge.server.effects.DirectorControlledFireBlocks.register(modBus);
        if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT){
            try{
                for(var name:java.util.List.of(
                    "fr.proutlost.director.adapter.neoforge.client.DirectorControlledWaterClient",
                    "fr.proutlost.director.adapter.neoforge.client.DirectorControlledFireClient",
                    "fr.proutlost.director.adapter.neoforge.client.DirectorClientPacketGateway"))
                    Class.forName(name).getMethod("register",IEventBus.class).invoke(null,modBus);
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("controlled native client registration failed",failure);}
        }
        if(Boolean.getBoolean("proutlost.director.nativeGameTests")) {
            modBus.addListener((RegisterGameTestsEvent event)->{
                try {
                    var suite=new String[]{
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeLabSaveHarness",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeAnchorMetadataLeaseGameTests",
                "fr.proutlost.director.adapter.neoforge.server.DirectorNativeMemoryTailGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativePerceptionColdEventGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeDisasterWriterLossGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeFloodWriterLossGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeFireWriterLossGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeStormWriterLossGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeTerrainDisasterGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeFloodProviderGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeFactoryCatalogGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeFireProviderGameTests",
                        "fr.proutlost.director.bootstrap.NativeGrowthMutationJournalGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.NativeControlledWaterPlanningGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeFauna24GameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeModdedFauna24GameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeWorldPrepImportGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativePagedLocationImportGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeChoiceResolutionGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeTraceAnchorGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeStartupClock",
                        "fr.proutlost.director.neoforge.DirectorStandaloneNativeGameTests",
                        "fr.proutlost.director.neoforge.DirectorStandaloneCampDiscoveryGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorLabCampAnchorIndexGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeWorldBootGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorCandidateCatalogNativeGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeScheduledDecisionGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeDurableRegionIndexGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeJ02SafeMicroSelectionGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeJ02J03SelectedSafeMicroGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeSelectedAdmissionGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeSelectedCampRegrowthGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeWeatherLeaseGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeWeatherCoreGlobalGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorNativeFaunaSpawnRecoveryGameTests",
                        "fr.proutlost.director.adapter.neoforge.server.DirectorRealEngineAuditNativeGameTests"
                    };
                    for(var test:suite)event.register(Class.forName(test));
                }catch(ClassNotFoundException absent){
                    throw new IllegalStateException("standalone native lab tests absent",absent);
                }
            });
        }
        DirectorNativeNetworkBootstrap.install(modBus,NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(DirectorRealtimeAdminCommands::register);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event)->
            fr.proutlost.director.adapter.neoforge.server.commands.DirectorCommands.register(event.getDispatcher(),
                source->directorLifecycle.productionRuntime(source.getServer()).map(runtime->(fr.proutlost.director.adapter.neoforge.server.commands.DirectorCommands.Access)runtime)));
        NeoForge.EVENT_BUS.addListener(DirectorRealtimeAdminCommands::tick);
        NeoForge.EVENT_BUS.addListener(DirectorRealtimeAdminCommands::stopped);
        System.out.println("DIRECTOR_STANDALONE_NEOFORGE_BOOTSTRAP "+
            "nativeLab="+labPlan.isPresent()+" gameplay=false worldprep=false");
    }
}
