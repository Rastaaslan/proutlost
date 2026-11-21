package fr.proutlost.director.admin.client;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.stream.Collectors;
/** Closed MEMORY search form: default directory, exact UUID/player or AND tags. */
public final class AdminMemoryFilters {
    private AdminMemoryFilters(){}
    public static AdminFilter parse(String input){
        Objects.requireNonNull(input);if(input.length()>128)throw new IllegalArgumentException("memory search bound");var value=input.trim();
        if(value.startsWith("player "))return new PlayerFilter(PlayerId.parse(value.substring(7).trim()));
        if(value.startsWith("tag ")){
            var values=value.substring(4).trim();if(values.isEmpty())throw new IllegalArgumentException("empty tag search");
            return new RegionFilter(Optional.empty(),Arrays.stream(values.split("\\s+")).map(TagId::new).collect(Collectors.toUnmodifiableSet()));
        }
        return new RegionFilter(value.isEmpty()?Optional.empty():Optional.of(RegionId.parse(value)),Set.of());
    }
    public static String text(RegionFilter filter){return filter.regionId().map(id->id.value().toString()).orElseGet(()->filter.tags().isEmpty()?"":"tag "+filter.tags().stream().map(TagId::value).sorted().collect(Collectors.joining(" ")));}
}
