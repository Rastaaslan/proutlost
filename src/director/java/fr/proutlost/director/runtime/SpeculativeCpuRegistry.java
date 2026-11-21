package fr.proutlost.director.runtime;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Actor-owned unpublished CPU captures only. This registry carries no journal,
 * admission, durable receipt or physical ownership release authority. */
public final class SpeculativeCpuRegistry {
 private final Thread actor=Thread.currentThread();
 private final IdentityHashMap<Object,Runnable> pending=new IdentityHashMap<>();
 private boolean revoked;
 private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("CPU capture owner actor");}
 public void track(Object owner,Runnable cancelAndDiscard){
  actor();Objects.requireNonNull(owner);Objects.requireNonNull(cancelAndDiscard);
  if(revoked)throw new CancellationException("CPU capture authority revoked");
  if(pending.containsKey(owner))throw new IllegalStateException("duplicate CPU capture owner");pending.put(owner,cancelAndDiscard);
 }
 public void completed(Object owner){actor();pending.remove(owner);}
 public int pending(){actor();return pending.size();}
 public boolean revoked(){actor();return revoked;}
 /** Revoke permanently for this runtime/session. Try every owner; a failed
  * transient discard stays registered for retry, never pretends release. */
 public void revokeAll(){
  actor();revoked=true;var snapshot=new ArrayList<Map.Entry<Object,Runnable>>();
  pending.forEach((owner,cancel)->snapshot.add(new AbstractMap.SimpleImmutableEntry<>(owner,cancel)));
  RuntimeException failure=null;
  for(var entry:snapshot){
   try{entry.getValue().run();pending.remove(entry.getKey());}
   catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
  }
  if(failure!=null)throw failure;
 }
}
