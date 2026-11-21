package fr.proutlost.director.api.model;

import java.util.Objects;

/** Additive adapter contract: shared centered atmosphere, distinct from both
 * dimension-wide GlobalWeatherLease and private per-player LocalWeather.
 * Geometry is sealed in the EffectIntent footprint and independently revalidated.
 */
public record RegionalWeatherLease(DimensionId dimension,ResourceId profile,
        DurationTicks duration,Revision externalRevision) implements PrimitiveArgs {
    public RegionalWeatherLease {
        Objects.requireNonNull(dimension);Objects.requireNonNull(profile);
        Objects.requireNonNull(duration);Objects.requireNonNull(externalRevision);
        if(duration.value()<=0||duration.value()>72000)
            throw new IllegalArgumentException("regional weather bounded positive duration");
    }
}
