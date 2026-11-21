package fr.proutlost.worldprep.persistence;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * HDD-only RUN111 candidate. Exact changed immutable-record keys are captured
 * through explicit Map mutations; prohibited view mutation fails closed when
 * strict tracking is opted in. Legacy maps remain mutable through views when
 * the RUN111 system property is absent (RUN103 is not changed).
 *
 * No original cold world, A/B, Java process or live SavedData is modified.
 */
final class TrackedRecordMap<K,V> extends LinkedHashMap<K,V> {
    static final String FLAG = "proutlost.run111.strictTrackedMetadata";
    private final LinkedHashSet<K> changedKeys = new LinkedHashSet<>();
    // Startup-immutable: a preexisting mutable view must never be able to
    // escape before strict tracking gets activated later in the same JVM.
    private final boolean strictAtConstruction = Boolean.getBoolean(FLAG);
    private boolean removed;

    boolean strictAtConstruction() { return strictAtConstruction; }
    Set<K> changedKeysSnapshot() { return new LinkedHashSet<>(changedKeys); }
    boolean wasRemoved() { return removed; }
    void resetJournal() { changedKeys.clear(); removed = false; }
    void markChanged(K key) { changedKeys.add(key); }

    private void trackAfter(K key, boolean hadKey, V oldValue) {
        boolean now = super.containsKey(key);
        if (hadKey && !now) removed = true;
        if (hadKey != now || !Objects.equals(oldValue, super.get(key)))
            changedKeys.add(key);
    }

    @Override public V put(K key, V value) {
        boolean had = super.containsKey(key);
        V old = super.get(key);
        V result = super.put(key,value);
        trackAfter(key,had,old);
        return result;
    }
    @Override public void putAll(Map<? extends K,? extends V> other) {
        for (var item : other.entrySet()) put(item.getKey(),item.getValue());
    }
    @Override public V putIfAbsent(K key,V value) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.putIfAbsent(key,value);
        trackAfter(key,had,old);return result;
    }
    @Override public V remove(Object key) {
        if(super.containsKey(key)) removed=true;
        return super.remove(key);
    }
    @Override public boolean remove(Object key,Object value) {
        boolean had=super.containsKey(key);
        boolean result=super.remove(key,value);
        if(had && result) removed=true;
        return result;
    }
    @Override public void clear() {
        if(!super.isEmpty())removed=true;
        super.clear();
    }
    @Override public V replace(K key,V value) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.replace(key,value);trackAfter(key,had,old);return result;
    }
    @Override public boolean replace(K key,V oldValue,V newValue) {
        boolean had=super.containsKey(key);V old=super.get(key);
        boolean result=super.replace(key,oldValue,newValue);
        trackAfter(key,had,old);return result;
    }
    @Override public void replaceAll(BiFunction<? super K,? super V,? extends V> function) {
        Objects.requireNonNull(function);
        super.replaceAll((k,v)-> {
            V next=function.apply(k,v);
            if(!Objects.equals(v,next))changedKeys.add(k);
            return next;
        });
    }
    @Override public V computeIfAbsent(K key, Function<? super K,? extends V> function) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.computeIfAbsent(key,function);trackAfter(key,had,old);return result;
    }
    @Override public V computeIfPresent(K key, BiFunction<? super K,? super V,? extends V> function) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.computeIfPresent(key,function);trackAfter(key,had,old);return result;
    }
    @Override public V compute(K key,BiFunction<? super K,? super V,? extends V> function) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.compute(key,function);trackAfter(key,had,old);return result;
    }
    @Override public V merge(K key,V value,BiFunction<? super V,? super V,? extends V> function) {
        boolean had=super.containsKey(key);V old=super.get(key);
        V result=super.merge(key,value,function);trackAfter(key,had,old);return result;
    }
    /**
     * Strict mode rejects mutation through collection views, including
     * iterator.remove, entry.setValue, values.remove and keySet.clear.
     * With the flag disabled, ordinary LinkedHashMap view behavior remains.
     */
    @Override public Set<K> keySet() {
        Set<K> keys=super.keySet();
        return strictAtConstruction?Collections.unmodifiableSet(keys):keys;
    }
    @Override public Collection<V> values() {
        Collection<V> values=super.values();
        return strictAtConstruction?Collections.unmodifiableCollection(values):values;
    }
    @Override public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> entries=super.entrySet();
        if(!strictAtConstruction) return entries;
        return new AbstractSet<>() {
            @Override public int size() { return entries.size(); }
            @Override public boolean contains(Object o) {return entries.contains(o);}
            @Override public Iterator<Map.Entry<K,V>> iterator() {
                Iterator<Map.Entry<K,V>> source=entries.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() {return source.hasNext();}
                    @Override public Map.Entry<K,V> next() {
                        return new AbstractMap.SimpleImmutableEntry<>(source.next());
                    }
                };
            }
        };
    }
}
