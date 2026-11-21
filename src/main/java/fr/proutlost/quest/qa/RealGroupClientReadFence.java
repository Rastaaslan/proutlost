package fr.proutlost.quest.qa;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.UUID;

/** Pure correlation fence; a valid command context alone is not proof that a
 * human read a screen or that an operator did not use execute-as. */
public final class RealGroupClientReadFence {
 private RealGroupClientReadFence(){}
 public record Offer(String nonce,SessionIdentity session,Fingerprint core,FactId fact,long issuedTick,long deadline){
  public Offer{Objects.requireNonNull(nonce);Objects.requireNonNull(session);Objects.requireNonNull(core);Objects.requireNonNull(fact);if(nonce.isBlank()||issuedTick<0||deadline<issuedTick)throw new IllegalArgumentException("invalid read offer");}
 }
 public static Offer issue(SessionIdentity session,Fingerprint core,FactId fact,long now){return new Offer(UUID.randomUUID().toString(),session,core,fact,now,Math.addExact(now,1200));}
 public static boolean matches(Offer offer,String nonce,SessionIdentity current,Fingerprint core,FactId fact,long now){
  return offer!=null&&offer.nonce().equals(nonce)&&offer.session().equals(current)&&offer.core().equals(core)&&offer.fact().equals(fact)&&now>=offer.issuedTick()&&now<=offer.deadline();
 }
}
