package fr.proutlost;

import fr.proutlost.worldprep.WorldPrepCommands;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.gametest.WorldPrepBiomeGameTests;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Proutlost.MOD_ID)
public final class Proutlost {
    public static final String MOD_ID = "proutlost";

    public Proutlost(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, WorldPrepConfig.SPEC);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(WorldPrepBiomeGameTests.class));
        NeoForge.EVENT_BUS.addListener(WorldPrepCommands::register);
        NeoForge.EVENT_BUS.addListener(WorldPrepRuntime::tick);
    }
}
