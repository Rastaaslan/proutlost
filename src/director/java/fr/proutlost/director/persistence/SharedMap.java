package fr.proutlost.director.persistence;

import java.util.*;

/** Path-copy AVL index. A projection fork shares immutable nodes, never a mutable map or entry.
 * Default ordering is internal only (not a persistence codec or transaction hash).
 */
final class SharedMap<K,V> extends AbstractMap<K,V> {
    private record Node<K,V>(K key,V value,Node<K,V> left,Node<K,V> right,int height,int size) {}
    private Node<K,V> root;
    private final Comparator<K> order;
    private int lastUpdateNodes;
    SharedMap(){this(SharedMap::compareKeys);}
    SharedMap(Comparator<K> order){this.order=order;}
    SharedMap(SharedMap<K,V> source){root=source.root;order=source.order;}
    private static int compareKeys(Object a,Object b) {
        if(a.equals(b))return 0;
        int result=Integer.compare(a.hashCode(),b.hashCode());
        if(result==0)result=a.getClass().getName().compareTo(b.getClass().getName());
        if(result==0)result=a.toString().compareTo(b.toString());
        if(result==0)throw new IllegalArgumentException("ambiguous internal index key ordering");
        return result;
    }
    private static int height(Node<?,?> n){return n==null?0:n.height();}
    private static int count(Node<?,?> n){return n==null?0:n.size();}
    private Node<K,V> node(K key,V value,Node<K,V> left,Node<K,V> right){lastUpdateNodes++;return new Node<>(key,value,left,right,1+Math.max(height(left),height(right)),1+count(left)+count(right));}
    private Node<K,V> right(Node<K,V> top){var n=top.left();return node(n.key(),n.value(),n.left(),node(top.key(),top.value(),n.right(),top.right()));}
    private Node<K,V> left(Node<K,V> top){var n=top.right();return node(n.key(),n.value(),node(top.key(),top.value(),top.left(),n.left()),n.right());}
    private Node<K,V> balance(Node<K,V> n){
        int diff=height(n.left())-height(n.right());
        if(diff>1){if(height(n.left().left())<height(n.left().right()))n=node(n.key(),n.value(),left(n.left()),n.right());return right(n);}
        if(diff < -1){if(height(n.right().right())<height(n.right().left()))n=node(n.key(),n.value(),n.left(),right(n.right()));return left(n);}
        return n;
    }
    private Node<K,V> put(Node<K,V> n,K key,V value){
        if(n==null)return node(key,value,null,null);
        int c=order.compare(key,n.key());
        if(c==0)return node(key,value,n.left(),n.right());
        return balance(c<0?node(n.key(),n.value(),put(n.left(),key,value),n.right()):node(n.key(),n.value(),n.left(),put(n.right(),key,value)));
    }
    private Node<K,V> remove(Node<K,V> current,K key){
        if(current==null)return null;
        int compared=order.compare(key,current.key());
        if(compared<0)return balance(node(current.key(),current.value(),remove(current.left(),key),current.right()));
        if(compared>0)return balance(node(current.key(),current.value(),current.left(),remove(current.right(),key)));
        if(current.left()==null)return current.right();
        if(current.right()==null)return current.left();
        var successor=current.right();while(successor.left()!=null)successor=successor.left();
        return balance(node(successor.key(),successor.value(),current.left(),remove(current.right(),successor.key())));
    }
    @Override @SuppressWarnings("unchecked") public V remove(Object key){
        Objects.requireNonNull(key);V previous=get(key);if(previous==null)return null;
        lastUpdateNodes=0;root=remove(root,(K)key);return previous;
    }
    @Override public V put(K key,V value){Objects.requireNonNull(key);Objects.requireNonNull(value);V prior=get(key);lastUpdateNodes=0;root=put(root,key,value);return prior;}
    @Override @SuppressWarnings("unchecked") public V get(Object key){
        Node<K,V> n=root;
        while(n!=null){int c=order.compare((K)key,n.key());if(c==0)return n.value();n=c<0?n.left():n.right();}return null;
    }
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public int size(){return count(root);}
    int lastUpdateNodes(){return lastUpdateNodes;}
    boolean sharesRoot(SharedMap<K,V> other){return root==other.root;}
    private Iterator<Map.Entry<K,V>> iterator(K lower,boolean bounded){
        Deque<Node<K,V>> stack=new ArrayDeque<>();
        for(Node<K,V> n=root;n!=null;){if(!bounded||order.compare(n.key(),lower)>=0){stack.push(n);n=n.left();}else n=n.right();}
        return new Iterator<>(){
            public boolean hasNext(){return !stack.isEmpty();}
            public Map.Entry<K,V> next(){if(stack.isEmpty())throw new NoSuchElementException();var n=stack.pop();for(var r=n.right();r!=null;r=r.left())stack.push(r);return Map.entry(n.key(),n.value());}
        };
    }
    Iterable<V> tailValues(K lower){return ()->{var entries=iterator(lower,true);return new Iterator<>(){public boolean hasNext(){return entries.hasNext();}public V next(){return entries.next().getValue();}};};}
    @Override public Set<Map.Entry<K,V>> entrySet(){return new AbstractSet<>(){public int size(){return SharedMap.this.size();}public Iterator<Map.Entry<K,V>> iterator(){return SharedMap.this.iterator(null,false);}};}
}
