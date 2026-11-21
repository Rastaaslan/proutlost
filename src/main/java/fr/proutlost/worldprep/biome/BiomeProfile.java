package fr.proutlost.worldprep.biome;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Immutable schema-1 biome content. Terrain policy and all tick budgets live elsewhere. */
public record BiomeProfile(String id, long seed, Map<String,List<BiomeCandidate>> palettes) {
    public static final int MAX_BYTES = 65536; // Existing startup-profile byte bound.
    public static final List<String> REQUIRED_PALETTES = List.of("coastal","stony_coast",
        "open_lowland","lowland_forest","rocky_highland","mountain","marine_shallow","marine","marine_deep");

    public BiomeProfile {
        new BiomeCandidate(id,null); // Same namespaced-ID contract as configured biomes.
        var copy=new LinkedHashMap<String,List<BiomeCandidate>>();
        Objects.requireNonNull(palettes).forEach((name,entries)->{
            if(name==null||name.isBlank()||entries==null||entries.isEmpty())
                throw new IllegalArgumentException("Empty biome palette");
            copy.put(name,List.copyOf(entries));
        });
        if(copy.isEmpty())throw new IllegalArgumentException("Empty biome profile");
        palettes=Collections.unmodifiableMap(copy);
    }

    public static BiomeProfile parse(String text) {
        Objects.requireNonNull(text);
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("Profile byte bound");
        var root=JsonParser.parseString(text).getAsJsonObject();
        if(integer(root,"schema")!=1)throw new IllegalArgumentException("Unsupported biome profile schema");
        String id=string(root,"id");
        long seed=integer(root,"seed");
        var families=root.getAsJsonObject("biomes");
        if(families==null)throw new IllegalArgumentException("Missing biome palettes");
        var palettes=new LinkedHashMap<String,List<BiomeCandidate>>();
        for(var family:families.entrySet()){
            if(!family.getValue().isJsonArray())throw new IllegalArgumentException("Biome palette must be an array: "+family.getKey());
            var entries=new ArrayList<BiomeCandidate>();
            for(var element:family.getValue().getAsJsonArray()){
                var candidate=element.getAsJsonObject();
                for(var key:candidate.keySet())if(!key.equals("id")&&!key.equals("fallback"))
                    throw new IllegalArgumentException("Unsupported biome candidate field: "+key);
                entries.add(new BiomeCandidate(string(candidate,"id"),
                    candidate.has("fallback")&&!candidate.get("fallback").isJsonNull()?string(candidate,"fallback"):null));
            }
            palettes.put(family.getKey(),entries);
        }
        return new BiomeProfile(id,seed,palettes);
    }

    private static String string(JsonObject root,String key){
        var value=root.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing or non-string profile field: "+key);
        return value.getAsString();
    }
    private static long integer(JsonObject root,String key){
        var value=root.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing or non-number profile field: "+key);
        try{return new BigDecimal(value.getAsString()).toBigIntegerExact().longValueExact();}
        catch(ArithmeticException|NumberFormatException invalid){
            throw new IllegalArgumentException("Profile field must be an exact signed-long integer: "+key,invalid);
        }
    }

    public record Validation(String palette,BiomeCandidate candidate,BiomeCandidate.Resolution resolution) {}
    public record Resolved(BiomeProfile profile,Map<String,List<BiomeCandidate.Resolution>> palettes,
            Map<String,Boolean> registryState,List<Validation> validations) {
        public Resolved {
            var copy=new LinkedHashMap<String,List<BiomeCandidate.Resolution>>();
            palettes.forEach((name,entries)->copy.put(name,List.copyOf(entries)));
            palettes=Collections.unmodifiableMap(copy);
            registryState=Collections.unmodifiableMap(new TreeMap<>(registryState));
            validations=List.copyOf(validations);
        }
        public EnumMap<BiomeCandidate.Status,Integer> counts(){
            var counts=new EnumMap<BiomeCandidate.Status,Integer>(BiomeCandidate.Status.class);
            for(var status:BiomeCandidate.Status.values())counts.put(status,0);
            for(var row:validations)counts.merge(row.resolution.status(),1,Math::addExact);
            return counts;
        }
        public void requireValid(){
            var missing=validations.stream().filter(v->v.resolution.status()==BiomeCandidate.Status.MISSING).toList();
            if(!missing.isEmpty())throw new IllegalStateException("Biome profile has unresolved candidates: "+
                missing.stream().limit(16).map(v->v.palette+":"+v.candidate.id()+" fallback="+v.candidate.fallback()).toList()+
                " total="+missing.size());
            for(var name:REQUIRED_PALETTES)if(!palettes.containsKey(name))
                throw new IllegalStateException("Missing required biome palette: "+name);
        }
        public BiomeCandidate.Resolution choose(String palette,long selector){
            var entries=palettes.get(palette);
            if(entries==null||entries.isEmpty())throw new IllegalStateException("Missing biome palette: "+palette);
            var result=entries.get((int)Math.floorMod(selector,(long)entries.size()));
            if(result.status()==BiomeCandidate.Status.MISSING)
                throw new IllegalStateException("Unresolved biome candidate in palette "+palette);
            return result;
        }
    }

    public Resolved resolve(Predicate<String> registry){
        Objects.requireNonNull(registry);
        var states=new TreeMap<String,Boolean>();
        Predicate<String> exists=id->states.computeIfAbsent(id,registry::test);
        var resolved=new LinkedHashMap<String,List<BiomeCandidate.Resolution>>();
        var validations=new ArrayList<Validation>();
        palettes.forEach((name,candidates)->{
            var choices=new ArrayList<BiomeCandidate.Resolution>();
            for(var candidate:candidates){
                // Include explicit fallbacks in the registry fingerprint even while the primary exists.
                exists.test(candidate.id());
                if(candidate.fallback()!=null)exists.test(candidate.fallback());
                var resolution=candidate.resolve(exists);
                choices.add(resolution);validations.add(new Validation(name,candidate,resolution));
            }
            resolved.put(name,choices);
        });
        return new Resolved(this,resolved,states,validations);
    }
}
