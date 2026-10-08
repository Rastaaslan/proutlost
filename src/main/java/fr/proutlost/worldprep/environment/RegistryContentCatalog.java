package fr.proutlost.worldprep.environment;

import java.util.*;

/** Immutable profile allow-list mapping semantic families to verified registry block states. */
public final class RegistryContentCatalog {
    public enum Family { ROCK, GRAVEL, SAND, DIRT, MUD, COARSE_DIRT, PODZOL, AQUATIC_PLANT, FLORA, LOG, LEAVES }
    private final Map<Family,List<String>> eligible;
    public RegistryContentCatalog(Map<Family,? extends Collection<String>> values, Set<String> registered) {
        Objects.requireNonNull(values); Objects.requireNonNull(registered);
        var copy=new EnumMap<Family,List<String>>(Family.class);
        values.forEach((family,ids)->{
            var sorted=ids.stream().filter(registered::contains).peek(RegistryContentCatalog::requireId).distinct().sorted().toList();
            if(!sorted.isEmpty()) copy.put(Objects.requireNonNull(family),sorted);
        });
        eligible=Collections.unmodifiableMap(copy);
    }
    public Optional<String> resolve(Family family,long deterministicChoice) {
        var values=eligible.getOrDefault(family,List.of());
        return values.isEmpty()?Optional.empty():Optional.of(values.get(Math.floorMod(deterministicChoice,values.size())));
    }
    public boolean eligible(String state) { return eligible.values().stream().flatMap(Collection::stream).anyMatch(state::equals); }
    public Set<Family> families(){return Collections.unmodifiableSet(eligible.keySet());}
    private static void requireId(String id){if(id==null||id.isBlank()||id.indexOf(':')<1)throw new IllegalArgumentException("Invalid registry id");}
}
