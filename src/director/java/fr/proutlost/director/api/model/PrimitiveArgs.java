package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface PrimitiveArgs permits TargetSound, TargetParticles, Apparition, LocalWeather, CampGrowth, FaunaMigrate, SharedChoice, GoatVisit, Warning, Landslide, Trace, ControlledWater, ControlledFire, ControlledDebris, GlobalWeatherLease, RegionalWeatherLease, ContextualSpawnRule, DecorativeMove {}
