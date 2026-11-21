package fr.proutlost.director.persistence;

import java.util.*;

final class SharedSet<E> extends AbstractSet<E> {
    private final SharedMap<E,Boolean> entries;
    SharedSet(){entries=new SharedMap<>();}
    SharedSet(SharedSet<E> source){entries=new SharedMap<>(source.entries);}
    @Override public int size(){return entries.size();}
    @Override public boolean contains(Object value){return entries.containsKey(value);}
    @Override public boolean add(E value){return entries.put(value,Boolean.TRUE)==null;}
    @Override public Iterator<E> iterator(){return entries.keySet().iterator();}
}
