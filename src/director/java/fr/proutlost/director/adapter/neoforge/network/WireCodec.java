package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;

/** V1 closed binary perception schema. Record order is pinned by API 1.0.0.
 * UUIDs are binary, counters canonical nonnegative VarLongs. J07 matched wire1.1 admin uses the closed JSON codec including typed memory documents.
 * No input supplies a Java class name. Bounds are checked before allocation. */
public final class WireCodec<T> {
    private static final Set<Class<?>> ROOTS = Set.of(SessionReset.class, Capabilities.class,
        PerceptionUpsert.class, PerceptionStop.class, PerceptionReset.class, PerceptionAck.class,
        RecordObservation.class, AnchorInteraction.class, AdminQuery.class, AdminCommand.class,
        AdminSnapshot.class, AdminCommandResult.class, AdminDelta.class);
    private static final List<Class<?>> PARAMETERS = List.of(SoundParameters.class, ParticleParameters.class,
        ApparitionParameters.class, WeatherParameters.class, GhostParameters.class, SymbolParameters.class);
    private final Class<T> type;
    private final int maximum;
    public WireCodec(Class<T> type, int maximum) {
        Checks.require(ROOTS.contains(type) && maximum > 0 && maximum <= 24576, "wire schema/bound");
        this.type = type; this.maximum = maximum;
    }
    public int maximum() { return maximum; }
    public byte[] encode(T value) {
        type.cast(value);
        if (type.getSimpleName().startsWith("Admin")) {
            byte[] bytes = new ModelCodec<>(type).encode(value);
            Checks.require(bytes.length <= maximum, "payload bound"); return bytes;
        }
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int size;
                @Override public void write(int b) throws IOException {
                    Checks.require(++size <= maximum, "payload bound"); out.write(b);
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    Checks.require(len <= maximum - size, "payload bound"); size += len; out.write(b, off, len);
                }
            });
            write(type, value, out); return bytes.toByteArray();
        } catch (ReflectiveOperationException | IOException e) { throw new IllegalArgumentException("wire encode", e); }
    }
    public T decode(byte[] bytes) {
        Checks.require(bytes.length <= maximum, "payload bound");
        if (type.getSimpleName().startsWith("Admin")) return new ModelCodec<>(type).decode(bytes);
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = type.cast(read(type, in));
            Checks.require(in.available() == 0, "trailing fields"); return value;
        } catch (ReflectiveOperationException | IOException e) { throw new IllegalArgumentException("wire decode", e); }
    }
    private static void number(DataOutputStream out, long n) throws IOException {
        Checks.require(n >= 0, "negative counter");
        do { int b = (int)(n & 127); n >>>= 7; out.writeByte(n == 0 ? b : b | 128); } while (n != 0);
    }
    private static long number(DataInputStream in) throws IOException {
        long n = 0;
        for (int i = 0; i < 9; i++) {
            int b = in.readUnsignedByte(); n |= (long)(b & 127) << (7 * i);
            if ((b & 128) == 0) { Checks.require(i == 0 || b != 0, "overlong counter"); return n; }
        }
        throw new IllegalArgumentException("counter overflow");
    }
    private static int size(DataInputStream in, int max) throws IOException {
        long n = number(in); Checks.require(n <= max && n <= in.available(), "length bound"); return (int)n;
    }
    private static void write(Type t, Object v, DataOutputStream o) throws ReflectiveOperationException, IOException {
        if (t instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments(); Class<?> raw = (Class<?>)p.getRawType();
            if (raw == Optional.class) { Optional<?> x = (Optional<?>)v; o.writeBoolean(x.isPresent()); if (x.isPresent()) write(a[0], x.get(), o); return; }
            if (raw == Set.class) {
                var items = new ArrayList<>((Set<?>)v); Checks.require(items.size() <= 128, "set bound");
                items.sort(Comparator.comparing(Object::toString)); number(o, items.size());
                for (Object x : items) write(a[0], x, o); return;
            }
            if (raw == Map.class) {
                Map<?,?> m = (Map<?,?>)v; Checks.require(m.size() <= 128, "asset bound"); number(o, m.size());
                var keys = new ArrayList<>(m.keySet()); keys.sort(Comparator.comparing(Object::toString));
                for (Object k : keys) { write(a[0], k, o); write(a[1], m.get(k), o); } return;
            }
            throw new IllegalArgumentException("wire collection");
        }
        Class<?> c = (Class<?>)t;
        if (c == DimensionId.class) Checks.require(((DimensionId)v).value().getBytes(StandardCharsets.UTF_8).length<=256,"dimension bound");
        if (c == String.class) { byte[] b = ((String)v).getBytes(StandardCharsets.UTF_8); Checks.require(b.length <= 4000, "string bound"); number(o,b.length); o.write(b); }
        else if (c == UUID.class) { UUID id = (UUID)v; o.writeLong(id.getMostSignificantBits()); o.writeLong(id.getLeastSignificantBits()); }
        else if (c == int.class || c == long.class) number(o, ((Number)v).longValue());
        else if (c == double.class) { Checks.finite((Double)v); o.writeDouble((Double)v); }
        else if (c == boolean.class) o.writeBoolean((Boolean)v);
        else if (c.isEnum()) number(o, ((Enum<?>)v).ordinal());
        else if (c == PerceptionParameters.class) { int i = PARAMETERS.indexOf(v.getClass()); Checks.require(i >= 0, "kind"); number(o,i); write(PARAMETERS.get(i),v,o); }
        else { for (var r : c.getRecordComponents()) write(r.getGenericType(), r.getAccessor().invoke(v), o); }
    }
    private static Object read(Type t, DataInputStream in) throws ReflectiveOperationException, IOException {
        if (t instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments(); Class<?> raw = (Class<?>)p.getRawType();
            if (raw == Optional.class) return bool(in) ? Optional.of(read(a[0],in)) : Optional.empty();
            int n = size(in,128);
            if (raw == Set.class) { Set<Object> s = new HashSet<>(); for (int i=0;i<n;i++) Checks.require(s.add(read(a[0],in)), "duplicate set"); return Set.copyOf(s); }
            if (raw == Map.class) { Map<Object,Object> m = new HashMap<>(); for (int i=0;i<n;i++) Checks.require(m.put(read(a[0],in),read(a[1],in)) == null, "duplicate asset"); return Map.copyOf(m); }
            throw new IllegalArgumentException("wire collection");
        }
        Class<?> c = (Class<?>)t;
        if (c == String.class) return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(in.readNBytes(size(in,4000)))).toString();
        if (c == UUID.class) return new UUID(in.readLong(),in.readLong());
        if (c == int.class) return Math.toIntExact(number(in));
        if (c == long.class) return number(in);
        if (c == double.class) { double d=in.readDouble(); Checks.finite(d); return d; }
        if (c == boolean.class) return bool(in);
        if (c.isEnum()) { long i=number(in); Object[] values=c.getEnumConstants(); Checks.require(i<values.length,"enum"); return values[(int)i]; }
        if (c == PerceptionParameters.class) { long i=number(in); Checks.require(i<PARAMETERS.size(),"parameters"); return read(PARAMETERS.get((int)i),in); }
        var fields=c.getRecordComponents(); Object[] args=new Object[fields.length]; Class<?>[] types=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++){ types[i]=fields[i].getType(); args[i]=read(fields[i].getGenericType(),in); }
        Object result=c.getConstructor(types).newInstance(args);
        if(c==DimensionId.class)Checks.require(((DimensionId)result).value().getBytes(StandardCharsets.UTF_8).length<=256,"dimension bound");
        return result;
    }
    private static boolean bool(DataInputStream in) throws IOException { int b=in.readUnsignedByte(); Checks.require(b<=1,"boolean"); return b==1; }
}
