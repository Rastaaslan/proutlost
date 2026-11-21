package fr.proutlost.quest;

import fr.proutlost.director.api.model.*;
import fr.proutlost.quest.qa.RealGroupClientReadFence;
import java.util.UUID;

/** Pure nonce/session contract; no Minecraft clients or native acceptance. */
public final class RealGroupClientReadFenceScenario {
 private RealGroupClientReadFenceScenario(){}
 private static int checks;
 private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 public static void main(String[] args){
  var world=new WorldId(UUID.randomUUID());var id=new PlayerId(UUID.randomUUID());var dim=new DimensionId("minecraft:overworld");
  var session=new SessionIdentity(world,new Epoch(1),new Epoch(2),dim,id);var core=BlobContent.hash(new byte[]{1});var fact=new FactId(UUID.randomUUID());
  var offer=RealGroupClientReadFence.issue(session,core,fact,100);
  check(RealGroupClientReadFence.matches(offer,offer.nonce(),session,core,fact,100),"current exact read accepted");
  check(!RealGroupClientReadFence.matches(null,offer.nonce(),session,core,fact,100),"missing offer refused");
  check(!RealGroupClientReadFence.matches(offer,"foreign",session,core,fact,100),"foreign nonce refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),new SessionIdentity(world,new Epoch(1),new Epoch(2),dim,new PlayerId(UUID.randomUUID())),core,fact,100),"foreign player refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),new SessionIdentity(world,new Epoch(1),new Epoch(3),dim,id),core,fact,100),"reconnected session refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),new SessionIdentity(world,new Epoch(2),new Epoch(2),dim,id),core,fact,100),"new boot refused old nonce");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),new SessionIdentity(new WorldId(UUID.randomUUID()),new Epoch(1),new Epoch(2),dim,id),core,fact,100),"foreign world refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),new SessionIdentity(world,new Epoch(1),new Epoch(2),new DimensionId("minecraft:the_nether"),id),core,fact,100),"changed dimension refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),session,BlobContent.hash(new byte[]{2}),fact,100),"changed Quest page refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),session,core,new FactId(UUID.randomUUID()),100),"changed fact refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),session,core,fact,99),"clock rollback refused");
  check(!RealGroupClientReadFence.matches(offer,offer.nonce(),session,core,fact,1301),"expired offer refused");
  System.out.println("RealGroupClientReadFenceScenario checks="+checks+" PASS scope=PURE_CORRELATION_NO_CLIENT_OR_NATIVE_RECEIPT");
 }
}
