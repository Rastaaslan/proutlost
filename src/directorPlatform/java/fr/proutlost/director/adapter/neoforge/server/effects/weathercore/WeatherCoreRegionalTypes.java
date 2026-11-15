package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import io.github.steamautomata.weathercore.WeatherCore;
import io.github.steamautomata.weathercore.api.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class WeatherCoreRegionalTypes {
    private static final DeferredRegister<WeatherType<?>> TYPES=DeferredRegister.create(WeatherCore.WEATHER_REGISTRY,"proutlost_director");
    public static final DeferredHolder<WeatherType<?>,WeatherType<DirectorRegionalMistWeather>> MIST=TYPES.register(
        "regional_mist",()->WeatherType.Builder.of(DirectorRegionalMistWeather::new).weight(0).duration(200,600).particle(16,2).build());
    public static final DeferredHolder<WeatherType<?>,WeatherType<WeatherCoreGlobalLeaseMarker>> GLOBAL_LEASE=TYPES.register(
        "global_weather_lease_marker",()->WeatherType.Builder.of(WeatherCoreGlobalLeaseMarker::new).weight(0).duration(1,1).particle(0,0).build());
    private static boolean registered;
    private WeatherCoreRegionalTypes(){}
    public static void register(IEventBus bus){if(registered)throw new IllegalStateException("regional registration duplicate");registered=true;TYPES.register(bus);}
}
