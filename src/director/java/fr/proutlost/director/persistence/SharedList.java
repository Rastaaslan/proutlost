package fr.proutlost.director.persistence;

import java.util.*;

/** Persistent indexed append sequence; snapshots retain their original size/root. */
final class SharedList<E> extends AbstractList<E> {
    private final SharedMap<Integer,E> entries;
    SharedList(){entries=new SharedMap<>(Integer::compare);}
    SharedList(SharedList<E> source){entries=new SharedMap<>(source.entries);}
    @Override public E get(int index){Objects.checkIndex(index,size());return entries.get(index);}
    @Override public int size(){return entries.size();}
    @Override public boolean add(E value){entries.put(size(),value);return true;}
    @Override public Iterator<E> iterator(){return entries.values().iterator();}
}
