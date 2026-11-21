package fr.proutlost.worldprep.persistence;

import java.util.*;
import java.util.function.*;

/** All mutators pass one owner-thread journal; navigation views cannot bypass it. */
final class DirtyBlockMap<K,V extends TrackedBlockRecord> extends AbstractMap<K,V>
        implements NavigableMap<K,V> {
    private final TreeMap<K,V> values = new TreeMap<>();
    private final NavigableMap<K,V> view = Collections.unmodifiableNavigableMap(values);
    private final LinkedHashSet<K> dirty = new LinkedHashSet<>();
    private final Set<K> included = new HashSet<>(), captured = new HashSet<>();
    private final BlockWorldPrepData owner;
    private final Predicate<V> persisted;
    private final Function<V,K> canonicalKey;
    DirtyBlockMap(BlockWorldPrepData owner, Predicate<V> persisted,Function<V,K> canonicalKey) {
        this.owner=owner; this.persisted=persisted;this.canonicalKey=canonicalKey;
    }
    private void changed(K key) {
        owner.requireMetadataOwner();
        V value=values.get(key);
        if(value!=null && persisted.test(value)) included.add(key); else included.remove(key);
        dirty.add(key); owner.changed();
    }
    int persistedSize() { return included.size(); }
    Set<K> changedKeys() { owner.requireMetadataOwner(); return new LinkedHashSet<>(dirty); }
    boolean previouslyCaptured(K key) { return captured.contains(key); }
    void admitLoadedBaseline() {
        owner.requireMetadataOwner(); captured.clear(); captured.addAll(included); dirty.clear();
    }
    void capturedChanges() {
        owner.requireMetadataOwner();
        for(K key:dirty) { if(included.contains(key)) captured.add(key); else captured.remove(key); }
        dirty.clear();
    }
    @Override public V put(K key,V value) {
        owner.requireMetadataOwner(); Objects.requireNonNull(key); Objects.requireNonNull(value);
        if(!key.equals(canonicalKey.apply(value)))throw new IllegalArgumentException("Noncanonical block metadata map key");
        value.bind(owner,key,()-> { if(values.get(key)==value) changed(key); });
        V previous=values.put(key,value); changed(key); return previous;
    }
    @Override public V remove(Object key) {
        owner.requireMetadataOwner();
        V previous=values.get(key);
        if(previous==null)return null;
        K canonical=canonicalKey.apply(previous);
        values.remove(canonical);changed(canonical);return previous;
    }
    @Override public void clear() { for(K key:new ArrayList<>(values.keySet()))remove(key); }
    @Override public void putAll(Map<? extends K,? extends V> m) {
        for(var e:m.entrySet())put(e.getKey(),e.getValue());
    }
    @Override public void replaceAll(BiFunction<? super K,? super V,? extends V> f) {
        Objects.requireNonNull(f);
        for(K key:new ArrayList<>(values.keySet()))put(key,f.apply(key,values.get(key)));
    }
    @Override public V get(Object key) { return values.get(key); }
    @Override public boolean containsKey(Object key) { return values.containsKey(key); }
    @Override public int size() { return values.size(); }
    @Override public Set<Entry<K,V>> entrySet() { return view.entrySet(); }
    @Override public Set<K> keySet() { return view.keySet(); }
    @Override public Collection<V> values() { return view.values(); }
    @Override public Comparator<? super K> comparator() { return values.comparator(); }
    @Override public K firstKey() { return values.firstKey(); }
    @Override public K lastKey() { return values.lastKey(); }
    @Override public Entry<K,V> lowerEntry(K k) { return view.lowerEntry(k); }
    @Override public K lowerKey(K k) { return values.lowerKey(k); }
    @Override public Entry<K,V> floorEntry(K k) { return view.floorEntry(k); }
    @Override public K floorKey(K k) { return values.floorKey(k); }
    @Override public Entry<K,V> ceilingEntry(K k) { return view.ceilingEntry(k); }
    @Override public K ceilingKey(K k) { return values.ceilingKey(k); }
    @Override public Entry<K,V> higherEntry(K k) { return view.higherEntry(k); }
    @Override public K higherKey(K k) { return values.higherKey(k); }
    @Override public Entry<K,V> firstEntry() { return view.firstEntry(); }
    @Override public Entry<K,V> lastEntry() { return view.lastEntry(); }
    @Override public Entry<K,V> pollFirstEntry() {
        var e=firstEntry(); if(e!=null)remove(e.getKey()); return e;
    }
    @Override public Entry<K,V> pollLastEntry() {
        var e=lastEntry(); if(e!=null)remove(e.getKey()); return e;
    }
    @Override public NavigableMap<K,V> descendingMap() { return view.descendingMap(); }
    @Override public NavigableSet<K> navigableKeySet() { return view.navigableKeySet(); }
    @Override public NavigableSet<K> descendingKeySet() { return view.descendingKeySet(); }
    @Override public NavigableMap<K,V> subMap(K a,boolean ai,K b,boolean bi) { return view.subMap(a,ai,b,bi); }
    @Override public NavigableMap<K,V> headMap(K k,boolean inc) { return view.headMap(k,inc); }
    @Override public NavigableMap<K,V> tailMap(K k,boolean inc) { return view.tailMap(k,inc); }
    @Override public SortedMap<K,V> subMap(K a,K b) { return view.subMap(a,b); }
    @Override public SortedMap<K,V> headMap(K k) { return view.headMap(k); }
    @Override public SortedMap<K,V> tailMap(K k) { return view.tailMap(k); }
}

abstract class TrackedBlockRecord {
    private BlockWorldPrepData owner;
    private Object key;
    private Runnable changed;
    final void bind(BlockWorldPrepData owner,Object key,Runnable changed) {
        if(this.owner!=null && (this.owner!=owner || !Objects.equals(this.key,key))
                && (this.owner.strictMetadataTracking() || owner.strictMetadataTracking()))
            throw new IllegalStateException("Block metadata record cannot change owner/key");
        this.owner=owner; this.key=key; this.changed=changed;
    }
    final void beforeMutation() { if(owner!=null)owner.requireMetadataOwner(); }
    final void metadataChanged() { if(changed!=null)changed.run(); }
}
