package fr.proutlost.director.api.codec;

import fr.proutlost.director.api.model.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Closed JDK-only model codec. Class names from input are never loaded. Unknown fields fail. */
public final class ModelCodec<T> {
    private final Class<T> type;
    public ModelCodec(Class<T> type) { allowed(type); this.type=type; }
    public byte[] encode(T value) { return CanonicalJsonV1.encode(toJson(type,type.cast(value),0)); }
    public T decode(byte[] bytes) { return type.cast(fromJson(type,CanonicalJsonV1.decode(bytes),0)); }
    private static void allowed(Class<?> c) {
        Checks.require((c.getPackageName().equals("fr.proutlost.director.api.model") || c==fr.proutlost.director.api.error.ReasonCode.class) && (c.isRecord()||c.isEnum()||c.isSealed()||c==BlobContent.class),"not a model type");
    }
    private static String snake(String s) { return s.replaceAll("([A-Z])([A-Z][a-z])","$1_$2").replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT); }
    private static JsonValue.Text text(String s) { return new JsonValue.Text(s); }
    private static JsonValue toJson(Type type,Object value,int depth) {
        Checks.require(depth<=32,"model depth");
        if(type instanceof ParameterizedType p) {
            Class<?> raw=(Class<?>)p.getRawType(); Type[] args=p.getActualTypeArguments();
            if(raw==Optional.class) return ((Optional<?>)value).map(v->toJson(args[0],v,depth+1)).orElse(JsonValue.Nil.INSTANCE);
            if(raw==List.class||raw==Set.class) {
                List<JsonValue> items=new ArrayList<>(); for(Object v:(Collection<?>)value)items.add(toJson(args[0],v,depth+1));
                if(raw==Set.class)items.sort(Comparator.comparing(v->new String(CanonicalJsonV1.encode(v),StandardCharsets.UTF_8)));
                return new JsonValue.Array(items);
            }
            if(raw==Map.class) {
                if(args[0]==String.class) {Map<String,JsonValue> m=new TreeMap<>();((Map<?,?>)value).forEach((k,v)->m.put((String)k,toJson(args[1],v,depth+1)));return new JsonValue.ObjectValue(m);}
                List<JsonValue> entries=new ArrayList<>();((Map<?,?>)value).forEach((k,v)->entries.add(new JsonValue.ObjectValue(Map.of("key",toJson(args[0],k,depth+1),"value",toJson(args[1],v,depth+1)))));
                entries.sort(Comparator.comparing(v->new String(CanonicalJsonV1.encode(((JsonValue.ObjectValue)v).values().get("key")),StandardCharsets.UTF_8)));return new JsonValue.Array(entries);
            }
            throw new IllegalArgumentException("unsupported parameter type");
        }
        Class<?> c=(Class<?>)type; Objects.requireNonNull(value);
        if(c==BlobContent.class){BlobContent b=(BlobContent)value;return new JsonValue.ObjectValue(Map.of("reference",toJson(BlobRef.class,b.reference(),depth+1),"bytes_base64",text(Base64.getEncoder().encodeToString(b.bytes()))));}
        if(c==JsonValue.class||JsonValue.class.isAssignableFrom(c))return (JsonValue)value;
        if(c==String.class||c==UUID.class||c==Instant.class)return text(value.toString());
        if(c==long.class)return text(value.toString());
        if(c==int.class)return new JsonValue.Number(BigDecimal.valueOf((Integer)value));
        if(c==double.class){Checks.finite((Double)value);return new JsonValue.Number(BigDecimal.valueOf((Double)value));}
        if(c==boolean.class)return new JsonValue.Bool((Boolean)value);
        if(c==Double.class)return toJson(double.class,value,depth);
        if(c==Long.class)return toJson(long.class,value,depth);
        allowed(c);
        if(c.isEnum())return text(((Enum<?>)value).name());
        if(c.isSealed()&&!c.isRecord()) {
            Checks.require(Arrays.asList(c.getPermittedSubclasses()).contains(value.getClass()),"variant");
            return new JsonValue.ObjectValue(Map.of("type",text(snake(value.getClass().getSimpleName())),"value",toJson(value.getClass(),value,depth+1)));
        }
        Map<String,JsonValue> m=new TreeMap<>();
        try { for(RecordComponent r:c.getRecordComponents())m.put(snake(r.getName()),toJson(r.getGenericType(),r.getAccessor().invoke(value),depth+1)); }
        catch(ReflectiveOperationException e){throw new IllegalArgumentException("model access",e);}
        // Identity and logical counter wrappers have an unambiguous scalar representation.
        if(c.getRecordComponents().length==1&&c.getRecordComponents()[0].getName().equals("value"))return m.get("value");
        return new JsonValue.ObjectValue(m);
    }
    private static String string(JsonValue v) { if(v instanceof JsonValue.Text s)return s.value();throw new IllegalArgumentException("expected string"); }
    private static Map<String,JsonValue> object(JsonValue v){if(v instanceof JsonValue.ObjectValue o)return o.values();throw new IllegalArgumentException("expected object");}
    private static List<JsonValue> array(JsonValue v){if(v instanceof JsonValue.Array a)return a.values();throw new IllegalArgumentException("expected array");}
    private static BigDecimal number(JsonValue v){if(v instanceof JsonValue.Number n)return n.value();throw new IllegalArgumentException("expected number");}
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object fromJson(Type type,JsonValue value,int depth) {
        Checks.require(depth<=32,"model depth");
        if(type instanceof ParameterizedType p) {
            Class<?> raw=(Class<?>)p.getRawType();Type[] args=p.getActualTypeArguments();
            if(raw==Optional.class)return value==JsonValue.Nil.INSTANCE?Optional.empty():Optional.of(fromJson(args[0],value,depth+1));
            if(raw==List.class||raw==Set.class){List<Object> l=new ArrayList<>();for(JsonValue v:array(value))l.add(fromJson(args[0],v,depth+1));if(raw==Set.class){Set<Object>s=Set.copyOf(l);Checks.require(s.size()==l.size(),"duplicate set element");return s;}return List.copyOf(l);}
            if(raw==Map.class){Map<Object,Object> m=new HashMap<>();if(args[0]==String.class)object(value).forEach((k,v)->m.put(k,fromJson(args[1],v,depth+1)));else for(JsonValue entry:array(value)){Map<String,JsonValue>o=object(entry);Checks.require(o.keySet().equals(Set.of("key","value")),"map entry");Object old=m.put(fromJson(args[0],o.get("key"),depth+1),fromJson(args[1],o.get("value"),depth+1));Checks.require(old==null,"duplicate map key");}return Map.copyOf(m);}
            throw new IllegalArgumentException("unsupported parameter type");
        }
        Class<?> c=(Class<?>)type;
        if(c==BlobContent.class){var o=object(value);Checks.require(o.keySet().equals(Set.of("reference","bytes_base64")),"blob fields");String encoded=string(o.get("bytes_base64"));byte[] bytes=Base64.getDecoder().decode(encoded);Checks.require(Base64.getEncoder().encodeToString(bytes).equals(encoded),"canonical base64");return new BlobContent((BlobRef)fromJson(BlobRef.class,o.get("reference"),depth+1),bytes);}
        if(c==JsonValue.class||JsonValue.class.isAssignableFrom(c))return c.cast(value);
        if(c==String.class)return string(value);
        if(c==UUID.class){String s=string(value);UUID id=UUID.fromString(s);Checks.require(id.toString().equals(s),"UUID canonical");return id;}
        if(c==Instant.class){String s=string(value);Instant t=Instant.parse(s);Checks.require(t.toString().equals(s),"UTC canonical");return t;}
        if(c==long.class||c==Long.class){String s=string(value);Checks.require(s.matches("0|-?[1-9][0-9]*"),"canonical long");return Long.parseLong(s);}
        if(c==int.class)return number(value).intValueExact();
        if(c==double.class||c==Double.class){double n=number(value).doubleValue();Checks.finite(n);return n;}
        if(c==boolean.class){if(value instanceof JsonValue.Bool b)return b.value();throw new IllegalArgumentException("boolean");}
        allowed(c);
        if(c.isEnum())return Enum.valueOf((Class)c,string(value));
        if(c.isSealed()&&!c.isRecord()) {Map<String,JsonValue> o=object(value);Checks.require(o.keySet().equals(Set.of("type","value")),"variant fields");String name=string(o.get("type"));for(Class<?> variant:c.getPermittedSubclasses())if(snake(variant.getSimpleName()).equals(name))return fromJson(variant,o.get("value"),depth+1);throw new IllegalArgumentException("unknown variant");}
        RecordComponent[] components=c.getRecordComponents();Map<String,JsonValue> o;
        if(components.length==1&&components[0].getName().equals("value"))o=Map.of("value",value);else o=object(value);
        Set<String> expected=new HashSet<>();for(var r:components)expected.add(snake(r.getName()));Checks.require(o.keySet().equals(expected),"unknown/missing fields: "+c.getSimpleName());
        Object[] args=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
        for(int i=0;i<args.length;i++){types[i]=components[i].getType();args[i]=fromJson(components[i].getGenericType(),o.get(snake(components[i].getName())),depth+1);}
        try{return c.getConstructor(types).newInstance(args);}catch(ReflectiveOperationException e){throw new IllegalArgumentException("invalid "+c.getSimpleName(),e);}
    }
}
