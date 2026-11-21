package fr.proutlost.director.admin.server;

import fr.proutlost.director.adapter.neoforge.network.AdminQueryReplyFence.Reply;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.*;

/** An admitted read may finish on its original request while J04 IO completes.
 * Every attempt runs on the actor and repeats all live authority/data checks.
 * There is one bounded ticket per player; cancellation never cancels shared IO.
 */
public final class DeferredAdminReadGate {
 private final Thread owner=Thread.currentThread();
 private final int capacity,timeoutTicks,maximumLoads;
 private final IntSupplier ticks;private final Consumer<Runnable> actorQueue;
 private final Map<PlayerId,Ticket> tickets=new HashMap<>();
 private final class Ticket {
  final PlayerId player;final AdminQuery query;final Reply initial;final int admittedTick;
  final BooleanSupplier authorized;final Supplier<Reply> evaluate;
  final Supplier<Optional<CompletionStage<?>>> pending;final Consumer<Reply> deliver;
  int loads;volatile boolean notificationFailed;
  Ticket(PlayerId p,AdminQuery q,Reply initial,BooleanSupplier auth,Supplier<Reply> eval,
    Supplier<Optional<CompletionStage<?>>> pending,Consumer<Reply> deliver){
   player=p;query=q;this.initial=initial;authorized=auth;evaluate=eval;this.pending=pending;
   this.deliver=deliver;admittedTick=ticks.getAsInt();
  }
 }
 public DeferredAdminReadGate(int capacity,int timeoutTicks,int maximumLoads,IntSupplier ticks,Consumer<Runnable> actorQueue){
  if(capacity<1||timeoutTicks<1||maximumLoads<1)throw new IllegalArgumentException("read completion bounds");
  this.capacity=capacity;this.timeoutTicks=timeoutTicks;this.maximumLoads=maximumLoads;
  this.ticks=Objects.requireNonNull(ticks);this.actorQueue=Objects.requireNonNull(actorQueue);
 }
 private void actor(){if(Thread.currentThread()!=owner)throw new IllegalStateException("read completion actor");}
 public static boolean awaitingData(Reply reply){
  return reply.rejection().filter(r->(r.status()==AdminStatus.REJECTED||r.status()==AdminStatus.DEFERRED)
    &&r.reasonCodes().equals(List.of(ReasonCode.STALE_CONTEXT))).isPresent();
 }
 public boolean await(PlayerId player,AdminQuery query,Reply initial,BooleanSupplier authorized,
   Supplier<Reply> evaluate,Supplier<Optional<CompletionStage<?>>> pending,Consumer<Reply> deliver){
  actor();Objects.requireNonNull(player);Objects.requireNonNull(query);
  if(query.panel()!=AdminPanel.MEMORY||!(query.filter() instanceof PlayerFilter)
    ||query.cursor().isPresent()||!awaitingData(initial)||!initial.rejection().orElseThrow().commandId().equals(query.requestId())||tickets.containsKey(player)
    ||tickets.size()>=capacity)return false;
  try{
   if(!authorized.getAsBoolean())return false;
   var work=pending.get();if(work.isEmpty())return false;
   var ticket=new Ticket(player,query,initial,authorized,evaluate,pending,deliver);
   tickets.put(player,ticket);arm(ticket,work.orElseThrow());return true;
  }catch(RuntimeException unavailable){tickets.remove(player);return false;}
 }
 private void arm(Ticket ticket,CompletionStage<?> work){
  ticket.loads++;
  // Completion threads only notify the actor. They never read world/authority.
  work.whenComplete((ignored,error)->{
   try{actorQueue.accept(()->resume(ticket));}
   catch(RuntimeException saturated){ticket.notificationFailed=true;}
  });
 }
 private boolean expired(Ticket ticket){
  int elapsed=ticks.getAsInt()-ticket.admittedTick; // Defined int wrap for a short tick window.
  return elapsed<0||elapsed>=timeoutTicks;
 }
 private void finish(Ticket ticket,Reply reply){
  var request=reply.snapshot().map(AdminSnapshot::requestId)
    .orElseGet(()->new RequestId(reply.rejection().orElseThrow().commandId().value()));
  var bounded=request.equals(ticket.query.requestId())?reply:fault(ticket);
  if(tickets.remove(ticket.player,ticket))ticket.deliver.accept(bounded);
 }
 private Reply fault(Ticket ticket){
  return new Reply(Optional.empty(),Optional.of(new AdminCommandResult(ticket.query.requestId(),
    AdminStatus.FAILED_SAFE,List.of(ReasonCode.INTERNAL_FAULT),Optional.empty(),Optional.empty())));
 }
 private void resume(Ticket ticket){
  actor();if(tickets.get(ticket.player)!=ticket)return;
  try{
   if(!ticket.authorized.getAsBoolean()){tickets.remove(ticket.player,ticket);return;}
   if(expired(ticket)){finish(ticket,ticket.initial);return;}
   var reply=Objects.requireNonNull(ticket.evaluate.get());
   if(!awaitingData(reply)){finish(ticket,reply);return;}
   var work=ticket.pending.get();
   if(work.isEmpty()||ticket.loads>=maximumLoads){finish(ticket,reply);return;}
   arm(ticket,work.orElseThrow());
  }catch(RuntimeException failure){finish(ticket,fault(ticket));}
 }
 public void expire(){
  actor();if(tickets.isEmpty())return;
  for(var ticket:List.copyOf(tickets.values())){
   try{
    if(!ticket.authorized.getAsBoolean()){tickets.remove(ticket.player,ticket);continue;}
    if(ticket.notificationFailed||expired(ticket))finish(ticket,ticket.initial);
   }catch(RuntimeException failure){finish(ticket,fault(ticket));}
  }
 }
 public void cancel(PlayerId player){actor();tickets.remove(player);}
 public void clear(){actor();tickets.clear();}
 public int pendingCount(){actor();return tickets.size();}
}
