package fr.proutlost;

import fr.proutlost.worldprep.WorldPrepCommands;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.gametest.WorldPrepBiomeGameTests;
import fr.proutlost.worldprep.gametest.WorldPrepBlockGameTests;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Proutlost.MOD_ID)
public final class Proutlost {
    public static final String MOD_ID = "proutlost";

    /** Explicit callback; NeoForge controls whether GameTest hooks run at all. */
    public static void registerGameTests(RegisterGameTestsEvent event) {
        // Dedicated planner fixtures never include operator-only pipeline tests.
        if (Boolean.getBoolean("proutlost.planner27.labOnly")) {
            event.register(fr.proutlost.worldprep.gametest.NativePlanner26GameTests.class);
            return;
        }
        event.register(fr.proutlost.worldprep.gametest.WorldPrepBiomeGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepBlockGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepCommandGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepExistingChunkGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepDiskLoadingGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepEcologyGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepDiscoveryGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepPhysicalGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.NativePlanner26GameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepEnableGateGameTests.class);
        event.register(fr.proutlost.worldprep.gametest.WorldPrepPackInspectionGameTests.class);
    }

    public Proutlost(IEventBus modBus, ModContainer container) {
        fr.proutlost.worldprep.WorldPrepIdArgument.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, WorldPrepConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, fr.proutlost.quest.runtime.QuestConfig.SPEC, "proutlost-quest-server.toml");
        NeoForge.EVENT_BUS.addListener(WorldPrepCommands::register);
        NeoForge.EVENT_BUS.addListener(WorldPrepRuntime::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.NativePhysicalRuntime::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.NativeQualificationRuntime::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepStructureModule::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepStructureModule::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepFeatureModule::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepFeatureModule::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepFaunaModule::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.WorldPrepFaunaModule::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.NativeStructureReconciliation::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.NativeStructureReconciliation::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.physical.NativeQualificationRuntime::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.persistence.WorldPrepSaveBoundary::save);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.runtime.ExistingChunkLoader::unload);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.runtime.WorldPrepStartupReport::started);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.worldprep.runtime.WorldPrepPreviewExports::stopped);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            if (!Boolean.getBoolean("proutlost.quest.campaignGameTest")) registerGameTests(event);
        });
        // Quest-only TEST_ONLY bootstrap: no campaign listeners or narrative data loaders.
        if (Boolean.getBoolean("proutlost.quest.neutralLab")) {
            fr.proutlost.quest.qa.NeutralLabRuntime.install();
            return;
        }
        if (net.neoforged.fml.ModList.get().isLoaded("proutlost_director"))
            fr.proutlost.quest.runtime.QuestDirectorFacts.register();
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.QuestRuntime::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.EarlyInteractions::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.VoteCommands::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.InvestigationCommands::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.PerceptionCommands::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.CampaignCommands::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.PerceptionDelivery::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.PerceptionDelivery::stopped);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.Arrivals::register);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.Arrivals::joined);
        container.registerConfig(ModConfig.Type.SERVER, fr.proutlost.ambient.AmbientRuntime.SPEC, "proutlost-ambient-server.toml");
        NeoForge.EVENT_BUS.addListener(fr.proutlost.ambient.AmbientRuntime::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.QuestRuntime::routine);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.QuestRuntime::tick);
        NeoForge.EVENT_BUS.addListener(fr.proutlost.quest.runtime.QuestRuntime::stopped);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            if (Boolean.getBoolean("proutlost.planner27.labOnly")) return;
            if (Boolean.getBoolean("proutlost.quest.campaignGameTest"))
                event.register(fr.proutlost.quest.gametest.CampaignGameTests.class);
            else event.register(fr.proutlost.quest.gametest.QuestGameTests.class);
        });
    }
}
