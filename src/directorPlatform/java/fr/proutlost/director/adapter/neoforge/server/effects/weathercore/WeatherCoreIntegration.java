package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import fr.proutlost.director.adapter.neoforge.server.effects.RestorableWeatherBinding;
import fr.proutlost.director.api.model.*;
import java.util.function.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import io.github.steamautomata.weathercore.api.WeatherManager;
import io.github.steamautomata.weathercore.api.Scope;
import io.github.steamautomata.weathercore.WeatherCore;

/** This class is resolved only after the optional provider-presence check. */
public final class WeatherCoreIntegration {
    private WeatherCoreIntegration(){}
    private static void pin() {
        var info=ModList.get().getModContainerById("weathercore").orElseThrow().getModInfo();
        if(!info.getVersion().toString().equals("0.1.2"))
            throw new IllegalStateException("WeatherCore adapter requires qualified pin 0.1.2");
    }
    public static void register(IEventBus bus) {
        pin();WeatherCoreRegionalTypes.register(bus);WeatherCoreGlobalLeaseMarker.register();
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event)->{
            if(event.getLevel() instanceof ServerLevel level){
                // Core 0.1.2 changes countdowns without dirtying SavedData each
                // tick. Mark only its existing in-memory manager dirty; periodic
                // native save/stop performs I/O. This prevents a cold reopen from
                // restoring an older remaining TTL after its first checkpoint.
                var manager=WeatherManager.get(level);if(manager!=null)manager.setDirty();
            }
        });
    }
    public static RestorableWeatherBinding createBinding(Function<DimensionId,ServerLevel> levels,
            Supplier<OwnershipView> ownership,Function<DimensionId,Revision> externalRevision,int capacity) {
        pin();return new WeatherCoreGlobalBinding(levels,ownership,externalRevision,capacity);
    }
    public static WeatherCoreRegionalBinding createRegionalBinding(Function<DimensionId,ServerLevel> levels,
            Supplier<OwnershipView> ownership,Function<ResourceKey,Revision> regionalRevision,int capacity){
        pin();return new WeatherCoreRegionalBinding(levels,ownership,regionalRevision,capacity);
    }
    /** Actor-only identity witness. Timers naturally age; UUID/type/order do not.
     * This observes direct API changes and redirected commands on the actual
     * changed level, even when the native flags remain rain -> rain.
     */
    public static String globalIdentity(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Core identity actor required");
        var manager=WeatherManager.get(level);if(manager==null)return "CORE_MANAGER_UNAVAILABLE";
        var globals=manager.weathersAt(net.minecraft.world.phys.Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global).limit(17).toList();
        if(globals.size()>16)throw new IllegalStateException("Core global identity observation capacity");
        return globals.stream().map(w->java.util.Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(w.type()))+"/"+w.uuid())
            .collect(java.util.stream.Collectors.joining("|"));
    }
}
