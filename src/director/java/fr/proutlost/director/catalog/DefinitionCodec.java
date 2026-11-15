package fr.proutlost.director.catalog;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import java.lang.reflect.*;
import java.util.*;
import static fr.proutlost.director.catalog.ContentJson.*;
import static fr.proutlost.director.catalog.InvalidContent.require;

/** Content wire format is distinct from the persistence ModelCodec format. No input-selected classes. */
public final class DefinitionCodec {
    static final Map<String, Class<? extends PrimitiveArgs>> ARGUMENTS = Map.ofEntries(
        Map.entry("proutlost:target_sound", TargetSound.class), Map.entry("proutlost:target_particles", TargetParticles.class),
        Map.entry("proutlost:apparition", Apparition.class), Map.entry("proutlost:local_weather", LocalWeather.class),
        Map.entry("proutlost:camp_growth", CampGrowth.class), Map.entry("proutlost:fauna_migrate", FaunaMigrate.class),
        Map.entry("proutlost:shared_choice", SharedChoice.class), Map.entry("proutlost:goat_visit", GoatVisit.class),
        Map.entry("proutlost:warning", Warning.class), Map.entry("proutlost:landslide", Landslide.class), Map.entry("proutlost:trace", Trace.class),
        Map.entry("proutlost:controlled_water", ControlledWater.class), Map.entry("proutlost:controlled_fire", ControlledFire.class),
        Map.entry("proutlost:global_weather_lease", GlobalWeatherLease.class), Map.entry("proutlost:regional_weather_lease", RegionalWeatherLease.class));
    private static final Map<String, Class<? extends Condition>> CONDITIONS = Map.ofEntries(
        Map.entry("all", AllConditions.class), Map.entry("any", AnyConditions.class), Map.entry("not", NotCondition.class),
        Map.entry("players_present", PlayersPresent.class), Map.entry("region_tag", RegionTag.class),
        Map.entry("pattern_confidence", PatternConfidence.class), Map.entry("solo_ready", SoloReady.class),
        Map.entry("risk_minimum", RiskMinimum.class), Map.entry("requirement_pending", RequirementPending.class), Map.entry("memory_flag", MemoryFlag.class));
    private final ContentSchema eventSchema = ContentSchema.bundled("event-definition");
    private final ContentSchema chainSchema = ContentSchema.bundled("chain-definition");
    private final ConfigView config;
    public DefinitionCodec(ConfigView config) { this.config = Objects.requireNonNull(config); }
    public EventDefinition event(byte[] bytes) { return decode(bytes, eventSchema, EventDefinition.class); }
    public ChainDefinition chain(byte[] bytes) { return decode(bytes, chainSchema, ChainDefinition.class); }
    private <T> T decode(byte[] bytes, ContentSchema schema, Class<T> type) {
        JsonValue raw = CanonicalJsonV1.decode(bytes);
        schema.validate(raw);
        boolean[] testing = {false};
        JsonValue resolved = resolve(raw, "", testing);
        if (testing[0]) require(text(object(raw).get("calibration")).equals("DEFAULT_FOR_TESTING"), "/calibration", "test references require test calibration");
        schema.validate(resolved);
        return type.cast(convert(type, resolved, ""));
    }
    private JsonValue resolve(JsonValue value, String p, boolean[] used) {
        if (value instanceof JsonValue.Text t && t.value().startsWith("@test:")) {
            used[0] = true;
            require(config.calibration() == Calibration.DEFAULT_FOR_TESTING, p, "test reference requires test config");
            JsonValue n = config.values().get(t.value().substring(6));
            require(n != null, p, "unknown test parameter " + t.value()); return n;
        }
        if (value instanceof JsonValue.ObjectValue o) {
            Map<String, JsonValue> result = new TreeMap<>();
            o.values().forEach((k, v) -> result.put(k, resolve(v, pointer(p, k), used))); return obj(result);
        }
        if (value instanceof JsonValue.Array a) {
            List<JsonValue> result = new ArrayList<>();
            for (int i = 0; i < a.values().size(); i++) result.add(resolve(a.values().get(i), p + "/" + i, used));
            return new JsonValue.Array(result);
        }
        return value;
    }
    static String snake(String s) { return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convert(Type type, JsonValue value, String p) {
        try {
            if (type instanceof ParameterizedType t) {
                Class<?> raw = (Class<?>) t.getRawType(); Type[] a = t.getActualTypeArguments();
                if (raw == Optional.class) return value == JsonValue.Nil.INSTANCE ? Optional.empty() : Optional.of(convert(a[0], value, p));
                if (raw == List.class || raw == Set.class) {
                    List<Object> result = new ArrayList<>(); List<JsonValue> values = array(value);
                    for (int i = 0; i < values.size(); i++) result.add(convert(a[0], values.get(i), p + "/" + i));
                    if (raw == List.class) return List.copyOf(result);
                    require(new HashSet<>(result).size() == result.size(), p, "duplicate set entry"); return Set.copyOf(result);
                }
                if (raw == Map.class && a[0] == String.class) {
                    Map<String, Object> result = new TreeMap<>();
                    object(value).forEach((k, v) -> result.put(k, convert(a[1], v, pointer(p, k)))); return Map.copyOf(result);
                }
                throw new InvalidContent(p, "unsupported content collection");
            }
            Class<?> c = (Class<?>) type;
            if (c == String.class) return text(value);
            if (c == boolean.class) return ((JsonValue.Bool) value).value();
            if (c == int.class) return number(value).intValueExact();
            if (c == long.class) return number(value).longValueExact();
            if (c == double.class) { double n = number(value).doubleValue(); require(Double.isFinite(n), p, "nonfinite number"); return n; }
            require(c.getPackageName().equals("fr.proutlost.director.api.model"), p, "not a content model");
            if (c.isEnum()) return Enum.valueOf((Class) c, text(value));
            if (c == Condition.class) {
                var fields = new TreeMap<>(object(value));
                Class<?> variant = CONDITIONS.get(text(fields.remove("type")));
                require(variant != null, p + "/type", "unknown condition"); return convert(variant, obj(fields), p);
            }
            if (c == EventStep.class) {
                var fields = object(value); String primitive = text(fields.get("primitive"));
                Class<?> args = ARGUMENTS.get(primitive); require(args != null, p + "/primitive", "unknown argument codec");
                return new EventStep(text(fields.get("id")), array(fields.get("after")).stream().map(ContentJson::text).toList(),
                    new DurationTicks(number(fields.get("delay_ticks")).longValueExact()), new PrimitiveId(primitive),
                    (PrimitiveArgs) convert(args, fields.get("args"), p + "/args"), OnFailure.valueOf(text(fields.get("on_failure"))));
            }
            if (c == BudgetVector.class) {
                var b = object(value);
                long entities = number(b.get("entity_slots")).longValueExact();
                return new BudgetVector(number(b.get("server_ms_per_tick")).doubleValue(), number(b.get("global_units")).doubleValue(),
                    number(b.get("region_units")).doubleValue(), entities, entities, number(b.get("mutation_units")).longValueExact(),
                    0, number(b.get("client_units")).doubleValue(), 0, 0);
            }
            require(c.isRecord(), p, "unsupported content type");
            var components = c.getRecordComponents();
            boolean scalar = components.length == 1 && components[0].getName().equals("value");
            Map<String, JsonValue> fields = scalar ? Map.of("value", value) : object(value);
            Set<String> keys = new HashSet<>(); for (var r : components) keys.add(snake(r.getName()));
            require(keys.equals(fields.keySet()), p, "unknown/missing fields in " + c.getSimpleName());
            Object[] args = new Object[components.length]; Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < args.length; i++) {
                var r = components[i]; types[i] = r.getType();
                args[i] = convert(r.getGenericType(), fields.get(snake(r.getName())), scalar ? p : pointer(p, snake(r.getName())));
            }
            return c.getConstructor(types).newInstance(args);
        } catch (InvalidContent e) { throw e; }
        catch (ReflectiveOperationException | RuntimeException e) {
            throw new InvalidContent(p, "invalid typed content: " + (e instanceof InvocationTargetException i ? i.getCause().getMessage() : e.getMessage()));
        }
    }
}